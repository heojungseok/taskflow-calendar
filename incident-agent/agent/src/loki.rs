use analyzer::{LogLine, Service};
use serde::Deserialize;

use crate::http::Http;

const MAX_LINES: u32 = 5000;

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

pub(crate) fn parse_streams(body: &str) -> Result<Vec<LogLine>, String> {
    let response: Response =
        serde_json::from_str(body).map_err(|_| "invalid loki response".to_owned())?;
    let mut lines = Vec::new();
    for stream in response.data.result {
        let service = stream
            .stream
            .get("service")
            .map_or("unknown", String::as_str);
        for (nanos, text) in stream.values {
            let nanos: u128 = nanos
                .parse()
                .map_err(|_| "invalid loki timestamp".to_owned())?;
            let at_unix = u64::try_from(nanos / 1_000_000_000)
                .map_err(|_| "invalid loki timestamp".to_owned())?;
            lines.push(LogLine {
                service: Service::new(service),
                at_unix,
                text,
            });
        }
    }
    lines.sort_by_key(|line| line.at_unix);
    Ok(lines)
}

fn selector(services: &[Service]) -> String {
    let names: Vec<&str> = services
        .iter()
        .map(Service::as_str)
        .filter(|name| {
            !name.is_empty()
                && name
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        })
        .collect();
    let pattern = if names.is_empty() {
        ".+".to_owned()
    } else {
        names.join("|")
    };
    format!("{{service=~\"{pattern}\"}} |~ \"ERROR|WARN\"")
}

pub(crate) fn fetch(
    http: &Http,
    base: &str,
    services: &[Service],
    (from_unix, to_unix): (u64, u64),
) -> Result<Vec<LogLine>, String> {
    let nanos = |secs: u64| (u128::from(secs) * 1_000_000_000).to_string();
    let query = [
        ("query", selector(services)),
        ("start", nanos(from_unix)),
        ("end", nanos(to_unix)),
        ("limit", MAX_LINES.to_string()),
        ("direction", "forward".to_owned()),
    ];
    let body = http
        .get(&format!("{base}/loki/api/v1/query_range"), &query)
        .map_err(|error| format!("loki: {error:?}"))?;
    parse_streams(&body)
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
        assert!(parse_streams("nope").is_err());
        let bad_time = r#"{"data":{"result":[{"stream":{},"values":[["abc","x"]]}]}}"#;
        assert!(parse_streams(bad_time).is_err());
    }
}
