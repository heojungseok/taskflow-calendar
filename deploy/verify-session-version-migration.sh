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
BACKUP_DIR=$(mktemp -d)
V3_DUMP="$BACKUP_DIR/taskflow-v3.dump"

cleanup() {
    docker rm -f -v "$POSTGRES_CONTAINER" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
    rm -r "$BACKUP_DIR" >/dev/null 2>&1 || true
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
    database="$1"
    shift
    docker run --rm \
        --network "$NETWORK" \
        -e FLYWAY_URL="jdbc:postgresql://$POSTGRES_CONTAINER:5432/$database" \
        -e FLYWAY_USER=taskflow_owner \
        -e FLYWAY_PASSWORD="$OWNER_PASSWORD" \
        -v "$PROJECT_ROOT/deploy/db/migration:/flyway/sql:ro" \
        "$FLYWAY_IMAGE" "$@"
}

run_flyway taskflow -target=2 migrate

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

run_flyway taskflow -target=3 migrate

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

UPDATE users
SET expires_at = TIMESTAMP '2026-08-26 12:34:56.123456'
WHERE email = 'migration-demo@example.test';

INSERT INTO users (created_at, email, name, updated_at, provider, expires_at)
VALUES
    (CURRENT_TIMESTAMP, 'migration-expired-demo@example.test', 'Expired Demo', CURRENT_TIMESTAMP,
     'DEMO', TIMESTAMP '2026-08-25 12:34:56.654321'),
    (CURRENT_TIMESTAMP, 'migration-null-expiry@example.test', 'Null Expiry', CURRENT_TIMESTAMP,
     'DEMO', NULL);
SQL

EXPECTED_ACTIVE_EPOCH=$(docker exec -e PGPASSWORD="$BOOTSTRAP_PASSWORD" "$POSTGRES_CONTAINER" \
    psql --tuples-only --no-align --username taskflow_bootstrap --dbname taskflow \
    --command="SELECT EXTRACT(EPOCH FROM expires_at AT TIME ZONE 'UTC') FROM users WHERE email = 'migration-demo@example.test'")
EXPECTED_EXPIRED_EPOCH=$(docker exec -e PGPASSWORD="$BOOTSTRAP_PASSWORD" "$POSTGRES_CONTAINER" \
    psql --tuples-only --no-align --username taskflow_bootstrap --dbname taskflow \
    --command="SELECT EXTRACT(EPOCH FROM expires_at AT TIME ZONE 'UTC') FROM users WHERE email = 'migration-expired-demo@example.test'")

docker exec -e PGPASSWORD="$BOOTSTRAP_PASSWORD" "$POSTGRES_CONTAINER" \
    pg_dump --format=custom --username taskflow_bootstrap --dbname taskflow > "$V3_DUMP"

run_flyway taskflow -target=4 migrate

verify_v4() {
    database="$1"
    actual_active_epoch=$(docker exec -e PGPASSWORD="$BOOTSTRAP_PASSWORD" "$POSTGRES_CONTAINER" \
        psql --tuples-only --no-align --username taskflow_bootstrap --dbname "$database" \
        --command="SELECT EXTRACT(EPOCH FROM expires_at) FROM users WHERE email = 'migration-demo@example.test'")
    if [ "$actual_active_epoch" != "$EXPECTED_ACTIVE_EPOCH" ]; then
        echo "Active DEMO expiry epoch changed: expected=$EXPECTED_ACTIVE_EPOCH actual=$actual_active_epoch" >&2
        exit 1
    fi
    actual_expired_epoch=$(docker exec -e PGPASSWORD="$BOOTSTRAP_PASSWORD" "$POSTGRES_CONTAINER" \
        psql --tuples-only --no-align --username taskflow_bootstrap --dbname "$database" \
        --command="SELECT EXTRACT(EPOCH FROM expires_at) FROM users WHERE email = 'migration-expired-demo@example.test'")
    if [ "$actual_expired_epoch" != "$EXPECTED_EXPIRED_EPOCH" ]; then
        echo "Expired DEMO expiry epoch changed: expected=$EXPECTED_EXPIRED_EPOCH actual=$actual_expired_epoch" >&2
        exit 1
    fi

    docker exec -i -e PGPASSWORD="$BOOTSTRAP_PASSWORD" "$POSTGRES_CONTAINER" \
        psql --set=ON_ERROR_STOP=1 --username taskflow_bootstrap --dbname "$database" <<'SQL'
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'users'
          AND column_name = 'expires_at'
          AND data_type = 'timestamp with time zone') THEN
        RAISE EXCEPTION 'users.expires_at is not TIMESTAMPTZ';
    END IF;
    IF (SELECT expires_at FROM users WHERE email = 'migration-null-expiry@example.test')
            IS NOT NULL THEN
        RAISE EXCEPTION 'NULL expiry changed during V4';
    END IF;
END $$;
SQL
}

verify_restore_access() {
    database="$1"
    docker exec -i -e PGPASSWORD="$BOOTSTRAP_PASSWORD" "$POSTGRES_CONTAINER" \
        psql --set=ON_ERROR_STOP=1 --username taskflow_bootstrap --dbname "$database" <<'SQL'
DO $$
BEGIN
    IF (SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database())
            IS DISTINCT FROM 'taskflow_owner' THEN
        RAISE EXCEPTION 'Unexpected database owner';
    END IF;
    IF (SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'users')
            IS DISTINCT FROM 'taskflow_owner' THEN
        RAISE EXCEPTION 'Unexpected users table owner';
    END IF;
    IF NOT has_schema_privilege('taskflow_app', 'public', 'USAGE') THEN
        RAISE EXCEPTION 'taskflow_app lacks public schema usage';
    END IF;
    IF NOT has_table_privilege('taskflow_app', 'public.users', 'SELECT,INSERT,UPDATE,DELETE') THEN
        RAISE EXCEPTION 'taskflow_app lacks users table privileges';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public'
          AND c.relkind = 'S'
          AND NOT has_sequence_privilege(
              'taskflow_app', format('%I.%I', n.nspname, c.relname), 'USAGE,SELECT,UPDATE')) THEN
        RAISE EXCEPTION 'taskflow_app lacks sequence privileges';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        RAISE EXCEPTION 'vector extension is missing';
    END IF;
END $$;
SQL
}

verify_v4 taskflow

docker exec -e PGPASSWORD="$BOOTSTRAP_PASSWORD" "$POSTGRES_CONTAINER" \
    createdb --username taskflow_bootstrap --owner taskflow_owner taskflow_restore
docker exec -e PGPASSWORD="$BOOTSTRAP_PASSWORD" "$POSTGRES_CONTAINER" \
    psql --set=ON_ERROR_STOP=1 --username taskflow_bootstrap --dbname taskflow_restore \
    --command='CREATE EXTENSION IF NOT EXISTS vector' >/dev/null
docker exec -i -e PGPASSWORD="$BOOTSTRAP_PASSWORD" "$POSTGRES_CONTAINER" \
    pg_restore --username taskflow_bootstrap --dbname taskflow_restore \
    < "$V3_DUMP"
run_flyway taskflow_restore -target=4 migrate
verify_v4 taskflow_restore
verify_restore_access taskflow_restore

PORT=$(docker port "$POSTGRES_CONTAINER" 5432/tcp | sed -n '1s/.*://p')
if [ -z "$PORT" ]; then
    echo "Failed to resolve PostgreSQL host port" >&2
    exit 1
fi

cd "$PROJECT_ROOT"

run_application_tests() {
    database="$1"
    jvm_timezone="$2"
    database_timezone="$3"
    docker exec -e PGPASSWORD="$BOOTSTRAP_PASSWORD" "$POSTGRES_CONTAINER" \
        psql --set=ON_ERROR_STOP=1 --username taskflow_bootstrap --dbname postgres \
        --command="ALTER DATABASE $database SET timezone TO '$database_timezone'" >/dev/null
    JAVA_TOOL_OPTIONS="-Duser.timezone=$jvm_timezone" \
    DB_URL="jdbc:postgresql://127.0.0.1:$PORT/$database" \
    DB_USERNAME=taskflow_app \
    DB_PASSWORD="$APP_PASSWORD" \
    JPA_DDL_AUTO=validate \
    ./gradlew --no-daemon --rerun-tasks test \
        --tests 'com.taskflow.calendar.domain.user.DemoExpiryIntegrationTest' \
        --tests 'com.taskflow.calendar.domain.user.DemoCleanupRepositoryTest' \
        --tests 'com.taskflow.calendar.domain.oauth.SessionInvalidationIntegrationTest'
}

run_full_build() {
    database="$1"
    jvm_timezone="$2"
    database_timezone="$3"
    docker exec -e PGPASSWORD="$BOOTSTRAP_PASSWORD" "$POSTGRES_CONTAINER" \
        psql --set=ON_ERROR_STOP=1 --username taskflow_bootstrap --dbname postgres \
        --command="ALTER DATABASE $database SET timezone TO '$database_timezone'" >/dev/null
    JAVA_TOOL_OPTIONS="-Duser.timezone=$jvm_timezone" \
    DB_URL="jdbc:postgresql://127.0.0.1:$PORT/$database" \
    DB_USERNAME=taskflow_owner \
    DB_PASSWORD="$OWNER_PASSWORD" \
    JPA_DDL_AUTO=validate \
    ./gradlew --no-daemon --rerun-tasks build
}

run_application_tests taskflow Asia/Seoul UTC
run_application_tests taskflow America/New_York Asia/Seoul
run_application_tests taskflow UTC America/New_York
run_application_tests taskflow_restore UTC UTC
run_full_build taskflow_restore UTC UTC

echo "PASS: session migrations, DEMO expiry preservation, restore-forward, timezone matrix, and full build"
