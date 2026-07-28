<?php
/**
 * archive-service - eBPF observability stress test
 *
 * Terminal service that receives requests from audit-service and implements
 * deliberate 503 failure injection for retry testing.
 *
 * Endpoints:
 *   POST /api/archive  - Archive with retry test logic
 *   GET  /health       - Health check
 */

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Send a JSON response and exit.
 */
function jsonResponse(int $statusCode, array $body): void
{
    http_response_code($statusCode);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit;
}

/**
 * Retrieve all request headers (works with PHP built-in server).
 */
function getAllHeaders(): array
{
    $headers = [];
    foreach ($_SERVER as $key => $value) {
        if (str_starts_with($key, 'HTTP_')) {
            $name = strtolower(str_replace('_', '-', substr($key, 5)));
            $headers[$name] = $value;
        }
    }
    // Content-Type and Content-Length are not prefixed with HTTP_
    if (isset($_SERVER['CONTENT_TYPE'])) {
        $headers['content-type'] = $_SERVER['CONTENT_TYPE'];
    }
    if (isset($_SERVER['CONTENT_LENGTH'])) {
        $headers['content-length'] = $_SERVER['CONTENT_LENGTH'];
    }
    return $headers;
}

/**
 * Log received x-* tracing headers to stderr (visible in container logs).
 */
function logTracingHeaders(array $headers): void
{
    $traced = [];
    foreach (['x-tenant-id', 'x-env', 'x-degrade', 'x-test-retry', 'x-request-id', 'traceparent'] as $h) {
        if (isset($headers[$h])) {
            $traced[$h] = $headers[$h];
        }
    }
    if (!empty($traced)) {
        error_log('[archive-service] received headers: ' . json_encode($traced));
    }
}

// ---------------------------------------------------------------------------
// Retry counter (file-based, shared across requests via /tmp)
// ---------------------------------------------------------------------------

const RETRY_COUNTER_FILE = '/tmp/retry_counts.json';

/**
 * Load retry counters from disk.
 */
function loadCounters(): array
{
    $raw = @file_get_contents(RETRY_COUNTER_FILE);
    if ($raw === false || $raw === '') {
        return [];
    }
    $data = json_decode($raw, true);
    return is_array($data) ? $data : [];
}

/**
 * Save retry counters to disk.
 */
function saveCounters(array $counters): void
{
    file_put_contents(RETRY_COUNTER_FILE, json_encode($counters), LOCK_EX);
}

// ---------------------------------------------------------------------------
// Routing
// ---------------------------------------------------------------------------

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$path   = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH);
$headers = getAllHeaders();

// --- GET /health ---
if ($method === 'GET' && $path === '/health') {
    jsonResponse(200, [
        'status'  => 'ok',
        'service' => 'archive-service',
    ]);
}

// --- POST /api/archive ---
if ($method === 'POST' && $path === '/api/archive') {
    logTracingHeaders($headers);

    // Parse optional JSON body (not strictly required, but log it)
    $rawBody = file_get_contents('php://input');
    $body = $rawBody !== '' && $rawBody !== false ? (json_decode($rawBody, true) ?? []) : [];

    $requestId = $headers['x-request-id'] ?? 'unknown';
    $now = date('c'); // ISO 8601

    $retryTest = ($headers['x-test-retry'] ?? '') === 'true';

    if (!$retryTest) {
        // No retry header — immediate success
        error_log("[archive-service] request_id={$requestId} → 200 (no retry header)");
        jsonResponse(200, [
            'service'    => 'archive-service',
            'request_id' => $requestId,
            'status'     => 'archived',
            'attempt'    => 1,
            'timestamp'  => $now,
        ]);
    }

    // Retry test mode — fail first 2 attempts, succeed on 3rd
    $counters = loadCounters();
    $count = ($counters[$requestId] ?? 0) + 1;

    if ($count >= 3) {
        // Success on 3rd attempt — reset counter
        unset($counters[$requestId]);
        saveCounters($counters);

        error_log("[archive-service] request_id={$requestId} attempt={$count} → 200 (success)");
        jsonResponse(200, [
            'service'    => 'archive-service',
            'request_id' => $requestId,
            'status'     => 'archived',
            'attempt'    => $count,
            'timestamp'  => $now,
        ]);
    }

    // Attempts 1 and 2 — return 503
    $counters[$requestId] = $count;
    saveCounters($counters);

    error_log("[archive-service] request_id={$requestId} attempt={$count} → 503 (retry)");
    jsonResponse(503, [
        'service'    => 'archive-service',
        'request_id' => $requestId,
        'status'     => 'unavailable',
        'attempt'    => $count,
        'timestamp'  => $now,
    ]);
}

// --- 404 for everything else ---
jsonResponse(404, [
    'error'   => 'not found',
    'service' => 'archive-service',
]);
