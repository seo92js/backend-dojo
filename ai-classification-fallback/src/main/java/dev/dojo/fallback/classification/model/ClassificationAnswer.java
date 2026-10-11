package dev.dojo.fallback.classification.model;

import dev.dojo.fallback.review.ReviewReason;

public record ClassificationAnswer(String label, Double confidence, ReviewReason failure) {
    public static ClassificationAnswer failed(ReviewReason reason) {
        return new ClassificationAnswer(null, null, reason);
    }

    public boolean valid() {
        if (failure != null
                || confidence == null
                || !Double.isFinite(confidence)
                || confidence < 0
                || confidence > 1) {
            return false;
        }
        try {
            Label.valueOf(label);
            return true;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }
}
