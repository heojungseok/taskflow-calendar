#!/bin/sh
set -eu

eval_dir=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$eval_dir/../.." && pwd)
cases_dir="$repo/incident-agent/bakeoff/cases"
case_name=${1:?usage: reproduce.sh <demo-traffic-spike|db-lock-pool-exhausted|db-down|backend-down|deploy-sort-npe|gemini-quota-exhausted>}
work=${WORK_DIR:-$(mktemp -d)}
warm_secs=${WARM_SECS:-1080}
export EVAL_JWT_SECRET="$(openssl rand -hex 32)"

log() { echo "$(date -u +%H:%M:%SZ) $case_name: $*" >&2; }
wait_until() { while [ "$(date +%s)" -lt "$1" ]; do sleep 5; done; }
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

stop_backend() {
  pid=$(lsof -nP -iTCP:8080 -sTCP:LISTEN -t || true)
  [ -z "$pid" ] || kill "$pid"
  while lsof -nP -iTCP:8080 -sTCP:LISTEN -t >/dev/null 2>&1; do sleep 1; done
}

start_backend() {
  REPO=$1 LOG_FILE=$2 "$eval_dir/run-backend.sh" >/dev/null 2>&1 &
  until curl -fs localhost:9091/actuator/health/readiness >/dev/null 2>&1; do sleep 1; done
}

cleanup() {
  pkill -f "k6 run -q" || true
  pkill -f gemini-quota.py || true
  stop_backend
  docker start taskflow-postgres >/dev/null
  [ ! -d "$work/bug" ] || { git -C "$repo" worktree remove --force "$work/bug"; git -C "$repo" branch -D eval/sort-tasks-by-due >/dev/null; }
  (cd "$eval_dir" && docker compose down >/dev/null 2>&1)
}
trap cleanup EXIT

google_session() {
  user_id=$(docker exec taskflow-postgres psql -U taskflow -d taskflow -tAc \
    "insert into users(id, created_at, updated_at, demo_mutation_count, email, name, provider, session_version)
     values (nextval('users_id_seq'), now(), now(), 0, 'eval-google-$(date +%s)@example.test', 'eval', 'GOOGLE', 0) returning id" | head -1)
  now=$(date +%s)
  head=$(printf '{"alg":"HS256"}' | b64url)
  body=$(printf '{"sub":"%s","sv":0,"iat":%s,"exp":%s}' "$user_id" "$now" "$((now + 3 * 3600))" | b64url)
  sig=$(printf '%s.%s' "$head" "$body" | openssl dgst -sha256 -hmac "$EVAL_JWT_SECRET" -binary | b64url)
  echo "$head.$body.$sig"
}

prepare_bug() {
  git -C "$repo" worktree add -q -b eval/sort-tasks-by-due "$work/bug" HEAD
  perl -0pi -e 's/import java.util.List;\n/import java.util.Comparator;\nimport java.util.List;\n/; s/(        return tasks.stream\(\)\n)/$1                .sorted(Comparator.comparing(Task::getDueAt))\n/' \
    "$work/bug/src/main/java/com/taskflow/planning/task/TaskService.java"
  git -C "$work/bug" -c user.name=eval -c user.email=eval@example.test commit -qam "feat: 작업 목록을 마감일 순으로 정렬한다"
  (cd "$work/bug" && ./gradlew -q classes)
}

docker start taskflow-postgres >/dev/null
stop_backend
(cd "$eval_dir" && docker compose down >/dev/null 2>&1 && docker compose up -d >/dev/null 2>&1)
[ "$case_name" != deploy-sort-npe ] || prepare_bug
main_log="$work/main.log"
start_backend "$repo" "$main_log"
log "backend up, warming ${warm_secs}s"
(cd "$eval_dir" && k6 run -q -e VUS=1 -e DURATION=90m load.js >/dev/null 2>&1 &)
if [ "$case_name" = gemini-quota-exhausted ]; then
  session=$(google_session)
  (cd "$eval_dir" && k6 run -q -e VUS=1 -e DURATION=90m -e PAUSE=3 -e SESSION="$session" load.js >/dev/null 2>&1 &)
fi
sleep "$warm_secs"

t0=$(date +%s)
log_files="$main_log"
commits='[]'
alert="backend error logs"
case "$case_name" in
  demo-traffic-spike)
    (cd "$eval_dir" && k6 run -q -e MODE=visitors -e VUS=25 -e DURATION=4m load.js >/dev/null 2>&1 &)
    alert="demo traffic spike"
    alert_at=$((t0 + 180))
    origin="재현: 공개 데모에 방문자 25명이 각자 데모 세션을 만들어 4분 동안 정상 요청을 보냈다. 장애가 아니다"
    answer=null ;;
  db-lock-pool-exhausted)
    (cd "$eval_dir" && k6 run -q -e VUS=20 -e DURATION=3m load.js >/dev/null 2>&1 &)
    sleep 20
    t0=$(date +%s)
    docker exec taskflow-postgres psql -U taskflow -d taskflow \
      -c "BEGIN; LOCK TABLE tasks IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(90); COMMIT;" >/dev/null 2>&1 &
    alert_at=$((t0 + 90))
    origin="재현: 긴 트랜잭션이 tasks 테이블을 90초 잠가 요청이 연결을 붙잡고 연결 풀이 고갈됐다(부하 20 VU)"
    answer='{"suspect":{"new_error":{"logger":"o.h.e.jdbc.spi.SqlExceptionHelper","contains":"Connection is not available"}},"line":"Connection is not available","keywords":["풀","pool","hikari","커넥션","연결","잠금","lock"]}' ;;
  db-down)
    docker stop taskflow-postgres >/dev/null
    (sleep 60 && docker start taskflow-postgres >/dev/null) &
    alert_at=$((t0 + 60))
    origin="재현: DB 컨테이너가 60초 내려갔다(재시작·OOM 상황)"
    answer='{"suspect":{"new_error":{"logger":"o.h.e.jdbc.spi.SqlExceptionHelper","contains":"refused"}},"line":"refused","keywords":["db","데이터베이스","postgres","postgresql","거부","refused","중단","다운","접속"]}' ;;
  backend-down)
    stop_backend
    alert="backend metrics collection down"
    alert_at=$((t0 + 195))
    origin="재현: backend 프로세스가 갑자기 종료됐다(OOM kill 상황). 내려가 있는 동안 로그가 없다"
    answer='{"suspect":{"metric":"backend_up"},"line":"지표 backend_up","keywords":["다운","중단","종료","죽","down","프로세스","내려","응답 없"]}' ;;
  deploy-sort-npe)
    stop_backend
    start_backend "$work/bug" "$work/bug.log"
    log_files="$main_log $work/bug.log"
    sha=$(git -C "$work/bug" rev-parse HEAD)
    commits=$(printf '[{"sha":"%s","files":["src/main/java/com/taskflow/planning/task/TaskService.java"]}]' "$sha")
    alert_at=$(($(date +%s) + 60))
    origin="재현: '작업 목록을 마감일 순으로 정렬한다' 커밋을 배포했다. 마감일이 없는 작업에서 Comparator가 NPE를 던져 목록 API가 500을 낸다"
    answer=$(printf '{"suspect":{"commit":"%s"},"line":"NullPointerException","keywords":["커밋","배포","정렬","sort","마감","duedate","null","npe"]}' "$sha") ;;
  gemini-quota-exhausted)
    python3 "$eval_dir/gemini-quota.py" 18080 &
    (cd "$eval_dir" && k6 run -q -e VUS=3 -e DURATION=5m -e PAUSE=5 -e MODE=recommend -e SESSION="$session" load.js >/dev/null 2>&1 &)
    alert="gemini quota exhausted"
    alert_at=$((t0 + 60))
    origin="재현: Gemini 쿼터가 바닥나 작업 추천 API가 429 RESOURCE_EXHAUSTED를 받는다(Google 사용자 3명). logger·예외 이름은 Spring AI 전환 전 기준"
    answer='{"suspect":{"metric":"gemini_failures_5m"},"line":"LLM_QUOTA_EXHAUSTED","keywords":["쿼터","quota","한도","429","gemini","소진","초과"]}' ;;
  *) echo "unknown case: $case_name" >&2; exit 2 ;;
esac

now=$((alert_at + 30))
log "fault at $t0, alert at $alert_at, capturing at $now"
wait_until "$((now + 15))"
cat $log_files >"$work/combined.log"
LOG_FILE="$work/combined.log" ORIGIN="$origin" COMMITS_JSON="$commits" \
  "$eval_dir/capture.sh" "$case_name" "$alert" "$alert_at" "$now" "$answer" >"$cases_dir/$case_name.json"
log "wrote $cases_dir/$case_name.json"
