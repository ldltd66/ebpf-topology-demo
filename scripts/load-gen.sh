#!/bin/bash
# eBPF Topology Demo - Load Generator
# Generates continuous traffic with probabilistic header injection
# Usage: GATEWAY=http://<gateway-ip>:30080 bash scripts/load-gen.sh

GATEWAY="${GATEWAY:-http://localhost:30080}"
RATE="${RATE:-2}"  # requests per second
echo "=== eBPF Topology Load Generator ==="
echo "Gateway: $GATEWAY"
echo "Rate: ~${RATE} req/s"
echo "Distribution: 70% default, 10% VIP, 10% degrade, 10% retry"
echo "Press Ctrl+C to stop"
echo ""

count=0
while true; do
  rand=$((RANDOM % 100))
  rid=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "req-$RANDOM-$RANDOM")

  if [ $rand -lt 70 ]; then
    # Default: Node → Java → Go(gRPC) → C++ → Java(callback) → Python → PHP
    resp=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$GATEWAY/api/trade" \
      -H "Content-Type: application/json" \
      -H "x-request-id: $rid" \
      -d "{\"amount\":$((RANDOM % 1000 + 1))}" 2>/dev/null)
    tag="DEFAULT"
  elif [ $rand -lt 80 ]; then
    # VIP: Java → Rust + Python (parallel)
    resp=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$GATEWAY/api/trade" \
      -H "Content-Type: application/json" \
      -H "x-tenant-id: vip" \
      -H "x-request-id: $rid" \
      -d "{\"amount\":$((RANDOM % 1000 + 1))}" 2>/dev/null)
    tag="VIP"
  elif [ $rand -lt 90 ]; then
    # Degrade: Java → PHP only
    resp=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$GATEWAY/api/trade" \
      -H "Content-Type: application/json" \
      -H "x-env: gray" \
      -H "x-degrade: true" \
      -H "x-request-id: $rid" \
      -d "{\"amount\":$((RANDOM % 1000 + 1))}" 2>/dev/null)
    tag="DEGRADE"
  else
    # Retry: Python → PHP (503 × 2 → 200)
    resp=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$GATEWAY/api/trade" \
      -H "Content-Type: application/json" \
      -H "x-test-retry: true" \
      -H "x-request-id: $rid" \
      -d "{\"amount\":$((RANDOM % 1000 + 1))}" 2>/dev/null)
    tag="RETRY"
  fi

  count=$((count + 1))
  ts=$(date '+%H:%M:%S')
  printf "[%s] #%04d %-8s → %s\n" "$ts" "$count" "$tag" "${resp:-TIMEOUT}"

  sleep "$(echo "scale=2; 1/$RATE" | bc 2>/dev/null || echo 0.5)"
done
