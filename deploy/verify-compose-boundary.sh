#!/bin/sh
set -eu

test -x deploy/postgres/init-roles.sh || {
  echo "PostgreSQL init script must be executable" >&2
  exit 1
}

config=$(mktemp)
trap 'rm -f "$config"' EXIT
cat > "$config"

# Grafana provisioning의 object_matchers는 연산자를 따옴표로 감싸야 한다. 그대로 쓰면 YAML 태그로 읽혀 Grafana가 시작하지 못한다.
if grep -En '\[[^],]+, *(!=|=~|!~)[],]' deploy/grafana/provisioning/alerting/*.yml; then
  echo "Quote matcher operators in Grafana alerting provisioning" >&2
  exit 1
fi

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
  and ((.services.backend.networks | keys | sort) == ["cache", "data", "edge", "log-ingest", "monitoring"])
  and ((.services.migrator.networks | keys) == ["data"])
  and ((.services.postgres.networks | keys) == ["data"])
  and ((.services["postgres-exporter"].networks | keys | sort) == ["data", "monitoring"])
  and (.services["postgres-exporter"].image | test("^prometheuscommunity/postgres-exporter:v[0-9.]+@sha256:[0-9a-f]{64}$"))
  and .services["postgres-exporter"].environment.DATA_SOURCE_USER == "taskflow_monitor"
  and (.services["postgres-exporter"].environment.DATA_SOURCE_NAME == null)
  and (.services["postgres-exporter"].ports == null)
  and ((.services["container-stats-exporter"].networks | keys) == ["monitoring"])
  and (.services["container-stats-exporter"].image | test("^prom/node-exporter:v[0-9.]+@sha256:[0-9a-f]{64}$"))
  and (.services["container-stats-exporter"].ports == null)
  and ([.services["container-stats-exporter"].volumes[] | select(.target == "/textfile") | .read_only] == [true])
  and ((.services["container-stats-exporter"].volumes | length) == 1)
  and ([.services[] | .volumes[]? | (.source // "") | select(test("docker\\.sock|/\\.docker"))] | length == 0)
  and (.services["container-stats-exporter"].command | index("--collector.disable-defaults") != null)
  and ((.services.prometheus.networks | keys) == ["monitoring"])
  and ((.services.grafana.networks | keys | sort) == ["grafana-egress", "logging", "monitoring"])
  and ((.services["incident-agent"].networks | keys | sort) == ["agent-egress", "logging", "monitoring"])
  and (.networks["agent-egress"].internal != true)
  and .services["incident-agent"].image == "taskflow-incident-agent:replace-with-exact-agent-git-sha"
  and (.services["incident-agent"].ports == null)
  and (.services["incident-agent"].read_only == true)
  and (.services["incident-agent"].cap_drop == ["ALL"])
  and (.services["incident-agent"].security_opt == ["no-new-privileges:true"])
  and (.services["incident-agent"].cap_add == null)
  and (.services["incident-agent"].privileged == null)
  and (.services["incident-agent"].network_mode == null)
  and (.services["incident-agent"].pid == null)
  and (.services["incident-agent"].user | test("^[1-9][0-9]*:[1-9][0-9]*$"))
  and ([.services["incident-agent"].volumes[] | .target] | sort == ["/data", "/deploy-state/journal.tsv", "/incident/drafts", "/incident/requests", "/incident/results"])
  and ([.services["incident-agent"].volumes[] | select(.type == "bind") | .bind.create_host_path] == [false, false, false, false])
  and ([.services["incident-agent"].volumes[] | select(.target == "/deploy-state/journal.tsv") | .read_only] == [true])
  and ([.services | to_entries[] | select((.value.networks // {}) | has("agent-egress")) | .key] == ["incident-agent"])
  and (.networks.logging.internal and .networks["log-ingest"].internal)
  and (.networks["log-ingest"].ipam.config[0].subnet == "10.213.47.0/28")
  and ((.services.loki.networks | keys) == ["logging"])
  and ((.services.alloy.networks | keys | sort) == ["log-ingest", "logging"])
  and (.services.alloy.networks["log-ingest"].ipv4_address == "10.213.47.10")
  and ((.services.nginx.networks | keys | sort) == ["edge", "log-ingest"])
  and ([.services | to_entries[] | select((.value.networks // {}) | has("log-ingest")) | .key] | sort == ["alloy", "backend", "nginx"])
  and ([.services[] | .volumes[]? | select(.source == "/var/run/docker.sock")] | length == 0)
  and .services.backend.environment.APP_LOGGING_SYSLOG_HOST == "10.213.47.10"
  and .volumes["loki-data"].name == "taskflow-public-loki-data"
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
