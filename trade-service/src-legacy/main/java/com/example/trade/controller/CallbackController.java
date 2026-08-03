package com.example.trade.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/trade")
public class CallbackController {

    private static final Logger log = LoggerFactory.getLogger(CallbackController.class);

    // Shared callback store — keyed by request_id
    private static final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> callbackFutures
            = new ConcurrentHashMap<>();

    // Also store received callback payloads for inspection
    private static final ConcurrentHashMap<String, Map<String, Object>> callbackResults
            = new ConcurrentHashMap<>();

    public static ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> getCallbackFutures() {
        return callbackFutures;
    }

    public static ConcurrentHashMap<String, Map<String, Object>> getCallbackResults() {
        return callbackResults;
    }

    /**
     * Register a future waiting for a callback with the given requestId.
     */
    public static CompletableFuture<Map<String, Object>> registerCallback(String requestId) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        callbackFutures.put(requestId, future);
        return future;
    }

    /**
     * POST /api/trade/callback
     * Receives async/sync callbacks from C++ clearing-service and Rust services.
     */
    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> receiveCallback(@RequestBody Map<String, Object> callbackPayload) {
        String requestId = (String) callbackPayload.get("request_id");
        String status = (String) callbackPayload.get("status");
        String service = (String) callbackPayload.get("service");

        log.info("Callback received: requestId={}, status={}, service={}", requestId, status, service);

        // Store the result
        callbackResults.put(requestId, callbackPayload);

        // Complete the future if one is waiting
        CompletableFuture<Map<String, Object>> future = callbackFutures.remove(requestId);
        if (future != null) {
            future.complete(callbackPayload);
            log.info("Callback future completed for requestId={}", requestId);
        } else {
            log.warn("No waiting future found for requestId={}", requestId);
        }

        // JDK 8 compatible: replaced Map.of() with HashMap
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "received");
        resp.put("request_id", requestId != null ? requestId : "unknown");
        return ResponseEntity.ok(resp);
    }
}
