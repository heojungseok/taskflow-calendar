#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM

repo="$tmp_dir/repo"
mkdir -p "$repo/deploy/db/migration" "$repo/frontend/src" "$repo/src/main/java"
cp "$project_root/deploy/resolve-deployment-scope.sh" "$repo/deploy/"

git -C "$repo" init -q
git -C "$repo" config user.email test@example.invalid
git -C "$repo" config user.name "Deployment Scope Test"

printf '%s\n' 'backend-v1' >"$repo/src/main/java/App.java"
printf '%s\n' 'frontend-v1' >"$repo/frontend/src/App.tsx"
printf '%s\n' 'services: {}' >"$repo/compose.production.yml"
git -C "$repo" add .
git -C "$repo" commit -qm 'base'
base_sha=$(git -C "$repo" rev-parse HEAD)

printf '%s\n' 'backend-v2' >"$repo/src/main/java/App.java"
git -C "$repo" commit -qam 'backend'
backend_sha=$(git -C "$repo" rev-parse HEAD)

printf '%s\n' 'frontend-v2' >"$repo/frontend/src/App.tsx"
git -C "$repo" commit -qam 'frontend'
frontend_sha=$(git -C "$repo" rev-parse HEAD)

printf '%s\n' 'services: { backend: {} }' >"$repo/compose.production.yml"
git -C "$repo" commit -qam 'full'
full_sha=$(git -C "$repo" rev-parse HEAD)

printf '%s\n' 'docs only' >"$repo/README.md"
git -C "$repo" add README.md
git -C "$repo" commit -qm 'docs'
docs_sha=$(git -C "$repo" rev-parse HEAD)

write_env() {
  printf 'BACKEND_GIT_SHA=%s\nFRONTEND_GIT_SHA=%s\n' "$1" "$2" >"$repo/production.env"
  if [ "$#" -ge 3 ]; then
    printf 'AGENT_GIT_SHA=%s\n' "$3" >>"$repo/production.env"
  fi
}

assert_plan() {
  expected_scope=$1
  expected_backend=$2
  expected_frontend=$3
  expected_migration=$4
  expected_approval=$5
  target=$6
  expected_agent=${7:-}

  output=$(sh "$repo/deploy/resolve-deployment-scope.sh" "$repo/production.env" "$target")
  printf '%s\n' "$output" | grep -Fx "DEPLOY_SCOPE=$expected_scope" >/dev/null
  printf '%s\n' "$output" | grep -Fx "NEXT_BACKEND_GIT_SHA=$expected_backend" >/dev/null
  printf '%s\n' "$output" | grep -Fx "NEXT_FRONTEND_GIT_SHA=$expected_frontend" >/dev/null
  if [ -n "$expected_agent" ]; then
    printf '%s\n' "$output" | grep -Fx "NEXT_AGENT_GIT_SHA=$expected_agent" >/dev/null
  fi
  printf '%s\n' "$output" | grep -Fx "MIGRATION_CHANGED=$expected_migration" >/dev/null
  printf '%s\n' "$output" | grep -Fx "REQUIRES_APPROVAL=$expected_approval" >/dev/null
}

write_env "$base_sha" "$base_sha"
assert_plan backend "$backend_sha" "$base_sha" false false "$backend_sha"

write_env "$backend_sha" "$base_sha"
assert_plan frontend "$backend_sha" "$frontend_sha" false false "$frontend_sha"

write_env "$frontend_sha" "$frontend_sha"
assert_plan full "$full_sha" "$full_sha" false true "$full_sha"

printf '%s\n' 'NEW_REQUIRED_KEY=replace-me' >"$repo/.env.production.example"
git -C "$repo" add .env.production.example
git -C "$repo" commit -qm 'environment contract'
env_contract_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$full_sha" "$full_sha"
assert_plan full "$env_contract_sha" "$env_contract_sha" false true "$env_contract_sha"
full_sha=$env_contract_sha

printf '%s\n' 'docs after environment contract' >>"$repo/README.md"
git -C "$repo" commit -qam 'docs after environment contract'
docs_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$full_sha" "$full_sha"
assert_plan none "$full_sha" "$full_sha" false false "$docs_sha"

mkdir -p "$repo/incident-agent" "$repo/.github/workflows"
printf '%s\n' '[workspace]' >"$repo/incident-agent/Cargo.toml"
printf '%s\n' 'name: incident-agent' >"$repo/.github/workflows/incident-agent.yml"
git -C "$repo" add incident-agent .github/workflows/incident-agent.yml
git -C "$repo" commit -qm 'incident agent'
incident_agent_sha=$(git -C "$repo" rev-parse HEAD)
assert_plan agent "$full_sha" "$full_sha" false false "$incident_agent_sha" "$incident_agent_sha"
printf '%s\n' 'backend-with-incident-agent' >"$repo/src/main/java/App.java"
git -C "$repo" commit -qam 'incident agent and backend'
incident_backend_sha=$(git -C "$repo" rev-parse HEAD)
assert_plan full "$incident_backend_sha" "$incident_backend_sha" false true "$incident_backend_sha" "$incident_backend_sha"
git -C "$repo" reset -q --hard "$docs_sha"

printf '%s\n' 'unknown production contract' >"$repo/deploy/new-production-contract.yml"
git -C "$repo" add deploy/new-production-contract.yml
git -C "$repo" commit -qm 'unknown path'
unknown_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$docs_sha" "$docs_sha"
assert_plan unknown "$docs_sha" "$docs_sha" false false "$unknown_sha"

git -C "$repo" mv deploy/new-production-contract.yml deploy/renamed-production-contract.yml
git -C "$repo" commit -qm 'rename unknown path'
unknown_rename_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$unknown_sha" "$unknown_sha"
assert_plan unknown "$unknown_sha" "$unknown_sha" false false "$unknown_rename_sha"

git -C "$repo" reset -q --hard "$unknown_sha"
printf '%s\n' 'backend-unknown' >"$repo/src/main/java/App.java"
git -C "$repo" commit -qam 'unknown and backend'
unknown_backend_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$docs_sha" "$docs_sha"
assert_plan unknown "$docs_sha" "$docs_sha" false false "$unknown_backend_sha"

git -C "$repo" reset -q --hard "$unknown_sha"
printf '%s\n' 'frontend-unknown' >"$repo/frontend/src/App.tsx"
git -C "$repo" commit -qam 'unknown and frontend'
unknown_frontend_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$docs_sha" "$docs_sha"
assert_plan unknown "$docs_sha" "$docs_sha" false false "$unknown_frontend_sha"

printf '%s\n' 'backend-and-frontend-unknown' >"$repo/src/main/java/App.java"
git -C "$repo" commit -qam 'unknown backend and frontend'
unknown_both_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$docs_sha" "$docs_sha"
assert_plan unknown "$docs_sha" "$docs_sha" false false "$unknown_both_sha"

git -C "$repo" reset -q --hard "$unknown_sha"
printf '%s\n' 'services: { unknown: {} }' >"$repo/compose.production.yml"
git -C "$repo" commit -qam 'unknown and full'
unknown_full_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$docs_sha" "$docs_sha"
assert_plan unknown "$docs_sha" "$docs_sha" false false "$unknown_full_sha"

git -C "$repo" reset -q --hard "$unknown_sha"
printf '%s\n' 'select 2;' >"$repo/deploy/db/migration/V2__unknown.sql"
git -C "$repo" add deploy/db/migration/V2__unknown.sql
git -C "$repo" commit -qm 'unknown and migration'
unknown_migration_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$docs_sha" "$docs_sha"
assert_plan unknown "$docs_sha" "$docs_sha" true false "$unknown_migration_sha"
git -C "$repo" reset -q --hard "$docs_sha"

mkdir -p "$repo/.github/workflows"
printf '%s\n' 'name: changed CI' >"$repo/.github/workflows/ci.yml"
git -C "$repo" add .github/workflows/ci.yml
git -C "$repo" commit -qm 'CI trust policy'
ci_policy_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$docs_sha" "$docs_sha"
assert_plan none "$docs_sha" "$docs_sha" false false "$ci_policy_sha"

printf '%s\n' 'name: sibling workflow' >"$repo/.github/workflows/other.yml"
git -C "$repo" add .github/workflows/other.yml
git -C "$repo" commit -qm 'unknown sibling workflow'
sibling_workflow_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$ci_policy_sha" "$ci_policy_sha"
assert_plan unknown "$ci_policy_sha" "$ci_policy_sha" false false "$sibling_workflow_sha"
git -C "$repo" reset -q --hard "$docs_sha"

mkdir -p "$repo/docs"
printf '%s\n' 'local defaults' >"$repo/.env.example"
printf '%s\n' 'operations' >"$repo/docs/operations.md"
printf '%s\n' 'license' >"$repo/LICENSE.txt"
git -C "$repo" add .env.example docs/operations.md LICENSE.txt
git -C "$repo" commit -qm 'none allowlist'
none_allowlist_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$docs_sha" "$docs_sha"
assert_plan none "$docs_sha" "$docs_sha" false false "$none_allowlist_sha"

git -C "$repo" rm -q LICENSE.txt
git -C "$repo" commit -qm 'delete known none path'
none_delete_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$none_allowlist_sha" "$none_allowlist_sha"
assert_plan none "$none_allowlist_sha" "$none_allowlist_sha" false false "$none_delete_sha"
git -C "$repo" reset -q --hard "$docs_sha"

printf '%s\n' 'export default {}' >"$repo/frontend/tailwind.config.js"
git -C "$repo" add frontend/tailwind.config.js
git -C "$repo" commit -qm 'frontend build config'
frontend_config_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$docs_sha" "$docs_sha"
assert_plan frontend "$docs_sha" "$frontend_config_sha" false false "$frontend_config_sha"

mkdir -p "$repo/deploy/db/migration"
printf '%s\n' 'select 1;' >"$repo/deploy/db/migration/V2__change.sql"
git -C "$repo" add deploy/db/migration/V2__change.sql
git -C "$repo" commit -qm 'migration'
migration_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$docs_sha" "$frontend_config_sha"
assert_plan backend "$migration_sha" "$frontend_config_sha" true true "$migration_sha"

printf '%s\n' 'lockfileVersion=1' >"$repo/gradle.lockfile"
git -C "$repo" add gradle.lockfile
git -C "$repo" commit -qm 'backend lockfile'
lockfile_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$migration_sha" "$frontend_config_sha"
assert_plan backend "$lockfile_sha" "$frontend_config_sha" false false "$lockfile_sha"

mkdir -p "$repo/deploy/prometheus"
printf '%s\n' 'global: {}' >"$repo/deploy/prometheus/prometheus.yml"
git -C "$repo" add deploy/prometheus/prometheus.yml
git -C "$repo" commit -qm 'monitoring config'
monitoring_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$lockfile_sha" "$frontend_config_sha"
assert_plan full "$monitoring_sha" "$monitoring_sha" false true "$monitoring_sha"

mkdir -p "$repo/deploy/grafana/dashboards"
printf '%s\n' '{}' >"$repo/deploy/grafana/dashboards/taskflow.json"
git -C "$repo" add deploy/grafana/dashboards/taskflow.json
git -C "$repo" commit -qm 'grafana config'
grafana_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$monitoring_sha" "$monitoring_sha"
assert_plan full "$grafana_sha" "$grafana_sha" false true "$grafana_sha"

mkdir -p "$repo/deploy/postgres"
printf '%s\n' '#!/bin/sh' >"$repo/deploy/postgres/init-roles.sh"
git -C "$repo" add deploy/postgres/init-roles.sh
git -C "$repo" commit -qm 'postgres init contract'
postgres_init_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$grafana_sha" "$grafana_sha"
assert_plan full "$postgres_init_sha" "$postgres_init_sha" false true "$postgres_init_sha"

# 로그 수집 설정만 바뀌어도 full 배포로 잡혀야 한다. 목록에 빠지면 unknown으로 막히거나 조용히 건너뛴다.
mkdir -p "$repo/deploy/loki"
printf '%s\n' 'auth_enabled: false' >"$repo/deploy/loki/loki.yml"
git -C "$repo" add deploy/loki/loki.yml
git -C "$repo" commit -qm 'loki config'
loki_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$postgres_init_sha" "$postgres_init_sha"
assert_plan full "$loki_sha" "$loki_sha" false true "$loki_sha"

mkdir -p "$repo/deploy/alloy"
printf '%s\n' 'loki.write "local" {}' >"$repo/deploy/alloy/config.alloy"
git -C "$repo" add deploy/alloy/config.alloy
git -C "$repo" commit -qm 'alloy config'
alloy_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$loki_sha" "$loki_sha"
assert_plan full "$alloy_sha" "$alloy_sha" false true "$alloy_sha"

mkdir -p "$repo/deploy/container-stats"
printf '%s\n' '#!/bin/sh' >"$repo/deploy/container-stats/collect.sh"
git -C "$repo" add deploy/container-stats/collect.sh
git -C "$repo" commit -qm 'container stats collector'
container_stats_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$alloy_sha" "$alloy_sha"
assert_plan full "$container_stats_sha" "$container_stats_sha" false true "$container_stats_sha"

printf '%s\n' 'backend-v3' >"$repo/src/main/java/App.java"
printf '%s\n' 'frontend-v3' >"$repo/frontend/src/App.tsx"
git -C "$repo" commit -qam 'backend and frontend'
both_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$alloy_sha" "$alloy_sha"
assert_plan full "$both_sha" "$both_sha" false true "$both_sha"

write_env "$base_sha" "$frontend_sha"
assert_plan backend "$frontend_sha" "$frontend_sha" false false "$frontend_sha"

# 분석기(incident-agent/**)만 바뀌면 agent 범위, 승인 없이 자동. 워크플로 파일만 바뀌면 none.
sentinel_base=$(git -C "$repo" rev-parse HEAD)
mkdir -p "$repo/.github/workflows"
printf '%s\n' 'name: incident-agent' >"$repo/.github/workflows/incident-agent.yml"
git -C "$repo" add .github/workflows/incident-agent.yml
git -C "$repo" commit -qm 'agent workflow'
agent_workflow_sha=$(git -C "$repo" rev-parse HEAD)
write_env "$sentinel_base" "$sentinel_base" "$sentinel_base"
assert_plan none "$sentinel_base" "$sentinel_base" false false "$agent_workflow_sha" "$sentinel_base"

mkdir -p "$repo/incident-agent/agent"
printf '%s\n' 'fn main() {}' >"$repo/incident-agent/agent/main.rs"
git -C "$repo" add incident-agent
git -C "$repo" commit -qm 'agent code'
agent_sha=$(git -C "$repo" rev-parse HEAD)
write_env "$sentinel_base" "$sentinel_base" "$sentinel_base"
assert_plan agent "$sentinel_base" "$sentinel_base" false false "$agent_sha" "$agent_sha"

# AGENT_GIT_SHA가 아직 env에 없으면 BACKEND_GIT_SHA를 기준으로 판별한다.
write_env "$sentinel_base" "$sentinel_base"
assert_plan agent "$sentinel_base" "$sentinel_base" false false "$agent_sha" "$agent_sha"

# 이미 배포된 agent 위에서는 다른 범위만 바뀐 것으로 본다(agent sha는 그대로).
write_env "$sentinel_base" "$sentinel_base" "$agent_sha"
assert_plan none "$sentinel_base" "$sentinel_base" false false "$agent_sha" "$agent_sha"

# agent와 backend가 함께 바뀌면 full(승인), agent와 frontend도 마찬가지.
printf '%s\n' 'backend-v4' >"$repo/src/main/java/App.java"
git -C "$repo" commit -qam 'backend after agent'
agent_backend_sha=$(git -C "$repo" rev-parse HEAD)
write_env "$agent_sha" "$agent_sha" "$sentinel_base"
assert_plan full "$agent_backend_sha" "$agent_backend_sha" false true "$agent_backend_sha" "$agent_backend_sha"

printf '%s\n' 'frontend-v4' >"$repo/frontend/src/App.tsx"
git -C "$repo" commit -qam 'frontend after agent'
agent_frontend_sha=$(git -C "$repo" rev-parse HEAD)
write_env "$agent_backend_sha" "$sentinel_base" "$sentinel_base"
assert_plan full "$agent_frontend_sha" "$agent_frontend_sha" false true "$agent_frontend_sha" "$agent_frontend_sha"

# agent와 migration이 함께 바뀌면 full(승인)이고 migration 표시는 유지된다.
printf '%s\n' 'select 2;' >"$repo/deploy/db/migration/V3__agent.sql"
printf '%s\n' 'fn main() { }' >"$repo/incident-agent/agent/main.rs"
git -C "$repo" add deploy/db/migration incident-agent
git -C "$repo" commit -qm 'agent and migration'
agent_migration_sha=$(git -C "$repo" rev-parse HEAD)
write_env "$agent_frontend_sha" "$agent_frontend_sha" "$agent_frontend_sha"
assert_plan full "$agent_migration_sha" "$agent_migration_sha" true true "$agent_migration_sha" "$agent_migration_sha"

# agent와 compose가 함께 바뀌면 full.
printf '%s\n' 'services: { agent: {} }' >"$repo/compose.production.yml"
git -C "$repo" commit -qam 'compose after agent'
agent_compose_sha=$(git -C "$repo" rev-parse HEAD)
write_env "$agent_migration_sha" "$agent_migration_sha" "$agent_migration_sha"
assert_plan full "$agent_compose_sha" "$agent_compose_sha" false true "$agent_compose_sha" "$agent_compose_sha"

write_env "$base_sha" "$frontend_sha"
sentinel="$tmp_dir/sourced-secret"
printf 'UNRELATED_SECRET=$(touch %s)\n' "$sentinel" >>"$repo/production.env"
sh "$repo/deploy/resolve-deployment-scope.sh" "$repo/production.env" "$frontend_sha" >/dev/null
[ ! -e "$sentinel" ] || {
  echo "Production env contents were executed" >&2
  exit 1
}

write_env "$both_sha" "$both_sha"
printf 'BACKEND_GIT_SHA=%s\n' "$both_sha" >>"$repo/production.env"
if sh "$repo/deploy/resolve-deployment-scope.sh" "$repo/production.env" "$both_sha" >/dev/null 2>&1; then
  echo "Duplicate deployment SHA was accepted" >&2
  exit 1
fi

write_env short "$both_sha"
if sh "$repo/deploy/resolve-deployment-scope.sh" "$repo/production.env" "$both_sha" >/dev/null 2>&1; then
  echo "Short deployment SHA was accepted" >&2
  exit 1
fi

blob_sha=$(printf '%s' 'not a commit' | git -C "$repo" hash-object -w --stdin)
write_env "$blob_sha" "$both_sha"
if sh "$repo/deploy/resolve-deployment-scope.sh" "$repo/production.env" "$both_sha" >/dev/null 2>&1; then
  echo "Non-commit deployment SHA was accepted" >&2
  exit 1
fi

git -C "$repo" checkout -qb unrelated "$base_sha"
printf '%s\n' 'unrelated' >"$repo/unrelated.txt"
git -C "$repo" add unrelated.txt
git -C "$repo" commit -qm 'unrelated'
unrelated_sha=$(git -C "$repo" rev-parse HEAD)
write_env "$both_sha" "$both_sha"
if sh "$repo/deploy/resolve-deployment-scope.sh" "$repo/production.env" "$unrelated_sha" >/dev/null 2>&1; then
  echo "Non-ancestor deployment SHA was accepted" >&2
  exit 1
fi

echo "PASS: deployment scope resolution"
