use analyzer::MetricSeries;
use serde::Deserialize;

use crate::http::{Failure, Http};

const STEP_SECS: u64 = 30;
const BASELINE_SECS: u64 = 3600;

pub(crate) struct MetricSpec {
    pub(crate) name: &'static str,
    pub(crate) query: &'static str,
    pub(crate) floor: f64,
}

#[derive(Deserialize)]
struct Response {
    data: Data,
}

#[derive(Deserialize)]
struct Data {
    result: Vec<Matrix>,
}

#[derive(Deserialize)]
struct Matrix {
    values: Vec<(f64, String)>,
}

fn points(body: &str) -> Result<Vec<(u64, f64)>, Failure> {
    let response: Response = serde_json::from_str(body).map_err(|_| Failure::Format)?;
    let Some(matrix) = response.data.result.into_iter().next() else {
        return Ok(Vec::new());
    };
    Ok(matrix
        .values
        .into_iter()
        .filter_map(|(at, value)| Some((at as u64, value.parse::<f64>().ok()?)))
        .filter(|(_, value)| value.is_finite())
        .collect())
}

fn median(points: &[(u64, f64)]) -> f64 {
    let mut values: Vec<f64> = points.iter().map(|(_, value)| *value).collect();
    values.sort_by(f64::total_cmp);
    let middle = values.len() / 2;
    if values.len() % 2 == 1 {
        return values[middle];
    }
    (values[middle - 1] + values[middle]) / 2.0
}

pub(crate) fn parse_series(
    spec: &MetricSpec,
    window: &str,
    baseline: &str,
) -> Result<Option<MetricSeries>, Failure> {
    let (points, baseline) = (points(window)?, points(baseline)?);
    if points.is_empty() || baseline.is_empty() {
        return Ok(None);
    }
    Ok(Some(MetricSeries {
        name: spec.name.to_owned(),
        baseline: median(&baseline),
        floor: spec.floor,
        points,
    }))
}

fn range(
    http: &Http,
    base: &str,
    query: &str,
    (from_unix, to_unix): (u64, u64),
) -> Result<String, Failure> {
    let params = [
        ("query", query.to_owned()),
        ("start", from_unix.to_string()),
        ("end", to_unix.to_string()),
        ("step", STEP_SECS.to_string()),
    ];
    http.get(&format!("{base}/api/v1/query_range"), &params)
}

pub(crate) fn fetch(
    http: &Http,
    base: &str,
    spec: &MetricSpec,
    (from_unix, to_unix): (u64, u64),
) -> Result<Option<MetricSeries>, Failure> {
    let window = range(http, base, spec.query, (from_unix, to_unix))?;
    let before = from_unix.saturating_sub(BASELINE_SECS);
    let baseline = range(http, base, spec.query, (before, from_unix))?;
    parse_series(spec, &window, &baseline)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SPEC: MetricSpec = MetricSpec {
        name: "pg_connection_ratio",
        query: "q",
        floor: 0.1,
    };

    fn matrix(values: &[(u64, &str)]) -> String {
        let pairs: Vec<String> = values
            .iter()
            .map(|(t, v)| format!("[{t},\"{v}\"]"))
            .collect();
        format!(
            r#"{{"status":"success","data":{{"resultType":"matrix","result":[{{"metric":{{}},"values":[{}]}}]}}}}"#,
            pairs.join(",")
        )
    }

    #[test]
    fn t33_matrix를_points로_바꾸고_baseline은_중앙값이다() {
        let window = matrix(&[(100, "0.5"), (130, "0.9")]);
        let baseline = matrix(&[(10, "0.1"), (40, "0.3"), (70, "100")]);
        let series = parse_series(&SPEC, &window, &baseline).unwrap().unwrap();
        assert_eq!(series.name, "pg_connection_ratio");
        assert_eq!(series.floor, 0.1);
        assert_eq!(series.baseline, 0.3);
        assert_eq!(series.points, vec![(100, 0.5), (130, 0.9)]);
    }

    #[test]
    fn t33_짝수_개_중앙값은_가운데_둘의_평균이다() {
        let baseline = matrix(&[(10, "1"), (40, "3")]);
        let series = parse_series(&SPEC, &matrix(&[(100, "2")]), &baseline)
            .unwrap()
            .unwrap();
        assert_eq!(series.baseline, 2.0);
    }

    #[test]
    fn t33_결과가_비었거나_유한한_값이_없으면_series_없음이고_깨지면_오류다() {
        let empty = r#"{"data":{"result":[]}}"#;
        assert!(parse_series(&SPEC, empty, empty).unwrap().is_none());
        let nan = matrix(&[(1, "NaN")]);
        assert!(parse_series(&SPEC, &nan, &nan).unwrap().is_none());
        assert!(parse_series(&SPEC, "nope", "nope").is_err());
    }
}
