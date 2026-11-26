use std::path::PathBuf;

use contract::DiscordUserId;
use incident::Settings;

pub(crate) struct Config {
    pub(crate) listen_addr: String,
    pub(crate) webhook_token: String,
    pub(crate) loki_url: String,
    pub(crate) prometheus_url: String,
    pub(crate) model_url: String,
    pub(crate) model_name: String,
    pub(crate) db_path: PathBuf,
    pub(crate) knowledge_dir: PathBuf,
    pub(crate) drafts_dir: PathBuf,
    pub(crate) requests_dir: PathBuf,
    pub(crate) results_dir: PathBuf,
    pub(crate) deploy_journal: PathBuf,
    pub(crate) discord_token: String,
    pub(crate) discord_channel_id: u64,
    pub(crate) owner: DiscordUserId,
    pub(crate) github_repo: Option<String>,
    pub(crate) grafana_url: Option<String>,
    pub(crate) syslog_addr: Option<String>,
}

#[derive(Debug)]
pub(crate) struct ConfigError(String);

impl std::fmt::Display for ConfigError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

struct Reader<F> {
    lookup: F,
    problems: Vec<String>,
}

impl<F: Fn(&str) -> Option<String>> Reader<F> {
    fn text(&mut self, key: &str) -> String {
        match (self.lookup)(key).filter(|value| !value.is_empty()) {
            Some(value) => value,
            None => {
                self.problems.push(format!("{key} is not set"));
                String::new()
            }
        }
    }

    fn path(&mut self, key: &str) -> PathBuf {
        PathBuf::from(self.text(key))
    }
}

pub(crate) fn load(lookup: impl Fn(&str) -> Option<String>) -> Result<Config, ConfigError> {
    let mut reader = Reader {
        lookup,
        problems: Vec::new(),
    };
    let channel = reader.text("AGENT_DISCORD_CHANNEL_ID");
    let channel_id = channel
        .parse::<u64>()
        .ok()
        .filter(|id| *id != 0)
        .unwrap_or_else(|| {
            if !channel.is_empty() {
                reader
                    .problems
                    .push("AGENT_DISCORD_CHANNEL_ID is invalid".to_owned());
            }
            0
        });
    let owner = reader.text("AGENT_OWNER_ID");
    let owner = DiscordUserId::try_from(owner.clone()).unwrap_or_else(|_| {
        if !owner.is_empty() {
            reader.problems.push("AGENT_OWNER_ID is invalid".to_owned());
        }
        placeholder_owner()
    });
    let grafana_url = (reader.lookup)("AGENT_GRAFANA_URL").filter(|value| !value.is_empty());
    if grafana_url.as_deref().is_some_and(|url| !is_link_base(url)) {
        reader
            .problems
            .push("AGENT_GRAFANA_URL is invalid".to_owned());
    }
    let config = Config {
        listen_addr: reader.text("AGENT_LISTEN_ADDR"),
        webhook_token: reader.text("AGENT_WEBHOOK_TOKEN"),
        loki_url: reader.text("AGENT_LOKI_URL"),
        prometheus_url: reader.text("AGENT_PROMETHEUS_URL"),
        model_url: reader.text("AGENT_MODEL_URL"),
        model_name: reader.text("AGENT_MODEL_NAME"),
        db_path: reader.path("AGENT_DB_PATH"),
        knowledge_dir: reader.path("AGENT_KNOWLEDGE_DIR"),
        drafts_dir: reader.path("AGENT_DRAFTS_DIR"),
        requests_dir: reader.path("AGENT_REQUESTS_DIR"),
        results_dir: reader.path("AGENT_RESULTS_DIR"),
        deploy_journal: reader.path("AGENT_DEPLOY_JOURNAL"),
        discord_token: reader.text("AGENT_DISCORD_TOKEN"),
        discord_channel_id: channel_id,
        owner,
        github_repo: (reader.lookup)("AGENT_GITHUB_REPO").filter(|value| !value.is_empty()),
        grafana_url,
        syslog_addr: (reader.lookup)("AGENT_SYSLOG_ADDR").filter(|value| !value.is_empty()),
    };
    if reader.problems.is_empty() {
        return Ok(config);
    }
    Err(ConfigError(reader.problems.join("; ")))
}

fn is_link_base(url: &str) -> bool {
    (url.starts_with("https://") || url.starts_with("http://"))
        && !url
            .chars()
            .any(|c| c.is_whitespace() || matches!(c, '<' | '>'))
}

fn placeholder_owner() -> DiscordUserId {
    DiscordUserId::try_from("0".to_owned()).expect("0 is a valid id")
}

pub(crate) fn settings(owner: DiscordUserId) -> Settings {
    Settings {
        owner,
        daily_limit: 20,
        restart_after_secs: 900,
        button_ttl_secs: 600,
        rollback_window_secs: 86_400,
        recurrence_window_secs: 1_800,
    }
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;

    use super::*;

    const KEYS: [&str; 15] = [
        "AGENT_LISTEN_ADDR",
        "AGENT_WEBHOOK_TOKEN",
        "AGENT_LOKI_URL",
        "AGENT_PROMETHEUS_URL",
        "AGENT_MODEL_URL",
        "AGENT_MODEL_NAME",
        "AGENT_DB_PATH",
        "AGENT_KNOWLEDGE_DIR",
        "AGENT_DRAFTS_DIR",
        "AGENT_REQUESTS_DIR",
        "AGENT_RESULTS_DIR",
        "AGENT_DEPLOY_JOURNAL",
        "AGENT_DISCORD_TOKEN",
        "AGENT_DISCORD_CHANNEL_ID",
        "AGENT_OWNER_ID",
    ];

    fn env_without(missing: &[&str]) -> HashMap<String, String> {
        KEYS.iter()
            .filter(|key| !missing.contains(key))
            .map(|key| {
                let value = match *key {
                    "AGENT_DISCORD_CHANNEL_ID" | "AGENT_OWNER_ID" => "1234567890".to_owned(),
                    _ => format!("secret-value-of-{key}"),
                };
                ((*key).to_owned(), value)
            })
            .collect()
    }

    fn load_from(env: &HashMap<String, String>) -> Result<Config, ConfigError> {
        load(|key| env.get(key).cloned())
    }

    #[test]
    fn t42_모든_키가_있으면_읽힌다() {
        let config = load_from(&env_without(&[])).ok().unwrap();
        assert_eq!(config.discord_channel_id, 1_234_567_890);
        assert_eq!(config.owner.as_str(), "1234567890");
        assert_eq!(config.github_repo, None);
        assert_eq!(config.grafana_url, None);
        assert_eq!(config.syslog_addr, None);
        assert_eq!(config.model_name, "secret-value-of-AGENT_MODEL_NAME");
    }

    #[test]
    fn t42_필수_키가_빠지면_키_이름만_든_오류를_낸다() {
        let message = load_from(&env_without(&[
            "AGENT_WEBHOOK_TOKEN",
            "AGENT_LOKI_URL",
            "AGENT_MODEL_NAME",
        ]))
        .err()
        .unwrap()
        .to_string();
        assert!(message.contains("AGENT_WEBHOOK_TOKEN"));
        assert!(message.contains("AGENT_LOKI_URL"));
        assert!(message.contains("AGENT_MODEL_NAME"));
        assert!(!message.contains("secret-value"));
    }

    #[test]
    fn t42_형식이_틀린_값은_값을_노출하지_않는다() {
        let mut env = env_without(&[]);
        env.insert(
            "AGENT_OWNER_ID".to_owned(),
            "not-a-number-secret".to_owned(),
        );
        let message = load_from(&env).err().unwrap().to_string();
        assert!(message.contains("AGENT_OWNER_ID"));
        assert!(!message.contains("not-a-number-secret"));
    }

    #[test]
    fn t42_grafana_주소는_http_링크여야_한다() {
        for bad in ["grafana.example", "https://g.example/> x"] {
            let mut env = env_without(&[]);
            env.insert("AGENT_GRAFANA_URL".to_owned(), bad.to_owned());
            let message = load_from(&env).err().unwrap().to_string();
            assert!(message.contains("AGENT_GRAFANA_URL"));
            assert!(!message.contains("g.example"));
        }
        let mut env = env_without(&[]);
        env.insert(
            "AGENT_GRAFANA_URL".to_owned(),
            "https://grafana.example/".to_owned(),
        );
        assert!(load_from(&env).is_ok());
    }

    #[test]
    fn t42_채널_id_0은_거절한다() {
        let mut env = env_without(&[]);
        env.insert("AGENT_DISCORD_CHANNEL_ID".to_owned(), "0".to_owned());
        let message = load_from(&env).err().unwrap().to_string();
        assert!(message.contains("AGENT_DISCORD_CHANNEL_ID"));
    }
}
