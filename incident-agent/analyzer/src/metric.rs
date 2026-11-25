use crate::model::{Evidence, MetricSeries, Suspect};

const CHANGE_FACTOR: f64 = 2.0;

pub(crate) fn first_changed_metric(evidence: &Evidence) -> Option<Suspect> {
    let mut earliest: Option<(&str, u64)> = None;
    for series in &evidence.metrics {
        if let Some(at) = changed_at(series)
            && earliest.is_none_or(|(_, best)| at < best)
        {
            earliest = Some((&series.name, at));
        }
    }
    earliest.map(|(name, changed_at_unix)| Suspect::FirstChangedMetric {
        name: name.to_owned(),
        changed_at_unix,
    })
}

fn changed_at(series: &MetricSeries) -> Option<u64> {
    if series.baseline <= 0.0 {
        return None;
    }
    series
        .points
        .iter()
        .find(|(_, value)| {
            *value >= series.baseline * CHANGE_FACTOR || *value <= series.baseline / CHANGE_FACTOR
        })
        .map(|(at, _)| *at)
}
