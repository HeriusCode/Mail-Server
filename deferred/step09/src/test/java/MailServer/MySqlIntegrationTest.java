package MailServer;

import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Runs only against a user-provisioned disposable database whose name ends in _test. */
@EnabledIfEnvironmentVariable(named="MAIL_TEST_DB_URL", matches=".+")
class MySqlIntegrationTest {
    private static DatabaseConfig config;
    private static JdbcMailRepository repository;
    private Path parent, root;
    private MailStorageService storage;
    private String a, b;
    @BeforeAll static void setupSchema() throws Exception {
        String url = System.getenv("MAIL_TEST_DB_URL");
        assertTrue(url.matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]+_test(?:\\?.*)?"), "Require isolated *_test database");
        config = new DatabaseConfig(url, System.getenv("MAIL_TEST_DB_USER"), System.getenv("MAIL_TEST_DB_PASSWORD"));
        repository = new JdbcMailRepository(config);
        String sql = Files.readString(Path.of("database/schema.sql")).replaceAll("(?m)^--.*$", "");
        try (Connection c = config.connect()) {
            for (String statement : sql.split(";")) {
                String value = statement.trim();
                if (value.isEmpty() || value.startsWith("CREATE DATABASE") || value.startsWith("USE ")) continue;
                try (PreparedStatement s = c.prepareStatement(value)) { s.execute(); }
            }
        }
    }
    @BeforeEach void setup() throws Exception {
        parent = Files.createTempDirectory(Path.of("target"), "mysql-it-"); root = parent.resolve("storage");
        storage = new MailStorageService(root, repository);
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        a = "db.a." + suffix + "@gmail.com"; b = "db.b." + suffix + "@gmail.com";
    }
    @Test void connectionAndUtf8mb4() throws Exception {
        repository.check();
        try (Connection c = config.connect(); PreparedStatement s = c.prepareStatement("SELECT @@character_set_connection"); ResultSet r = s.executeQuery()) {
            assertTrue(r.next()); assertEquals("utf8mb4", r.getString(1));
        }
        System.out.println("PASS real MySQL connection/schema and utf8mb4");
    }
    @Test void userRoundTrip() throws Exception {
        assertTrue(storage.register(a, "synthetic-db-password"));
        assertFalse(storage.register(a, "synthetic-db-password"));
        assertTrue(storage.authenticate(a, "synthetic-db-password"));
        assertFalse(storage.authenticate(a, "wrong-password"));
        assertFalse(Files.exists(root.resolve(a).resolve(".credentials")));
        assertEquals(MailStorageService.WELCOME_MESSAGE, Files.readString(root.resolve(a).resolve("new_email.txt")));
        System.out.println("PASS MySQL users register/login/duplicate and exact welcome; hash only in DB");
    }
    private String message() { return "Người gửi: " + a + "\nNgười nhận: " + b + "\nTiêu đề: MySQL UTF-8\n\nDữ liệu 😀"; }
    @Test void mailRoundTripAndRestartIdempotency() throws Exception {
        assertTrue(storage.register(a, "synthetic-db-password")); assertTrue(storage.register(b, "synthetic-db-password"));
        String id = UUID.randomUUID().toString();
        assertTrue(storage.saveMail(a, b, message(), id));
        assertTrue(storage.saveMail(a, b, message(), id));
        assertEquals(2, repository.entries(b, false).size());
        MailStorageService restart = new MailStorageService(root, repository);
        assertTrue(restart.authenticate(a, "synthetic-db-password"));
        String filename = repository.entries(b, false).stream().filter(e -> e.filename().startsWith("mail_")).findFirst().orElseThrow().filename();
        assertTrue(restart.readMail(b, filename).contains("Dữ liệu 😀"));
        assertTrue(restart.saveMail(a, b, message(), id));
        assertEquals(2, repository.entries(b, false).size());
        System.out.println("PASS MySQL email/file round trip, storage restart and durable idempotency");
    }
    @Test void outboxRecoveryAndConflict() throws Exception {
        assertTrue(storage.register(a, "synthetic-db-password")); assertTrue(storage.register(b, "synthetic-db-password"));
        String id = UUID.randomUUID().toString();
        assertTrue(repository.saveMail(id, a, b, "pending", "body", "inbox body", "sent body"));
        Path conflict = root.resolve(b).resolve("mail_" + id + ".txt");
        Files.writeString(conflict, "existing conflict");
        assertThrows(java.io.IOException.class, storage::recoverPending);
        assertEquals("existing conflict", Files.readString(conflict));
        Files.move(conflict, conflict.resolveSibling("conflict-preserved.txt"));
        new MailStorageService(root, repository).recoverPending();
        assertEquals("inbox body", Files.readString(conflict));
        assertTrue(repository.pending().isEmpty());
        System.out.println("PASS outbox conflict preserves file and recovery completes after conflict isolated");
    }
    @Test void migrationRerunIsIdempotent() throws Exception {
        Path legacyRoot = parent.resolve("legacy");
        MailStorageService legacy = new MailStorageService(legacyRoot);
        assertTrue(legacy.register(a, "synthetic-db-password")); assertTrue(legacy.register(b, "synthetic-db-password"));
        assertTrue(legacy.saveMail(a, b, message()));
        MigrationService migration = new MigrationService(legacyRoot);
        MigrationService.Result first = migration.migrate(repository, parent.resolve("backups"));
        assertEquals(2, first.usersInserted()); assertEquals(4, first.entriesInserted());
        MigrationService.Result second = migration.migrate(repository, parent.resolve("backups"));
        assertEquals(0, second.usersInserted()); assertEquals(0, second.entriesInserted());
        assertTrue(new MailStorageService(legacyRoot, repository).authenticate(a, "synthetic-db-password"));
        System.out.println("PASS real DB migration rerun inserts zero duplicates and preserves login");
    }
    @Test void transactionRollbackOnDuplicateWelcomeId() throws Exception {
        // Insert an existing logical mail ID to force the welcome transaction to roll back.
        String id = UUID.nameUUIDFromBytes(("welcome:" + a).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        assertTrue(storage.register(b, "synthetic-db-password"));
        repository.importEntry(new JdbcMailRepository.Entry(b, false, "mail_" + id + ".txt", "fixture", Instant.now()), id, b, b, "fixture", "fixture");
        assertFalse(repository.register(a, PasswordHasher.hash("synthetic-db-password"), "welcome"));
        assertNull(repository.passwordHash(a));
        System.out.println("PASS failed registration transaction leaves no orphan user");
    }
}
