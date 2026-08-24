ALTER TABLE users ADD COLUMN session_version INTEGER NOT NULL DEFAULT 0;

UPDATE users SET session_version = 1 WHERE provider = 'GOOGLE';
