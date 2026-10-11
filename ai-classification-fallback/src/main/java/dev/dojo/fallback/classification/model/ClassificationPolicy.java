package dev.dojo.fallback.classification.model;

public record ClassificationPolicy(double threshold, boolean reviewAll) {
    public ClassificationPolicy {
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("Threshold must be between 0 and 1");
        }
    }

    public static ClassificationPolicy automatic(double threshold) {
        return new ClassificationPolicy(threshold, false);
    }

    public static ClassificationPolicy humanReview(double threshold) {
        return new ClassificationPolicy(threshold, true);
    }
}
