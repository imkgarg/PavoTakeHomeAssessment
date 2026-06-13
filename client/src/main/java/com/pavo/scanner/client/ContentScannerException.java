package com.pavo.scanner.client;

public class ContentScannerException extends RuntimeException {

    public ContentScannerException(String message) {
        super(message);
    }

    public ContentScannerException(String message, Throwable cause) {
        super(message, cause);
    }
}
