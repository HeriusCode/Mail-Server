CREATE DATABASE IF NOT EXISTS mail_server_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE mail_server_db;

CREATE TABLE IF NOT EXISTS users (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(254) NOT NULL,
  email VARCHAR(254) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_users_username (username),
  UNIQUE KEY uq_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS emails (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  sender_id BIGINT NULL,
  sender_address VARCHAR(254) NOT NULL,
  receiver_id BIGINT NULL,
  receiver_address VARCHAR(254) NOT NULL,
  subject VARCHAR(998) NOT NULL,
  body MEDIUMTEXT NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_emails_sender FOREIGN KEY (sender_id) REFERENCES users(id),
  CONSTRAINT fk_emails_receiver FOREIGN KEY (receiver_id) REFERENCES users(id),
  KEY ix_emails_sender_time (sender_id, created_at),
  KEY ix_emails_receiver_time (receiver_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Transactional file outbox: content is authoritative; retries never overwrite differing files.
CREATE TABLE IF NOT EXISTS mailbox_entries (
  owner_id BIGINT NOT NULL,
  folder VARCHAR(5) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  filename VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  email_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  content MEDIUMTEXT NOT NULL,
  materialized BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (owner_id, folder, filename),
  CONSTRAINT fk_entry_owner FOREIGN KEY (owner_id) REFERENCES users(id),
  CONSTRAINT fk_entry_email FOREIGN KEY (email_id) REFERENCES emails(id),
  KEY ix_entries_pending (materialized),
  KEY ix_entries_email (email_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
