FROM debian:bookworm-slim AS builder
RUN apt-get update && \
    apt-get install -y --no-install-recommends g++ cmake make && \
    rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY CMakeLists.txt main.cpp ./
RUN cmake -B build && cmake --build build

FROM debian:bookworm-slim
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates libstdc++6 && \
    rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /app/build/server .
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s \
    CMD curl -sf http://localhost:8080/health || exit 1
CMD ["./server"]
