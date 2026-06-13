package com.pavo.scanner.controller;

import com.pavo.scanner.model.HealthResponse;
import com.pavo.scanner.model.ScanRequest;
import com.pavo.scanner.model.ScanResponse;
import com.pavo.scanner.service.PromptInjectionScanner;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ContentScannerController {

    private final PromptInjectionScanner scanner;

    public ContentScannerController(PromptInjectionScanner scanner) {
        this.scanner = scanner;
    }

    @PostMapping(value = "/scan", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScanResponse scan(@RequestBody ScanRequest request) {
        String content = request != null ? request.content() : null;
        return scanner.scan(content);
    }

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public HealthResponse health() {
        return new HealthResponse("ok");
    }
}
