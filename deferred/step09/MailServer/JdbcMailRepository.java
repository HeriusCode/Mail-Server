package MailServer;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** JDBC only. No SQL errors, passwords or connection URLs escape to the wire/log. */
public final class JdbcMailRepository {
    public record Entry(String owner, boolean sent, String filename, String content, Instant createdAt) { }
    private final DatabaseConfig config;
    public JdbcMailRepository(DatabaseConfig config) { this.config = config; }
    public Connection connect() throws SQLException { return config.connect(); }
    public void check() throws SQLException {
        try (Connection c = connect()) {
            for (String query : new String[]{"SELECT id FROM users LIMIT 0", "SELECT id FROM emails LIMIT 0", "SELECT owner_id FROM mailbox_entries LIMIT 0"}) {
                try (PreparedStatement s = c.prepareStatement(query)) { s.executeQuery().close(); }
            }
        }
    }
    private Long userId(Connection c, String email) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT id FROM users WHERE email=?")) {
            s.setString(1, email);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getLong(1) : null; }
        }
    }
    public String passwordHash(String email) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("SELECT password_hash FROM users WHERE email=?")) {
            s.setString(1, email);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }
    public boolean register(String email, String hash, String welcome) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                insertUser(c, email, hash);
                String id = java.util.UUID.nameUUIDFromBytes(("welcome:" + email).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                insertMail(c, id, "MailFlow System", email, "Welcome", MailStorageService.WELCOME_MESSAGE, "PENDING_FILES", Instant.now());
                insertEntry(c, email, false, "new_email.txt", id, welcome, false);
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                if (e.getErrorCode() == 1062) return false;
                throw e;
            }
        }
    }
    private void insertUser(Connection c, String email, String hash) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO users(username,email,password_hash) VALUES(?,?,?)")) {
            s.setString(1, email); s.setString(2, email); s.setString(3, hash); s.executeUpdate();
        }
    }
    private void insertMail(Connection c, String id, String sender, String receiver, String subject,
            String body, String status, Instant time) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO emails(id,sender_id,sender_address,receiver_id,receiver_address,subject,body,status,created_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            s.setString(1, id);
            Long senderId = userId(c, sender), receiverId = userId(c, receiver);
            if (senderId == null) s.setNull(2, Types.BIGINT); else s.setLong(2, senderId);
            s.setString(3, sender);
            if (receiverId == null) s.setNull(4, Types.BIGINT); else s.setLong(4, receiverId);
            s.setString(5, receiver); s.setString(6, subject); s.setString(7, body);
            s.setString(8, status); s.setTimestamp(9, Timestamp.from(time)); s.executeUpdate();
        }
    }
    private void insertEntry(Connection c, String owner, boolean sent, String filename,
            String id, String content, boolean materialized) throws SQLException {
        Long ownerId = userId(c, owner);
        if (ownerId == null) throw new SQLException("Unknown owner");
        try (PreparedStatement s = c.prepareStatement("INSERT INTO mailbox_entries(owner_id,folder,filename,email_id,content,materialized) VALUES(?,?,?,?,?,?)")) {
            s.setLong(1, ownerId); s.setString(2, sent ? "SENT" : "INBOX"); s.setString(3, filename);
            s.setString(4, id); s.setString(5, content); s.setBoolean(6, materialized); s.executeUpdate();
        }
    }
    public boolean saveMail(String id, String sender, String receiver, String subject, String body,
            String inboxContent, String sentContent) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                if (userId(c, sender) == null || userId(c, receiver) == null) { c.rollback(); return false; }
                try (PreparedStatement s = c.prepareStatement("SELECT sender_address,receiver_address,subject,body FROM emails WHERE id=?")) {
                    s.setString(1, id);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) {
                            boolean same = sender.equals(r.getString(1)) && receiver.equals(r.getString(2))
                                    && subject.equals(r.getString(3)) && body.equals(r.getString(4));
                            c.rollback(); return same;
                        }
                    }
                }
                insertMail(c, id, sender, receiver, subject, body, "PENDING_FILES", Instant.now());
                insertEntry(c, receiver, false, "mail_" + id + ".txt", id, inboxContent, false);
                insertEntry(c, sender, true, "sent_" + id + ".txt", id, sentContent, false);
                c.commit(); return true;
            } catch (SQLException e) { c.rollback(); throw e; }
        }
    }
    public List<Entry> entries(String owner, boolean sent) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "SELECT m.filename,m.content,e.created_at FROM mailbox_entries m JOIN users u ON u.id=m.owner_id JOIN emails e ON e.id=m.email_id WHERE u.email=? AND m.folder=? AND m.materialized=TRUE ORDER BY e.created_at DESC")) {
            s.setString(1, owner); s.setString(2, sent ? "SENT" : "INBOX");
            List<Entry> entries = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) entries.add(new Entry(owner, sent, r.getString(1), r.getString(2), r.getTimestamp(3).toInstant()));
            }
            return entries;
        }
    }
    public List<Entry> pending() throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
                "SELECT u.email,m.folder,m.filename,m.content,e.created_at FROM mailbox_entries m JOIN users u ON u.id=m.owner_id JOIN emails e ON e.id=m.email_id WHERE m.materialized=FALSE")) {
            List<Entry> result = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) result.add(new Entry(r.getString(1), r.getString(2).equals("SENT"), r.getString(3), r.getString(4), r.getTimestamp(5).toInstant()));
            }
            return result;
        }
    }
    public void markMaterialized(Entry entry) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("UPDATE mailbox_entries SET materialized=TRUE WHERE owner_id=(SELECT id FROM users WHERE email=?) AND folder=? AND filename=?")) {
                s.setString(1, entry.owner()); s.setString(2, entry.sent() ? "SENT" : "INBOX");
                s.setString(3, entry.filename()); s.executeUpdate();
                try (PreparedStatement status = c.prepareStatement("UPDATE emails e SET status='STORED' WHERE e.status='PENDING_FILES' AND NOT EXISTS(SELECT 1 FROM mailbox_entries m WHERE m.email_id=e.id AND m.materialized=FALSE)")) {
                    status.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
        }
    }
    /** Import only an existing, validated hash; conflicting credentials require manual resolution. */
    public boolean importUser(String email, String hash) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("SELECT password_hash FROM users WHERE email=? FOR UPDATE")) {
                s.setString(1, email);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) {
                        if (!hash.equals(r.getString(1))) throw new SQLException("Migration credential conflict");
                        c.rollback(); return false;
                    }
                }
                insertUser(c, email, hash); c.commit(); return true;
            } catch (SQLException e) { c.rollback(); throw e; }
        }
    }
    public boolean importEntry(Entry entry, String id, String sender, String receiver, String subject, String body) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s = c.prepareStatement("SELECT content FROM mailbox_entries WHERE owner_id=(SELECT id FROM users WHERE email=?) AND folder=? AND filename=? FOR UPDATE")) {
                    s.setString(1, entry.owner()); s.setString(2, entry.sent() ? "SENT" : "INBOX"); s.setString(3, entry.filename());
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) {
                            if (!entry.content().equals(r.getString(1))) throw new SQLException("Migration file conflict");
                            c.rollback(); return false;
                        }
                    }
                }
                boolean exists;
                try (PreparedStatement s = c.prepareStatement("SELECT sender_address,receiver_address,subject,body FROM emails WHERE id=?")) {
                    s.setString(1, id);
                    try (ResultSet r = s.executeQuery()) {
                        exists = r.next();
                        if (exists && (!sender.equals(r.getString(1)) || !receiver.equals(r.getString(2))
                                || !subject.equals(r.getString(3)) || !body.equals(r.getString(4))))
                            throw new SQLException("Migration message conflict");
                    }
                }
                if (!exists) insertMail(c, id, sender, receiver, subject, body, "MIGRATED", entry.createdAt());
                insertEntry(c, entry.owner(), entry.sent(), entry.filename(), id, entry.content(), true);
                c.commit(); return true;
            } catch (SQLException e) { c.rollback(); throw e; }
        }
    }
}
