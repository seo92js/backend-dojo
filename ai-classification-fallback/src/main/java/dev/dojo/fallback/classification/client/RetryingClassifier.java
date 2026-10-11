package dev.dojo.fallback.classification.client;

import dev.dojo.fallback.classification.model.ClassificationAnswer;
import dev.dojo.fallback.error.ErrorLog;

import lombok.RequiredArgsConstructor;

import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RetryingClassifier {
    private final Classifier classifier;

    @Retryable(
            includes = TemporaryFailure.class,
            maxRetries = 2,
            delayString = "${classification.retry-delay-ms:1000}")
    public ClassificationAnswer classify(ErrorLog error) {
        return classifier.classify(error);
    }
}
