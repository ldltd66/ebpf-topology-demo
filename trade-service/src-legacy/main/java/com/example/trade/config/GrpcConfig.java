package com.example.trade.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcConfig {

    @Value("${RISK_SERVICE_GRPC:risk-service:50051}")
    private String riskServiceGrpc;

    @Value("${AUDIT_SERVICE_URL:http://audit-service:8080}")
    private String auditServiceUrl;

    @Value("${RISK_ENGINE_URL:http://risk-engine:8080}")
    private String riskEngineUrl;

    @Value("${ARCHIVE_SERVICE_URL:http://archive-service:8080}")
    private String archiveServiceUrl;

    public String getRiskServiceGrpc() {
        return riskServiceGrpc;
    }

    public String getAuditServiceUrl() {
        return auditServiceUrl;
    }

    public String getRiskEngineUrl() {
        return riskEngineUrl;
    }

    public String getArchiveServiceUrl() {
        return archiveServiceUrl;
    }

    public String getRiskServiceHost() {
        String addr = riskServiceGrpc;
        int colonIdx = addr.lastIndexOf(':');
        if (colonIdx > 0) {
            return addr.substring(0, colonIdx);
        }
        return addr;
    }

    public int getRiskServicePort() {
        String addr = riskServiceGrpc;
        int colonIdx = addr.lastIndexOf(':');
        if (colonIdx > 0) {
            try {
                return Integer.parseInt(addr.substring(colonIdx + 1));
            } catch (NumberFormatException e) {
                return 50051;
            }
        }
        return 50051;
    }

    @Bean
    public DownstreamUrlHolder downstreamUrlHolder() {
        return new DownstreamUrlHolder(auditServiceUrl, riskEngineUrl, archiveServiceUrl);
    }

    /**
     * Centralized holder for downstream service URLs, populated from env vars.
     */
    public static class DownstreamUrlHolder {
        private final String auditServiceUrl;
        private final String riskEngineUrl;
        private final String archiveServiceUrl;

        public DownstreamUrlHolder(String auditServiceUrl, String riskEngineUrl, String archiveServiceUrl) {
            this.auditServiceUrl = auditServiceUrl;
            this.riskEngineUrl = riskEngineUrl;
            this.archiveServiceUrl = archiveServiceUrl;
        }

        public String getAuditServiceUrl() { return auditServiceUrl; }
        public String getRiskEngineUrl() { return riskEngineUrl; }
        public String getArchiveServiceUrl() { return archiveServiceUrl; }
    }
}
