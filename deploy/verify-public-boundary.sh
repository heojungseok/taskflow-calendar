#!/bin/sh
set -eu

origin=${PUBLIC_ORIGIN:?required}

check_code() {
  url=$1
  expected=$2
  code=$(curl --silent --output /dev/null --write-out '%{http_code}' "$url")
  if [ "$code" != "$expected" ]; then
    echo "Unexpected HTTP code for $url: $code (expected $expected)" >&2
    exit 1
  fi
}

check_forbidden_path() {
  url=$1
  method=${2:-GET}
  code=$(curl --silent --output /dev/null --write-out '%{http_code}' --request "$method" "$url")
  case "$code" in
    404)
      ;;
    *)
      echo "Public boundary exposed unexpectedly for $url: $code" >&2
      exit 1
      ;;
  esac
}

echo "[1/5] public health"
check_code "$origin/healthz" 200

echo "[2/5] session probe"
check_code "$origin/api/auth/session" 200

echo "[3/5] blocked actuator"
check_forbidden_path "$origin/actuator/health/readiness"
check_forbidden_path "$origin/actuator"

echo "[4/5] blocked internal test API"
check_forbidden_path "$origin/api/test/calendar/create" POST

echo "[5/5] blocked management endpoints via proxy"
check_forbidden_path "$origin/api/admin"
check_forbidden_path "$origin/api/admin/calendar-outbox"

printf '%s\n' 'Public boundary validation passed'
