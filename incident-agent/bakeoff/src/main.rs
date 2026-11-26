mod report;
mod run;
mod scenarios;
mod score;

use std::env;
use std::process::ExitCode;

const DEFAULT_URL: &str = "http://127.0.0.1:11434";
const DEFAULT_MODELS: [&str; 3] = [
    "hf.co/unsloth/Qwen3.5-4B-GGUF:Q4_K_M",
    "hf.co/unsloth/gemma-4-E2B-it-GGUF:Q4_K_M",
    "hf.co/unsloth/Phi-4-mini-instruct-GGUF:Q4_K_M",
];

fn main() -> ExitCode {
    let url = env::var("OLLAMA_URL").unwrap_or_else(|_| DEFAULT_URL.to_owned());
    let think = env::var("BAKEOFF_THINK").is_ok_and(|value| value == "1");
    let args: Vec<String> = env::args().skip(1).collect();
    let models: Vec<String> = if args.is_empty() {
        DEFAULT_MODELS.iter().map(|m| (*m).to_owned()).collect()
    } else {
        args
    };
    let scenarios = scenarios::all();
    let results: Vec<run::ModelRun> = models
        .iter()
        .map(|model| {
            eprintln!("bake-off: {model} (think={think})");
            run::run_model(&url, model, think, &scenarios)
        })
        .collect();
    print!("{}", report::render(&results, &scenarios, think));
    ExitCode::SUCCESS
}
