package com.pavo.scanner.controller;

import com.pavo.scanner.model.HealthResponse;
import com.pavo.scanner.model.ScanRequest;
import com.pavo.scanner.model.ScanResponse;
import com.pavo.scanner.observability.ScanMetrics;
import com.pavo.scanner.service.PromptInjectionScanner;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ContentScannerController {

    private final PromptInjectionScanner scanner;
    private final ScanMetrics scanMetrics;

    public ContentScannerController(PromptInjectionScanner scanner, ScanMetrics scanMetrics) {
        this.scanner = scanner;
        this.scanMetrics = scanMetrics;
    }

    @PostMapping(value = "/scan", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScanResponse scan(@RequestBody ScanRequest request) {
        String content = request != null ? request.content() : null;
        ScanResponse response = scanner.scan(content);
        scanMetrics.recordScan(response.safe());
        return response;
    }

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public HealthResponse health() {
        return new HealthResponse("ok");
    }
}
