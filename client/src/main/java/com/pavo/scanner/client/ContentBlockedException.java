package com.pavo.scanner.client;

public class ContentBlockedException extends RuntimeException {

    public ContentBlockedException(String message) {
        super(message);
    }

    public ContentBlockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
