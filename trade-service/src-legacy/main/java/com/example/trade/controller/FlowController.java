package com.example.trade.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@RestController
public class FlowController {

    private static final Logger log = LoggerFactory.getLogger(FlowController.class);
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${app.risk-service-url:http://risk-service:8080}")
    private String riskServiceUrl;

    @Value("${app.audit-service-url:http://audit-service:8080}")
    private String auditServiceUrl;

    @Value("${app.risk-engine-url:http://risk-engine:8080}")
    private String riskEngineUrl;

    @PostMapping("/api/flow")
    public Map<String, Object> flow(@RequestHeader(value = "x-flow", required = false) String flowHeader) throws InterruptedException {
        long start = System.currentTimeMillis();
        Thread.sleep(10);
        log.info("[flow] trade-service branch={}", flowHeader);

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("service", "trade-service");

        String branch = (flowHeader == null || flowHeader.isEmpty()) ? "risk" : flowHeader;
        result.put("branch", branch);

        try {
            String downstreamUrl;
            if ("audit".equals(branch)) {
                downstreamUrl = auditServiceUrl + "/api/flow";
            } else if ("engine".equals(branch)) {
                downstreamUrl = riskEngineUrl + "/api/flow";
            } else {
                downstreamUrl = riskServiceUrl + "/api/flow";
            }
            Map resp = restTemplate.postForObject(downstreamUrl, new HashMap<String, Object>(), Map.class);
            result.put("downstream", resp);
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }

        result.put("elapsed_ms", System.currentTimeMillis() - start);
        return result;
    }
}
