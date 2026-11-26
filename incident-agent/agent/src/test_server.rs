use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::thread;

pub(crate) struct Request {
    pub(crate) target: String,
}

pub(crate) struct Reply {
    pub(crate) status: u16,
    pub(crate) body: String,
}

impl Reply {
    pub(crate) fn ok(body: &str) -> Self {
        Self::status(200, body)
    }

    pub(crate) fn status(status: u16, body: &str) -> Self {
        Self {
            status,
            body: body.to_owned(),
        }
    }
}

pub(crate) struct TestServer {
    pub(crate) addr: SocketAddr,
    pub(crate) requests: Arc<Mutex<Vec<Request>>>,
}

impl TestServer {
    pub(crate) fn url(&self) -> String {
        format!("http://{}", self.addr)
    }
}

pub(crate) fn serve(handler: impl Fn(&Request) -> Reply + Send + Sync + 'static) -> TestServer {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let requests = Arc::new(Mutex::new(Vec::new()));
    let handler = Arc::new(handler);
    let seen = Arc::clone(&requests);
    thread::spawn(move || {
        for stream in listener.incoming().flatten() {
            let (handler, seen) = (Arc::clone(&handler), Arc::clone(&seen));
            thread::spawn(move || answer(stream, &*handler, &seen));
        }
    });
    TestServer { addr, requests }
}

fn read_request(stream: &mut TcpStream) -> Option<Request> {
    let mut raw = Vec::new();
    let mut chunk = [0_u8; 4096];
    let head_end = loop {
        let read = stream.read(&mut chunk).ok().filter(|n| *n > 0)?;
        raw.extend_from_slice(&chunk[..read]);
        if let Some(at) = raw.windows(4).position(|w| w == b"\r\n\r\n") {
            break at + 4;
        }
    };
    let head = String::from_utf8_lossy(&raw[..head_end]).into_owned();
    let length = head
        .lines()
        .find_map(|line| {
            line.to_ascii_lowercase()
                .strip_prefix("content-length:")
                .map(|v| v.trim().parse().unwrap_or(0))
        })
        .unwrap_or(0_usize);
    while raw.len() < head_end + length {
        let read = stream.read(&mut chunk).ok().filter(|n| *n > 0)?;
        raw.extend_from_slice(&chunk[..read]);
    }
    let target = head.lines().next()?.split(' ').nth(1)?.to_owned();
    Some(Request { target })
}

fn answer(mut stream: TcpStream, handler: &dyn Fn(&Request) -> Reply, seen: &Mutex<Vec<Request>>) {
    let Some(request) = read_request(&mut stream) else {
        return;
    };
    let reply = handler(&request);
    seen.lock().unwrap().push(request);
    let response = format!(
        "HTTP/1.1 {} X\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        reply.status,
        reply.body.len(),
        reply.body
    );
    let _ = stream.write_all(response.as_bytes());
}
