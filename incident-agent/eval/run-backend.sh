#!/bin/sh
set -eu

repo=${REPO:-$(cd "$(dirname "$0")/../.." && pwd)}
log_file=${LOG_FILE:?LOG_FILE is required}
gemini=${GEMINI_URL:-http://127.0.0.1:18080/v1beta}

export JWT_SECRET="${EVAL_JWT_SECRET:-$(openssl rand -hex 32)}"
export TOKEN_ENC_PASSWORD="$(openssl rand -hex 16)"
export TOKEN_ENC_SALT="$(openssl rand -hex 16)"
export GOOGLE_CLIENT_ID=eval-not-used
export GOOGLE_CLIENT_SECRET=eval-not-used
export SESSION_COOKIE_SECURE=false SEARCH_SCHEMA_MANAGEMENT_ENABLED=true GEMINI_SEARCH_SEMANTIC_ENABLED=false
export JPA_SHOW_SQL=false LOG_LEVEL_APP=INFO LOG_LEVEL_SQL=WARN LOG_LEVEL_SQL_BIND=WARN
export GEMINI_SUMMARY_API_KEY=eval-not-used GEMINI_SUMMARY_BASEURL="$gemini"
export GEMINI_RECOMMENDATION_API_KEY=eval-not-used GEMINI_RECOMMENDATION_BASEURL="$gemini"
export GEMINI_SEARCH_API_KEY=eval-not-used GEMINI_SEARCH_BASEURL="$gemini"
export LOGGING_PATTERN_CONSOLE="%d{yyyy-MM-dd'T'HH:mm:ss'Z',UTC} %-5level %logger{36} : %msg%replace( traceId=%X{traceId}){' traceId=\$', ''}%.-2000replace(%replace( | %rEx{12}){'\\s*\\R\\s*', ' | '}){'( \\| )+\$', ''}%n%nopex"

cd "$repo"
exec ./gradlew bootRun -q >"$log_file" 2>&1
