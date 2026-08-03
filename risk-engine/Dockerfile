FROM rust:bookworm AS builder
RUN apt-get update && apt-get install -y --no-install-recommends protobuf-compiler && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY Cargo.toml Cargo.lock* ./
COPY build.rs ./
COPY proto/ ./proto/
COPY src/ ./src/
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates libssl3 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /app/target/release/risk-engine .
EXPOSE 8080
HEALTHCHECK --interval=10s CMD curl -f http://localhost:8080/health || exit 1
CMD ["./risk-engine"]
