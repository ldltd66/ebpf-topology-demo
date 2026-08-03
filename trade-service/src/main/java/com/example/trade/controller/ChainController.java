package com.example.trade.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
public class ChainController {

    private static final Logger log = LoggerFactory.getLogger(ChainController.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @Value("${app.risk-service-url:http://risk-service:8080}")
    private String riskServiceUrl;

    @Value("${app.clearing-service-url:http://clearing-service:8080}")
    private String clearingServiceUrl;

    @Value("${app.audit-service-url:http://audit-service:8080}")
    private String auditServiceUrl;

    // Path A: sequential chain — trade → risk → clearing → audit → archive
    @PostMapping("/api/chain")
    public Map<String, Object> chain() throws InterruptedException {
        long start = System.currentTimeMillis();
        Thread.sleep(10);
        log.info("[chain] trade-service processing");

        Map<String, Object> result = new HashMap<>();
        result.put("service", "trade-service");

        try {
            Map resp = restTemplate.postForObject(riskServiceUrl + "/api/chain", Map.of(), Map.class);
            result.put("risk_service", resp);
        } catch (Exception e) {
            result.put("risk_service", Map.of("error", e.getMessage()));
        }

        result.put("elapsed_ms", System.currentTimeMillis() - start);
        return result;
    }

    // Path C: concurrent fan-out — trade → [clearing, audit] in parallel → both → archive
    @PostMapping("/api/concurrent")
    public Map<String, Object> concurrent() throws InterruptedException {
        long start = System.currentTimeMillis();
        Thread.sleep(10);
        log.info("[concurrent] trade-service fan-out");

        CompletableFuture<Map> clearingFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return restTemplate.postForObject(clearingServiceUrl + "/api/concurrent", Map.of(), Map.class);
            } catch (Exception e) {
                return Map.of("error", e.getMessage());
            }
        }, executor);

        CompletableFuture<Map> auditFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return restTemplate.postForObject(auditServiceUrl + "/api/concurrent", Map.of(), Map.class);
            } catch (Exception e) {
                return Map.of("error", e.getMessage());
            }
        }, executor);

        Map<String, Object> result = new HashMap<>();
        result.put("service", "trade-service");
        result.put("mode", "concurrent");

        try {
            CompletableFuture.allOf(clearingFuture, auditFuture).join();
            result.put("clearing_service", clearingFuture.get());
            result.put("audit_service", auditFuture.get());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }

        result.put("elapsed_ms", System.currentTimeMillis() - start);
        return result;
    }
}
