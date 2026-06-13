package com.pavo.scanner.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ScanMetrics {

    private final Counter scanRequests;
    private final Counter scanSafe;
    private final Counter scanUnsafe;
    private final Counter authFailures;

    public ScanMetrics(MeterRegistry meterRegistry) {
        this.scanRequests = Counter.builder("scanner.requests.total")
                .description("Total scan requests received")
                .register(meterRegistry);
        this.scanSafe = Counter.builder("scanner.results.safe.total")
                .description("Scan results marked safe")
                .register(meterRegistry);
        this.scanUnsafe = Counter.builder("scanner.results.unsafe.total")
                .description("Scan results marked unsafe")
                .register(meterRegistry);
        this.authFailures = Counter.builder("scanner.auth.failures.total")
                .description("Scan requests rejected due to invalid auth")
                .register(meterRegistry);
    }

    public void recordScan(boolean safe) {
        scanRequests.increment();
        if (safe) {
            scanSafe.increment();
        } else {
            scanUnsafe.increment();
        }
    }

    public void recordAuthFailure() {
        authFailures.increment();
    }
}
