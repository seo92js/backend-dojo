package dev.dojo.fallback.classification;

import dev.dojo.fallback.classification.client.PermanentFailure;
import dev.dojo.fallback.classification.client.RetryingClassifier;
import dev.dojo.fallback.classification.client.TemporaryFailure;
import dev.dojo.fallback.classification.model.ClassificationAnswer;
import dev.dojo.fallback.classification.model.ClassificationPolicy;
import dev.dojo.fallback.error.ErrorLog;
import dev.dojo.fallback.review.ReviewReason;

import lombok.RequiredArgsConstructor;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class ClassificationService {
    private final RetryingClassifier classifier;
    private final ClassificationResultWriter writer;

    @Async("classificationExecutor")
    public CompletableFuture<ClassificationAnswer> classifyAsync(
            ErrorLog error, ClassificationPolicy policy) {
        return CompletableFuture.completedFuture(classify(error, policy));
    }

    public ClassificationAnswer classify(ErrorLog error, ClassificationPolicy policy) {
        ClassificationAnswer answer;
        try {
            answer = classifier.classify(error);
        } catch (TemporaryFailure failure) {
            answer = ClassificationAnswer.failed(ReviewReason.RETRY_EXHAUSTED);
        } catch (PermanentFailure failure) {
            answer = ClassificationAnswer.failed(ReviewReason.NON_RETRYABLE_ERROR);
        }
        writer.save(error.id(), answer, policy);
        return answer;
    }
}
