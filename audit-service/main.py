"""
audit-service
Receives audit events from trade-service and forwards them to archive-service
with exponential backoff retry logic.
"""

import os
import time
import uuid
import asyncio
import logging
from datetime import datetime, timezone
from typing import Any

import httpx
from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import JSONResponse

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("audit-service")

# ---------------------------------------------------------------------------
# App
# ---------------------------------------------------------------------------

app = FastAPI(title="audit-service", version="1.0.0")

ARCHIVE_SERVICE_URL: str = os.getenv(
    "ARCHIVE_SERVICE_URL", "http://archive-service:8080"
)

# Headers that must never be forwarded upstream
_BLOCKED_HEADERS: set[str] = {
    "host",
    "content-length",
    "transfer-encoding",
    "connection",
    "accept-encoding",
}

# Headers that should be forwarded when present
_FORWARDED_HEADERS: list[str] = [
    "x-tenant-id",
    "x-env",
    "x-degrade",
    "x-test-retry",
    "x-request-id",
    "traceparent",
]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _build_forward_headers(incoming_headers: dict[str, str]) -> dict[str, str]:
    """Return a clean header dict safe to forward to archive-service."""
    out: dict[str, str] = {}

    # Always ensure an x-request-id exists
    request_id = incoming_headers.get("x-request-id") or str(uuid.uuid4())
    out["x-request-id"] = request_id

    for key in _FORWARDED_HEADERS:
        if key == "x-request-id":
            # Already handled above
            continue
        value = incoming_headers.get(key)
        if value is not None:
            out[key] = value

    return out


async def _call_archive(
    url: str,
    json_data: Any,
    headers: dict[str, str],
) -> tuple[dict[str, Any], int]:
    """Single attempt to call archive-service. Returns (body, status_code)."""
    async with httpx.AsyncClient() as client:
        resp = await client.post(url, json=json_data, headers=headers, timeout=5.0)
        try:
            body = resp.json()
        except Exception:
            body = {"raw": resp.text}
        return body, resp.status_code


async def call_with_retry(
    url: str,
    json_data: Any,
    headers: dict[str, str],
    max_retries: int = 3,
) -> tuple[dict[str, Any], int]:
    """
    Call archive-service with exponential backoff.

    Backoff schedule: 0.5 s, 1 s, 2 s  (up to max_retries attempts).
    Retries are triggered on any 5xx response or connection exception.
    Returns (response_body, attempts_made).
    """
    last_body: dict[str, Any] = {"error": "max retries exceeded"}
    attempts = 0

    for attempt in range(max_retries):
        attempts = attempt + 1
        try:
            body, status = await _call_archive(url, json_data, headers)
            if status < 500:
                return body, attempts
            # 5xx → retry with exponential backoff
            last_body = body
            if attempt < max_retries - 1:
                wait = 0.5 * (2 ** attempt)
                await asyncio.sleep(wait)
        except Exception as exc:
            last_body = {"error": str(exc)}
            if attempt < max_retries - 1:
                wait = 0.5 * (2 ** attempt)
                await asyncio.sleep(wait)

    return last_body, attempts


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@app.get("/health")
async def health():
    return {"status": "ok", "service": "audit-service"}


@app.post("/api/audit")
async def audit(request: Request):
    # Parse incoming JSON
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON body")

    # Derive request_id (prefer incoming header, fall back to generated UUID)
    incoming_headers: dict[str, str] = dict(request.headers)
    request_id = incoming_headers.get("x-request-id") or str(uuid.uuid4())

    # Log the event
    ts = datetime.now(timezone.utc).isoformat()
    print(f"[{ts}] audit request_id={request_id}", flush=True)

    # Build headers to forward
    forward_headers = _build_forward_headers(incoming_headers)
    # Ensure x-request-id in forwarded headers matches what we logged
    forward_headers["x-request-id"] = request_id

    # Decide whether retry logic is needed
    use_retry = incoming_headers.get("x-test-retry", "").lower() == "true"

    archive_url = f"{ARCHIVE_SERVICE_URL.rstrip('/')}/api/archive"

    if use_retry:
        archive_result, attempts = await call_with_retry(
            archive_url, body, forward_headers, max_retries=3
        )
        retries_performed = attempts - 1  # first attempt is not a "retry"
    else:
        try:
            archive_result, _ = await _call_archive(archive_url, body, forward_headers)
        except Exception as exc:
            archive_result = {"error": str(exc)}
        retries_performed = 0

    return JSONResponse(
        content={
            "service": "audit-service",
            "request_id": request_id,
            "archive_result": archive_result,
            "retries": retries_performed,
            "timestamp": ts,
        }
    )


@app.post("/api/chain")
async def chain():
    start = time.time()
    await asyncio.sleep(0.01)  # 10ms business logic
    logger.info("audit-service /api/chain")

    archive_url = os.getenv("ARCHIVE_SERVICE_URL", "http://archive-service:8080")

    result = {}
    try:
        # Plain httpx call, NO header forwarding
        async with httpx.AsyncClient() as client:
            resp = await client.post(f"{archive_url}/api/chain", json={}, timeout=30.0)
            result = resp.json()
    except Exception as e:
        result = {"error": str(e)}

    elapsed = int((time.time() - start) * 1000)
    return {"service": "audit-service", "path": "chain", "downstream": result, "elapsed_ms": elapsed}


@app.post("/api/flow")
async def flow():
    start = time.time()
    await asyncio.sleep(0.01)  # 10ms
    logger.info("audit-service /api/flow")

    archive_url = os.getenv("ARCHIVE_SERVICE_URL", "http://archive-service:8080")
    result = {}
    try:
        async with httpx.AsyncClient() as client:
            resp = await client.post(f"{archive_url}/api/flow", json={}, timeout=30.0)
            result = resp.json()
    except Exception as e:
        result = {"error": str(e)}

    elapsed = int((time.time() - start) * 1000)
    return {"service": "audit-service", "path": "flow", "downstream": result, "elapsed_ms": elapsed}


@app.post("/api/concurrent")
async def concurrent():
    start = time.time()
    await asyncio.sleep(0.01)  # 10ms
    logger.info("audit-service /api/concurrent")

    archive_url = os.getenv("ARCHIVE_SERVICE_URL", "http://archive-service:8080")

    result = {}
    try:
        async with httpx.AsyncClient() as client:
            resp = await client.post(f"{archive_url}/api/concurrent", json={}, timeout=30.0)
            result = resp.json()
    except Exception as e:
        result = {"error": str(e)}

    elapsed = int((time.time() - start) * 1000)
    return {"service": "audit-service", "path": "concurrent", "downstream": result, "elapsed_ms": elapsed}
