use crate::model::{Evidence, MetricSeries, Suspect};

const CHANGE_FACTOR: f64 = 2.0;

pub(crate) fn first_changed_metric(evidence: &Evidence) -> Option<Suspect> {
    evidence
        .metrics
        .iter()
        .filter_map(|series| Some((series, changed_at(series)?)))
        .min_by_key(|(_, at)| *at)
        .map(|(series, changed_at_unix)| Suspect::FirstChangedMetric {
            name: series.name.clone(),
            changed_at_unix,
        })
}

fn changed_at(series: &MetricSeries) -> Option<u64> {
    changed_points(series).into_iter().map(|(at, _)| at).min()
}

pub(crate) fn changed_points(series: &MetricSeries) -> Vec<(u64, f64)> {
    series
        .points
        .iter()
        .filter(|(_, value)| has_changed(series, *value))
        .copied()
        .collect()
}

fn has_changed(series: &MetricSeries, value: f64) -> bool {
    let baseline = series.baseline;
    if baseline > 0.0 {
        let moved = value >= baseline * CHANGE_FACTOR || value <= baseline / CHANGE_FACTOR;
        return moved && (value <= baseline || value >= series.floor);
    }
    value >= series.floor && value > 0.0
}
