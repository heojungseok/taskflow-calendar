use std::sync::mpsc::Sender;

use axum::Router;
use axum::body::Bytes;
use axum::extract::{DefaultBodyLimit, State};
use axum::http::{HeaderMap, StatusCode, header::AUTHORIZATION};
use axum::routing::{get, post};
use incident::Event;

use crate::grafana;

const MAX_BODY_BYTES: usize = 64 * 1024;

#[derive(Clone)]
struct Shared {
    token: String,
    events: Sender<Event>,
}

pub(crate) fn router(token: String, events: Sender<Event>) -> Router {
    Router::new()
        .route("/grafana", post(receive))
        .route("/healthz", get(|| async { "ok" }))
        .layer(DefaultBodyLimit::max(MAX_BODY_BYTES))
        .with_state(Shared { token, events })
}

fn constant_time_eq(left: &[u8], right: &[u8]) -> bool {
    let mut difference = left.len() ^ right.len();
    for index in 0..left.len().max(right.len()) {
        let a = left.get(index).copied().unwrap_or(0);
        let b = right.get(index).copied().unwrap_or(0);
        difference |= usize::from(a ^ b);
    }
    difference == 0
}

fn authorized(headers: &HeaderMap, token: &str) -> bool {
    let presented = headers
        .get(AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .unwrap_or("");
    constant_time_eq(presented.as_bytes(), token.as_bytes()) && !token.is_empty()
}

async fn receive(State(shared): State<Shared>, headers: HeaderMap, body: Bytes) -> StatusCode {
    if !authorized(&headers, &shared.token) {
        return StatusCode::UNAUTHORIZED;
    }
    let Ok(notification) = grafana::parse(&body) else {
        return StatusCode::BAD_REQUEST;
    };
    let mut events = Vec::new();
    if !notification.firing.is_empty() {
        events.push(Event::AlertsReceived(notification.firing));
    }
    if !notification.resolved.is_empty() {
        events.push(Event::AlertsResolved(notification.resolved));
    }
    for event in events {
        if shared.events.send(event).is_err() {
            return StatusCode::SERVICE_UNAVAILABLE;
        }
    }
    StatusCode::OK
}

#[cfg(test)]
mod tests {
    use std::net::SocketAddr;
    use std::sync::mpsc::{self, Receiver};
    use std::time::Duration;

    use super::*;

    const BODY: &str = r#"{"alerts":[{"status":"firing","labels":{"alertname":"error logs","service":"backend"},"startsAt":"2025-11-26T00:00:00Z"}]}"#;

    async fn serve(token: &str) -> (SocketAddr, Receiver<Event>) {
        let (sender, receiver) = mpsc::channel();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let app = router(token.to_owned(), sender);
        tokio::spawn(async move { axum::serve(listener, app).await });
        (addr, receiver)
    }

    async fn call(
        addr: SocketAddr,
        method: &'static str,
        auth: Option<&str>,
        body: Vec<u8>,
    ) -> u16 {
        let url = format!(
            "http://{addr}/{}",
            if method == "GET" {
                "healthz"
            } else {
                "grafana"
            }
        );
        let auth = auth.map(|value| format!("Bearer {value}"));
        tokio::task::spawn_blocking(move || {
            let agent: ureq::Agent = ureq::Agent::config_builder()
                .http_status_as_error(false)
                .timeout_global(Some(Duration::from_secs(5)))
                .build()
                .into();
            let response = if method == "GET" {
                agent.get(&url).call()
            } else {
                let mut request = agent.post(&url);
                if let Some(auth) = &auth {
                    request = request.header("Authorization", auth);
                }
                request.send(&body[..])
            };
            response.unwrap().status().as_u16()
        })
        .await
        .unwrap()
    }

    #[tokio::test]
    async fn t30_토큰이_없거나_틀리면_401이고_이벤트가_없다() {
        let (addr, events) = serve("right-token").await;
        assert_eq!(call(addr, "POST", None, BODY.into()).await, 401);
        assert_eq!(
            call(addr, "POST", Some("wrong-token"), BODY.into()).await,
            401
        );
        assert!(events.try_recv().is_err());
    }

    #[tokio::test]
    async fn t30_64kb를_넘는_본문은_413이다() {
        let (addr, events) = serve("right-token").await;
        let big = vec![b' '; 64 * 1024 + 1];
        assert_eq!(call(addr, "POST", Some("right-token"), big).await, 413);
        assert!(events.try_recv().is_err());
    }

    #[tokio::test]
    async fn t30_올바른_firing은_이벤트_하나를_보낸다() {
        let (addr, events) = serve("right-token").await;
        assert_eq!(
            call(addr, "POST", Some("right-token"), BODY.into()).await,
            200
        );
        assert!(
            matches!(events.try_recv(), Ok(Event::AlertsReceived(alerts)) if alerts.len() == 1)
        );
    }

    #[tokio::test]
    async fn t30_resolved는_해소_이벤트이고_깨진_본문은_400이다() {
        let (addr, events) = serve("right-token").await;
        let resolved = BODY.replace("firing", "resolved");
        assert_eq!(
            call(addr, "POST", Some("right-token"), resolved.into()).await,
            200
        );
        assert!(matches!(events.try_recv(), Ok(Event::AlertsResolved(_))));
        assert_eq!(
            call(addr, "POST", Some("right-token"), b"nope".to_vec()).await,
            400
        );
        assert!(events.try_recv().is_err());
    }

    #[tokio::test]
    async fn healthz는_200이다() {
        let (addr, _events) = serve("right-token").await;
        assert_eq!(call(addr, "GET", None, Vec::new()).await, 200);
    }
}
