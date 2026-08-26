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

[ -f "$env_file" ] || fail "Production env not found: $env_file"

backend_sha=$(read_sha BACKEND_GIT_SHA)
frontend_sha=$(read_sha FRONTEND_GIT_SHA)

validate_sha BACKEND_GIT_SHA "$backend_sha"
validate_sha FRONTEND_GIT_SHA "$frontend_sha"
validate_sha target_sha "$target_sha"

git -C "$project_root" merge-base --is-ancestor "$backend_sha" "$target_sha" || \
  fail "BACKEND_GIT_SHA is not an ancestor of target_sha"
git -C "$project_root" merge-base --is-ancestor "$frontend_sha" "$target_sha" || \
  fail "FRONTEND_GIT_SHA is not an ancestor of target_sha"

backend_changed=false
frontend_changed=false
full_changed=false

if has_changes "$backend_sha" \
    Dockerfile .dockerignore build.gradle settings.gradle gradle gradle.lockfile gradlew gradlew.bat \
    src/main deploy/db/migration; then
  backend_changed=true
fi

if has_changes "$frontend_sha" \
    frontend/Dockerfile frontend/.dockerignore frontend/nginx.conf frontend/index.html \
    frontend/package.json frontend/package-lock.json frontend/public frontend/src \
    frontend/postcss.config.js frontend/tailwind.config.js \
    frontend/vite.config.js frontend/vite.config.ts frontend/tsconfig.json \
    'frontend/tsconfig.*.json'; then
  frontend_changed=true
fi

if has_changes "$backend_sha" \
      compose.production.yml deploy/grafana deploy/postgres/init-roles.sh \
      deploy/prometheus/prometheus.yml || \
    has_changes "$frontend_sha" \
      compose.production.yml deploy/grafana deploy/postgres/init-roles.sh \
      deploy/prometheus/prometheus.yml; then
  full_changed=true
fi

scope=none
next_backend_sha=$backend_sha
next_frontend_sha=$frontend_sha

if [ "$full_changed" = true ] || { [ "$backend_changed" = true ] && [ "$frontend_changed" = true ]; }; then
  scope=full
  next_backend_sha=$target_sha
  next_frontend_sha=$target_sha
elif [ "$backend_changed" = true ]; then
  scope=backend
  next_backend_sha=$target_sha
elif [ "$frontend_changed" = true ]; then
  scope=frontend
  next_frontend_sha=$target_sha
fi

printf 'DEPLOY_SCOPE=%s\n' "$scope"
printf 'NEXT_BACKEND_GIT_SHA=%s\n' "$next_backend_sha"
printf 'NEXT_FRONTEND_GIT_SHA=%s\n' "$next_frontend_sha"
