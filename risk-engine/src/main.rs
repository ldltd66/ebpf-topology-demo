use axum::{
    extract::State,
    http::{HeaderMap, StatusCode},
    response::IntoResponse,
    routing::{get, post},
    Json, Router,
};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use std::env;
use std::sync::Arc;
use tokio::time::{sleep, Duration};
use tracing::{error, info};

// ── Request / Response types ────────────────────────────────────────────────

#[derive(Debug, Deserialize)]
struct RiskCheckRequest {
    request_id: Option<String>,
    amount: Option<f64>,
    #[serde(default)]
    headers: serde_json::Value,
}

#[derive(Debug, Serialize)]
struct RiskCheckResponse {
    service: &'static str,
    request_id: String,
    status: &'static str,
    timestamp: String,
}

#[derive(Debug, Serialize)]
struct CallbackPayload {
    request_id: String,
    status: &'static str,
    service: &'static str,
}

#[derive(Debug, Serialize)]
struct HealthResponse {
    status: &'static str,
    service: &'static str,
}

// ── Shared application state ────────────────────────────────────────────────

#[derive(Clone)]
struct AppState {
    http_client: reqwest::Client,
    callback_url: String,
}

// ── Headers that must NEVER be forwarded ────────────────────────────────────

const NEVER_FORWARD: &[&str] = &[
    "host",
    "content-length",
    "transfer-encoding",
    "connection",
];

// ── Headers we actively look for (lowercase) ────────────────────────────────

const FORWARD_HEADERS: &[&str] = &[
    "x-tenant-id",
    "x-env",
    "x-degrade",
    "x-test-retry",
    "x-request-id",
    "traceparent",
];

/// Collect all x-* / traceparent headers from the incoming request, skipping
/// the ones that must never be forwarded.
fn collect_forwardable_headers(incoming: &HeaderMap) -> Vec<(String, String)> {
    let mut out = Vec::new();

    // Always-forward list: grab these by name if present.
    for name in FORWARD_HEADERS {
        if let Some(val) = incoming.get(*name) {
            if let Ok(v) = val.to_str() {
                out.push((name.to_string(), v.to_string()));
            }
        }
    }

    // Also sweep for any other x-* headers that aren't on the block list.
    for (name, value) in incoming.iter() {
        let lower = name.as_str().to_lowercase();
        if lower.starts_with("x-") && !FORWARD_HEADERS.contains(&lower.as_str()) {
            if NEVER_FORWARD.contains(&lower.as_str()) {
                continue;
            }
            if let Ok(v) = value.to_str() {
                out.push((lower, v.to_string()));
            }
        }
    }

    out
}

// ── Handlers ────────────────────────────────────────────────────────────────

async fn health() -> impl IntoResponse {
    (StatusCode::OK, Json(HealthResponse {
        status: "ok",
        service: "risk-engine",
    }))
}

async fn risk_check(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(body): Json<RiskCheckRequest>,
) -> impl IntoResponse {
    let request_id = body
        .request_id
        .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());

    let timestamp = Utc::now().to_rfc3339();

    info!("[{}] risk-check request_id={}", timestamp, request_id);

    // Collect headers worth forwarding.
    let forwardable = collect_forwardable_headers(&headers);

    // Spawn background task – the caller gets 202 immediately.
    let client = state.http_client.clone();
    let callback_url = state.callback_url.clone();
    let rid = request_id.clone();

    tokio::spawn(async move {
        // Simulate async computation.
        sleep(Duration::from_millis(100)).await;

        // Build callback payload.
        let payload = CallbackPayload {
            request_id: rid.clone(),
            status: "risk_passed",
            service: "risk-engine",
        };

        // Build the outbound request with forwarded headers.
        let mut req_builder = client.post(&callback_url).json(&payload);

        for (k, v) in &forwardable {
            req_builder = req_builder.header(k.as_str(), v.as_str());
        }

        match req_builder.send().await {
            Ok(resp) => {
                info!(
                    "callback sent request_id={} status={}",
                    rid,
                    resp.status()
                );
            }
            Err(e) => {
                error!("callback failed request_id={} error={}", rid, e);
            }
        }
    });

    // Immediate 202 Accepted.
    let resp = RiskCheckResponse {
        service: "risk-engine",
        request_id,
        status: "accepted",
        timestamp,
    };

    (StatusCode::ACCEPTED, Json(resp))
}

// ── Entry point ─────────────────────────────────────────────────────────────

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_target(false)
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    let callback_url = env::var("TRADE_CALLBACK_URL")
        .unwrap_or_else(|_| "http://trade-service:8080/api/trade/callback".to_string());

    let state = Arc::new(AppState {
        http_client: reqwest::Client::new(),
        callback_url,
    });

    let app = Router::new()
        .route("/api/risk-check", post(risk_check))
        .route("/health", get(health))
        .with_state(state);

    let bind_addr = "0.0.0.0:8080";
    info!("risk-engine listening on {}", bind_addr);

    let listener = tokio::net::TcpListener::bind(bind_addr)
        .await
        .expect("failed to bind");

    axum::serve(listener, app)
        .await
        .expect("server error");
}
