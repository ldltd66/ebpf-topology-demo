package main

import (
	"log"
	"net"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/ldltd66/ebpf-topology-demo/risk-service/handler"
	"github.com/ldltd66/ebpf-topology-demo/risk-service/proto"
	"google.golang.org/grpc"
)

func main() {
	// --- gRPC server on port 50051 ---
	grpcServer := grpc.NewServer()
	proto.RegisterRiskCheckServer(grpcServer, &handler.RiskCheckHandler{})

	go func() {
		lis, err := net.Listen("tcp", ":50051")
		if err != nil {
			log.Fatalf("failed to listen on :50051: %v", err)
		}
		log.Println("[risk-service] gRPC server listening on :50051")
		if err := grpcServer.Serve(lis); err != nil {
			log.Fatalf("gRPC server failed: %v", err)
		}
	}()

	// --- HTTP server on port 8080 ---
	r := gin.Default()
	handler.RegisterHTTPHandlers(r)

	log.Println("[risk-service] HTTP server listening on :8080")
	if err := http.ListenAndServe(":8080", r); err != nil {
		log.Fatalf("HTTP server failed: %v", err)
	}
}
