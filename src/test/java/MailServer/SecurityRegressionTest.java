package MailServer;

import MailClient.UdpMailClient;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SecurityRegressionTest {
    private Path root;
    private static final String A = "security.a@gmail.com", B = "security.b@gmail.com", PASSWORD = "synthetic-test-only";
    private MailStorageService storage;
    private UdpMailServer server;
    private Thread thread;
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private static String encode(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static void pass(String name, boolean value) { assertTrue(value, name); System.out.println("PASS " + name); }
    private String content(String from, String to) { return "Người gửi: " + from + "\nNgười nhận: " + to + "\nTiêu đề: UTF-8 test\n\nThư kiểm thử"; }
    @BeforeEach void start() throws Exception {
        root = Files.createTempDirectory(Path.of("target"), "security-");
        storage = new MailStorageService(root);
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        server = new UdpMailServer("127.0.0.1", 0, storage, new UdpMailServer.Listener() {
            public void onClientSeen(InetSocketAddress address) { }
            public void onRequest(InetSocketAddress address) { }
            public void onLog(String message) { logs.add(message); }
        }, new SessionManager(clock, Duration.ofSeconds(30)));
        thread = new Thread(() -> { try { server.run(); } catch (Exception e) { throw new RuntimeException(e); } });
        thread.start();
    }
    @AfterEach void stop() throws Exception { if (server != null) { server.close(); thread.join(3000); pass("server stopped", !thread.isAlive()); } }
    private UdpMailClient client() throws Exception { return new UdpMailClient("127.0.0.1", server.getPort()); }
    private void accounts() {
        assertTrue(storage.register(A, PASSWORD)); assertTrue(storage.register(B, PASSWORD));
    }
    private String raw(String command) throws Exception { return raw(command, UUID.randomUUID().toString()); }
    private String raw(String command, String id) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(3000);
            byte[] bytes = (id + "|" + command).getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), server.getPort()));
            DatagramPacket response = new DatagramPacket(new byte[60_001], 60_001);
            socket.receive(response);
            String text = new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8);
            assertTrue(text.startsWith(id + "|"), "matching response ID");
            return text.substring(id.length() + 1);
        }
    }
    private String token(String owner) throws Exception {
        String response = raw("LOGIN " + owner + " " + encode(PASSWORD));
        assertTrue(response.startsWith("LOGIN_SUCCESS|"), "login succeeds");
        return response.split("\\|", 3)[1];
    }
    @Test void registrationAndFiles() throws Exception {
        try (UdpMailClient c = client()) {
            pass("valid registration", c.sendRequest("REGISTER " + A + " " + encode(PASSWORD)).equals("REGISTER_SUCCESS"));
            pass("duplicate rejected", c.sendRequest("REGISTER " + A + " " + encode(PASSWORD)).startsWith("REGISTER_FAILED"));
            pass("invalid username rejected", c.sendRequest("REGISTER ../bad " + encode(PASSWORD)).startsWith("REGISTER_FAILED"));
            pass("short password rejected", c.sendRequest("REGISTER " + B + " " + encode("short")).startsWith("REGISTER_FAILED"));
            pass("bcrypt over 72 UTF8 bytes rejected", c.sendRequest("REGISTER " + B + " " + encode("ệ".repeat(25))).startsWith("REGISTER_FAILED"));
            pass("welcome exact", Files.readString(root.resolve(A).resolve("new_email.txt")).equals(MailStorageService.WELCOME_MESSAGE));
            pass("folder confined", Files.isDirectory(root.resolve(A).resolve(".sent")));
            String hash = Files.readString(root.resolve(A).resolve(".credentials"));
            pass("bcrypt cost 12 and no plaintext", hash.matches("\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}") && !hash.contains(PASSWORD));
            String before = Files.readString(root.resolve(A).resolve("new_email.txt"));
            storage.register(A, PASSWORD);
            pass("duplicate never overwrites welcome", before.equals(Files.readString(root.resolve(A).resolve("new_email.txt"))));
        }
    }
    @Test void twoClientsAndRefresh() throws Exception {
        accounts();
        try (UdpMailClient a = client(); UdpMailClient b = client()) {
            pass("wrong login", a.sendRequest("LOGIN " + A + " " + encode("wrong-password")).startsWith("LOGIN_FAILED"));
            pass("correct login old caller shape", a.sendRequest("LOGIN " + A + " " + encode(PASSWORD)).startsWith("LOGIN_SUCCESS|new_email.txt;"));
            b.sendRequest("LOGIN " + B + " " + encode(PASSWORD));
            pass("internal SEND", a.sendRequest("SEND " + B + " " + encode(content(A, B))).equals("SEND_SUCCESS"));
            String listing = b.sendRequest("INBOX " + B);
            pass("refresh without password", listing.contains("mail_") && b.isAuthenticated());
            String filename;
            try (var files = Files.list(root.resolve(B))) { filename = files.filter(p -> p.getFileName().toString().startsWith("mail_")).findFirst().orElseThrow().getFileName().toString(); }
            pass("owner reads UTF8", b.sendRequest("READ " + B + " " + filename).equals("MAIL_CONTENT|" + encode(Files.readString(root.resolve(B).resolve(filename)))));
            pass("own Sent", a.sendRequest("SENT " + A).contains("sent_"));
            pass("other Sent forbidden", a.sendRequest("SENT " + B).equals("FORBIDDEN"));
            pass("other Inbox forbidden", a.sendRequest("INBOX " + B).equals("FORBIDDEN"));
            pass("other mail forbidden", a.sendRequest("READ " + B + " " + filename).equals("FORBIDDEN"));
            pass("own credentials blocked", a.sendRequest("READ " + A + " .credentials").startsWith("READ_FAILED"));
            pass("path traversal blocked", a.sendRequest("READ " + A + " ../" + B + "/new_email.txt").startsWith("READ_FAILED"));
            pass("authenticated sender spoof blocked", a.sendRequest("SEND " + B + " " + encode(content(B, B))).equals("FORBIDDEN"));
        }
    }
    @Test void anonymousAndForgedTokens() throws Exception {
        accounts();
        pass("anonymous READ denied", raw("READ " + B + " new_email.txt").equals("AUTH_REQUIRED"));
        pass("anonymous hash denied", raw("READ " + B + " .credentials").equals("AUTH_REQUIRED"));
        pass("anonymous SENT denied", raw("SENT " + A).equals("AUTH_REQUIRED"));
        pass("anonymous SEND denied", raw("SEND " + B + " " + encode(content(A, B))).equals("AUTH_REQUIRED"));
        pass("forged token denied", raw("AUTH " + "A".repeat(43) + " READ " + B + " new_email.txt").equals("AUTH_REQUIRED"));
        String token = token(A);
        pass("token not bound to IP port alone", raw("AUTH " + token + " INBOX " + A).startsWith("LOGIN_SUCCESS|"));
        pass("token random format", token.matches("[A-Za-z0-9_-]{43}") && !token.equals(token(A)));
        pass("logs contain no token or password", logs.stream().noneMatch(s -> s.contains(token) || s.contains(PASSWORD) || s.contains(encode(PASSWORD))));
    }
    @Test void expiryLogoutAndReplay() throws Exception {
        accounts();
        String token = token(A), id = UUID.randomUUID().toString();
        pass("read before logout", raw("AUTH " + token + " READ " + A + " new_email.txt", id).startsWith("MAIL_CONTENT|"));
        pass("logout acknowledged", raw("AUTH " + token + " LOGOUT").equals("LOGOUT_SUCCESS"));
        pass("revoked token replay denied", raw("AUTH " + token + " READ " + A + " new_email.txt", id).equals("AUTH_REQUIRED"));
        String expiring = token(A);
        now.addAndGet(30_001);
        pass("expired token denied", raw("AUTH " + expiring + " SENT " + A).equals("AUTH_REQUIRED"));
        try (UdpMailClient c = client()) {
            c.sendRequest("LOGIN " + A + " " + encode(PASSWORD)); c.logout();
            pass("updated client logout clears session", !c.isAuthenticated() && c.sendRequest("SENT " + A).equals("AUTH_REQUIRED"));
        }
    }
    @Test void validationAndConcurrentDuplicate() throws Exception {
        try (UdpMailClient a = client(); UdpMailClient b = client()) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<String> x = pool.submit(() -> a.sendRequest("REGISTER " + A + " " + encode(PASSWORD)));
                Future<String> y = pool.submit(() -> b.sendRequest("REGISTER " + A + " " + encode(PASSWORD)));
                List<String> replies = List.of(x.get(), y.get());
                pass("concurrent duplicate exactly one success", Collections.frequency(replies, "REGISTER_SUCCESS") == 1);
            } finally { pool.shutdownNow(); }
            pass("malformed base64 rejected", raw("LOGIN " + A + " %%%").startsWith("LOGIN_FAILED"));
            pass("request newline rejected", raw("PING\nREAD " + A + " .credentials").equals("INVALID_COMMAND"));
            pass("extra arguments rejected", raw("LOGIN " + A + " " + encode(PASSWORD) + " extra").equals("INVALID_COMMAND"));
            assertThrows(java.io.IOException.class, () -> a.sendRequest("X".repeat(60_001)));
            System.out.println("PASS oversized client request rejected");
        }
    }
    @Test void idempotentSendAndRestart() throws Exception {
        accounts();
        String token = token(A), id = UUID.randomUUID().toString();
        String command = "AUTH " + token + " SEND " + B + " " + encode(content(A, B));
        pass("first request stored", raw(command, id).equals("SEND_SUCCESS"));
        pass("same request retry stored once", raw(command, id).equals("SEND_SUCCESS"));
        try (var files = Files.list(root.resolve(B))) { pass("one delivered file", files.filter(p -> p.getFileName().toString().startsWith("mail_")).count() == 1); }
        pass("request ID conflict denied", raw("AUTH " + token + " SEND " + B + " " + encode(content(A, B) + "changed"), id).startsWith("SEND_FAILED"));
        MailStorageService restarted = new MailStorageService(root);
        pass("credentials survive storage restart", restarted.authenticate(A, PASSWORD));
        pass("mail survives storage restart", restarted.getMailSummaries(B).size() == 2);
        int stoppedPort = server.getPort();
        server.close(); thread.join(3000);
        assertThrows(java.io.IOException.class, () -> { try (UdpMailClient c = new UdpMailClient("127.0.0.1", stoppedPort)) { c.ping(); } });
        System.out.println("PASS disconnected server reported as IOException");
    }
    @Test void legacyPbkdAndNoAccountTakeover() throws Exception {
        Path legacy = root.resolve(A); Files.createDirectory(legacy); Files.createDirectory(legacy.resolve(".sent"));
        byte[] salt = new byte[16]; new java.security.SecureRandom().nextBytes(salt);
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(PASSWORD.toCharArray(), salt, 120_000, 256);
        byte[] hash = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); spec.clearPassword();
        Files.writeString(legacy.resolve(".credentials"), "120000:" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash));
        Files.writeString(legacy.resolve("new_email.txt"), "Existing welcome must be preserved");
        pass("legacy PBKDF2 login supported", storage.authenticate(A, PASSWORD));
        pass("legacy bad password rejected", !storage.authenticate(A, "wrong-password"));
        Files.createDirectory(root.resolve(B));
        pass("unclaimed folder not silently adopted", !storage.register(B, PASSWORD));
        pass("unclaimed folder not treated as registered recipient", !storage.saveMail(A, B, content(A, B)));
        pass("existing legacy welcome untouched", Files.readString(legacy.resolve("new_email.txt")).equals("Existing welcome must be preserved"));
        pass("invalid legacy hash rejected", !PasswordHasher.verify(PASSWORD, "999999999:invalid:invalid"));
    }
    @Test void filesystemFailuresAndLinkDefense() throws Exception {
        accounts();
        Files.writeString(root.resolve(A).resolve("mail_00000000-0000-0000-0000-000000000000.txt"), "normal");
        pass("unknown txt hidden", storage.readMail(A, "private.txt") == null);
        Files.delete(root.resolve(A).resolve(".sent")); Files.writeString(root.resolve(A).resolve(".sent"), "blocked");
        try (UdpMailClient c = client()) {
            c.sendRequest("LOGIN " + A + " " + encode(PASSWORD));
            pass("unsafe sent directory fails closed", c.sendRequest("SEND " + B + " " + encode(content(A, B))).startsWith("SEND_FAILED"));
        }
        Path blocker = root.resolve("blocked@gmail.com"); Files.writeString(blocker, "do not delete");
        assertThrows(MailStorageService.StorageUnavailableException.class, () -> storage.register("blocked@gmail.com", PASSWORD));
        pass("filesystem error preserves existing file", Files.readString(blocker).equals("do not delete"));
    }
}
