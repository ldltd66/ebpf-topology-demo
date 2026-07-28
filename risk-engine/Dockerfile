FROM rust:1.77-bookworm AS planner
WORKDIR /app
RUN cargo install cargo-chef
COPY . .
RUN cargo chef prepare --recipe-path recipe.json

FROM rust:1.77-bookworm AS builder
WORKDIR /app
COPY --from=planner /app/recipe.json recipe.json
RUN cargo install cargo-chef && cargo chef cook --release --recipe-path recipe.json
COPY . .
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates libssl3 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /app/target/release/risk-engine .
EXPOSE 8080
HEALTHCHECK --interval=10s CMD curl -f http://localhost:8080/health || exit 1
CMD ["./risk-engine"]
