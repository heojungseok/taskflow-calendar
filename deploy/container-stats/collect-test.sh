#!/bin/sh
set -eu

script_dir=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM

cat >"$work/docker" <<'MOCK'
#!/bin/sh
printf '%s\n' "$*" >>"$MOCK_LOG"
case "$1" in
  ps)
    [ "${MOCK_PS_FAIL:-false}" = true ] && exit 1
    [ "$3" = "label=com.docker.compose.project=taskflow-public" ] || exit 2
    [ "${MOCK_PS_EMPTY:-false}" = true ] && exit 0
    printf 'aaa111\tbackend\nbbb222\tpostgres\nccc333\tbackend\nddd444\tredis\n'
    ;;
  stats)
    [ "${MOCK_STATS_FAIL:-false}" = true ] && exit 1
    printf 'aaa111\t12.50%%\t512MiB / 4GiB\nbbb222\t150.00%%\t1.5GiB / 4GiB\nccc333\t2.50%%\t512KiB / 4GiB\nddd444\t--\t-- / --\n'
    ;;
  info)
    printf '4294967296 4\n'
    ;;
esac
MOCK
chmod +x "$work/docker"
out=$work/out
metrics=$out/containers.prom

run() {
  MOCK_LOG="$work/calls" DOCKER="$work/docker" sh "$script_dir/collect.sh" "$out"
}

run
expect() {
  grep -qxF "$1" "$metrics" || { echo "missing: $1" >&2; cat "$metrics" >&2; exit 1; }
}
expect 'taskflow_container_cpu_ratio{service="backend"} 0.15'
expect 'taskflow_container_cpu_ratio{service="postgres"} 1.5'
expect 'taskflow_container_memory_bytes{service="backend"} 537395200'
expect 'taskflow_container_memory_bytes{service="postgres"} 1610612736'
expect 'taskflow_docker_memory_total_bytes 4294967296'
expect 'taskflow_docker_cpus 4'
if grep -q 'redis' "$metrics"; then echo "unreadable stats row was exported" >&2; exit 1; fi
grep -q '^stats --no-stream .* aaa111 bbb222 ccc333 ddd444$' "$work/calls" || { echo "stats did not receive the project container ids" >&2; exit 1; }
[ "$(stat -c %a "$out" 2>/dev/null || stat -f %Lp "$out")" = 755 ] || { echo "output directory is not 755" >&2; exit 1; }

no_temp_left() {
  [ "$(find "$out" -name '.*' -type f | wc -l | tr -d ' ')" = 0 ] || { echo "temporary file left: $1" >&2; exit 1; }
}
no_temp_left success
cp "$metrics" "$work/before.prom"
for failure in MOCK_PS_FAIL MOCK_PS_EMPTY MOCK_STATS_FAIL; do
  if env "$failure=true" MOCK_LOG="$work/calls" DOCKER="$work/docker" sh "$script_dir/collect.sh" "$out" 2>/dev/null; then
    echo "$failure was accepted" >&2
    exit 1
  fi
  cmp -s "$metrics" "$work/before.prom" || { echo "$failure replaced the last metrics" >&2; exit 1; }
  no_temp_left "$failure"
done

printf 'aaa111\t1.00%%\t1.5TiB / 2TiB\n' >"$work/tib"
sed -i.bak "s#^    printf .aaa111.t12.50.*#    cat $work/tib#" "$work/docker"
run
expect 'taskflow_container_memory_bytes{service="backend"} 1649267441664'

journal=$work/journal.tsv
printf '2026-11-26T06:43:09Z\tsha1\tdeployment-running\tagent\n2026-11-26T06:43:26Z\tsha1\tsuccess\tagent\n2026-11-26T06:55:58Z\tsha2\tapproval-running\tfull\n2026-11-26T06:56:30Z\tsha2\tapproval-failed\tfull\n' >"$journal"
stamp=$work/last-backup-epoch
printf '1795564799\n' >"$stamp"
TASKFLOW_DEPLOY_JOURNAL=$journal TASKFLOW_BACKUP_STAMP=$stamp run
expect 'taskflow_deploy_last_started_timestamp_seconds 1795676158'
expect 'taskflow_deploy_last_success_timestamp_seconds 1795675406'
expect 'taskflow_db_backup_last_success_timestamp_seconds 1795564799'

TASKFLOW_DEPLOY_JOURNAL=$work/missing.tsv TASKFLOW_BACKUP_STAMP=$work/missing run
if grep -q 'deploy_last\|db_backup' "$metrics"; then echo "missing deploy or backup source was exported" >&2; exit 1; fi
expect 'taskflow_docker_cpus 4'

rm -rf "$out"
ln -s "$work" "$out"
if run 2>/dev/null; then echo "symlinked output directory was accepted" >&2; exit 1; fi

echo "PASS: container stats collection"
