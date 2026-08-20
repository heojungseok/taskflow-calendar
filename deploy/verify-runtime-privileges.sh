#!/bin/sh
set -eu

psql --set=ON_ERROR_STOP=1 <<'SQL'
BEGIN;
INSERT INTO users(created_at, email, name, updated_at, provider)
VALUES (now(), 'runtime-verify@demo.taskflow.local', 'verify', now(), 'DEMO');
UPDATE users SET name = 'verified' WHERE email = 'runtime-verify@demo.taskflow.local';
DELETE FROM users WHERE email = 'runtime-verify@demo.taskflow.local';
COMMIT;
SQL

for statement in \
  'CREATE TABLE runtime_must_not_create(id bigint)' \
  'CREATE ROLE runtime_must_not_create' \
  'CREATE EXTENSION IF NOT EXISTS hstore'
do
  if psql --set=ON_ERROR_STOP=1 --command "$statement" >/dev/null 2>&1; then
    echo "runtime privilege unexpectedly allowed: $statement" >&2
    exit 1
  fi
done
