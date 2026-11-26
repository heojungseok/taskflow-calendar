use std::collections::HashMap;

use analyzer::{Alert, Service};
use serde::Deserialize;

use crate::rfc3339;

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct Notification {
    pub(crate) firing: Vec<Alert>,
    pub(crate) resolved: Vec<Alert>,
}

#[derive(Deserialize)]
struct Payload {
    alerts: Vec<RawAlert>,
}

#[derive(Deserialize)]
struct RawAlert {
    status: String,
    labels: HashMap<String, String>,
    #[serde(rename = "startsAt")]
    starts_at: String,
}

pub(crate) fn parse(body: &[u8]) -> Result<Notification, String> {
    let payload: Payload =
        serde_json::from_slice(body).map_err(|_| "invalid grafana body".to_owned())?;
    let mut notification = Notification {
        firing: Vec::new(),
        resolved: Vec::new(),
    };
    for raw in payload.alerts {
        let name = raw
            .labels
            .get("alertname")
            .ok_or("alert without alertname")?
            .clone();
        let service = raw.labels.get("service").unwrap_or(&name);
        let starts_at_unix = rfc3339::parse_unix(&raw.starts_at).ok_or("invalid startsAt")?;
        let alert = Alert {
            service: Service::new(service),
            name,
            starts_at_unix,
        };
        match raw.status.as_str() {
            "firing" => notification.firing.push(alert),
            "resolved" => notification.resolved.push(alert),
            _ => return Err("unknown alert status".to_owned()),
        }
    }
    Ok(notification)
}

#[cfg(test)]
mod tests {
    use analyzer::Service;

    use super::*;

    fn alert(name: &str, service: &str, at: u64) -> Alert {
        Alert {
            name: name.to_owned(),
            service: Service::new(service),
            starts_at_unix: at,
        }
    }

    #[test]
    fn t29_firing과_resolved를_나눈다() {
        let body = r#"{"alerts":[
            {"status":"firing","labels":{"alertname":"error logs","service":"backend"},"startsAt":"2025-11-26T00:00:00Z"},
            {"status":"resolved","labels":{"alertname":"slow","service":"db"},"startsAt":"2025-11-26T00:01:00Z"}
        ]}"#;
        let parsed = parse(body.as_bytes()).unwrap();
        assert_eq!(
            parsed.firing,
            vec![alert("error logs", "backend", 1_764_115_200)]
        );
        assert_eq!(parsed.resolved, vec![alert("slow", "db", 1_764_115_260)]);
    }

    #[test]
    fn t29_service_라벨이_없으면_alertname을_service로_쓴다() {
        let body = r#"{"alerts":[{"status":"firing","labels":{"alertname":"disk full"},"startsAt":"2025-11-26T00:00:00Z"}]}"#;
        let parsed = parse(body.as_bytes()).unwrap();
        assert_eq!(
            parsed.firing,
            vec![alert("disk full", "disk full", 1_764_115_200)]
        );
    }

    #[test]
    fn t29_startsat이_틀리거나_본문이_깨지면_오류다() {
        let bad_time =
            r#"{"alerts":[{"status":"firing","labels":{"alertname":"a"},"startsAt":"yesterday"}]}"#;
        assert!(parse(bad_time.as_bytes()).is_err());
        assert!(parse(b"not json").is_err());
    }
}
