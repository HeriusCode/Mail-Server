package MailServer;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileRepositoryTest {
    @Test void repositoryInterfacesCanReplaceFileImplementations() {
        Map<String, String> hashes = new HashMap<>(); Map<String, String> messages = new HashMap<>();
        UserRepository users = new UserRepository() {
            public boolean createAccount(String email, String hash, String welcome) { assertEquals(MailStorageService.WELCOME_MESSAGE, welcome); return hashes.putIfAbsent(email, hash) == null; }
            public String findPasswordHash(String email) { return hashes.get(email); }
            public boolean exists(String email) { return hashes.containsKey(email); }
        };
        EmailRepository emails = new EmailRepository() {
            public List<MailSummary> list(String owner, boolean sent) { return List.of(new MailSummary("new_email.txt", "test", 1)); }
            public String read(String owner, String file, boolean sent) { return messages.get(owner); }
            public boolean save(String sender, String recipient, String content, String id) { messages.put(recipient, content); return true; }
            public void saveSent(String owner, String content, String id) { messages.put(owner, content); }
            public void archiveInbox(String owner, List<String> files) { messages.remove(owner); }
        };
        MailStorageService service = new MailStorageService(users, emails);
        assertTrue(service.register("replace@local.test", "synthetic-password")); assertTrue(service.authenticate("replace@local.test", "synthetic-password"));
        assertTrue(service.saveMail("replace@local.test", "recipient@local.test", "body")); assertEquals("body", service.readMail("recipient@local.test", "new_email.txt"));
        assertEquals(1, service.getMailSummaries("replace@local.test").size());
        System.out.println("PASS business service works through injected repository interfaces without filesystem or SQL dependencies");
    }
    @Test void existingBytesPreservedAndPartialDeliveryRecovered() throws Exception {
        Path root = Files.createTempDirectory(Path.of("target"), "file-contract-");
        MailStorageService service = new MailStorageService(root);
        assertTrue(service.register("a@local.test", "synthetic-password")); assertTrue(service.register("b@local.test", "synthetic-password"));
        Path welcome = root.resolve("a@local.test/new_email.txt"); Files.writeString(welcome, "legacy welcome");
        assertFalse(service.register("a@local.test", "replacement-password")); assertEquals("legacy welcome", Files.readString(welcome));
        String id = UUID.randomUUID().toString(); String message = MimeSupport.canonical("a@local.test", "b@local.test", "Recover", "body");
        assertTrue(service.saveMail("a@local.test", "b@local.test", message, id));
        MailSummary sent = service.getSentSummaries("a@local.test").get(0);
        Path sentPath = root.resolve("a@local.test/.sent").resolve(sent.getFileName()); Files.move(sentPath, root.resolve("preserved-sent-copy.txt"));
        assertTrue(new MailStorageService(root).saveMail("a@local.test", "b@local.test", message, id)); assertTrue(Files.isRegularFile(sentPath));
        assertEquals(2, service.getMailSummaries("b@local.test").size()); assertEquals("legacy welcome", Files.readString(welcome));
        System.out.println("PASS legacy welcome is never overwritten; same request restores missing Sent copy without duplicating Inbox");
    }
    @Test void runtimeStartsAllProtocolsWithFileBackend() throws Exception {
        assertNotNull(Class.forName("com.mysql.cj.jdbc.Driver"));
        Path root = Files.createTempDirectory(Path.of("target"), "runtime-");
        try (MailServerRuntime runtime = new MailServerRuntime("127.0.0.1", 0, 0, 0, new MailStorageService(root), null)) {
            Thread thread = new Thread(() -> { try { runtime.run(); } catch (Exception e) { throw new RuntimeException(e); } }); thread.start();
            try (MailClient.UdpMailClient client = new MailClient.UdpMailClient("127.0.0.1", runtime.getUdpPort())) {
                assertTrue(client.ping());
                try (java.net.Socket smtp = new java.net.Socket("127.0.0.1", runtime.getSmtpPort()); java.net.Socket pop = new java.net.Socket("127.0.0.1", runtime.getPop3Port())) {
                    assertEquals('2', smtp.getInputStream().read()); assertEquals('+', pop.getInputStream().read());
                }
            } finally { runtime.close(); thread.join(3000); assertFalse(thread.isAlive()); }
        }
        System.out.println("PASS FILE backend starts/stops UDP + SMTP + POP3 despite JDBC driver availability; no database connection required");
    }
}
