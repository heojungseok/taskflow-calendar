#!/bin/sh
set -eu

script_dir=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

cat >"$work/docker" <<'MOCK'
#!/bin/sh
case "$1" in
  ps)
    [ "${MOCK_DOCKER_FAIL:-false}" = true ] && exit 1
    printf 'aaa111\tbackend\nbbb222\tpostgres\n'
    ;;
  stats)
    printf 'aaa111\t12.50%%\t512MiB / 1GiB\nbbb222\t150.00%%\t1.5GiB / 2GiB\n'
    ;;
esac
MOCK
chmod +x "$work/docker"

DOCKER="$work/docker" sh "$script_dir/collect.sh" "$work/out"
metrics=$work/out/containers.prom

expect() {
  grep -qxF "$1" "$metrics" || { echo "missing: $1" >&2; cat "$metrics" >&2; exit 1; }
}
expect 'taskflow_container_cpu_ratio{service="backend"} 0.125'
expect 'taskflow_container_cpu_ratio{service="postgres"} 1.5'
expect 'taskflow_container_memory_bytes{service="backend"} 536870912'
expect 'taskflow_container_memory_limit_bytes{service="backend"} 1073741824'
expect 'taskflow_container_memory_bytes{service="postgres"} 1610612736'
[ "$(find "$work/out" -name '.*' -type f | wc -l | tr -d ' ')" = 0 ] || { echo "temporary file left" >&2; exit 1; }

cp "$metrics" "$work/before.prom"
if MOCK_DOCKER_FAIL=true DOCKER="$work/docker" sh "$script_dir/collect.sh" "$work/out" 2>/dev/null; then
  echo "docker failure was accepted" >&2
  exit 1
fi
cmp -s "$metrics" "$work/before.prom" || { echo "failed run replaced the last metrics" >&2; exit 1; }
[ "$(find "$work/out" -name '.*' -type f | wc -l | tr -d ' ')" = 0 ] || { echo "temporary file left after failure" >&2; exit 1; }

echo "PASS: container stats collection"
