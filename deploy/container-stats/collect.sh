#!/bin/sh
set -eu

out_dir=${1:?usage: collect.sh <textfile-directory>}
docker_bin=${DOCKER:-docker}
project=${TASKFLOW_COMPOSE_PROJECT:-taskflow-public}

[ ! -L "$out_dir" ] || { echo "output directory must not be a symlink: $out_dir" >&2; exit 1; }
mkdir -p "$out_dir"
[ -O "$out_dir" ] || { echo "output directory must be owned by the collector: $out_dir" >&2; exit 1; }
chmod 755 "$out_dir"

services=$("$docker_bin" ps --filter "label=com.docker.compose.project=$project" \
  --format '{{.ID}}\t{{.Label "com.docker.compose.service"}}')
[ -n "$services" ] || { echo "no running containers for project $project" >&2; exit 1; }
ids=$(printf '%s\n' "$services" | cut -f1 | tr '\n' ' ')
# shellcheck disable=SC2086
stats=$("$docker_bin" stats --no-stream --format '{{.ID}}\t{{.CPUPerc}}\t{{.MemUsage}}' $ids)
host=$("$docker_bin" info --format '{{.MemTotal}} {{.NCPU}}')

tmp=$(mktemp "$out_dir/.containers.prom.XXXXXX")
trap 'rm -f "$tmp"' EXIT HUP INT TERM
printf '%s\n%s\nHOST\t%s\n' "$services" "$stats" "$host" | awk -F'\t' '
  function bytes(value,   number, unit) {
    number = value; sub(/[A-Za-z]+$/, "", number)
    unit = value; sub(/^[0-9.]+/, "", unit)
    if (unit == "B") return number
    if (unit == "KiB") return number * 1024
    if (unit == "MiB") return number * 1024 * 1024
    if (unit == "GiB") return number * 1024 * 1024 * 1024
    if (unit == "TiB") return number * 1024 * 1024 * 1024 * 1024
    print "unknown memory unit: " value > "/dev/stderr"
    failed = 1
    exit 1
  }
  $1 == "HOST" { split($2, host, " "); next }
  NF == 2 { service[$1] = $2; next }
  NF == 3 && ($1 in service) && $2 ~ /^[0-9.]+%$/ {
    name = service[$1]
    cpu = $2; sub(/%$/, "", cpu)
    split($3, memory, " / ")
    cpu_total[name] += cpu / 100
    memory_total[name] += bytes(memory[1])
  }
  END {
    if (failed) exit 1
    for (name in cpu_total) {
      printf "taskflow_container_cpu_ratio{service=\"%s\"} %.10g\n", name, cpu_total[name]
      printf "taskflow_container_memory_bytes{service=\"%s\"} %.0f\n", name, memory_total[name]
    }
    printf "taskflow_docker_memory_total_bytes %.0f\n", host[1]
    printf "taskflow_docker_cpus %d\n", host[2]
  }
' >"$tmp"
{
  if [ -n "${TASKFLOW_DEPLOY_JOURNAL:-}" ] && [ -f "$TASKFLOW_DEPLOY_JOURNAL" ]; then
    awk -F'\t' '$3 == "success" { last = $1 } END { if (last != "") print "taskflow_deploy_last_success_timestamp_seconds", last }' \
      "$TASKFLOW_DEPLOY_JOURNAL"
  fi
  if [ -n "${TASKFLOW_BACKUP_ROOT:-}" ] && [ -d "$TASKFLOW_BACKUP_ROOT" ]; then
    ls "$TASKFLOW_BACKUP_ROOT" | grep -E '^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{7}$' | sort | tail -1 |
      sed 's/^/taskflow_db_backup_last_success_timestamp_seconds /'
  fi
} | awk '
  function days(y, m, d,   era, yoe, doy) {
    if (m <= 2) y -= 1
    era = int(y / 400); yoe = y - era * 400
    doy = int((153 * (m > 2 ? m - 3 : m + 9) + 2) / 5) + d - 1
    return era * 146097 + yoe * 365 + int(yoe / 4) - int(yoe / 100) + doy - 719468
  }
  {
    t = $2; gsub(/[^0-9]/, "", t)
    if (length(t) < 14) next
    print $1, days(substr(t, 1, 4) + 0, substr(t, 5, 2) + 0, substr(t, 7, 2) + 0) * 86400 \
      + substr(t, 9, 2) * 3600 + substr(t, 11, 2) * 60 + substr(t, 13, 2)
  }
' >>"$tmp"
chmod 644 "$tmp"
mv "$tmp" "$out_dir/containers.prom"
