package MailServer;

import java.net.ServerSocket;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MigrationAndConfigurationTest {
    private Path temp(String prefix) throws Exception { return Files.createTempDirectory(Path.of("target"), prefix); }
    @Test void inspectBackupAndPreserveLegacy() throws Exception {
        Path parent = temp("migration-"), root = parent.resolve("source");
        MailStorageService storage = new MailStorageService(root);
        assertTrue(storage.register("legacy@gmail.com", "synthetic-password"));
        Files.createDirectories(root.resolve("unknown@gmail.com"));
        Files.writeString(root.resolve("unknown@gmail.com").resolve("new_email.txt"), "do not claim this account");
        Files.createDirectories(root.resolve("invalid@gmail.com"));
        Files.writeString(root.resolve("invalid@gmail.com").resolve(".credentials"), "not-a-valid-hash");
        MigrationService plan = new MigrationService(root);
        assertEquals(1, plan.accountCount()); assertEquals(1, plan.entryCount()); assertEquals(2, plan.skippedCount());
        System.out.println("PASS migration inspection skips missing/unsupported credentials");
        String hash = Files.readString(root.resolve("legacy@gmail.com").resolve(".credentials"));
        Path backup = plan.backup(parent.resolve("backups"));
        assertEquals(hash, Files.readString(backup.resolve("legacy@gmail.com").resolve(".credentials")));
        assertTrue(Files.isRegularFile(backup.resolve("backup-manifest.sha256")));
        assertTrue(Files.readString(backup.resolve("backup-manifest.sha256")).matches("(?s).*[a-f0-9]{64}  .*"));
        assertEquals("do not claim this account", Files.readString(root.resolve("unknown@gmail.com").resolve("new_email.txt")));
        System.out.println("PASS complete backup verified, manifest produced, original data preserved");
        Path second = plan.backup(parent.resolve("backups"));
        assertNotEquals(backup, second);
        System.out.println("PASS backup rerun creates separate backup without overwriting");
        assertThrows(java.io.IOException.class, () -> plan.backup(root.resolve("bad-backup")));
        System.out.println("PASS backup inside source rejected");
    }
    @Test void missingDatabaseFailsClosed() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
        DatabaseConfig config = new DatabaseConfig("jdbc:mysql://127.0.0.1:" + closedPort + "/mail_server_db", "synthetic-user", "synthetic-password");
        Path root = temp("unavailable-db-");
        assertThrows(IllegalStateException.class, () -> new MailStorageService(root, new JdbcMailRepository(config)));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
        System.out.println("PASS actual refused JDBC connection fails startup; no fallback data written");
        assertThrows(IllegalArgumentException.class, () -> new DatabaseConfig("jdbc:mysql://localhost/mail_server_db?password=forbidden", "user", "secret"));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseConfig("jdbc:mysql://localhost/mail_server_db", null, null));
        System.out.println("PASS incomplete configuration and URL credentials rejected");
    }
    @Test void linkedRootIsRefused() throws Exception {
        Path parent = temp("link-defense-");
        Path destination = Files.createDirectory(parent.resolve("destination")), link = parent.resolve("junction");
        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toAbsolutePath().toString(), destination.toAbsolutePath().toString()).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            org.junit.jupiter.api.Assumptions.assumeTrue(process.waitFor() == 0, "Junction creation unavailable");
        } else Files.createSymbolicLink(link, destination.toAbsolutePath());
        assertThrows(IllegalStateException.class, () -> new MailStorageService(link));
        assertThrows(java.io.IOException.class, () -> new MigrationService(link));
        System.out.println("PASS storage and migration reject filesystem junction/symlink");
    }
}
