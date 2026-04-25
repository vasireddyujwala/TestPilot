package com.executionagent.exceptions;

/** Raised when the model output is not valid / not parseable per our contract. */
public class FormatErrorException extends RuntimeException {
    public FormatErrorException(String message) {
        super(message);
    }
    public FormatErrorException(String message, Throwable cause) {
        super(message, cause);
    }
}
