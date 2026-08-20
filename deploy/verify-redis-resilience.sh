#!/bin/sh
set -eu

project=${COMPOSE_PROJECT_NAME:-taskflow-public}
env_file=${ENV_FILE:-.env.production.example}
compose_file=${COMPOSE_FILE:-compose.production.yml}
http_port=${HTTP_PORT:-8088}

compose() {
  docker compose -p "$project" --env-file "$env_file" -f "$compose_file" "$@"
}

wait_for() {
  attempts=$1
  shift
  while ! "$@" >/dev/null 2>&1; do
    attempts=$((attempts - 1))
    [ "$attempts" -gt 0 ] || return 1
    sleep 1
  done
}

backend_id=$(compose ps -q backend)
[ -n "$backend_id" ]

restore_redis() { compose start redis >/dev/null 2>&1 || true; }
trap restore_redis EXIT INT TERM

compose stop redis >/dev/null
wait_for 15 compose exec -T backend curl --fail --silent \
  http://127.0.0.1:9091/actuator/health/readiness
wait_for 15 curl --fail --silent "http://127.0.0.1:${http_port}/healthz"
[ "$(compose ps -q backend)" = "$backend_id" ]

compose start redis >/dev/null
wait_for 15 compose exec -T redis redis-cli ping
[ "$(compose ps -q backend)" = "$backend_id" ]

redis_id=$(compose ps -q redis)
grafana_id=$(compose ps -q grafana)
[ -n "$(docker inspect --format '{{index .HostConfig.Tmpfs "/data"}}' "$redis_id")" ]
[ -n "$(docker inspect --format '{{index .HostConfig.Tmpfs "/var/lib/grafana"}}' "$grafana_id")" ]
[ -z "$(docker inspect --format '{{range .Mounts}}{{if eq .Type "volume"}}{{.Destination}}{{end}}{{end}}' "$redis_id" "$grafana_id")" ]

trap - EXIT INT TERM
printf '%s\n' 'Redis fail-open runtime boundary verified'
