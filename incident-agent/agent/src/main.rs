mod atomic;
mod clock;
mod config;
mod core;
mod custom_id;
mod discord;
mod discord_interact;
mod discord_render;
mod drafts;
mod evidence;
mod github;
mod grafana;
mod http;
mod journal;
mod llama;
mod loki;
mod metrics;
mod prometheus;
mod requests;
mod results;
mod rfc3339;
#[cfg(test)]
mod test_server;
#[cfg(test)]
mod test_support;
mod webhook;

use std::process::ExitCode;
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use analyzer::load_knowledge;
use incident::Incidents;

use crate::clock::SystemClock;
use crate::config::Config;
use crate::core::{Core, Paths};
use crate::discord::{DiscordChat, Link};
use crate::evidence::{Github, Sources};
use crate::llama::Llama;

const TICK_INTERVAL: Duration = Duration::from_secs(60);
const MODEL_TIMEOUT: Duration = Duration::from_secs(90);
const GITHUB_API: &str = "https://api.github.com";

fn sources(config: &Config) -> Sources {
    let mut sources = Sources::new(
        config.loki_url.clone(),
        config.prometheus_url.clone(),
        config.deploy_journal.clone(),
    );
    sources.github = config.github_repo.clone().map(|repo| Github {
        api_base: GITHUB_API.to_owned(),
        repo,
    });
    sources
}

fn start_core(
    config: &Config,
    chat: DiscordChat,
    events: mpsc::Receiver<incident::Event>,
) -> Result<(), String> {
    let (knowledge, issues) = load_knowledge(&config.knowledge_dir);
    for issue in issues {
        eprintln!("knowledge {} {:?}", issue.file, issue.kind);
    }
    let incidents = Incidents::open(&config.db_path, config::settings(config.owner.clone()))
        .map_err(|error| format!("incident store: {error}"))?;
    let core = Core {
        incidents,
        knowledge,
        evidence: sources(config),
        model: Llama::new(config.model_url.clone(), MODEL_TIMEOUT),
        chat,
        clock: SystemClock,
        paths: Paths {
            drafts_dir: config.drafts_dir.clone(),
            requests_dir: config.requests_dir.clone(),
            deploy_journal: config.deploy_journal.clone(),
        },
    };
    thread::spawn(move || core::run(core, events));
    Ok(())
}

async fn serve(config: Config) -> Result<(), String> {
    let (event_tx, event_rx) = mpsc::channel();
    let (notice_tx, notice_rx) = tokio::sync::mpsc::unbounded_channel();
    start_core(&config, DiscordChat::new(notice_tx), event_rx)?;
    results::spawn(config.results_dir.clone(), event_tx.clone(), TICK_INTERVAL);
    let listener = tokio::net::TcpListener::bind(&config.listen_addr)
        .await
        .map_err(|error| format!("listen: {}", error.kind()))?;
    let app = webhook::router(config.webhook_token.clone(), event_tx.clone());
    let link = Link {
        token: config.discord_token.clone(),
        channel_id: config.discord_channel_id,
        owner: config.owner.clone(),
    };
    tokio::select! {
        served = axum::serve(listener, app) => served.map_err(|error| format!("webhook: {}", error.kind())),
        chat = discord::run(link, event_tx, notice_rx) => chat,
        _ = tokio::signal::ctrl_c() => Ok(()),
    }
}

fn run() -> Result<(), String> {
    let config = config::load(|key| std::env::var(key).ok()).map_err(|error| error.to_string())?;
    let runtime = tokio::runtime::Runtime::new().map_err(|error| error.kind().to_string())?;
    runtime.block_on(serve(config))
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}
