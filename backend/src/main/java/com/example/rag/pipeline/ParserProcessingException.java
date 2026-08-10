package com.example.rag.pipeline;

public final class ParserProcessingException extends Exception {

    private final String code;

    public ParserProcessingException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ParserProcessingException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
