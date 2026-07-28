FROM golang:1.21-bookworm AS builder
WORKDIR /app

# Install protoc
RUN apt-get update && apt-get install -y --no-install-recommends protobuf-compiler && rm -rf /var/lib/apt/lists/*

# Install Go protobuf plugins
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.32.0 && \
    go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.3.0

# Copy go.mod first
COPY go.mod ./

# Copy proto file and generate Go code BEFORE copying rest (so go mod tidy sees all imports)
COPY proto/trade.proto ./proto/
RUN protoc --go_out=. --go_opt=paths=source_relative \
           --go-grpc_out=. --go-grpc_opt=paths=source_relative \
           proto/trade.proto

# Copy rest of source
COPY . .

# Resolve ALL dependencies
RUN go mod tidy

# Build
RUN CGO_ENABLED=0 GOOS=linux go build -o /app/server .

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /app/server .
EXPOSE 8080 50051
HEALTHCHECK --interval=10s CMD curl -f http://localhost:8080/health || exit 1
CMD ["./server"]
