#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM

mkdir -p "$tmp_dir/bin"
printf '%s\n' \
  '#!/bin/sh' \
  'if [ "${MOCK_GH_FAILURE:-false}" = true ]; then exit 1; fi' \
  'printf "%s\n" "$MOCK_GH_RESPONSE"' \
  >"$tmp_dir/bin/gh"
chmod +x "$tmp_dir/bin/gh"

target_sha=1111111111111111111111111111111111111111
other_sha=2222222222222222222222222222222222222222
export PATH="$tmp_dir/bin:$PATH"

assert_accepts() {
  MOCK_GH_RESPONSE=$1
  export MOCK_GH_RESPONSE
  sh "$project_root/deploy/verify-merged-main-sha.sh" heojungseok/taskflow-calendar "$target_sha"
}

assert_rejects() {
  MOCK_GH_RESPONSE=$1
  export MOCK_GH_RESPONSE
  if sh "$project_root/deploy/verify-merged-main-sha.sh" heojungseok/taskflow-calendar "$target_sha" >/dev/null 2>&1; then
    echo "Unmerged SHA was accepted" >&2
    exit 1
  fi
}

assert_accepts "[{\"merged_at\":\"2026-09-01T00:00:00Z\",\"base\":{\"ref\":\"main\"},\"merge_commit_sha\":\"$target_sha\"}]"
assert_rejects '[]'
assert_rejects "[{\"merged_at\":null,\"base\":{\"ref\":\"main\"},\"merge_commit_sha\":\"$target_sha\"}]"
assert_rejects "[{\"merged_at\":\"2026-09-01T00:00:00Z\",\"base\":{\"ref\":\"develop\"},\"merge_commit_sha\":\"$target_sha\"}]"
assert_rejects "[{\"merged_at\":\"2026-09-01T00:00:00Z\",\"base\":{\"ref\":\"main\"},\"merge_commit_sha\":\"$other_sha\"}]"

if sh "$project_root/deploy/verify-merged-main-sha.sh" heojungseok/taskflow-calendar short >/dev/null 2>&1; then
  echo "Invalid SHA was accepted" >&2
  exit 1
fi

MOCK_GH_FAILURE=true
export MOCK_GH_FAILURE
if sh "$project_root/deploy/verify-merged-main-sha.sh" heojungseok/taskflow-calendar "$target_sha" >/dev/null 2>&1; then
  echo "GitHub API failure was accepted" >&2
  exit 1
fi

echo "PASS: merged main SHA verification"
