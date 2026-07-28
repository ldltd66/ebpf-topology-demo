package com.example.trade.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DownstreamService {

    private static final Logger log = LoggerFactory.getLogger(DownstreamService.class);

    private static final Set<String> FORWARDED_HEADERS = Set.of(
            "x-tenant-id", "x-env", "x-degrade", "x-test-retry", "x-request-id", "traceparent"
    );

    private static final Set<String> BLOCKED_HEADERS = Set.of(
            "host", "content-length", "transfer-encoding", "connection", "accept-encoding"
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;

    @Value("${AUDIT_SERVICE_URL:http://audit-service:8080}")
    private String auditServiceUrl;

    @Value("${RISK_ENGINE_URL:http://risk-engine:8080}")
    private String riskEngineUrl;

    @Value("${ARCHIVE_SERVICE_URL:http://archive-service:8080}")
    private String archiveServiceUrl;

    public DownstreamService(ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.executorService = Executors.newFixedThreadPool(10);
    }

    public HttpHeaders buildForwardedHeaders(HttpHeaders incomingHeaders, String requestId) {
        HttpHeaders forwarded = new HttpHeaders();
        forwarded.setContentType(MediaType.APPLICATION_JSON);

        for (String headerName : FORWARDED_HEADERS) {
            List<String> values = incomingHeaders.get(headerName);
            if (values != null) {
                forwarded.put(headerName, new ArrayList<>(values));
            }
        }

        // Always set x-request-id
        if (!forwarded.containsKey("x-request-id")) {
            forwarded.set("x-request-id", requestId);
        }

        return forwarded;
    }

    public String resolveRequestId(HttpHeaders incomingHeaders, String fallbackId) {
        List<String> values = incomingHeaders.get("x-request-id");
        if (values != null && !values.isEmpty() && !values.get(0).isBlank()) {
            return values.get(0);
        }
        return fallbackId;
    }

    // ---- Synchronous calls ----

    public Map<String, Object> callAuditService(HttpHeaders incomingHeaders, String requestId, Map<String, Object> body) {
        return callDownstream(auditServiceUrl + "/api/audit", incomingHeaders, requestId, body, "audit-service");
    }

    public Map<String, Object> callRiskEngine(HttpHeaders incomingHeaders, String requestId, Map<String, Object> body) {
        return callDownstream(riskEngineUrl + "/api/risk-check", incomingHeaders, requestId, body, "risk-engine");
    }

    public Map<String, Object> callArchiveService(HttpHeaders incomingHeaders, String requestId, Map<String, Object> body) {
        return callDownstream(archiveServiceUrl + "/api/archive", incomingHeaders, requestId, body, "archive-service");
    }

    // ---- Async wrappers ----

    public CompletableFuture<Map<String, Object>> callAuditServiceAsync(HttpHeaders incomingHeaders, String requestId, Map<String, Object> body) {
        return CompletableFuture.supplyAsync(() ->
                callAuditService(incomingHeaders, requestId, body), executorService);
    }

    public CompletableFuture<Map<String, Object>> callRiskEngineAsync(HttpHeaders incomingHeaders, String requestId, Map<String, Object> body) {
        return CompletableFuture.supplyAsync(() ->
                callRiskEngine(incomingHeaders, requestId, body), executorService);
    }

    // ---- Core HTTP call ----

    private Map<String, Object> callDownstream(String url, HttpHeaders incomingHeaders, String requestId,
                                                Map<String, Object> body, String serviceName) {
        try {
            HttpHeaders headers = buildForwardedHeaders(incomingHeaders, requestId);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            log.info("Calling {} at {}: requestId={}", serviceName, url, requestId);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            log.info("{} responded with status {}: requestId={}", serviceName, response.getStatusCode(), requestId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("service", serviceName);
            result.put("status", "success");
            result.put("http_status", response.getStatusCode().value());

            if (response.getBody() != null && !response.getBody().isBlank()) {
                try {
                    Map<String, Object> parsed = objectMapper.readValue(response.getBody(), Map.class);
                    result.put("response", parsed);
                } catch (Exception e) {
                    result.put("response", response.getBody());
                }
            }
            return result;

        } catch (Exception e) {
            log.error("Call to {} failed: requestId={}, error={}", serviceName, requestId, e.getMessage(), e);
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("service", serviceName);
            errorResult.put("status", "error");
            errorResult.put("message", "Downstream call failed: " + e.getMessage());
            return errorResult;
        }
    }
}
