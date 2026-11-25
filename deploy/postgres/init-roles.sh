#!/bin/sh
set -eu

: "${TASKFLOW_DB_OWNER_PASSWORD:?required}"
: "${TASKFLOW_DB_APP_PASSWORD:?required}"
: "${TASKFLOW_DB_MONITOR_PASSWORD:?required}"

psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=owner_password="$TASKFLOW_DB_OWNER_PASSWORD" \
  --set=app_password="$TASKFLOW_DB_APP_PASSWORD" \
  --set=monitor_password="$TASKFLOW_DB_MONITOR_PASSWORD" <<'SQL'
SET log_min_error_statement = panic;
SELECT 'CREATE ROLE taskflow_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'taskflow_owner')\gexec
SELECT 'CREATE ROLE taskflow_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'taskflow_app')\gexec
SELECT 'CREATE ROLE taskflow_monitor LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'taskflow_monitor')\gexec
ALTER ROLE taskflow_owner PASSWORD :'owner_password';
ALTER ROLE taskflow_app PASSWORD :'app_password';
ALTER ROLE taskflow_monitor PASSWORD :'monitor_password' CONNECTION LIMIT 3;
ALTER ROLE taskflow_monitor SET default_transaction_read_only = on;
GRANT pg_monitor TO taskflow_monitor;
ALTER DATABASE taskflow OWNER TO taskflow_owner;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO taskflow_owner;
GRANT USAGE ON SCHEMA public TO taskflow_app;
GRANT CONNECT ON DATABASE taskflow TO taskflow_owner, taskflow_app, taskflow_monitor;
CREATE EXTENSION IF NOT EXISTS vector;
SQL
