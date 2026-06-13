package com.pavo.scanner.auth;

import com.pavo.scanner.observability.ScanMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] expectedApiKey;
    private final ScanMetrics scanMetrics;

    public ApiKeyAuthFilter(@Value("${SCAN_API_KEY:}") String scanApiKey, ScanMetrics scanMetrics) {
        if (scanApiKey == null || scanApiKey.isBlank()) {
            throw new IllegalStateException("SCAN_API_KEY environment variable must be set");
        }
        this.expectedApiKey = scanApiKey.getBytes(StandardCharsets.UTF_8);
        this.scanMetrics = scanMetrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/health".equals(path)
                || path.startsWith("/actuator/health")
                || "/actuator/prometheus".equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            reject(response);
            return;
        }

        String providedKey = authorization.substring(BEARER_PREFIX.length()).trim();
        if (!constantTimeEquals(providedKey.getBytes(StandardCharsets.UTF_8), expectedApiKey)) {
            reject(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        scanMetrics.recordAuthFailure();
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Unauthorized\"}");
    }

    private static boolean constantTimeEquals(byte[] left, byte[] right) {
        return MessageDigest.isEqual(left, right);
    }
}
