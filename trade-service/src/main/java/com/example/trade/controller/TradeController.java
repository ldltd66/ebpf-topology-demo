package com.example.trade.controller;

import com.example.trade.service.DownstreamService;
import com.example.trade.service.GrpcClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
public class TradeController {

    private static final Logger log = LoggerFactory.getLogger(TradeController.class);

    private final DownstreamService downstreamService;
    private final GrpcClientService grpcClientService;

    public TradeController(DownstreamService downstreamService, GrpcClientService grpcClientService) {
        this.downstreamService = downstreamService;
        this.grpcClientService = grpcClientService;
    }

    /**
     * GET /health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "trade-service"
        ));
    }

    /**
     * POST /api/trade - Main trade endpoint with 3-branch routing
     */
    @PostMapping("/api/trade")
    public ResponseEntity<Map<String, Object>> trade(
            @RequestBody(required = false) Map<String, Object> requestBody,
            @RequestHeader HttpHeaders headers) {

        String requestId = downstreamService.resolveRequestId(headers, UUID.randomUUID().toString());
        String branch = determineBranch(headers);

        log.info("Trade request: requestId={}, branch={}, headers={}", requestId, branch, summarizeHeaders(headers));

        if (requestBody == null) {
            requestBody = new LinkedHashMap<>();
        }
        // Inject requestId into body for downstream
        requestBody.put("request_id", requestId);

        Map<String, Object> downstreamResults;
        switch (branch) {
            case "degrade":
                downstreamResults = executeDegradeBranch(headers, requestId, requestBody);
                break;
            case "vip":
                downstreamResults = executeVipBranch(headers, requestId, requestBody);
                break;
            default:
                downstreamResults = executeDefaultBranch(headers, requestId, requestBody);
                break;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "trade-service");
        response.put("request_id", requestId);
        response.put("branch", branch);
        response.put("downstream_results", downstreamResults);
        response.put("timestamp", Instant.now().toString());

        log.info("Trade response: requestId={}, branch={}", requestId, branch);
        return ResponseEntity.ok(response);
    }

    // ---- Branch determination ----

    private String determineBranch(HttpHeaders headers) {
        // Branch 3: DEGRADE - headers x-env: gray AND x-degrade: true
        String env = getFirstHeader(headers, "x-env");
        String degrade = getFirstHeader(headers, "x-degrade");
        if ("gray".equalsIgnoreCase(env) && "true".equalsIgnoreCase(degrade)) {
            return "degrade";
        }

        // Branch 2: VIP - header x-tenant-id: vip
        String tenantId = getFirstHeader(headers, "x-tenant-id");
        if ("vip".equalsIgnoreCase(tenantId)) {
            return "vip";
        }

        // Branch 1: DEFAULT
        return "default";
    }

    private String getFirstHeader(HttpHeaders headers, String name) {
        List<String> values = headers.get(name);
        if (values != null && !values.isEmpty()) {
            return values.get(0);
        }
        return null;
    }

    // ---- Branch 1: DEFAULT ----
    // 1. Call risk-service via gRPC CheckRisk
    // 2. Wait for callback from clearing-service (up to 10s)
    // 3. Call audit-service via POST /api/audit
    // 4. Return aggregated response

    private Map<String, Object> executeDefaultBranch(HttpHeaders headers, String requestId, Map<String, Object> body) {
        Map<String, Object> results = new LinkedHashMap<>();

        // Step 1: gRPC call to risk-service
        double amount = extractAmount(body);
        Map<String, String> headerMap = buildHeaderMap(headers);
        Map<String, Object> riskResult = grpcClientService.checkRisk(requestId, amount, headerMap);
        results.put("risk-service", riskResult);

        // Step 2: Wait for callback from clearing-service
        CompletableFuture<Map<String, Object>> callbackFuture = CallbackController.registerCallback(requestId);
        try {
            Map<String, Object> callbackResult = callbackFuture.get(2, TimeUnit.SECONDS);
            results.put("clearing-service", callbackResult);
        } catch (TimeoutException e) {
            log.warn("Callback timeout for requestId={}", requestId);
            CallbackController.getCallbackFutures().remove(requestId);
            Map<String, Object> timeoutResult = new LinkedHashMap<>();
            timeoutResult.put("service", "clearing-service");
            timeoutResult.put("status", "timeout");
            timeoutResult.put("message", "Callback not received within 2 seconds");
            results.put("clearing-service", timeoutResult);
        } catch (Exception e) {
            log.error("Callback error for requestId={}: {}", requestId, e.getMessage());
            CallbackController.getCallbackFutures().remove(requestId);
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("service", "clearing-service");
            errorResult.put("status", "error");
            errorResult.put("message", "Callback error: " + e.getMessage());
            results.put("clearing-service", errorResult);
        }

        // Step 3: Call audit-service
        Map<String, Object> auditResult = downstreamService.callAuditService(headers, requestId, body);
        results.put("audit-service", auditResult);

        return results;
    }

    // ---- Branch 2: VIP ----
    // Skip Go/C++ entirely
    // Parallel call: risk-engine (Rust) + audit-service
    // Wait for both, return combined

    private Map<String, Object> executeVipBranch(HttpHeaders headers, String requestId, Map<String, Object> body) {
        Map<String, Object> results = new LinkedHashMap<>();

        CompletableFuture<Map<String, Object>> riskFuture =
                downstreamService.callRiskEngineAsync(headers, requestId, body);
        CompletableFuture<Map<String, Object>> auditFuture =
                downstreamService.callAuditServiceAsync(headers, requestId, body);

        // Wait for both to complete
        CompletableFuture.allOf(riskFuture, auditFuture).join();

        try {
            results.put("risk-engine", riskFuture.get());
        } catch (Exception e) {
            results.put("risk-engine", errorResult("risk-engine", e.getMessage()));
        }

        try {
            results.put("audit-service", auditFuture.get());
        } catch (Exception e) {
            results.put("audit-service", errorResult("audit-service", e.getMessage()));
        }

        return results;
    }

    // ---- Branch 3: DEGRADE ----
    // Skip ALL downstream except archive-service
    // Call archive-service, return degraded response

    private Map<String, Object> executeDegradeBranch(HttpHeaders headers, String requestId, Map<String, Object> body) {
        Map<String, Object> results = new LinkedHashMap<>();

        Map<String, Object> archiveResult = downstreamService.callArchiveService(headers, requestId, body);
        results.put("archive-service", archiveResult);

        results.put("_degraded", true);
        results.put("_message", "Degraded mode: only archive-service called");

        return results;
    }

    // ---- Utilities ----

    private double extractAmount(Map<String, Object> body) {
        Object amountObj = body.get("amount");
        if (amountObj instanceof Number) {
            return ((Number) amountObj).doubleValue();
        }
        if (amountObj instanceof String) {
            try {
                return Double.parseDouble((String) amountObj);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private Map<String, String> buildHeaderMap(HttpHeaders headers) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String name : List.of("x-tenant-id", "x-env", "x-degrade", "x-test-retry", "x-request-id", "traceparent")) {
            String val = getFirstHeader(headers, name);
            if (val != null) {
                map.put(name, val);
            }
        }
        return map;
    }

    private Map<String, Object> errorResult(String service, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", service);
        result.put("status", "error");
        result.put("message", message);
        return result;
    }

    private String summarizeHeaders(HttpHeaders headers) {
        StringBuilder sb = new StringBuilder("{");
        for (String name : List.of("x-tenant-id", "x-env", "x-degrade", "x-test-retry", "x-request-id", "traceparent")) {
            String val = getFirstHeader(headers, name);
            if (val != null) {
                sb.append(name).append("=").append(val).append(", ");
            }
        }
        if (sb.length() > 1) {
            sb.setLength(sb.length() - 2); // remove trailing ", "
        }
        sb.append("}");
        return sb.toString();
    }
}
