package com.leash.llm;

/** The model is off, slow, or answered garbage. Callers always have a deterministic fallback. */
public class LlmException extends Exception {
    public LlmException(String message) { super(message); }
    public LlmException(String message, Throwable cause) { super(message, cause); }
}
