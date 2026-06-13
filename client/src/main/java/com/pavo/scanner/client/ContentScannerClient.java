package com.pavo.scanner.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class ContentScannerClient {

    private final HttpClient httpClient;
    private final URI scanUri;
    private final String apiKey;

    public ContentScannerClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build());
    }

    ContentScannerClient(String baseUrl, String apiKey, HttpClient httpClient) {
        this.httpClient = httpClient;
        this.scanUri = URI.create(normalizeBaseUrl(baseUrl) + "/scan");
        this.apiKey = apiKey;
    }

    public ScanResult scan(String content) {
        String body = "{\"content\":" + toJsonString(content) + "}";
        HttpRequest request = HttpRequest.newBuilder(scanUri)
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                throw new ContentScannerException("Unauthorized calling content-scanner");
            }
            if (response.statusCode() != 200) {
                throw new ContentScannerException("Unexpected status from content-scanner: " + response.statusCode());
            }
            return parseResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContentScannerException("Failed to call content-scanner", e);
        } catch (IOException e) {
            throw new ContentScannerException("Failed to call content-scanner", e);
        }
    }

    private static ScanResult parseResponse(String json) {
        boolean safe = json.contains("\"safe\":true") || json.contains("\"safe\": true");
        String reason = extractReason(json);
        return new ScanResult(safe, reason);
    }

    private static String extractReason(String json) {
        int keyIndex = json.indexOf("\"reason\"");
        if (keyIndex < 0) {
            return "Unknown";
        }
        int colon = json.indexOf(':', keyIndex);
        int firstQuote = json.indexOf('"', colon + 1);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) {
            return "Unknown";
        }
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private static String toJsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
