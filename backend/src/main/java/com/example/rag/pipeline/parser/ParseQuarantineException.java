package com.example.rag.pipeline.parser;

public final class ParseQuarantineException extends Exception {

    public enum Reason {
        ENCRYPTED_PDF,
        PAGE_LIMIT_EXCEEDED,
        CORRUPT_PDF,
        SCANNED_PDF,
        GIBBERISH_TEXT,
        LOW_QUALITY_TEXT
    }

    private final Reason reason;

    public ParseQuarantineException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ParseQuarantineException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
