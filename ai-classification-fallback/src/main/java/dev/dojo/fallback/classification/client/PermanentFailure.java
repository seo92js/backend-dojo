package dev.dojo.fallback.classification.client;

public class PermanentFailure extends RuntimeException {
    public PermanentFailure(Throwable cause) {
        super("Non-retryable classification failure", cause);
    }
}
