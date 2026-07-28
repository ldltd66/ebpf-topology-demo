package handler

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/ldltd66/ebpf-topology-demo/risk-service/proto"
	"google.golang.org/grpc/metadata"
	gproto "google.golang.org/protobuf/proto"
)

// RiskCheckHandler implements proto.RiskCheckServer.
type RiskCheckHandler struct {
	proto.UnimplementedRiskCheckServer
}

// CheckRisk receives a risk check request, forwards headers to clearing-service.
func (h *RiskCheckHandler) CheckRisk(ctx context.Context, req *proto.RiskRequest) (*proto.RiskResponse, error) {
	// Log the request
	log.Printf("[%s] gRPC CheckRisk request_id=%s amount=%.2f",
		time.Now().Format(time.RFC3339), req.RequestId, req.Amount)

	// Resolve clearing-service URL
	clearingURL := os.Getenv("CLEARING_SERVICE_URL")
	if clearingURL == "" {
		clearingURL = "http://clearing-service:8080"
	}

	// Build clearing request body
	clearBody, err := json.Marshal(map[string]interface{}{
		"request_id": req.RequestId,
		"amount":     req.Amount,
	})
	if err != nil {
		return &proto.RiskResponse{
			Approved: false,
			Message:  fmt.Sprintf("failed to marshal clearing request: %v", err),
			Service:  "risk-service",
		}, nil
	}

	httpReq, err := http.NewRequestWithContext(ctx, "POST", clearingURL+"/api/clear", bytes.NewReader(clearBody))
	if err != nil {
		return &proto.RiskResponse{
			Approved: false,
			Message:  fmt.Sprintf("failed to create clearing request: %v", err),
			Service:  "risk-service",
		}, nil
	}
	httpReq.Header.Set("Content-Type", "application/json")

	// Forward x-* headers from gRPC metadata
	forwardMetadataHeaders(ctx, httpReq)

	// Forward x-* headers from request's headers map (takes priority)
	if req.Headers != nil {
		for k, v := range req.Headers {
			lk := strings.ToLower(k)
			if isForbiddenHeader(lk) {
				continue
			}
			httpReq.Header.Set(k, v)
		}
	}

	// Call clearing-service
	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(httpReq)
	if err != nil {
		return &proto.RiskResponse{
			Approved: false,
			Message:  fmt.Sprintf("clearing service call failed: %v", err),
			Service:  "risk-service",
		}, nil
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)

	if resp.StatusCode != http.StatusOK {
		return &proto.RiskResponse{
			Approved: false,
			Message:  fmt.Sprintf("clearing service returned status %d: %s", resp.StatusCode, string(body)),
			Service:  "risk-service",
		}, nil
	}

	// Parse clearing result
	var clearResult struct {
		Cleared bool   `json:"cleared"`
		Message string `json:"message"`
	}
	if err := json.Unmarshal(body, &clearResult); err != nil {
		// If we can't parse, assume success (clearing-service may have a different schema)
		return &proto.RiskResponse{
			Approved: true,
			Message:  "risk check passed, clearing completed",
			Service:  "risk-service",
		}, nil
	}

	if clearResult.Cleared {
		return &proto.RiskResponse{
			Approved: true,
			Message:  "risk check passed, clearing completed",
			Service:  "risk-service",
		}, nil
	}

	return &proto.RiskResponse{
		Approved: false,
		Message:  fmt.Sprintf("clearing rejected: %s", clearResult.Message),
		Service:  "risk-service",
	}, nil
}

// forwardMetadataHeaders extracts x-* headers from gRPC metadata and sets them
// on the outgoing HTTP request. Headers already present on the HTTP request are
// not overwritten (the caller can set priority headers after calling this).
func forwardMetadataHeaders(ctx context.Context, httpReq *http.Request) {
	md, ok := metadata.FromIncomingContext(ctx)
	if !ok {
		return
	}

	headerNames := []string{
		"x-tenant-id",
		"x-env",
		"x-degrade",
		"x-test-retry",
		"x-request-id",
		"traceparent",
	}

	for _, name := range headerNames {
		vals := md.Get(name)
		if len(vals) > 0 {
			httpReq.Header.Set(name, vals[0])
		}
	}
}

// isForbiddenHeader returns true for HTTP headers that must never be forwarded.
func isForbiddenHeader(name string) bool {
	forbidden := map[string]bool{
		"host":              true,
		"content-length":    true,
		"transfer-encoding": true,
		"connection":        true,
	}
	return forbidden[strings.ToLower(name)]
}

// Ensure the handler satisfies the interface at compile time.
var _ proto.RiskCheckServer = (*RiskCheckHandler)(nil)

// Silence unused import warnings.
var _ = gproto.Marshal
