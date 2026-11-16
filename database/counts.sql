-- Read-only exact counts for before/after migration. Import in phpMyAdmin or run its contents in SQL tab.
USE mail_server_db;
SELECT COUNT(*) AS user_count FROM users;
SELECT COUNT(*) AS email_count FROM emails;
SELECT COUNT(*) AS mailbox_entry_count FROM mailbox_entries;
