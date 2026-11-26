#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <production-env> <target-sha>" >&2
  exit 2
fi

env_file=$1
target_sha=$2
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

fail() {
  echo "$*" >&2
  exit 1
}

read_sha() {
  key=$1
  count=$(awk -F= -v key="$key" '$1 == key { count++ } END { print count + 0 }' "$env_file")
  [ "$count" -eq 1 ] || fail "$key must appear exactly once in $env_file"
  awk -v key="$key" '$0 ~ ("^" key "=") { print substr($0, length(key) + 2) }' "$env_file"
}

validate_sha() {
  name=$1
  value=$2
  [ "${#value}" -eq 40 ] || fail "$name must be a full 40-character Git SHA"
  case "$value" in
    *[!0-9a-f]*) fail "$name must be a lowercase hexadecimal Git SHA" ;;
  esac
  git -C "$project_root" cat-file -e "$value^{commit}" 2>/dev/null || fail "$name does not identify a commit"
}

has_changes() {
  from_sha=$1
  shift
  if git -C "$project_root" diff --quiet "$from_sha..$target_sha" -- "$@"; then
    return 1
  else
    status=$?
  fi
  [ "$status" -eq 1 ] || fail "git diff failed for $from_sha..$target_sha"
  return 0
}

has_unknown_changes() {
  from_sha=$1
  has_changes "$from_sha" . \
    ':(exclude).gitignore' ':(exclude).env.example' ':(exclude)README.md' ':(exclude)LICENSE*' \
    ':(exclude)docs/**' ':(exclude).github/workflows/ci.yml' \
    ':(exclude).github/workflows/incident-agent.yml' ':(exclude)incident-agent/**' \
    ':(exclude)Dockerfile' ':(exclude).dockerignore' ':(exclude)build.gradle' \
    ':(exclude)settings.gradle' ':(exclude)gradle/**' ':(exclude)gradle.lockfile' \
    ':(exclude)gradlew' ':(exclude)gradlew.bat' ':(exclude)src/main/**' ':(exclude)src/test/**' \
    ':(exclude)frontend/Dockerfile' ':(exclude)frontend/.dockerignore' \
    ':(exclude)frontend/nginx.conf' ':(exclude)frontend/index.html' \
    ':(exclude)frontend/package.json' ':(exclude)frontend/package-lock.json' \
    ':(exclude)frontend/public/**' ':(exclude)frontend/src/**' ':(exclude)frontend/e2e/**' \
    ':(exclude)frontend/postcss.config.js' ':(exclude)frontend/tailwind.config.js' \
    ':(exclude)frontend/vite.config.js' ':(exclude)frontend/vite.config.ts' \
    ':(exclude)frontend/tsconfig.json' ':(exclude)frontend/tsconfig.*.json' \
    ':(exclude)compose.production.yml' ':(exclude).env.production.example' \
    ':(exclude)deploy/db/migration/**' ':(exclude)deploy/grafana/**' \
    ':(exclude)deploy/loki/**' ':(exclude)deploy/alloy/**' ':(exclude)deploy/container-stats/**' \
    ':(exclude)deploy/postgres/init-roles.sh' ':(exclude)deploy/prometheus/prometheus.yml' \
    ':(exclude)deploy/resolve-deployment-scope.sh' ':(exclude)deploy/resolve-deployment-scope-test.sh' \
    ':(exclude)deploy/verify-compose-boundary.sh' ':(exclude)deploy/verify-public-boundary.sh' \
    ':(exclude)deploy/verify-public-boundary-test.sh' ':(exclude)deploy/verify-redis-resilience.sh' \
    ':(exclude)deploy/verify-runtime-privileges.sh' ':(exclude)deploy/verify-session-version-migration.sh'
}

read_optional_sha() {
  key=$1
  if grep -q "^$key=" "$env_file"; then
    read_sha "$key"
  fi
}

[ -f "$env_file" ] || fail "Production env not found: $env_file"

backend_sha=$(read_sha BACKEND_GIT_SHA)
frontend_sha=$(read_sha FRONTEND_GIT_SHA)

validate_sha BACKEND_GIT_SHA "$backend_sha"
validate_sha FRONTEND_GIT_SHA "$frontend_sha"
agent_sha=$(read_optional_sha AGENT_GIT_SHA)
agent_tracked=false
if [ -n "$agent_sha" ]; then
  agent_tracked=true
  validate_sha AGENT_GIT_SHA "$agent_sha"
else
  agent_sha=$backend_sha
fi
validate_sha target_sha "$target_sha"

git -C "$project_root" merge-base --is-ancestor "$backend_sha" "$target_sha" || \
  fail "BACKEND_GIT_SHA is not an ancestor of target_sha"
git -C "$project_root" merge-base --is-ancestor "$frontend_sha" "$target_sha" || \
  fail "FRONTEND_GIT_SHA is not an ancestor of target_sha"
if [ "$agent_tracked" = true ]; then
  git -C "$project_root" merge-base --is-ancestor "$agent_sha" "$target_sha" || \
    fail "AGENT_GIT_SHA is not an ancestor of target_sha"
fi

backend_changed=false
frontend_changed=false
agent_changed=false
full_changed=false
migration_changed=false
unknown_changed=false

if has_changes "$backend_sha" \
    Dockerfile .dockerignore build.gradle settings.gradle gradle gradle.lockfile gradlew gradlew.bat \
    src/main deploy/db/migration; then
  backend_changed=true
fi

if has_changes "$backend_sha" deploy/db/migration; then
  migration_changed=true
fi

if has_changes "$frontend_sha" \
    frontend/Dockerfile frontend/.dockerignore frontend/nginx.conf frontend/index.html \
    frontend/package.json frontend/package-lock.json frontend/public frontend/src \
    frontend/postcss.config.js frontend/tailwind.config.js \
    frontend/vite.config.js frontend/vite.config.ts frontend/tsconfig.json \
    'frontend/tsconfig.*.json'; then
  frontend_changed=true
fi

if [ "$agent_tracked" = true ] && has_changes "$agent_sha" incident-agent; then
  agent_changed=true
fi

if has_changes "$backend_sha" \
      compose.production.yml .env.production.example deploy/grafana deploy/postgres/init-roles.sh \
      deploy/prometheus/prometheus.yml deploy/loki deploy/alloy deploy/container-stats ':(exclude)deploy/container-stats/*-test.sh' || \
    has_changes "$frontend_sha" \
      compose.production.yml .env.production.example deploy/grafana deploy/postgres/init-roles.sh \
      deploy/prometheus/prometheus.yml deploy/loki deploy/alloy deploy/container-stats ':(exclude)deploy/container-stats/*-test.sh'; then
  full_changed=true
fi

if has_unknown_changes "$backend_sha" || has_unknown_changes "$frontend_sha"; then
  unknown_changed=true
fi

scope=none
next_backend_sha=$backend_sha
next_frontend_sha=$frontend_sha
next_agent_sha=$agent_sha
requires_approval=false

if [ "$unknown_changed" = true ]; then
  scope=unknown
elif [ "$full_changed" = true ] || \
     { [ "$backend_changed" = true ] && [ "$frontend_changed" = true ]; }; then
  scope=full
  next_backend_sha=$target_sha
  next_frontend_sha=$target_sha
  next_agent_sha=$target_sha
elif [ "$backend_changed" = true ]; then
  scope=backend
  next_backend_sha=$target_sha
elif [ "$frontend_changed" = true ]; then
  scope=frontend
  next_frontend_sha=$target_sha
elif [ "$agent_changed" = true ]; then
  scope=agent
  next_agent_sha=$target_sha
fi

if [ "$scope" = full ] || \
   { [ "$scope" != unknown ] && [ "$migration_changed" = true ]; }; then
  requires_approval=true
fi

printf 'DEPLOY_SCOPE=%s\n' "$scope"
printf 'NEXT_BACKEND_GIT_SHA=%s\n' "$next_backend_sha"
printf 'NEXT_FRONTEND_GIT_SHA=%s\n' "$next_frontend_sha"
printf 'NEXT_AGENT_GIT_SHA=%s\n' "$next_agent_sha"
printf 'MIGRATION_CHANGED=%s\n' "$migration_changed"
printf 'REQUIRES_APPROVAL=%s\n' "$requires_approval"
