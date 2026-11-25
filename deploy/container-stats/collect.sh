#!/bin/sh
set -eu

out_dir=${1:?usage: collect.sh <textfile-directory>}
docker_bin=${DOCKER:-docker}
project=${TASKFLOW_COMPOSE_PROJECT:-taskflow-public}

services=$("$docker_bin" ps --filter "label=com.docker.compose.project=$project" \
  --format '{{.ID}}\t{{.Label "com.docker.compose.service"}}')
ids=$(printf '%s\n' "$services" | cut -f1)
stats=""
if [ -n "$ids" ]; then
  # shellcheck disable=SC2086
  stats=$("$docker_bin" stats --no-stream --format '{{.ID}}\t{{.CPUPerc}}\t{{.MemUsage}}' $ids)
fi

tmp=$(mktemp "$out_dir/.containers.prom.XXXXXX")
trap 'rm -f "$tmp"' EXIT
printf '%s\n%s\n' "$services" "$stats" | awk -F'\t' '
  function bytes(value,   number, unit) {
    number = value; sub(/[A-Za-z]+$/, "", number)
    unit = value; sub(/^[0-9.]+/, "", unit)
    if (unit == "KiB") return number * 1024
    if (unit == "MiB") return number * 1024 * 1024
    if (unit == "GiB") return number * 1024 * 1024 * 1024
    if (unit == "kB") return number * 1000
    if (unit == "MB") return number * 1000 * 1000
    if (unit == "GB") return number * 1000 * 1000 * 1000
    return number
  }
  NF == 2 { service[$1] = $2; next }
  NF == 3 && ($1 in service) {
    cpu = $2; sub(/%$/, "", cpu)
    split($3, memory, " / ")
    printf "taskflow_container_cpu_ratio{service=\"%s\"} %.10g\n", service[$1], cpu / 100
    printf "taskflow_container_memory_bytes{service=\"%s\"} %.0f\n", service[$1], bytes(memory[1])
    printf "taskflow_container_memory_limit_bytes{service=\"%s\"} %.0f\n", service[$1], bytes(memory[2])
  }
' >"$tmp"
chmod 644 "$tmp"
mv "$tmp" "$out_dir/containers.prom"
