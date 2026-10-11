package dev.dojo.fallback.classification.client;

public class TemporaryFailure extends RuntimeException {
    public TemporaryFailure() {
        super("Temporary classification failure");
    }

    public TemporaryFailure(Throwable cause) {
        super("Temporary classification failure", cause);
    }
}
