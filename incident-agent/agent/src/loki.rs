use std::collections::BTreeSet;

use analyzer::{LogLine, Service};
use serde::Deserialize;

use crate::http::{Failure, Http};

const MAX_LINES: u32 = 5000;
const GATEWAY: &str = "gateway";

#[derive(Deserialize)]
struct Response {
    data: Data,
}

#[derive(Deserialize)]
struct Data {
    result: Vec<Stream>,
}

#[derive(Deserialize)]
struct Stream {
    stream: std::collections::HashMap<String, String>,
    values: Vec<(String, String)>,
}

#[derive(Clone, Copy)]
pub(crate) enum Direction {
    Forward,
    Backward,
}

pub(crate) fn parse_streams(body: &str) -> Option<Vec<LogLine>> {
    let response: Response = serde_json::from_str(body).ok()?;
    let mut lines = Vec::new();
    for stream in response.data.result {
        let service = stream
            .stream
            .get("service")
            .map_or("unknown", String::as_str);
        for (nanos, text) in stream.values {
            let nanos: u128 = nanos.parse().ok()?;
            lines.push(LogLine {
                service: Service::new(service),
                at_unix: u64::try_from(nanos / 1_000_000_000).ok()?,
                text,
            });
        }
    }
    lines.sort_by_key(|line| line.at_unix);
    Some(lines)
}

fn loki_service(alert_service: &Service) -> &'static str {
    if alert_service.as_str() == GATEWAY {
        "nginx"
    } else {
        "backend"
    }
}

fn selector(services: &[Service]) -> String {
    let mut names: BTreeSet<&str> = services.iter().map(loki_service).collect();
    if names.is_empty() {
        names.extend(["backend", "nginx"]);
    }
    let pattern: Vec<&str> = names.into_iter().collect();
    format!("{{service=~\"{}\"}} |~ \"ERROR|WARN\"", pattern.join("|"))
}

pub(crate) fn fetch(
    http: &Http,
    base: &str,
    services: &[Service],
    (from_unix, to_unix): (u64, u64),
    direction: Direction,
) -> Result<Vec<LogLine>, Failure> {
    let nanos = |secs: u64| (u128::from(secs) * 1_000_000_000).to_string();
    let direction = match direction {
        Direction::Forward => "forward",
        Direction::Backward => "backward",
    };
    let query = [
        ("query", selector(services)),
        ("start", nanos(from_unix)),
        ("end", nanos(to_unix)),
        ("limit", MAX_LINES.to_string()),
        ("direction", direction.to_owned()),
    ];
    let body = http.get(&format!("{base}/loki/api/v1/query_range"), &query)?;
    parse_streams(&body).ok_or(Failure::Format)
}

#[cfg(test)]
mod tests {
    use analyzer::Service;

    use super::*;

    #[test]
    fn t31_stream의_service_라벨과_초_단위_시각으로_시각_순_정렬한다() {
        let body = r#"{"status":"success","data":{"resultType":"streams","result":[
            {"stream":{"service":"backend"},"values":[["1764115230123456789","ERROR late"],["1764115200000000000","ERROR early"]]},
            {"stream":{"service":"db"},"values":[["1764115210000000000","WARN middle"]]}
        ]}}"#;
        let lines = parse_streams(body).unwrap();
        let shown: Vec<(&str, u64, &str)> = lines
            .iter()
            .map(|l| (l.service.as_str(), l.at_unix, l.text.as_str()))
            .collect();
        assert_eq!(
            shown,
            vec![
                ("backend", 1_764_115_200, "ERROR early"),
                ("db", 1_764_115_210, "WARN middle"),
                ("backend", 1_764_115_230, "ERROR late"),
            ]
        );
        assert_eq!(lines[0].service, Service::new("backend"));
    }

    #[test]
    fn t31_service_라벨이_없으면_unknown이다() {
        let body = r#"{"data":{"result":[{"stream":{},"values":[["1764115200000000000","x"]]}]}}"#;
        assert_eq!(parse_streams(body).unwrap()[0].service.as_str(), "unknown");
    }

    #[test]
    fn t31_형식이_틀리면_오류다() {
        assert!(parse_streams("nope").is_none());
        let bad_time = r#"{"data":{"result":[{"stream":{},"values":[["abc","x"]]}]}}"#;
        assert!(parse_streams(bad_time).is_none());
    }
}
