package dev.dojo.fallback.review;

public enum ReviewReason {
    LOW_CONFIDENCE,
    INVALID_RESULT,
    RETRY_EXHAUSTED,
    NON_RETRYABLE_ERROR,
    POLICY_REVIEW_ALL
}
