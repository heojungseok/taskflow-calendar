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
}

assert_plan() {
  expected_scope=$1
  expected_backend=$2
  expected_frontend=$3
  expected_migration=$4
  expected_approval=$5
  target=$6

  output=$(sh "$repo/deploy/resolve-deployment-scope.sh" "$repo/production.env" "$target")
  printf '%s\n' "$output" | grep -Fx "DEPLOY_SCOPE=$expected_scope" >/dev/null
  printf '%s\n' "$output" | grep -Fx "NEXT_BACKEND_GIT_SHA=$expected_backend" >/dev/null
  printf '%s\n' "$output" | grep -Fx "NEXT_FRONTEND_GIT_SHA=$expected_frontend" >/dev/null
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

printf '%s\n' 'unknown production contract' >"$repo/deploy/new-production-contract.yml"
git -C "$repo" add deploy/new-production-contract.yml
git -C "$repo" commit -qm 'unknown path'
unknown_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$docs_sha" "$docs_sha"
assert_plan full "$unknown_sha" "$unknown_sha" false true "$unknown_sha"
git -C "$repo" reset -q --hard "$docs_sha"

mkdir -p "$repo/.github/workflows"
printf '%s\n' 'name: changed CI' >"$repo/.github/workflows/ci.yml"
git -C "$repo" add .github/workflows/ci.yml
git -C "$repo" commit -qm 'CI trust policy'
ci_policy_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$docs_sha" "$docs_sha"
assert_plan full "$ci_policy_sha" "$ci_policy_sha" false true "$ci_policy_sha"
git -C "$repo" reset -q --hard "$docs_sha"

printf '%s\n' 'export default {}' >"$repo/frontend/tailwind.config.js"
git -C "$repo" add frontend/tailwind.config.js
git -C "$repo" commit -qm 'frontend build config'
frontend_config_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$docs_sha" "$docs_sha"
assert_plan frontend "$docs_sha" "$frontend_config_sha" false false "$frontend_config_sha"

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

printf '%s\n' 'backend-v3' >"$repo/src/main/java/App.java"
printf '%s\n' 'frontend-v3' >"$repo/frontend/src/App.tsx"
git -C "$repo" commit -qam 'backend and frontend'
both_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$postgres_init_sha" "$postgres_init_sha"
assert_plan full "$both_sha" "$both_sha" false true "$both_sha"

write_env "$base_sha" "$frontend_sha"
assert_plan backend "$frontend_sha" "$frontend_sha" false false "$frontend_sha"

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
