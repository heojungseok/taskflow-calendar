#!/bin/sh
set -eu

tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM

cat >"$tmp_dir/curl" <<'EOF'
#!/bin/sh
set -eu

printf '%s\n' "$*" >>"$CURL_LOG"
case "$*" in
  */healthz*|*/api/auth/session*) printf '%s' 200 ;;
  *) printf '%s' "${FORBIDDEN_CODE:-404}" ;;
esac
EOF
chmod +x "$tmp_dir/curl"

run_check() {
  code=$1
  CURL_LOG="$tmp_dir/curl-$code.log" \
  FORBIDDEN_CODE=$code \
  PATH="$tmp_dir:$PATH" \
  PUBLIC_ORIGIN=https://example.invalid \
    sh deploy/verify-public-boundary.sh
}

run_check 404

grep -F -- '--request POST https://example.invalid/api/test/calendar/create' "$tmp_dir/curl-404.log" >/dev/null

for code in 403 405 410; do
  if run_check "$code" >/dev/null 2>&1; then
    echo "Forbidden path check accepted ambiguous HTTP $code" >&2
    exit 1
  fi
done
