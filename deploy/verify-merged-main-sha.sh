#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <owner/repository> <target-sha>" >&2
  exit 2
fi

repository=$1
target_sha=$2

fail() {
  echo "$*" >&2
  exit 1
}

[ "${#target_sha}" -eq 40 ] || fail "target_sha must be a full 40-character Git SHA"
case "$target_sha" in
  *[!0-9a-f]*) fail "target_sha must be a lowercase hexadecimal Git SHA" ;;
esac

response=$(gh api \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  "repos/$repository/commits/$target_sha/pulls") || fail "Failed to query pull requests for target_sha"

printf '%s\n' "$response" | jq -e --arg sha "$target_sha" \
  'any(.[]; .merged_at != null and .base.ref == "main" and .merge_commit_sha == $sha)' \
  >/dev/null || fail "target_sha is not the merge SHA of a pull request merged into main"

echo "Verified merged main SHA: $target_sha"
