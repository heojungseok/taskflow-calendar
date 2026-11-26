use std::time::Duration;

const MAX_RESPONSE_BYTES: u64 = 32 * 1024 * 1024;

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum HttpError {
    Timeout,
    Failed(String),
}

impl From<ureq::Error> for HttpError {
    fn from(error: ureq::Error) -> Self {
        match error {
            ureq::Error::Timeout(_) => Self::Timeout,
            ureq::Error::StatusCode(code) => Self::Failed(format!("status {code}")),
            other => Self::Failed(other.to_string()),
        }
    }
}

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

    pub(crate) fn get(&self, url: &str, query: &[(&str, String)]) -> Result<String, HttpError> {
        let mut request = self.agent.get(url).header("User-Agent", "incident-agent");
        for (key, value) in query {
            request = request.query(key, value);
        }
        Ok(read(request.call()?)?)
    }

    pub(crate) fn post_json(
        &self,
        url: &str,
        body: &serde_json::Value,
    ) -> Result<String, HttpError> {
        let response = self.agent.post(url).send_json(body)?;
        Ok(read(response)?)
    }
}

fn read(mut response: ureq::http::Response<ureq::Body>) -> Result<String, ureq::Error> {
    response
        .body_mut()
        .with_config()
        .limit(MAX_RESPONSE_BYTES)
        .read_to_string()
}
