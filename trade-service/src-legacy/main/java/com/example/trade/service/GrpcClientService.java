package com.example.trade.service;

import com.example.trade.grpc.RiskCheckGrpc;
import com.example.trade.grpc.TradeProto;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class GrpcClientService {

    private static final Logger log = LoggerFactory.getLogger(GrpcClientService.class);

    @GrpcClient("risk-service")
    private RiskCheckGrpc.RiskCheckBlockingStub riskCheckStub;

    public Map<String, Object> checkRisk(String requestId, double amount, Map<String, String> headers) {
        try {
            TradeProto.RiskRequest.Builder builder = TradeProto.RiskRequest.newBuilder()
                    .setRequestId(requestId)
                    .setAmount(amount);
            if (headers != null) {
                builder.putAllHeaders(headers);
            }
            TradeProto.RiskRequest request = builder.build();

            log.info("gRPC CheckRisk call: requestId={}, amount={}", requestId, amount);

            TradeProto.RiskResponse response = riskCheckStub
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .checkRisk(request);

            log.info("gRPC CheckRisk response: approved={}, service={}", response.getApproved(), response.getService());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("approved", response.getApproved());
            result.put("message", response.getMessage());
            result.put("service", response.getService());
            result.put("status", "success");
            return result;

        } catch (Exception e) {
            log.error("gRPC CheckRisk failed: requestId={}, error={}", requestId, e.getMessage(), e);
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("approved", false);
            errorResult.put("message", "gRPC call failed: " + e.getMessage());
            errorResult.put("service", "risk-service");
            errorResult.put("status", "error");
            return errorResult;
        }
    }
}
