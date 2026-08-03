#include <algorithm>
#include <arpa/inet.h>
#include <cctype>
#include <cerrno>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <iomanip>
#include <iostream>
#include <map>
#include <mutex>
#include <netdb.h>
#include <netinet/in.h>
#include <sstream>
#include <string>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>
#include <vector>

// ---------------------------------------------------------------------------
// Data types
// ---------------------------------------------------------------------------

struct HttpRequest {
    std::string method;
    std::string path;
    std::map<std::string, std::string> headers;
    std::string body;
};

struct HttpResponse {
    int         status_code;
    std::string status_text;
    std::map<std::string, std::string> headers;
    std::string body;

    std::string serialize() const {
        std::ostringstream oss;
        oss << "HTTP/1.1 " << status_code << " " << status_text << "\r\n";
        for (const auto& [k, v] : headers)
            oss << k << ": " << v << "\r\n";
        oss << "Content-Length: " << body.size() << "\r\n";
        oss << "\r\n";
        oss << body;
        return oss.str();
    }
};

// ---------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------

static std::mutex g_log_mutex;

static std::string get_timestamp() {
    auto now      = std::chrono::system_clock::now();
    auto time_t_n = std::chrono::system_clock::to_time_t(now);
    auto ms       = std::chrono::duration_cast<std::chrono::milliseconds>(
                        now.time_since_epoch()) % 1000;

    struct tm tm_buf;
    gmtime_r(&time_t_n, &tm_buf);

    char buf[64];
    strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%S", &tm_buf);

    std::ostringstream oss;
    oss << buf << "." << std::setw(3) << std::setfill('0') << ms.count() << "Z";
    return oss.str();
}

static void log_msg(const std::string& msg) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    std::cout << "[" << get_timestamp() << "] " << msg << std::endl;
}

static std::string to_lower(const std::string& s) {
    std::string r = s;
    std::transform(r.begin(), r.end(), r.begin(),
                   [](unsigned char c) { return (char)std::tolower(c); });
    return r;
}

// ---------------------------------------------------------------------------
// Simple JSON helpers (flat objects only)
// ---------------------------------------------------------------------------

// Extract a string or numeric value for a given key from a flat JSON object.
static std::string json_get(const std::string& json, const std::string& key) {
    std::string needle = "\"" + key + "\"";
    size_t pos = json.find(needle);
    if (pos == std::string::npos) return "";

    pos = json.find(':', pos + needle.size());
    if (pos == std::string::npos) return "";

    // skip whitespace
    while (++pos < json.size() && (json[pos] == ' ' || json[pos] == '\t')) {}
    if (pos >= json.size()) return "";

    if (json[pos] == '"') {
        size_t end = json.find('"', pos + 1);
        if (end == std::string::npos) return "";
        return json.substr(pos + 1, end - pos - 1);
    }
    // number / literal
    size_t end = json.find_first_of(",} \t\n\r", pos);
    if (end == std::string::npos) end = json.size();
    return json.substr(pos, end - pos);
}

// Build a JSON object from key-value pairs (all values treated as strings).
static std::string json_build(
        const std::vector<std::pair<std::string, std::string>>& kvs) {
    std::ostringstream oss;
    oss << "{";
    bool first = true;
    for (const auto& [k, v] : kvs) {
        if (!first) oss << ",";
        first = false;
        oss << "\"" << k << "\":\"" << v << "\"";
    }
    oss << "}";
    return oss.str();
}

// ---------------------------------------------------------------------------
// HTTP request parser
// ---------------------------------------------------------------------------

static HttpRequest parse_request(const std::string& raw) {
    HttpRequest req;
    std::istringstream iss(raw);

    // Request line
    std::string line;
    std::getline(iss, line);
    if (!line.empty() && line.back() == '\r') line.pop_back();
    {
        std::istringstream rl(line);
        rl >> req.method >> req.path;
    }

    // Headers
    while (std::getline(iss, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty()) break;
        size_t colon = line.find(':');
        if (colon != std::string::npos) {
            std::string key   = line.substr(0, colon);
            std::string value = line.substr(colon + 1);
            size_t start = value.find_first_not_of(" \t");
            if (start != std::string::npos) value = value.substr(start);
            req.headers[key] = value;
        }
    }

    // Body — use Content-Length to determine size
    for (const auto& [k, v] : req.headers) {
        if (to_lower(k) == "content-length") {
            int len = std::stoi(v);
            // Everything remaining in the stream is body data already read
            std::string rest;
            while (std::getline(iss, line)) {
                rest += line + "\n";
            }
            if (!rest.empty() && rest.back() == '\n') rest.pop_back();
            req.body = (int)rest.size() > len ? rest.substr(0, len) : rest;
            break;
        }
    }

    return req;
}

// ---------------------------------------------------------------------------
// HTTP client (POST only, minimal)
// ---------------------------------------------------------------------------

// Returns true on success; response_body receives the remote body.
static bool http_post(const std::string& url,
                      const std::string& body,
                      const std::map<std::string, std::string>& extra_headers,
                      std::string& response_body) {
    // Parse URL  (http://host:port/path)
    std::string work = url;
    if (work.size() > 7 && work.substr(0, 7) == "http://")
        work = work.substr(7);

    std::string host;
    int         port = 80;
    std::string path = "/";

    size_t slash = work.find('/');
    std::string host_port = (slash != std::string::npos)
                                ? work.substr(0, slash) : work;
    if (slash != std::string::npos) path = work.substr(slash);

    size_t colon = host_port.find(':');
    if (colon != std::string::npos) {
        host = host_port.substr(0, colon);
        port = std::stoi(host_port.substr(colon + 1));
    } else {
        host = host_port;
    }

    // DNS
    struct hostent* he = gethostbyname(host.c_str());
    if (!he) {
        log_msg("DNS resolution failed for " + host);
        return false;
    }

    int sock = ::socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return false;

    struct timeval tv;
    tv.tv_sec  = 5;
    tv.tv_usec = 0;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    struct sockaddr_in addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port   = htons(port);
    std::memcpy(&addr.sin_addr, he->h_addr, (size_t)he->h_length);

    if (::connect(sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        log_msg("connect() failed to " + host + ":" + std::to_string(port));
        ::close(sock);
        return false;
    }

    // Build HTTP request
    std::ostringstream req;
    req << "POST " << path << " HTTP/1.1\r\n"
        << "Host: " << host << ":" << port << "\r\n"
        << "Content-Type: application/json\r\n"
        << "Content-Length: " << body.size() << "\r\n";
    for (const auto& [k, v] : extra_headers)
        req << k << ": " << v << "\r\n";
    req << "Connection: close\r\n\r\n"
        << body;

    std::string req_str = req.str();
    ::send(sock, req_str.c_str(), req_str.size(), 0);

    // Read response
    char buf[4096];
    std::string response;
    ssize_t n;
    while ((n = ::recv(sock, buf, sizeof(buf), 0)) > 0)
        response.append(buf, (size_t)n);

    ::close(sock);

    size_t body_start = response.find("\r\n\r\n");
    if (body_start != std::string::npos)
        response_body = response.substr(body_start + 4);

    return true;
}

// ---------------------------------------------------------------------------
// Header forwarding
// ---------------------------------------------------------------------------

// Headers we MUST forward (lowercase for comparison).
static const std::vector<std::string> MUST_FORWARD = {
    "x-tenant-id", "x-env", "x-degrade",
    "x-test-retry", "x-request-id", "traceparent"
};

// Collect matching headers from the incoming request for forwarding.
static std::map<std::string, std::string>
collect_forward_headers(const std::map<std::string, std::string>& in_headers) {
    std::map<std::string, std::string> out;
    for (const auto& [k, v] : in_headers) {
        std::string lk = to_lower(k);
        for (const auto& want : MUST_FORWARD) {
            if (lk == want) {
                out[k] = v;
                break;
            }
        }
    }
    return out;
}

// ---------------------------------------------------------------------------
// Request handlers
// ---------------------------------------------------------------------------

static HttpResponse handle_health() {
    HttpResponse resp;
    resp.status_code  = 200;
    resp.status_text  = "OK";
    resp.headers["Content-Type"] = "application/json";
    resp.headers["Connection"]   = "close";
    resp.body = "{\"status\":\"ok\",\"service\":\"clearing-service\"}";
    return resp;
}

static HttpResponse handle_clear(const HttpRequest& req) {
    std::string request_id = json_get(req.body, "request_id");
    std::string amount     = json_get(req.body, "amount");

    log_msg("clearing request_id=" + request_id +
            (amount.empty() ? "" : " amount=" + amount));

    // Simulate clearing processing
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    // Resolve callback URL
    const char* env_url = std::getenv("TRADE_CALLBACK_URL");
    std::string callback_url = env_url
        ? env_url
        : "http://trade-service:8080/api/trade/callback";

    // Build callback payload
    std::string callback_body = json_build({
        {"request_id", request_id},
        {"status",     "cleared"},
        {"service",    "clearing-service"}
    });

    // Collect headers to forward
    auto fwd_headers = collect_forward_headers(req.headers);

    // Send callback (best-effort; log result)
    std::string cb_resp;
    bool cb_ok = http_post(callback_url, callback_body, fwd_headers, cb_resp);
    log_msg(cb_ok
        ? "callback sent for request_id=" + request_id
        : "callback FAILED for request_id=" + request_id);

    // Respond to caller (risk-service)
    HttpResponse resp;
    resp.status_code  = 200;
    resp.status_text  = "OK";
    resp.headers["Content-Type"] = "application/json";
    resp.headers["Connection"]   = "close";
    resp.body = json_build({
        {"service",    "clearing-service"},
        {"request_id", request_id},
        {"status",     "cleared"},
        {"timestamp",  get_timestamp()}
    });
    return resp;
}

static HttpResponse handle_chain(const HttpRequest& /*req*/) {
    auto start = std::chrono::steady_clock::now();

    // Simulate processing
    std::this_thread::sleep_for(std::chrono::milliseconds(10));

    // Resolve audit service URL
    const char* env_url = std::getenv("AUDIT_SERVICE_URL");
    std::string audit_url = env_url
        ? env_url
        : "http://audit-service:8080";
    audit_url += "/api/chain";

    // POST to audit-service with empty JSON body, no header forwarding
    std::string cb_resp;
    bool cb_ok = http_post(audit_url, "{}", {}, cb_resp);
    log_msg(cb_ok
        ? "chain: forwarded to audit-service"
        : "chain: FAILED to forward to audit-service");

    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start).count();

    HttpResponse resp;
    resp.status_code  = 200;
    resp.status_text  = "OK";
    resp.headers["Content-Type"] = "application/json";
    resp.headers["Connection"]   = "close";
    std::ostringstream oss;
    oss << "{\"service\":\"clearing-service\",\"path\":\"chain\",\"elapsed_ms\":"
        << elapsed << "}";
    resp.body = oss.str();
    return resp;
}

static HttpResponse handle_rpc(const HttpRequest& /*req*/) {
    auto start = std::chrono::steady_clock::now();

    // Simulate processing
    std::this_thread::sleep_for(std::chrono::milliseconds(10));

    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start).count();

    HttpResponse resp;
    resp.status_code  = 200;
    resp.status_text  = "OK";
    resp.headers["Content-Type"] = "application/json";
    resp.headers["Connection"]   = "close";
    std::ostringstream oss;
    oss << "{\"service\":\"clearing-service\",\"path\":\"rpc\",\"elapsed_ms\":"
        << elapsed << "}";
    resp.body = oss.str();
    return resp;
}

static HttpResponse handle_concurrent(const HttpRequest& /*req*/) {
    auto start = std::chrono::steady_clock::now();

    // Simulate processing
    std::this_thread::sleep_for(std::chrono::milliseconds(10));

    // Resolve archive service URL
    const char* env_url = std::getenv("ARCHIVE_SERVICE_URL");
    std::string archive_url = env_url
        ? env_url
        : "http://archive-service:8080";
    archive_url += "/api/concurrent";

    // POST to archive-service with empty JSON body, no header forwarding
    std::string cb_resp;
    bool cb_ok = http_post(archive_url, "{}", {}, cb_resp);
    log_msg(cb_ok
        ? "concurrent: forwarded to archive-service"
        : "concurrent: FAILED to forward to archive-service");

    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start).count();

    HttpResponse resp;
    resp.status_code  = 200;
    resp.status_text  = "OK";
    resp.headers["Content-Type"] = "application/json";
    resp.headers["Connection"]   = "close";
    std::ostringstream oss;
    oss << "{\"service\":\"clearing-service\",\"path\":\"concurrent\",\"elapsed_ms\":"
        << elapsed << "}";
    resp.body = oss.str();
    return resp;
}

// ---------------------------------------------------------------------------
// Connection handler (runs in a detached thread per connection)
// ---------------------------------------------------------------------------

static void handle_connection(int client_sock) {
    // Set a receive timeout so we don't block forever on slow clients.
    struct timeval tv;
    tv.tv_sec  = 10;
    tv.tv_usec = 0;
    setsockopt(client_sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    // Read raw request data.
    // Strategy: read until we have complete headers, then read remaining body
    // bytes as indicated by Content-Length.
    char        buf[8192];
    std::string raw;
    ssize_t     n;

    while ((n = ::recv(client_sock, buf, sizeof(buf), 0)) > 0) {
        raw.append(buf, (size_t)n);

        size_t hdr_end = raw.find("\r\n\r\n");
        if (hdr_end == std::string::npos) continue;   // need more data

        // We have the full header block.  Determine Content-Length.
        std::string hdr_block = raw.substr(0, hdr_end);
        int content_length    = 0;

        // Simple Content-Length extraction
        {
            std::string lower_hdrs = to_lower(hdr_block);
            size_t cl_pos = lower_hdrs.find("content-length:");
            if (cl_pos != std::string::npos) {
                size_t vstart = cl_pos + 15;  // strlen("content-length:")
                size_t vend   = lower_hdrs.find("\r\n", vstart);
                std::string cl_val = hdr_block.substr(vstart, vend - vstart);
                size_t first = cl_val.find_first_not_of(" \t");
                if (first != std::string::npos) cl_val = cl_val.substr(first);
                content_length = std::stoi(cl_val);
            }
        }

        size_t body_have = raw.size() - hdr_end - 4;
        while ((int)body_have < content_length) {
            n = ::recv(client_sock, buf, sizeof(buf), 0);
            if (n <= 0) break;
            raw.append(buf, (size_t)n);
            body_have += (size_t)n;
        }
        break;  // full request received
    }

    if (raw.empty()) {
        ::close(client_sock);
        return;
    }

    HttpRequest  req  = parse_request(raw);
    HttpResponse resp;

    if (req.method == "GET" && req.path == "/health") {
        resp = handle_health();
    } else if (req.method == "POST" && req.path == "/api/clear") {
        resp = handle_clear(req);
    } else if (req.method == "POST" && req.path == "/api/chain") {
        resp = handle_chain(req);
    } else if (req.method == "POST" && req.path == "/api/rpc") {
        resp = handle_rpc(req);
    } else if (req.method == "POST" && req.path == "/api/concurrent") {
        resp = handle_concurrent(req);
    } else {
        resp.status_code  = 404;
        resp.status_text  = "Not Found";
        resp.headers["Content-Type"] = "application/json";
        resp.headers["Connection"]   = "close";
        resp.body = "{\"error\":\"not found\"}";
    }

    std::string resp_str = resp.serialize();
    ::send(client_sock, resp_str.c_str(), resp_str.size(), 0);
    ::close(client_sock);
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

int main() {
    int server_sock = ::socket(AF_INET, SOCK_STREAM, 0);
    if (server_sock < 0) {
        std::cerr << "socket() failed: " << strerror(errno) << "\n";
        return 1;
    }

    int opt = 1;
    setsockopt(server_sock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sin_family      = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port        = htons(8080);

    if (::bind(server_sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        std::cerr << "bind(:8080) failed: " << strerror(errno) << "\n";
        return 1;
    }

    if (::listen(server_sock, 128) < 0) {
        std::cerr << "listen() failed: " << strerror(errno) << "\n";
        return 1;
    }

    log_msg("clearing-service listening on :8080");

    while (true) {
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_sock = ::accept(server_sock,
                                   (struct sockaddr*)&client_addr,
                                   &client_len);
        if (client_sock < 0) {
            log_msg(std::string("accept() failed: ") + strerror(errno));
            continue;
        }
        std::thread(handle_connection, client_sock).detach();
    }

    ::close(server_sock);
    return 0;
}
