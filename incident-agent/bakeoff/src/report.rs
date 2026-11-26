use std::fmt::Write;

use crate::run::{ModelRun, Row};
use crate::scenarios::Scenario;
use crate::score::{Expect, Outcome};

const ANSWER_CHARS: usize = 160;

pub(crate) fn render(runs: &[ModelRun], scenarios: &[Scenario], think: bool) -> String {
    let mut out = String::new();
    let _ = writeln!(out, "# bake-off 결과 (think={think})\n");
    let _ = writeln!(
        out,
        "| 모델 | 맞춤 | 원인 맞춤 | 모르겠다 맞춤 | 지어냄 | 형식 실패 | 근거 없음 | 시간 초과·호출 실패 | 첫 로드 ms | 평균 응답 ms | 최대 전체 ms | 메모리 GB |"
    );
    let _ = writeln!(out, "|---|---|---|---|---|---|---|---|---|---|---|---|");
    let causes = scenarios
        .iter()
        .filter(|s| matches!(s.expect, Expect::Cause { .. }))
        .count();
    let unknowns = scenarios.len() - causes;
    for run in runs {
        let count = |wanted: &[Outcome]| {
            run.rows
                .iter()
                .filter(|r| wanted.contains(&r.outcome))
                .count()
        };
        let replies: Vec<_> = run.rows.iter().filter_map(|r| r.reply.as_ref()).collect();
        let first_load = replies.first().map_or(0, |r| r.load_ms);
        let average = if replies.is_empty() {
            0
        } else {
            replies
                .iter()
                .map(|r| r.prompt_eval_ms + r.eval_ms)
                .sum::<u64>()
                / replies.len() as u64
        };
        let longest = run.rows.iter().map(|r| r.wall_ms).max().unwrap_or(0);
        let memory = run
            .memory_bytes
            .map_or("-".to_owned(), |bytes| format!("{:.2}", bytes as f64 / 1e9));
        let _ = writeln!(
            out,
            "| {} | {}/{} | {}/{causes} | {}/{unknowns} | {} | {} | {} | {} | {first_load} | {average} | {longest} | {memory} |",
            run.model,
            run.rows.iter().filter(|r| r.outcome.is_right()).count(),
            run.rows.len(),
            count(&[Outcome::Correct]),
            count(&[Outcome::SaidUnknown]),
            count(&[Outcome::Fabricated]),
            count(&[Outcome::InvalidAnswer]),
            count(&[Outcome::UnsupportedCitation]),
            count(&[Outcome::Timeout, Outcome::Unavailable]),
        );
    }
    let _ = writeln!(out, "\n## 시나리오별 답\n");
    let _ = writeln!(out, "| 시나리오 | 모델 | 결과 | 답 | 전체 ms |");
    let _ = writeln!(out, "|---|---|---|---|---|");
    for (index, scenario) in scenarios.iter().enumerate() {
        for run in runs {
            let row = &run.rows[index];
            let _ = writeln!(
                out,
                "| {} | {} | {} | {} | {} |",
                scenario.name,
                short_name(&run.model),
                row.outcome.label(),
                answer_cell(row),
                row.wall_ms
            );
        }
    }
    out
}

fn short_name(model: &str) -> &str {
    model.rsplit('/').next().unwrap_or(model)
}

fn answer_cell(row: &Row) -> String {
    let text: String = row
        .answer
        .as_deref()
        .unwrap_or("-")
        .chars()
        .take(ANSWER_CHARS)
        .collect();
    text.replace('|', "/").replace('\n', " ")
}
