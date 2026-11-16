-- First create mailflow_app with host 127.0.0.1 in phpMyAdmin User accounts.
-- Set its password privately in that UI; no password belongs in this SQL file.
-- Execute this file explicitly as an administrator; application startup never grants privileges.
GRANT SELECT, INSERT, UPDATE ON mail_server_db.* TO 'mailflow_app'@'127.0.0.1';
