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
  target=$4

  output=$(sh "$repo/deploy/resolve-deployment-scope.sh" "$repo/production.env" "$target")
  printf '%s\n' "$output" | grep -Fx "DEPLOY_SCOPE=$expected_scope" >/dev/null
  printf '%s\n' "$output" | grep -Fx "NEXT_BACKEND_GIT_SHA=$expected_backend" >/dev/null
  printf '%s\n' "$output" | grep -Fx "NEXT_FRONTEND_GIT_SHA=$expected_frontend" >/dev/null
}

write_env "$base_sha" "$base_sha"
assert_plan backend "$backend_sha" "$base_sha" "$backend_sha"

write_env "$backend_sha" "$base_sha"
assert_plan frontend "$backend_sha" "$frontend_sha" "$frontend_sha"

write_env "$frontend_sha" "$frontend_sha"
assert_plan full "$full_sha" "$full_sha" "$full_sha"

write_env "$full_sha" "$full_sha"
assert_plan none "$full_sha" "$full_sha" "$docs_sha"

printf '%s\n' 'export default {}' >"$repo/frontend/tailwind.config.js"
git -C "$repo" add frontend/tailwind.config.js
git -C "$repo" commit -qm 'frontend build config'
frontend_config_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$docs_sha" "$docs_sha"
assert_plan frontend "$docs_sha" "$frontend_config_sha" "$frontend_config_sha"

printf '%s\n' 'select 1;' >"$repo/deploy/db/migration/V2__change.sql"
git -C "$repo" add deploy/db/migration/V2__change.sql
git -C "$repo" commit -qm 'migration'
migration_sha=$(git -C "$repo" rev-parse HEAD)

write_env "$docs_sha" "$frontend_config_sha"
assert_plan backend "$migration_sha" "$frontend_config_sha" "$migration_sha"

write_env "$base_sha" "$frontend_sha"
assert_plan backend "$frontend_sha" "$frontend_sha" "$frontend_sha"

git -C "$repo" checkout -qb unrelated "$base_sha"
printf '%s\n' 'unrelated' >"$repo/unrelated.txt"
git -C "$repo" add unrelated.txt
git -C "$repo" commit -qm 'unrelated'
unrelated_sha=$(git -C "$repo" rev-parse HEAD)
write_env "$migration_sha" "$migration_sha"
if sh "$repo/deploy/resolve-deployment-scope.sh" "$repo/production.env" "$unrelated_sha" >/dev/null 2>&1; then
  echo "Non-ancestor deployment SHA was accepted" >&2
  exit 1
fi

echo "PASS: deployment scope resolution"
