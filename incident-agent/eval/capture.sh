#!/bin/sh
set -eu

[ $# -eq 5 ] || {
  echo "usage: LOG_FILE=<backend log> capture.sh <name> <alert name> <alert unix> <now unix> <answer json|null>" >&2
  exit 2
}
name=$1 alert=$2 alert_at=$3 now=$4 answer=$5
log_file=${LOG_FILE:?LOG_FILE is required}
prom=${PROM_URL:-http://127.0.0.1:9090}
origin=${ORIGIN:?ORIGIN is required}
commits=${COMMITS_JSON:-[]}

from=$((alert_at - 900))
baseline_from=$((from - 86400))

logs_between() {
  jq -R -s --argjson from "$1" --argjson to "$2" --arg keep "$3" '
    [split("\n")[] | capture("^(?<at>\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ) (?<text>.*)$")?
      | select(.text | test("ERROR|WARN"))
      | {service: "backend", at_unix: (.at | fromdateiso8601), text: .text}
      | select(.at_unix >= $from and .at_unix < $to)]
    | if $keep == "first" then .[:5000] else .[-5000:] end' "$log_file"
}

range() {
  curl -fsS --get "$prom/api/v1/query_range" \
    --data-urlencode "query=$1" --data-urlencode "start=$2" \
    --data-urlencode "end=$3" --data-urlencode "step=30"
}

metric() {
  window=$(range "$4" "$from" "$now") && baseline=$(range "$4" "$((from - 3600))" "$from") || {
    echo "skip metric $1" >&2
    return 0
  }
  jq -n --arg name "$1" --argjson floor "$2" --argjson rises "$3" \
    --argjson window "$window" --argjson baseline "$baseline" '
    def points($r): [$r.data.result[0].values[]? | (.[1] | tonumber?) as $v
      | select($v != null and ($v | isinfinite or isnan | not)) | [(.[0] | floor), $v]];
    def median: sort | if length % 2 == 1 then .[length / 2 | floor]
      else (.[length / 2 - 1] + .[length / 2]) / 2 end;
    points($window) as $w | points($baseline) as $b |
    if ($w | length) == 0 or ($b | length) == 0 then empty else
      ($b | map(.[1]) | median) as $m |
      {name: $name, baseline: $m, floor: $floor,
       points: (if $rises then $w | map([.[0], ([.[1], $m] | max)]) else $w end)}
    end'
}

metrics() {
  while IFS='|' read -r metric_name floor rises query; do
    metric "$metric_name" "$floor" "$rises" "$query"
  done <<'SPECS'
backend_up|1.0|false|min(up{job="taskflow"})
outbox_oldest_age_seconds|60.0|false|max(outbox_oldest_processable_age_seconds)
pg_connection_ratio|0.1|false|sum(pg_stat_activity_count) / max(pg_settings_max_connections)
gemini_failures_5m|3.0|false|sum(increase(gemini_calls_total{outcome="failure"}[5m]))
demo_cleanup_failures_15m|1.0|false|sum(increase(demo_cleanup_failures_total[15m]))
http_5xx_per_second|0.05|true|sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
http_p95_seconds|1.0|true|histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket[5m])))
hikari_pending|1.0|true|max(hikaricp_connections_pending)
jvm_heap_ratio|0.8|true|sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"})
backend_memory_bytes|1.0e9|true|max(taskflow_container_memory_bytes{service="backend"})
SPECS
}

jq -n --arg name "$name" --arg origin "$origin" --arg alert "$alert" \
  --argjson alert_at "$alert_at" --argjson now "$now" \
  --argjson logs "$(logs_between "$from" "$now" first)" \
  --argjson baseline_logs "$(logs_between "$baseline_from" "$from" last)" \
  --argjson commits "$commits" \
  --argjson metrics "$(metrics | jq -s .)" \
  --argjson answer "$answer" '
  {name: $name, origin: $origin,
   alert: {name: $alert, service: "backend", starts_at_unix: $alert_at},
   now_unix: $now, logs: $logs, baseline_logs: $baseline_logs,
   commits: $commits, metrics: $metrics, answer: $answer}'
