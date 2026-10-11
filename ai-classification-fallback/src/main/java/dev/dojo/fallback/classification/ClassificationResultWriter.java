package dev.dojo.fallback.classification;

import dev.dojo.fallback.classification.model.ClassificationAnswer;
import dev.dojo.fallback.classification.model.ClassificationPolicy;
import dev.dojo.fallback.error.ErrorRepository;
import dev.dojo.fallback.error.ErrorState;
import dev.dojo.fallback.review.ReviewQueueRepository;
import dev.dojo.fallback.review.ReviewReason;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ClassificationResultWriter {
    private final ErrorRepository errors;
    private final ClassificationResultRepository results;
    private final ReviewQueueRepository reviews;

    @Transactional
    public void save(long errorId, ClassificationAnswer answer, ClassificationPolicy settings) {
        ReviewReason reason = reviewReason(answer, settings);
        results.save(errorId, answer, reason == null ? answer.label() : null);
        if (reason != null) {
            reviews.save(errorId, reason);
        }
        errors.updateState(
                errorId, reason == null ? ErrorState.AUTO_CONFIRMED : ErrorState.REVIEW_PENDING);
    }

    private ReviewReason reviewReason(ClassificationAnswer answer, ClassificationPolicy settings) {
        if (answer.failure() != null) return answer.failure();
        if (!answer.valid()) return ReviewReason.INVALID_RESULT;
        if (answer.confidence() < settings.threshold()) return ReviewReason.LOW_CONFIDENCE;
        if (settings.reviewAll()) return ReviewReason.POLICY_REVIEW_ALL;
        return null;
    }
}
