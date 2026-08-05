// Legacy risk-engine (Rust 1.70 + axum 0.6, no tonic/reqwest)
// Only supports /api/flow (terminal) and /api/risk-check (raw TCP callback)
// No gRPC client — Path B gRPC is modern-only

use axum::{
    http::StatusCode,
    response::IntoResponse,
    routing::{get, post},
    Json, Router,
};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::env;
use std::net::SocketAddr;
use tokio::io::AsyncWriteExt;
use tokio::net::TcpStream;
use tokio::time::{sleep, Duration};
use tracing::info;

#[derive(Debug, Deserialize)]
struct RiskCheckRequest {
    request_id: Option<String>,
    amount: Option<f64>,
}

#[derive(Serialize)]
struct RiskCheckResponse {
    service: String,
    request_id: String,
    status: String,
    timestamp: String,
}

async fn health() -> impl IntoResponse {
    (StatusCode::OK, Json(json!({"status": "ok", "service": "risk-engine"})))
}

// POST /api/risk-check — async callback via raw TCP (no reqwest)
async fn risk_check(Json(req): Json<RiskCheckRequest>) -> impl IntoResponse {
    let request_id = req.request_id.unwrap_or_else(|| {
        use std::time::{SystemTime, UNIX_EPOCH};
        format!("req-{}", SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis())
    });

    info!("[{}] risk-check request_id={}", Utc::now(), request_id);

    // Spawn async callback task
    let rid = request_id.clone();
    tokio::spawn(async move {
        sleep(Duration::from_millis(100)).await;

        let callback_url = env::var("TRADE_CALLBACK_URL")
            .unwrap_or_else(|_| "http://trade-service:8080/api/trade/callback".to_string());

        // Parse URL manually (no reqwest)
        let (host, port, path) = parse_url(&callback_url);

        let body = format!(
            r#"{{"request_id":"{}","status":"risk_passed","service":"risk-engine"}}"#,
            rid
        );

        if let Ok(mut stream) = TcpStream::connect((host.as_str(), port)).await {
            let req = format!(
                "POST {} HTTP/1.1\r\nHost: {}:{}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                path, host, port, body.len(), body
            );
            let _ = stream.write_all(req.as_bytes()).await;
            let _ = stream.flush().await;
            info!("[{}] callback sent to {}", Utc::now(), callback_url);
        }
    });

    (
        StatusCode::ACCEPTED,
        Json(RiskCheckResponse {
            service: "risk-engine".to_string(),
            request_id,
            status: "accepted".to_string(),
            timestamp: Utc::now().to_rfc3339(),
        }),
    )
}

// POST /api/flow — terminal (sleep 10ms + return)
async fn flow_handler() -> impl IntoResponse {
    let start = std::time::Instant::now();
    sleep(Duration::from_millis(10)).await;
    let elapsed = start.elapsed().as_millis();
    info!("[{}] risk-engine /api/flow", Utc::now());
    Json(json!({
        "service": "risk-engine",
        "path": "flow",
        "elapsed_ms": elapsed,
    }))
}

// POST /api/rpc — modern path B uses gRPC, legacy just returns (no gRPC client)
async fn rpc_handler() -> impl IntoResponse {
    let start = std::time::Instant::now();
    sleep(Duration::from_millis(10)).await;
    let elapsed = start.elapsed().as_millis();
    Json(json!({
        "service": "risk-engine",
        "path": "rpc",
        "status": "ok",
        "note": "legacy build has no gRPC client",
        "elapsed_ms": elapsed,
    }))
}

fn parse_url(url: &str) -> (String, u16, String) {
    let url = url.strip_prefix("http://").unwrap_or(url);
    let (host_port, path) = url.split_once('/').unwrap_or((url, "/"));
    let (host, port) = if let Some((h, p)) = host_port.rsplit_once(':') {
        (h.to_string(), p.parse().unwrap_or(8080))
    } else {
        (host_port.to_string(), 8080)
    };
    (host, port, format!("/{}", path))
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt().with_env_filter(
        tracing_subscriber::EnvFilter::try_from_default_env()
            .unwrap_or_else(|_| "info".into())
    ).init();

    let port: u16 = env::var("PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(8080);
    let addr = SocketAddr::from(([0, 0, 0, 0], port));

    let app = Router::new()
        .route("/health", get(health))
        .route("/api/flow", post(flow_handler))
        .route("/api/risk-check", post(risk_check))
        .route("/api/rpc", post(rpc_handler));

    info!("[risk-engine] listening on :{}", port);
    info!("[risk-engine] TRADE_CALLBACK_URL={}", env::var("TRADE_CALLBACK_URL").unwrap_or_default());

    axum::Server::bind(&addr)
        .serve(app.into_make_service())
        .await
        .unwrap();
}
