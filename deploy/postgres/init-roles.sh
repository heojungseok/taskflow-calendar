#!/bin/sh
set -eu

: "${TASKFLOW_DB_OWNER_PASSWORD:?required}"
: "${TASKFLOW_DB_APP_PASSWORD:?required}"

psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=owner_password="$TASKFLOW_DB_OWNER_PASSWORD" \
  --set=app_password="$TASKFLOW_DB_APP_PASSWORD" <<'SQL'
SELECT 'CREATE ROLE taskflow_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'taskflow_owner')\gexec
SELECT 'CREATE ROLE taskflow_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'taskflow_app')\gexec
ALTER ROLE taskflow_owner PASSWORD :'owner_password';
ALTER ROLE taskflow_app PASSWORD :'app_password';
ALTER DATABASE taskflow OWNER TO taskflow_owner;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO taskflow_owner;
GRANT USAGE ON SCHEMA public TO taskflow_app;
GRANT CONNECT ON DATABASE taskflow TO taskflow_owner, taskflow_app;
CREATE EXTENSION IF NOT EXISTS vector;
SQL
