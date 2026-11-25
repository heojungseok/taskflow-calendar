use crate::grouping::group_alerts;
use crate::model::{Alert, Knowledge, Report};
use crate::ports::{Clock, EvidenceSource};

pub fn triage(
    alerts: &[Alert],
    _knowledge: &Knowledge,
    _evidence: &impl EvidenceSource,
    _clock: &impl Clock,
) -> Vec<Report> {
    group_alerts(alerts)
        .into_iter()
        .map(|alerts| Report { alerts })
        .collect()
}
