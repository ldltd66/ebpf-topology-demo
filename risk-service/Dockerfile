FROM golang:1.21-bookworm AS builder
WORKDIR /app

# Install protoc and Go plugins
RUN apt-get update && apt-get install -y --no-install-recommends protobuf-compiler && rm -rf /var/lib/apt/lists/*
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.32.0 && \
    go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.3.0

COPY go.mod go.sum* ./
RUN go mod download 2>/dev/null || true
COPY . .

# Generate protobuf Go code
RUN protoc --go_out=. --go_opt=paths=source_relative \
           --go-grpc_out=. --go-grpc_opt=paths=source_relative \
           proto/trade.proto

RUN CGO_ENABLED=0 GOOS=linux go build -o /app/server .

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /app/server .
EXPOSE 8080 50051
HEALTHCHECK --interval=10s CMD curl -f http://localhost:8080/health || exit 1
CMD ["./server"]
