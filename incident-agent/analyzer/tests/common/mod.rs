#![allow(dead_code, unreachable_pub)]

use analyzer::{Alert, Clock, Evidence, EvidenceError, EvidenceSource, Service, Window};

pub struct FixedClock(pub u64);

impl Clock for FixedClock {
    fn now_unix(&self) -> u64 {
        self.0
    }
}

pub struct FixedEvidence(pub Evidence);

impl EvidenceSource for FixedEvidence {
    fn collect(&self, _window: &Window, _services: &[Service]) -> Result<Evidence, EvidenceError> {
        Ok(self.0.clone())
    }
}

pub fn alert(name: &str, service: &str, starts_at_unix: u64) -> Alert {
    Alert {
        name: name.to_owned(),
        service: Service::new(service),
        starts_at_unix,
    }
}
