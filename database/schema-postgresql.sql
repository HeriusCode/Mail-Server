CREATE TABLE IF NOT EXISTS users (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(254) NOT NULL UNIQUE,
  email VARCHAR(254) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS emails (
  id VARCHAR(36) PRIMARY KEY,
  sender_id BIGINT NULL REFERENCES users(id),
  sender_address VARCHAR(254) NOT NULL,
  receiver_id BIGINT NULL REFERENCES users(id),
  receiver_address VARCHAR(254) NOT NULL,
  subject VARCHAR(998) NOT NULL,
  body TEXT NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_emails_sender_time ON emails(sender_id, created_at);
CREATE INDEX IF NOT EXISTS ix_emails_receiver_time ON emails(receiver_id, created_at);

CREATE TABLE IF NOT EXISTS mailbox_entries (
  owner_id BIGINT NOT NULL REFERENCES users(id),
  folder VARCHAR(5) NOT NULL CHECK (folder IN ('INBOX','SENT','TRASH')),
  filename VARCHAR(100) NOT NULL,
  email_id VARCHAR(36) NOT NULL REFERENCES emails(id),
  content TEXT NOT NULL,
  materialized BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (owner_id, folder, filename)
);

CREATE INDEX IF NOT EXISTS ix_entries_pending ON mailbox_entries(materialized);
CREATE INDEX IF NOT EXISTS ix_entries_email ON mailbox_entries(email_id);
