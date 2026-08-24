#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
SUFFIX="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-0}-$$"
NETWORK="taskflow-session-migration-$SUFFIX"
POSTGRES_CONTAINER="taskflow-session-postgres-$SUFFIX"
POSTGRES_IMAGE="pgvector/pgvector:0.8.6-pg17@sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f"
FLYWAY_IMAGE="flyway/flyway:11.13-alpine@sha256:ed275452f578feab0d94f8660da7ced33cd629c266d34294d7d3173852e358db"
BOOTSTRAP_PASSWORD="session-migration-bootstrap-test-only"
OWNER_PASSWORD="session-migration-owner-test-only"
APP_PASSWORD="session-migration-app-test-only"

cleanup() {
    docker rm -f "$POSTGRES_CONTAINER" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

docker network create "$NETWORK" >/dev/null
docker run -d \
    --name "$POSTGRES_CONTAINER" \
    --network "$NETWORK" \
    -p 127.0.0.1::5432 \
    -e POSTGRES_DB=taskflow \
    -e POSTGRES_USER=taskflow_bootstrap \
    -e POSTGRES_PASSWORD="$BOOTSTRAP_PASSWORD" \
    -e TASKFLOW_DB_OWNER_PASSWORD="$OWNER_PASSWORD" \
    -e TASKFLOW_DB_APP_PASSWORD="$APP_PASSWORD" \
    -v "$PROJECT_ROOT/deploy/postgres/init-roles.sh:/docker-entrypoint-initdb.d/10-init-roles.sh:ro" \
    "$POSTGRES_IMAGE" >/dev/null

attempt=0
until docker exec "$POSTGRES_CONTAINER" \
    pg_isready -h 127.0.0.1 -U taskflow_bootstrap -d taskflow >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 60 ]; then
        echo "PostgreSQL readiness timed out" >&2
        exit 1
    fi
    sleep 1
done

run_flyway() {
    docker run --rm \
        --network "$NETWORK" \
        -e FLYWAY_URL="jdbc:postgresql://$POSTGRES_CONTAINER:5432/taskflow" \
        -e FLYWAY_USER=taskflow_owner \
        -e FLYWAY_PASSWORD="$OWNER_PASSWORD" \
        -v "$PROJECT_ROOT/deploy/db/migration:/flyway/sql:ro" \
        "$FLYWAY_IMAGE" "$@"
}

run_flyway -target=2 migrate

docker exec -i -e PGPASSWORD="$BOOTSTRAP_PASSWORD" "$POSTGRES_CONTAINER" \
    psql --set=ON_ERROR_STOP=1 --username taskflow_bootstrap --dbname taskflow <<'SQL'
DO $$
BEGIN
    IF (SELECT count(*)
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'users'
          AND column_name IN ('created_at', 'email', 'name', 'updated_at')
          AND is_nullable = 'NO'
          AND column_default IS NULL) <> 4 THEN
        RAISE EXCEPTION 'Unexpected V2 required users columns';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'users'
          AND column_name = 'demo_mutation_count'
          AND data_type = 'integer'
          AND is_nullable = 'NO'
          AND column_default = '0') THEN
        RAISE EXCEPTION 'Unexpected V2 demo_mutation_count definition';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'users'
          AND column_name = 'provider'
          AND is_nullable = 'YES') THEN
        RAISE EXCEPTION 'Unexpected V2 provider definition';
    END IF;
END $$;

INSERT INTO users (created_at, email, name, updated_at, provider) VALUES
    (CURRENT_TIMESTAMP, 'migration-google@example.test', 'Google', CURRENT_TIMESTAMP, 'GOOGLE'),
    (CURRENT_TIMESTAMP, 'migration-demo@example.test', 'Demo', CURRENT_TIMESTAMP, 'DEMO'),
    (CURRENT_TIMESTAMP, 'migration-null@example.test', 'Null Provider', CURRENT_TIMESTAMP, NULL);
SQL

run_flyway migrate

docker exec -i -e PGPASSWORD="$BOOTSTRAP_PASSWORD" "$POSTGRES_CONTAINER" \
    psql --set=ON_ERROR_STOP=1 --username taskflow_bootstrap --dbname taskflow <<'SQL'
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'users'
          AND column_name = 'session_version'
          AND data_type = 'integer'
          AND is_nullable = 'NO'
          AND column_default = '0') THEN
        RAISE EXCEPTION 'Unexpected session_version definition';
    END IF;
    IF (SELECT session_version FROM users WHERE email = 'migration-google@example.test')
            IS DISTINCT FROM 1 THEN
        RAISE EXCEPTION 'Existing Google user was not invalidated';
    END IF;
    IF (SELECT session_version FROM users WHERE email = 'migration-demo@example.test')
            IS DISTINCT FROM 0 THEN
        RAISE EXCEPTION 'Demo user session version changed';
    END IF;
    IF (SELECT session_version FROM users WHERE email = 'migration-null@example.test')
            IS DISTINCT FROM 0 THEN
        RAISE EXCEPTION 'NULL provider session version changed';
    END IF;
END $$;

INSERT INTO users (created_at, email, name, updated_at, provider)
VALUES (CURRENT_TIMESTAMP, 'migration-new-google@example.test', 'New Google', CURRENT_TIMESTAMP, 'GOOGLE');

DO $$
BEGIN
    IF (SELECT session_version FROM users WHERE email = 'migration-new-google@example.test')
            IS DISTINCT FROM 0 THEN
        RAISE EXCEPTION 'New Google user did not receive default session version';
    END IF;
END $$;
SQL

PORT=$(docker port "$POSTGRES_CONTAINER" 5432/tcp | sed -n '1s/.*://p')
if [ -z "$PORT" ]; then
    echo "Failed to resolve PostgreSQL host port" >&2
    exit 1
fi

cd "$PROJECT_ROOT"
DB_URL="jdbc:postgresql://127.0.0.1:$PORT/taskflow" \
DB_USERNAME=taskflow_app \
DB_PASSWORD="$APP_PASSWORD" \
JPA_DDL_AUTO=validate \
./gradlew test --tests 'com.taskflow.calendar.domain.oauth.SessionInvalidationIntegrationTest'

echo "PASS: session_version migration and replay verification"
