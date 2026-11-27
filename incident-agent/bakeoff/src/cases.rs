use std::fs;
use std::path::Path;

use analyzer::{Commit, Evidence, LogLine, MetricSeries, Service};
use serde::Deserialize;

use crate::scenarios::{Scenario, SuspectAnswer};
use crate::score::Expect;

pub(crate) const CASES_DIR: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/cases");

#[derive(Deserialize)]
struct CaseFile {
    name: String,
    alert: AlertFile,
    now_unix: u64,
    logs: Vec<LogFile>,
    baseline_logs: Vec<LogFile>,
    commits: Vec<CommitFile>,
    metrics: Vec<MetricFile>,
    answer: Option<AnswerFile>,
}

#[derive(Deserialize)]
struct AlertFile {
    name: String,
    service: String,
    starts_at_unix: u64,
}

#[derive(Deserialize)]
struct LogFile {
    service: String,
    at_unix: u64,
    text: String,
}

#[derive(Deserialize)]
struct CommitFile {
    sha: String,
    files: Vec<String>,
}

#[derive(Deserialize)]
struct MetricFile {
    name: String,
    baseline: f64,
    floor: f64,
    points: Vec<(u64, f64)>,
}

#[derive(Deserialize)]
struct AnswerFile {
    #[serde(default)]
    known_gap: Option<String>,
    suspect: SuspectAnswer,
    line: String,
    keywords: Vec<String>,
}

fn log_line(file: LogFile) -> LogLine {
    LogLine {
        service: Service::new(&file.service),
        at_unix: file.at_unix,
        text: file.text,
    }
}

pub(crate) fn parse(file_name: &str, json: &str) -> Result<Scenario, String> {
    let case: CaseFile =
        serde_json::from_str(json).map_err(|error| format!("{file_name}: {error}"))?;
    let evidence = Evidence {
        logs: case.logs.into_iter().map(log_line).collect(),
        baseline_logs: case.baseline_logs.into_iter().map(log_line).collect(),
        commits: case
            .commits
            .into_iter()
            .map(|c| Commit {
                sha: c.sha,
                files: c.files,
            })
            .collect(),
        metrics: case
            .metrics
            .into_iter()
            .map(|m| MetricSeries {
                name: m.name,
                baseline: m.baseline,
                floor: m.floor,
                points: m.points,
            })
            .collect(),
        missing: Vec::new(),
    };
    let (expect, suspect, known_gap) = match case.answer {
        Some(answer) => (
            Expect::Cause {
                line: answer.line,
                keywords: answer.keywords,
            },
            Some(answer.suspect),
            answer.known_gap,
        ),
        None => (Expect::Unknown, None, None),
    };
    Ok(Scenario {
        name: case.name,
        alert: case.alert.name,
        alert_at: case.alert.starts_at_unix,
        service: Service::new(&case.alert.service),
        now: case.now_unix,
        evidence,
        expect,
        suspect,
        known_gap,
    })
}

pub(crate) fn load(dir: &Path) -> Result<Vec<Scenario>, String> {
    let mut paths = fs::read_dir(dir)
        .and_then(|entries| {
            entries
                .map(|entry| entry.map(|e| e.path()))
                .collect::<Result<Vec<_>, _>>()
        })
        .map_err(|error| format!("{}: {error}", dir.display()))?;
    paths.retain(|path| path.extension().is_some_and(|ext| ext == "json"));
    paths.sort();
    paths
        .iter()
        .map(|path| {
            let name = path.display().to_string();
            let json = fs::read_to_string(path).map_err(|error| format!("{name}: {error}"))?;
            parse(&name, &json)
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::scenarios::SuspectAnswer;
    use crate::score::Expect;

    const SAMPLE: &str = r#"{
      "name": "db-pool",
      "origin": "재현: k6로 연결 풀 고갈",
      "alert": {"name": "backend error logs", "service": "backend", "starts_at_unix": 1000},
      "now_unix": 1200,
      "logs": [{"service": "backend", "at_unix": 1010, "text": "ERROR c.t.p.TaskService : HikariPool-1 - Connection is not available"}],
      "baseline_logs": [],
      "commits": [{"sha": "abc", "files": ["src/main/java/A.java"]}],
      "metrics": [{"name": "backend_up", "baseline": 1.0, "floor": 1.0, "points": [[990, 1.0]]}],
      "answer": {"suspect": {"new_error": "c.t.p.TaskService"}, "line": "HikariPool", "keywords": ["풀", "pool"]}
    }"#;

    #[test]
    fn 사례_json이_알림_증거_정답으로_읽힌다() {
        let scenario = parse("db-pool.json", SAMPLE).unwrap();
        assert_eq!(scenario.name, "db-pool");
        assert_eq!(scenario.alert, "backend error logs");
        assert_eq!(scenario.alerts()[0].starts_at_unix, 1_000);
        assert_eq!(scenario.now, 1_200);
        assert_eq!(scenario.evidence.logs[0].at_unix, 1_010);
        assert_eq!(scenario.evidence.commits[0].sha, "abc");
        assert_eq!(scenario.evidence.metrics[0].points, vec![(990, 1.0)]);
        assert!(
            matches!(&scenario.expect, Expect::Cause { line, keywords } if line == "HikariPool" && keywords == &["풀", "pool"])
        );
        assert_eq!(
            scenario.suspect,
            Some(SuspectAnswer::NewError("c.t.p.TaskService".to_owned()))
        );
    }

    #[test]
    fn 형식이_틀린_사례는_파일_이름과_함께_오류다() {
        let error = parse("broken.json", r#"{"name": 1}"#).err().unwrap();
        assert!(error.starts_with("broken.json: "), "{error}");
    }

    struct Fixed(Evidence);

    impl analyzer::EvidenceSource for Fixed {
        fn collect(
            &self,
            _: &analyzer::Window,
            _: &[Service],
        ) -> Result<Evidence, analyzer::EvidenceError> {
            Ok(self.0.clone())
        }
    }

    struct At(u64);

    impl analyzer::Clock for At {
        fn now_unix(&self) -> u64 {
            self.0
        }
    }

    struct Unknown;

    impl analyzer::Model for Unknown {
        fn explain(&self, _: &str) -> Result<String, analyzer::ModelError> {
            Ok(r#"{"cause":"","evidence":"","confidence":"low","cited_lines":[]}"#.to_owned())
        }
    }

    #[test]
    fn 모든_사례에서_코드가_고른_의심_안에_정답_의심이_있다() {
        let cases = load(std::path::Path::new(CASES_DIR)).unwrap();
        assert!(!cases.is_empty());
        for scenario in cases {
            let report = analyzer::triage(
                &scenario.alerts(),
                &analyzer::Knowledge::default(),
                &Fixed(scenario.evidence.clone()),
                &At(scenario.now),
                &Unknown,
            )
            .remove(0);
            let Some(answer) = &scenario.suspect else {
                continue;
            };
            let found = answer.is_in(&report.suspects);
            if let Some(gap) = &scenario.known_gap {
                assert!(
                    !found,
                    "{}: 이제 정답 의심을 찾는다. known_gap을 지운다 ({gap})",
                    scenario.name
                );
                continue;
            }
            assert!(
                found,
                "{}: {answer:?} 없음, 고른 의심 {:?}",
                scenario.name, report.suspects
            );
        }
    }
}
