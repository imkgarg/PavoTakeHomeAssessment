package com.pavo.scanner.client;

/**
 * Fail-closed wrapper for exec-runner: any unsafe content, auth failure, or
 * scanner outage blocks the tool result from proceeding.
 */
public class FailClosedContentScanner {

    private final ContentScannerClient client;

    public FailClosedContentScanner(ContentScannerClient client) {
        this.client = client;
    }

    public ScanResult requireSafeContent(String content) {
        try {
            ScanResult result = client.scan(content);
            if (!result.safe()) {
                throw new ContentBlockedException("Blocked unsafe tool content: " + result.reason());
            }
            return result;
        } catch (ContentScannerException e) {
            throw new ContentBlockedException("Scanner unavailable; blocking content (fail-closed)", e);
        }
    }
}
