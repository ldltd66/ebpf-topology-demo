import express, { Request, Response } from "express";
import axios, { AxiosError } from "axios";
import { v4 as uuidv4 } from "uuid";

const app = express();
app.use(express.json());

const PORT = Number(process.env.PORT) || 8080;
const TRADE_SERVICE_URL =
  process.env.TRADE_SERVICE_URL || "http://trade-service:8080";

/**
 * Headers that must NEVER be forwarded to downstream services.
 */
const HOP_BY_HOP_HEADERS = new Set([
  "host",
  "content-length",
  "transfer-encoding",
  "connection",
  "accept-encoding",
]);

/**
 * Headers that MUST be forwarded when present (case-insensitive check).
 */
const WHITELIST_HEADERS = [
  "x-tenant-id",
  "x-env",
  "x-degrade",
  "x-test-retry",
  "x-request-id",
  "traceparent",
];

/**
 * Build the set of outgoing headers from the incoming request.
 * - Always forwards whitelisted x-* headers and traceparent.
 * - Strips hop-by-hop headers.
 */
function buildForwardHeaders(incoming: Record<string, string | string[] | undefined>): Record<string, string> {
  const out: Record<string, string> = {};

  for (const name of WHITELIST_HEADERS) {
    const val = incoming[name];
    if (val !== undefined) {
      out[name] = Array.isArray(val) ? val.join(", ") : val;
    }
  }

  return out;
}

/**
 * Log helper: [timestamp] [requestId] method path → status
 */
function logRequest(
  requestId: string,
  method: string,
  path: string,
  status: number
): void {
  const ts = new Date().toISOString();
  console.log(`[${ts}] [${requestId}] ${method} ${path} → ${status}`);
}

// ─── Health endpoint ─────────────────────────────────────────────────────────

app.get("/health", (_req: Request, res: Response) => {
  res.json({ status: "ok", service: "gateway" });
});

// ─── Trade proxy endpoint ────────────────────────────────────────────────────

app.post("/api/trade", async (req: Request, res: Response) => {
  // 1. Generate or reuse x-request-id
  const incomingHeaders = req.headers as Record<string, string | string[] | undefined>;
  let requestId = incomingHeaders["x-request-id"];
  if (!requestId) {
    requestId = uuidv4();
  }
  const rid = Array.isArray(requestId) ? requestId[0] : requestId;

  // 2. Build forwarded headers
  const forwardHeaders = buildForwardHeaders(
    incomingHeaders as Record<string, string | string[] | undefined>
  );
  // Ensure x-request-id is always present in forwarded headers
  forwardHeaders["x-request-id"] = rid;

  const downstreamUrl = `${TRADE_SERVICE_URL}/api/trade`;

  try {
    // 3. Call trade-service
    const response = await axios({
      method: "POST",
      url: downstreamUrl,
      data: req.body,
      headers: forwardHeaders,
      // Do not throw on non-2xx so we can relay the exact status
      validateStatus: () => true,
      // Propagate raw response data as-is (JSON passthrough)
      responseType: "json",
      timeout: 30_000,
    });

    // 4. Log
    logRequest(rid, req.method, req.path, response.status);

    // 5. Relay response to client
    res.status(response.status).json(response.data);
  } catch (err) {
    const axiosErr = err as AxiosError;
    const status = axiosErr.response?.status ?? 502;
    logRequest(rid, req.method, req.path, status);

    const message =
      axiosErr.response?.data ??
      (axiosErr.code === "ECONNREFUSED"
        ? { error: "trade-service unavailable" }
        : { error: "gateway upstream error", detail: axiosErr.message });

    res.status(status).json(message);
  }
});

// ─── Start server ────────────────────────────────────────────────────────────

app.listen(PORT, () => {
  console.log(`[gateway] listening on :${PORT}`);
  console.log(`[gateway] TRADE_SERVICE_URL=${TRADE_SERVICE_URL}`);
});

export default app;
