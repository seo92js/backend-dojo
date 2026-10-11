package dev.dojo.fallback.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.openai.errors.OpenAIServiceException;
import com.sun.net.httpserver.HttpServer;

import dev.dojo.fallback.FallbackConfiguration;
import dev.dojo.fallback.classification.ClassificationResultRepository;
import dev.dojo.fallback.classification.ClassificationService;
import dev.dojo.fallback.classification.client.Classifier;
import dev.dojo.fallback.classification.client.SpringAiClassifier;
import dev.dojo.fallback.classification.client.TemporaryFailure;
import dev.dojo.fallback.classification.model.ClassificationAnswer;
import dev.dojo.fallback.classification.model.ClassificationPolicy;
import dev.dojo.fallback.classification.model.Label;
import dev.dojo.fallback.error.ErrorLog;
import dev.dojo.fallback.error.ErrorRepository;
import dev.dojo.fallback.error.ErrorState;
import dev.dojo.fallback.review.ReviewQueueRepository;
import dev.dojo.fallback.review.ReviewReason;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest(
        classes = FallbackConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:workflow;DB_CLOSE_DELAY=-1",
            "spring.ai.openai.api-key=test-only",
            "classification.retry-delay-ms=1"
        })
class WorkflowTest {
    private static final ClassificationPolicy POLICY = ClassificationPolicy.automatic(0.7);
    @Autowired ClassificationService service;
    @Autowired ErrorRepository errors;
    @Autowired ClassificationResultRepository results;
    @Autowired ReviewQueueRepository reviews;
    @MockitoBean Classifier classifier;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry metrics;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM review_queue");
        jdbc.update("DELETE FROM classification_result");
        jdbc.update("DELETE FROM errors");
    }

    @Test
    @DisplayName("낮은 신뢰도는 검토 큐로 보낸다")
    void lowConfidenceCreatesOneUnconfirmedReview() {
        var error = errors.save("DB connection timeout");
        when(classifier.classify(error))
                .thenReturn(new ClassificationAnswer("DATABASE", 0.3, null));

        service.classify(error, POLICY);

        assertThat(errors.findState(error.id())).isEqualTo(ErrorState.REVIEW_PENDING);
        assertThat(results.find(error.id()).proposedLabel()).isEqualTo("DATABASE");
        assertThat(results.find(error.id()).confirmedLabel()).isNull();
        assertThat(reviews.waiting(10)).hasSize(1);
        assertThat(reviews.waiting(10).get(0).reason()).isEqualTo(ReviewReason.LOW_CONFIDENCE);
    }

    @Test
    @DisplayName("임계값 이상은 자동 확정한다")
    void responseAtThresholdIsAutomaticallyConfirmed() {
        var error = errors.save("DB connection refused");
        when(classifier.classify(error))
                .thenReturn(new ClassificationAnswer("DATABASE", 0.7, null));

        service.classify(error, POLICY);

        assertThat(results.find(error.id()).confirmedLabel()).isEqualTo(Label.DATABASE);
        assertThat(errors.findState(error.id())).isEqualTo(ErrorState.AUTO_CONFIRMED);
        assertThat(reviews.waiting(10)).isEmpty();
    }

    @Test
    @DisplayName("전체 검토 정책은 자동 확정하지 않는다")
    void fullReviewPolicyLeavesHighConfidenceAnswerUnconfirmed() {
        var error = errors.save("Connection reset by peer");
        when(classifier.classify(error))
                .thenReturn(new ClassificationAnswer("DATABASE", 0.98, null));

        service.classify(error, ClassificationPolicy.humanReview(0.7));

        assertThat(results.find(error.id()).confirmedLabel()).isNull();
        assertThat(reviews.waiting(10).get(0).reason()).isEqualTo(ReviewReason.POLICY_REVIEW_ALL);
    }

    @Test
    @DisplayName("잘못된 분류값은 재시도 없이 검토한다")
    void invalidCategoryGoesToReviewWithoutRetry() {
        var error = errors.save("Unexpected exception");
        when(classifier.classify(error))
                .thenReturn(new ClassificationAnswer("NOT_A_CATEGORY", 0.9, null));

        service.classify(error, POLICY);

        assertThat(reviews.waiting(10).get(0).reason()).isEqualTo(ReviewReason.INVALID_RESULT);
        verify(classifier, times(1)).classify(error);
    }

    @Test
    @DisplayName("NaN 신뢰도는 검토로 보낸다")
    void nonFiniteConfidenceIsNotAutomaticallyConfirmed() {
        var error = errors.save("Unexpected exception");
        when(classifier.classify(error))
                .thenReturn(new ClassificationAnswer("UNKNOWN", Double.NaN, null));

        service.classify(error, POLICY);

        assertThat(results.find(error.id()).confirmedLabel()).isNull();
        assertThat(reviews.waiting(10).get(0).reason()).isEqualTo(ReviewReason.INVALID_RESULT);
    }

    @Test
    @DisplayName("3회 실패하면 검토 큐로 보낸다")
    void threeFailuresGoToReviewWithExhaustionReason() {
        var error = errors.save("Upstream timeout");
        when(classifier.classify(error)).thenThrow(new TemporaryFailure());

        service.classify(error, POLICY);

        verify(classifier, times(3)).classify(error);
        assertThat(results.find(error.id()).proposedLabel()).isNull();
        assertThat(results.find(error.id()).failureReason())
                .isEqualTo(ReviewReason.RETRY_EXHAUSTED);
        assertThat(reviews.waiting(10).get(0).reason()).isEqualTo(ReviewReason.RETRY_EXHAUSTED);
    }

    @Test
    @DisplayName("비동기 분류도 재시도 후 검토로 보낸다")
    void asyncClassificationCanBeAwaitedWithoutStartingServer() throws Exception {
        var error = errors.save("Upstream timeout");
        var thread = new AtomicReference<String>();
        when(classifier.classify(error))
                .thenAnswer(
                        invocation -> {
                            thread.set(Thread.currentThread().getName());
                            throw new TemporaryFailure();
                        });

        service.classifyAsync(error, POLICY).get(5, TimeUnit.SECONDS);

        assertThat(thread.get()).startsWith("classification-");
        verify(classifier, times(3)).classify(error);
        assertThat(reviews.waiting(10).get(0).reason()).isEqualTo(ReviewReason.RETRY_EXHAUSTED);
    }

    @Test
    @DisplayName("비동기 큐 저장 실패도 롤백한다")
    void asyncQueueFailureStillRollsBackDatabaseWrites() {
        var error = errors.save("DB connection timeout");
        when(classifier.classify(error))
                .thenReturn(new ClassificationAnswer("DATABASE", 0.3, null));
        jdbc.execute(
                "ALTER TABLE review_queue ADD CONSTRAINT injected_failure CHECK (reason <>"
                        + " 'LOW_CONFIDENCE')");
        try {
            assertThatThrownBy(() -> service.classifyAsync(error, POLICY).get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            assertThat(count("classification_result")).isZero();
            assertThat(count("review_queue")).isZero();
            assertThat(errors.findState(error.id())).isEqualTo(ErrorState.PENDING);
        } finally {
            jdbc.execute("ALTER TABLE review_queue DROP CONSTRAINT injected_failure");
        }
    }

    @Test
    @DisplayName("메트릭에 검토 큐 상태를 반영한다")
    void actuatorMetersReflectQueueState() {
        var error = errors.save("DB connection timeout");
        when(classifier.classify(error))
                .thenReturn(new ClassificationAnswer("DATABASE", 0.3, null));
        service.classify(error, POLICY);

        assertThat(metrics.get("review.queue.pending").gauge().value()).isEqualTo(1);
        var secondError = errors.save("Another timeout");
        when(classifier.classify(secondError))
                .thenReturn(new ClassificationAnswer("TIMEOUT", 0.2, null));
        service.classify(secondError, POLICY);
        assertThat(metrics.get("review.queue.pending").gauge().value()).isEqualTo(2);
    }

    @Test
    @DisplayName("JSON 형식 오류는 검토 사유로 반환한다")
    void springAiInvalidJsonRequiresReview() {
        var adapter = classifierReturning("not JSON");

        var answer = adapter.classify(new ErrorLog(1, "DB connection refused"));

        assertThat(answer.failure()).isEqualTo(ReviewReason.INVALID_RESULT);
    }

    @Test
    @DisplayName("API 401 오류는 재시도 없이 검토 큐로 보낸다")
    void springAiAuthenticationFailureGoesToReviewWithoutRetry() {
        var model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        var failure = mock(OpenAIServiceException.class);
        when(failure.statusCode()).thenReturn(401);
        when(model.call(any(Prompt.class))).thenThrow(failure);
        var adapter = new SpringAiClassifier(ChatClient.builder(model));

        var error = errors.save("DB connection refused");
        when(classifier.classify(error)).thenAnswer(invocation -> adapter.classify(error));

        service.classify(error, POLICY);

        verify(classifier, times(1)).classify(error);
        assertThat(results.find(error.id()).failureReason())
                .isEqualTo(ReviewReason.NON_RETRYABLE_ERROR);
        assertThat(results.find(error.id()).confirmedLabel()).isNull();
        assertThat(errors.findState(error.id())).isEqualTo(ErrorState.REVIEW_PENDING);
        assertThat(reviews.waiting(10).get(0).reason()).isEqualTo(ReviewReason.NON_RETRYABLE_ERROR);
    }

    @Test
    @DisplayName("실제 HTTP 분류 호출도 세 번째 성공 후 저장한다")
    void springAiProviderRequestsRetryAndPersist() throws Exception {
        var requests = new AtomicInteger();
        var requestBody = new AtomicReference<String>();
        var provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext(
                "/v1/chat/completions",
                exchange -> {
                    requestBody.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    int status = requests.incrementAndGet() < 3 ? 503 : 200;
                    String response =
                            status == 503
                                    ? "{\"error\":{\"message\":\"temporarily"
                                            + " unavailable\",\"type\":\"server_error\"}}"
                                    : """
{"id":"local-response","object":"chat.completion","created":0,"model":"local-test",
 "choices":[{"index":0,"message":{"role":"assistant",
   "content":"{\\\"label\\\":\\\"DATABASE\\\",\\\"confidence\\\":0.9}"},"finish_reason":"stop"}],
 "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
""";
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(bytes);
                    }
                });
        provider.start();
        try {
            var options =
                    OpenAiChatOptions.builder()
                            .apiKey("test-only")
                            .baseUrl("http://127.0.0.1:" + provider.getAddress().getPort() + "/v1")
                            .model("local-test")
                            .maxRetries(0)
                            .timeout(Duration.ofSeconds(2))
                            .build();
            var model = OpenAiChatModel.builder().options(options).build();
            var adapter = new SpringAiClassifier(ChatClient.builder(model));
            var error = errors.save("DB connection refused");
            when(classifier.classify(error)).thenAnswer(invocation -> adapter.classify(error));

            service.classify(error, POLICY);

            assertThat(requests.get()).isEqualTo(3);
            assertThat(requestBody.get()).contains(error.message());
            assertThat(results.find(error.id()).confirmedLabel()).isEqualTo(Label.DATABASE);
            assertThat(reviews.waiting(10)).isEmpty();
        } finally {
            provider.stop(0);
        }
    }

    private SpringAiClassifier classifierReturning(String output) {
        var model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(List.of(new Generation(new AssistantMessage(output)))));
        return new SpringAiClassifier(ChatClient.builder(model));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
