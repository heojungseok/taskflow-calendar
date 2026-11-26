mod analysis;
mod atomic;
mod clock;
mod config;
mod core;
mod custom_id;
mod discord;
mod discord_interact;
mod discord_render;
mod discord_text;
mod drafts;
mod evidence;
mod github;
mod grafana;
mod http;
mod journal;
mod loki;
mod metrics;
mod prometheus;
mod requests;
mod results;
mod rfc3339;
mod telemetry;
#[cfg(test)]
mod test_server;
#[cfg(test)]
mod test_support;
mod webhook;

use std::process::ExitCode;
use std::sync::Arc;
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use tokio::signal::unix::{SignalKind, signal};

use analyzer::load_knowledge;
use incident::{Event, Incidents};
use ollama::Ollama;

use crate::analysis::Analyst;
use crate::clock::SystemClock;
use crate::config::Config;
use crate::core::{Core, Paths};
use crate::discord::{DiscordChat, Link};
use crate::evidence::{Github, Sources};
use crate::telemetry::{Telemetry, Timed};

const TICK_INTERVAL: Duration = Duration::from_secs(60);
const MODEL_TIMEOUT: Duration = Duration::from_secs(90);
const GITHUB_API: &str = "https://api.github.com";

fn sources(config: &Config) -> Sources {
    let github = config.github_repo.clone().map(|repo| Github {
        api_base: GITHUB_API.to_owned(),
        repo,
    });
    Sources::new(
        config.loki_url.clone(),
        config.prometheus_url.clone(),
        github,
    )
}

fn start_core(
    config: &Config,
    (chat, telemetry): (DiscordChat, Arc<Telemetry>),
    (event_tx, event_rx): (mpsc::Sender<Event>, mpsc::Receiver<Event>),
) -> Result<(), String> {
    let (knowledge, issues) = load_knowledge(&config.knowledge_dir);
    for issue in issues {
        eprintln!("knowledge {} {:?}", issue.file, issue.kind);
    }
    let incidents = Incidents::open(&config.db_path, config::settings(config.owner.clone()))
        .map_err(|error| format!("incident store: {error}"))?;
    let (job_tx, job_rx) = mpsc::channel();
    let sources = sources(config);
    let analyst = Analyst {
        knowledge,
        evidence_for: move |deploy| sources.for_batch(deploy),
        model: Timed::new(
            Ollama::new(&config.model_url, &config.model_name).timeout(MODEL_TIMEOUT),
            Arc::clone(&telemetry),
        ),
        clock: SystemClock,
        deploy_journal: config.deploy_journal.clone(),
    };
    thread::spawn(move || analysis::run(&analyst, job_rx, event_tx));
    let core = Core {
        incidents,
        chat,
        clock: SystemClock,
        paths: Paths {
            drafts_dir: config.drafts_dir.clone(),
            requests_dir: config.requests_dir.clone(),
        },
        analysis: job_tx,
        telemetry,
    };
    thread::spawn(move || core::run(core, event_rx));
    Ok(())
}

async fn terminated() {
    match signal(SignalKind::terminate()) {
        Ok(mut stream) => {
            stream.recv().await;
        }
        Err(_) => std::future::pending().await,
    }
}

async fn serve(config: Config) -> Result<(), String> {
    let (event_tx, event_rx) = mpsc::channel();
    let (notice_tx, notice_rx) = tokio::sync::mpsc::unbounded_channel();
    let chat = DiscordChat::new(notice_tx);
    let telemetry = Arc::new(Telemetry::default());
    start_core(
        &config,
        (chat, Arc::clone(&telemetry)),
        (event_tx.clone(), event_rx),
    )?;
    results::spawn(config.results_dir.clone(), event_tx.clone(), TICK_INTERVAL);
    let listener = tokio::net::TcpListener::bind(&config.listen_addr)
        .await
        .map_err(|error| format!("listen: {}", error.kind()))?;
    let app = webhook::router(
        config.webhook_token.clone(),
        event_tx.clone(),
        Arc::clone(&telemetry),
    );
    let link = Link {
        token: config.discord_token.clone(),
        channel_id: config.discord_channel_id,
        owner: config.owner.clone(),
        grafana_url: config.grafana_url.clone(),
        telemetry,
    };
    tokio::select! {
        served = axum::serve(listener, app) => served.map_err(|error| format!("webhook: {}", error.kind())),
        chat = discord::run(link, event_tx, notice_rx) => chat,
        _ = tokio::signal::ctrl_c() => Ok(()),
        () = terminated() => Ok(()),
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
