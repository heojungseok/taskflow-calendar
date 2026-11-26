#![allow(unreachable_pub)]

use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::sync::mpsc::{Receiver, channel};
use std::thread;
use std::time::Duration;

pub const OK_BODY: &str = r#"{"model":"qwen","message":{"role":"assistant","content":"{\"cause\":\"x\"}"},"done":true,"load_duration":2000000,"prompt_eval_count":10,"prompt_eval_duration":3000000,"eval_count":5,"eval_duration":4000000}"#;

pub struct FakeOllama {
    pub url: String,
    requests: Receiver<String>,
}

impl FakeOllama {
    pub fn request_body(&self) -> serde_json::Value {
        let body = self.requests.recv_timeout(Duration::from_secs(5)).unwrap();
        serde_json::from_str(&body).unwrap()
    }

    pub fn received_nothing(&self) -> bool {
        self.requests
            .recv_timeout(Duration::from_millis(300))
            .is_err()
    }
}

pub fn serve(status: u16, body: &str, delay: Duration) -> FakeOllama {
    serve_with_headers(status, "", body, delay)
}

pub fn serve_with_headers(status: u16, headers: &str, body: &str, delay: Duration) -> FakeOllama {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let (sender, requests) = channel();
    let body = body.to_owned();
    let headers = headers.to_owned();
    thread::spawn(move || {
        let Ok((stream, _)) = listener.accept() else {
            return;
        };
        let mut reader = BufReader::new(stream.try_clone().unwrap());
        let mut length = 0;
        loop {
            let mut header = String::new();
            reader.read_line(&mut header).unwrap();
            if header.trim().is_empty() {
                break;
            }
            if let Some(value) = header.to_ascii_lowercase().strip_prefix("content-length:") {
                length = value.trim().parse().unwrap();
            }
        }
        let mut request = vec![0; length];
        reader.read_exact(&mut request).unwrap();
        sender.send(String::from_utf8(request).unwrap()).unwrap();
        thread::sleep(delay);
        let response = format!(
            "HTTP/1.1 {status} X\r\n{headers}Content-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        );
        let mut stream = stream;
        let _ = stream.write_all(response.as_bytes());
    });
    FakeOllama { url, requests }
}

pub fn closed_port_url() -> String {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    drop(listener);
    url
}

pub fn serve_late_chunked_body(delay: Duration) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    thread::spawn(move || {
        let Ok((mut stream, _)) = listener.accept() else {
            return;
        };
        let mut buffer = [0; 8192];
        let _ = stream.read(&mut buffer);
        let _ = stream.write_all(
            b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n",
        );
        let _ = stream.flush();
        thread::sleep(delay);
        let chunk = format!("{:x}\r\n{OK_BODY}\r\n0\r\n\r\n", OK_BODY.len());
        let _ = stream.write_all(chunk.as_bytes());
    });
    url
}
