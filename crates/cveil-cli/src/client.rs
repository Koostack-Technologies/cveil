use std::io::Read;

use serde_json::Value;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::UnixStream;

use crate::cli::{EvaluateArgs, MaskArgs, MaskBatchArgs, SocketArgs};

fn read_json_input(input: &str) -> Result<Value, String> {
    if input == "-" {
        let mut buf = String::new();
        std::io::stdin()
            .read_to_string(&mut buf)
            .map_err(|e| format!("Failed to read stdin: {e}"))?;
        serde_json::from_str(&buf).map_err(|e| format!("Invalid JSON from stdin: {e}"))
    } else if input.starts_with('{') || input.starts_with('[') {
        serde_json::from_str(input).map_err(|e| format!("Invalid inline JSON: {e}"))
    } else {
        let content = std::fs::read_to_string(input)
            .map_err(|e| format!("Failed to read file {input}: {e}"))?;
        serde_json::from_str(&content).map_err(|e| format!("Invalid JSON in file {input}: {e}"))
    }
}

fn print_json(value: &Value, pretty: bool) {
    if pretty {
        println!("{}", serde_json::to_string_pretty(value).unwrap());
    } else {
        println!("{}", serde_json::to_string(value).unwrap());
    }
}

/// Send a raw HTTP/1.1 request over a Unix domain socket.
async fn uds_request(
    socket_path: &str,
    method: &str,
    path: &str,
    body: Option<Value>,
) -> Result<Value, String> {
    let mut stream = UnixStream::connect(socket_path)
        .await
        .map_err(|e| format!("Failed to connect to daemon at {socket_path}: {e}"))?;

    let body_str = body
        .map(|v| serde_json::to_string(&v).unwrap())
        .unwrap_or_default();

    // Build raw HTTP/1.1 request
    let request = if body_str.is_empty() {
        format!(
            "{method} {path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
        )
    } else {
        format!(
            "{method} {path} HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body_str}",
            body_str.len()
        )
    };

    stream
        .write_all(request.as_bytes())
        .await
        .map_err(|e| format!("Failed to send request: {e}"))?;

    // Read full response
    let mut response_buf = Vec::new();
    stream
        .read_to_end(&mut response_buf)
        .await
        .map_err(|e| format!("Failed to read response: {e}"))?;

    let response_str = String::from_utf8_lossy(&response_buf);

    // Parse HTTP response — find the body after \r\n\r\n
    let body_start = response_str
        .find("\r\n\r\n")
        .ok_or("Invalid HTTP response — no header/body separator")?
        + 4;
    let status_line = response_str.lines().next().unwrap_or("");

    let body_content = &response_str[body_start..];

    // Handle chunked transfer encoding
    let json_body = if response_str.contains("transfer-encoding: chunked")
        || response_str.contains("Transfer-Encoding: chunked")
    {
        decode_chunked(body_content)?
    } else {
        body_content.to_string()
    };

    // Check status
    if !status_line.contains("200") && !status_line.contains("201") {
        return Err(format!("Daemon returned {status_line}: {json_body}"));
    }

    serde_json::from_str(json_body.trim())
        .map_err(|e| format!("Invalid JSON response: {e}\nRaw: {json_body}"))
}

/// Decode chunked transfer encoding.
fn decode_chunked(input: &str) -> Result<String, String> {
    let mut result = String::new();
    let mut remaining = input;

    loop {
        // Skip leading whitespace/newlines
        remaining = remaining.trim_start_matches(|c: char| c == '\r' || c == '\n');
        if remaining.is_empty() {
            break;
        }

        // Read chunk size (hex)
        let size_end = remaining.find("\r\n").unwrap_or(remaining.len());
        let size_str = &remaining[..size_end];
        let chunk_size = usize::from_str_radix(size_str.trim(), 16)
            .map_err(|_| format!("Invalid chunk size: '{size_str}'"))?;

        if chunk_size == 0 {
            break;
        }

        remaining = &remaining[size_end + 2..];
        if remaining.len() < chunk_size {
            result.push_str(remaining);
            break;
        }
        result.push_str(&remaining[..chunk_size]);
        remaining = &remaining[chunk_size..];
    }

    Ok(result)
}

// ── Subcommand implementations ─────────────────────────────────────

pub async fn run_mask(args: MaskArgs) -> i32 {
    let data = match read_json_input(&args.input) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("Error: {e}");
            return 1;
        }
    };

    let mut body = serde_json::json!({
        "subject": args.subject,
        "entity": args.entity,
        "purpose": args.purpose,
        "data": data,
    });
    if let Some(ref j) = args.jurisdiction {
        body["jurisdiction"] = Value::String(j.clone());
    }

    match uds_request(&args.socket, "POST", "/mask", Some(body)).await {
        Ok(result) => {
            print_json(&result, args.pretty);
            0
        }
        Err(e) => {
            eprintln!("Error: {e}");
            1
        }
    }
}

pub async fn run_mask_batch(args: MaskBatchArgs) -> i32 {
    let data = match read_json_input(&args.input) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("Error: {e}");
            return 1;
        }
    };

    let mut body = serde_json::json!({
        "subject": args.subject,
        "entity": args.entity,
        "purpose": args.purpose,
        "data": data,
    });
    if let Some(ref j) = args.jurisdiction {
        body["jurisdiction"] = Value::String(j.clone());
    }

    match uds_request(&args.socket, "POST", "/mask/batch", Some(body)).await {
        Ok(result) => {
            print_json(&result, args.pretty);
            0
        }
        Err(e) => {
            eprintln!("Error: {e}");
            1
        }
    }
}

pub async fn run_evaluate(args: EvaluateArgs) -> i32 {
    let body = serde_json::json!({
        "subject": args.subject,
        "entity": args.entity,
        "purpose": args.purpose,
        "fields": args.fields,
    });

    match uds_request(&args.socket, "POST", "/evaluate", Some(body)).await {
        Ok(result) => {
            print_json(&result, args.pretty);
            0
        }
        Err(e) => {
            eprintln!("Error: {e}");
            1
        }
    }
}

pub async fn run_status(args: SocketArgs) -> i32 {
    match uds_request(&args.socket, "GET", "/status", None).await {
        Ok(result) => {
            print_json(&result, true);
            0
        }
        Err(e) => {
            eprintln!("Error: {e}");
            1
        }
    }
}

pub async fn run_stop(args: SocketArgs) -> i32 {
    match uds_request(&args.socket, "POST", "/shutdown", None).await {
        Ok(_) => {
            println!("Daemon shutting down");
            0
        }
        Err(e) => {
            eprintln!("Error: {e}");
            1
        }
    }
}
