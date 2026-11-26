use std::fmt;
use std::time::Duration;

const MAX_RESPONSE_BYTES: u64 = 32 * 1024 * 1024;

#[derive(Debug, Clone, Copy)]
pub(crate) enum Failure {
    Timeout,
    Request,
    Format,
}

impl fmt::Display for Failure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Timeout => "timeout",
            Self::Request => "request",
            Self::Format => "format",
        })
    }
}

impl From<ureq::Error> for Failure {
    fn from(error: ureq::Error) -> Self {
        match error {
            ureq::Error::Timeout(_) => Self::Timeout,
            _ => Self::Request,
        }
    }
}

#[derive(Clone)]
pub(crate) struct Http {
    agent: ureq::Agent,
}

impl Http {
    pub(crate) fn new(timeout: Duration) -> Self {
        let agent = ureq::Agent::config_builder()
            .timeout_global(Some(timeout))
            .build()
            .into();
        Self { agent }
    }

    pub(crate) fn get(&self, url: &str, query: &[(&str, String)]) -> Result<String, Failure> {
        let mut request = self.agent.get(url).header("User-Agent", "incident-agent");
        for (key, value) in query {
            request = request.query(key, value);
        }
        Ok(read(request.call()?)?)
    }
}

fn read(mut response: ureq::http::Response<ureq::Body>) -> Result<String, ureq::Error> {
    response
        .body_mut()
        .with_config()
        .limit(MAX_RESPONSE_BYTES)
        .read_to_string()
}
