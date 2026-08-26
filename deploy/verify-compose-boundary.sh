#!/bin/sh
set -eu

test -x deploy/postgres/init-roles.sh || {
  echo "PostgreSQL init script must be executable" >&2
  exit 1
}

config=$(mktemp)
trap 'rm -f "$config"' EXIT
cat > "$config"

jq -e '
  .name == "taskflow-public"
  and (.services | all(.container_name == null))
  and .services.backend.image == "taskflow-backend:replace-with-exact-backend-git-sha"
  and .services["http-verify"].image == "taskflow-backend:replace-with-exact-backend-git-sha"
  and .services.nginx.image == "taskflow-frontend:replace-with-exact-frontend-git-sha"
  and .volumes["postgres-data"].name == "taskflow-public-postgres-data"
  and .volumes["prometheus-data"].name == "taskflow-public-prometheus-data"
  and ([.services[] | .ports[]? | .host_ip] | all(. == "127.0.0.1"))
  and ([.services | to_entries[] | select(.key != "nginx" and .key != "grafana") | .value.ports[]?] | length == 0)
  and (.networks.data.internal and .networks.cache.internal and .networks.monitoring.internal)
  and ((.services.redis.networks | keys) == ["cache"])
  and ((.services.backend.networks | keys | sort) == ["cache", "data", "edge", "monitoring"])
  and ((.services.migrator.networks | keys) == ["data"])
  and ((.services.postgres.networks | keys) == ["data"])
  and ((.services.prometheus.networks | keys) == ["monitoring"])
  and ((.services.grafana.networks | keys | sort) == ["grafana-egress", "monitoring"])
  and (.services.redis.volumes == null)
  and (.services.redis.tmpfs | any(startswith("/data")))
  and ([.services.grafana.volumes[]? | select(.type == "volume")] | length == 0)
  and (.services.grafana.tmpfs | any(startswith("/var/lib/grafana")))
  and (.services.redis.mem_limit == "201326592")
  and (.services.redis.command | join(" ") | contains("--save  --appendonly no --protected-mode no --maxmemory 128mb --maxmemory-policy allkeys-lru"))
  and (.services.postgres.healthcheck.start_period == "15s" and .services.postgres.healthcheck.retries == 3)
  and (.services.backend.healthcheck.start_period == "30s" and .services.backend.healthcheck.retries == 3)
  and (.services.redis.healthcheck.start_period == "5s" and .services.redis.healthcheck.retries == 3)
  and (.services.nginx.healthcheck.test[-1] == "http://127.0.0.1:8080/healthz")
  and .services.backend.environment.WEEKLY_SUMMARY_CACHE_ENABLED == "true"
  and .services.backend.environment.WEEKLY_SUMMARY_CACHE_TTL_SECONDS == "900"
  and .services.backend.environment.REDIS_URL == "redis://redis:6379"
  and (.services.backend.environment.REDIS_URL | test("^redis://[^@/:]+:[0-9]+$") and (contains("@") | not))
' "$config" >/dev/null
