package dev.dojo.fallback.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dojo.fallback.FallbackConfiguration;
import dev.dojo.fallback.classification.ClassificationResultWriter;
import dev.dojo.fallback.classification.ClassificationService;
import dev.dojo.fallback.classification.model.ClassificationAnswer;
import dev.dojo.fallback.classification.model.ClassificationPolicy;
import dev.dojo.fallback.classification.model.Label;
import dev.dojo.fallback.error.ErrorRepository;
import dev.dojo.fallback.error.ErrorState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Tag("experiment")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "\\S+")
@SpringBootTest(
        classes = FallbackConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.datasource.url=jdbc:h2:mem:experiments;DB_CLOSE_DELAY=-1")
class ClassificationPolicyExperiment {
    record Sample(int id, String message, Label truth) {}

    record Observed(Sample sample, ClassificationAnswer answer) {}

    @Autowired ClassificationService service;
    @Autowired ClassificationResultWriter writer;
    @Autowired ErrorRepository errors;
    @Autowired JdbcTemplate db;

    @Value("${spring.ai.openai.chat.model}")
    String model;

    @Test
    @DisplayName("실제 AI 응답으로 임계값과 신뢰도 구간을 비교한다")
    void compareThresholdsAndConfidenceBands() throws Exception {
        var samples = samples();
        assertThat(samples).hasSize(50);
        clean();
        var observed = new ArrayList<Observed>();
        for (var sample : samples) {
            var error = errors.save(sample.message());
            var answer = service.classify(error, ClassificationPolicy.humanReview(0.7));
            observed.add(new Observed(sample, answer));
        }
        assertThat(observed.stream().filter(item -> item.answer().valid()).count())
                .as("유효한 모델 응답이 있어야 합니다. API 키·모델·네트워크 설정을 확인하세요.")
                .isPositive();

        var report = new StringBuilder("# 실제 AI 분류 실험\n\n");
        report.append("- 실행 시각: ").append(Instant.now()).append('\n');
        report.append("- 모델: ").append(model).append('\n');
        report.append("- 입력: 수작업 정답을 붙인 테스트 에러 50건\n");
        report.append("- 같은 모델 응답을 재사용해 임계값만 변경했습니다. 실패 건은 검토 건수에 포함합니다.\n");
        report.append("- confidence는 모델의 자기 평가이며 정답 확률이 아닙니다.\n\n");
        report.append("| 신뢰도 구간 | 유효 응답 | 정답 | 일치율 |\n| --- | ---: | ---: | ---: |\n");
        double[] lower = {0, 0.5, 0.9};
        double[] upper = {0.5, 0.9, Double.POSITIVE_INFINITY};
        for (int band = 0; band < lower.length; band++) {
            int total = 0;
            int correct = 0;
            for (var item : observed) {
                var answer = item.answer();
                if (answer.valid()
                        && answer.confidence() >= lower[band]
                        && answer.confidence() < upper[band]) {
                    total++;
                    if (answer.label().equals(item.sample().truth().name())) correct++;
                }
            }
            String range = band == 2 ? "[0.9, 1.0]" : "[" + lower[band] + ", " + upper[band] + ")";
            report.append(
                    String.format(
                            Locale.ROOT,
                            "| %s | %d | %d | %s |%n",
                            range,
                            total,
                            correct,
                            percentage(correct, total)));
        }

        report.append("\n| 임계값 | 자동 확정 | 자동 확정 오답 | 자동 통과 오답률 | 검토 건수 | 검토 전환율 |\n");
        report.append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
        int previousAuto = samples.size();
        for (double threshold : new double[] {0.5, 0.7, 0.9}) {
            clean();
            int auto = 0;
            int wrong = 0;
            int review = 0;
            for (var item : observed) {
                var error = errors.save(item.sample().message());
                writer.save(error.id(), item.answer(), ClassificationPolicy.automatic(threshold));
                var state = errors.findState(error.id());
                boolean expectedAuto =
                        item.answer().valid() && item.answer().confidence() >= threshold;
                assertThat(state)
                        .isEqualTo(
                                expectedAuto
                                        ? ErrorState.AUTO_CONFIRMED
                                        : ErrorState.REVIEW_PENDING);
                if (state == ErrorState.AUTO_CONFIRMED) {
                    auto++;
                    if (!item.answer().label().equals(item.sample().truth().name())) wrong++;
                } else {
                    review++;
                }
            }
            assertThat(auto + review).isEqualTo(samples.size());
            assertThat(auto).isLessThanOrEqualTo(previousAuto);
            previousAuto = auto;
            report.append(
                    String.format(
                            Locale.ROOT,
                            "| %.1f | %d | %d | %s | %d | %s |%n",
                            threshold,
                            auto,
                            wrong,
                            percentage(wrong, auto),
                            review,
                            percentage(review, samples.size())));
        }

        report.append("\n| 입력 ID | 정답 | 모델 분류 | 신뢰도 | 실패 사유 |\n| --- | --- | --- | --- | --- |\n");
        for (var item : observed) {
            var answer = item.answer();
            report.append(
                    String.format(
                            Locale.ROOT,
                            "| %d | %s | %s | %s | %s |%n",
                            item.sample().id(),
                            item.sample().truth(),
                            answer.label(),
                            answer.confidence(),
                            answer.failure()));
        }
        report.append("\n작은 테스트 데이터의 관찰 결과입니다. 운영 정확도나 최적 임계값으로 일반화할 수 없습니다.\n");
        Files.createDirectories(Path.of("reports"));
        Files.writeString(Path.of("reports/classification-policy.md"), report);
    }

    private List<Sample> samples() throws Exception {
        try (var stream = getClass().getResourceAsStream("/fixtures/errors.csv")) {
            if (stream == null) throw new IllegalStateException("Error fixture not found");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .skip(1)
                    .map(
                            line -> {
                                var values = line.split(",", 3);
                                return new Sample(
                                        Integer.parseInt(values[0]),
                                        values[1],
                                        Label.valueOf(values[2]));
                            })
                    .toList();
        }
    }

    private void clean() {
        db.update("DELETE FROM review_queue");
        db.update("DELETE FROM classification_result");
        db.update("DELETE FROM errors");
    }

    private String percentage(int numerator, int denominator) {
        return denominator == 0
                ? "—"
                : String.format(Locale.ROOT, "%.1f%%", 100.0 * numerator / denominator);
    }
}
