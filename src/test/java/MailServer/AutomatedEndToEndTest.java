package MailServer;

import MailClient.UdpMailClient;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class AutomatedEndToEndTest {
    static final String A = "usera@local.test", B = "userb@local.test";
    static final String PASSWORD = "synthetic-e2e-" + UUID.randomUUID();
    static final String SUBJECT = "MailFlow Automated E2E Test";
    static final String BODY = "Kiem thu gui nhan email noi bo tu dong.";
    static String encode(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    static String content(String from, String to, String subject, String body) { return MimeSupport.canonical(from, to, subject, body); }
    static String raw(int port, String request) throws Exception { return raw(port, request, UUID.randomUUID().toString()); }
    static String raw(int port, String request, String id) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("127.0.0.1"), port); socket.setSoTimeout(5000);
            byte[] bytes = (id + "|" + request).getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(bytes, bytes.length));
            DatagramPacket reply = new DatagramPacket(new byte[60001], 60001); socket.receive(reply);
            String response = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8);
            assertTrue(response.startsWith(id + "|"), "matching UDP request ID");
            return response.substring(id.length() + 1);
        }
    }
    static String token(int port, String owner) throws Exception {
        String response = raw(port, "LOGIN " + owner + " " + encode(PASSWORD));
        assertTrue(response.startsWith("LOGIN_SUCCESS|"));
        String token = response.split("\\|", 3)[1]; assertTrue(token.matches("[A-Za-z0-9_-]{43}")); return token;
    }
    static String filename(Path storage, String owner, boolean sent, String subject) throws Exception {
        Path dir = sent ? storage.resolve(owner).resolve(".sent") : storage.resolve(owner);
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches((sent ? "sent_" : "mail_") + ".*\\.txt")).toList())
                if (Files.readString(file).contains("Tiêu đề: " + subject + "\n")) return file.getFileName().toString();
        }
        throw new AssertionError("Expected mail file not found");
    }
    static String read(UdpMailClient client, String owner, String file, boolean sent) throws Exception {
        String response = client.sendRequest((sent ? "READ_SENT " : "READ ") + owner + " " + file);
        assertTrue(response.startsWith("MAIL_CONTENT|"));
        return new String(Base64.getDecoder().decode(response.substring(13)), StandardCharsets.UTF_8);
    }
    static void verifyMessage(String value, String from, String to, String subject, String body, boolean sent) {
        assertEquals(from, MimeSupport.header(value, "Người gửi:"));
        assertEquals(to, MimeSupport.header(value, "Người nhận:"));
        assertEquals(subject, MimeSupport.header(value, "Tiêu đề:"));
        assertEquals(body, MimeSupport.body(value));
        LocalDateTime.parse(MimeSupport.header(value, sent ? "Thời gian gửi:" : "Thời gian nhận:"),
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
    }

    @Test @Timeout(150) void isolatedChildServerTwoWayDeliveryProtocolsAndRestart() throws Exception {
        Path root = Files.createTempDirectory(Path.of("target"), "automated-e2e-");
        Path storage = root.resolve("storage"); Map<String, byte[]> durable = new HashMap<>();
        String aInbox, bInbox, aSent, bSent;
        try (E2eTestServer server = new E2eTestServer(root)) {
            assertEquals("PONG", raw(server.udp, "PING"));
            assertEquals("INVALID_COMMAND", raw(server.udp, "PING\nINVALID"));
            assertEquals("AUTH_REQUIRED", raw(server.udp, "READ " + A + " new_email.txt"));
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Boolean>> tasks = new ArrayList<>();
                for (int i = 0; i < 8; i++) tasks.add(() -> {
                    assertEquals("PONG", raw(server.udp, "PING"));
                    try (Wire smtp = new Wire(server.smtp); Wire pop = new Wire(server.pop)) {
                        assertTrue(smtp.line().startsWith("220")); assertTrue(pop.line().startsWith("+OK"));
                        assertTrue(smtp.command("QUIT").startsWith("221")); assertTrue(pop.command("QUIT").startsWith("+OK"));
                    }
                    return true;
                });
                for (Future<Boolean> result : pool.invokeAll(tasks)) assertTrue(result.get());
            } finally { pool.shutdownNow(); }
            System.out.println("PASS E2E startup, UDP/SMTP/POP3, 8 concurrent clients, malformed/anonymous rejection");
            try (UdpMailClient a = new UdpMailClient("127.0.0.1", server.udp);
                 UdpMailClient b = new UdpMailClient("127.0.0.1", server.udp)) {
                for (String owner : List.of(A, B)) {
                    assertEquals("REGISTER_SUCCESS", a.sendRequest("REGISTER " + owner + " " + encode(PASSWORD)));
                    assertTrue(a.sendRequest("REGISTER " + owner + " " + encode(PASSWORD)).startsWith("REGISTER_FAILED"));
                    assertTrue(Files.isDirectory(storage.resolve(owner).resolve(".sent")));
                    assertEquals(MailStorageService.WELCOME_MESSAGE, Files.readString(storage.resolve(owner).resolve("new_email.txt")));
                    String hash = Files.readString(storage.resolve(owner).resolve(".credentials"));
                    assertTrue(hash.matches("\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}")); assertFalse(hash.contains(PASSWORD));
                }
                assertTrue(a.sendRequest("REGISTER ../invalid " + encode(PASSWORD)).startsWith("REGISTER_FAILED"));
                assertTrue(a.sendRequest("LOGIN " + A + " " + encode("wrong-secret")).startsWith("LOGIN_FAILED"));
                assertTrue(a.sendRequest("LOGIN absent@local.test " + encode(PASSWORD)).startsWith("LOGIN_FAILED"));
                assertTrue(a.sendRequest("LOGIN " + A + " " + encode(PASSWORD)).startsWith("LOGIN_SUCCESS|"));
                assertTrue(b.sendRequest("LOGIN " + B + " " + encode(PASSWORD)).startsWith("LOGIN_SUCCESS|"));
                assertTrue(a.isAuthenticated()); assertTrue(b.isAuthenticated());
                System.out.println("PASS E2E registration, duplicates/invalid input, welcome, BCrypt, login and sessions");
                String tokenA = token(server.udp, A), id = UUID.randomUUID().toString();
                String command = "AUTH " + tokenA + " SEND " + B + " " + encode(content(A, B, SUBJECT, BODY));
                assertEquals("SEND_SUCCESS", raw(server.udp, command, id));
                assertEquals("SEND_SUCCESS", raw(server.udp, command, id));
                assertEquals("SEND_SUCCESS", b.sendRequest("SEND " + A + " " + encode(content(B, A, "Re: " + SUBJECT, "Reply: " + BODY))));
                bInbox = filename(storage, B, false, SUBJECT); aSent = filename(storage, A, true, SUBJECT);
                aInbox = filename(storage, A, false, "Re: " + SUBJECT); bSent = filename(storage, B, true, "Re: " + SUBJECT);
                for (int refresh = 0; refresh < 2; refresh++) {
                    assertTrue(a.sendRequest("INBOX " + A).contains(aInbox)); assertTrue(b.sendRequest("INBOX " + B).contains(bInbox));
                    assertTrue(a.sendRequest("SENT " + A).contains(aSent)); assertTrue(b.sendRequest("SENT " + B).contains(bSent));
                }
                verifyMessage(read(b, B, bInbox, false), A, B, SUBJECT, BODY, false);
                verifyMessage(read(a, A, aSent, true), A, B, SUBJECT, BODY, true);
                verifyMessage(read(a, A, aInbox, false), B, A, "Re: " + SUBJECT, "Reply: " + BODY, false);
                verifyMessage(read(b, B, bSent, true), B, A, "Re: " + SUBJECT, "Reply: " + BODY, true);
                try (var files = Files.list(storage.resolve(B))) { assertEquals(1, files.filter(p -> p.getFileName().toString().startsWith("mail_")).count()); }
                assertEquals("FORBIDDEN", a.sendRequest("INBOX " + B)); assertEquals("FORBIDDEN", a.sendRequest("SENT " + B));
                assertEquals("FORBIDDEN", a.sendRequest("READ " + B + " " + bInbox));
                assertTrue(a.sendRequest("READ " + A + " .credentials").startsWith("READ_FAILED"));
                assertTrue(a.sendRequest("READ " + A + " ../outside.txt").startsWith("READ_FAILED"));
                assertEquals("FORBIDDEN", a.sendRequest("SEND " + B + " " + encode(content(B, B, SUBJECT, BODY))));
                assertEquals("LOGOUT_SUCCESS", raw(server.udp, "AUTH " + tokenA + " LOGOUT"));
                assertEquals("AUTH_REQUIRED", raw(server.udp, "AUTH " + tokenA + " READ " + A + " " + aInbox));
                a.logout(); b.logout(); assertFalse(a.isAuthenticated()); assertFalse(b.isAuthenticated());
                assertEquals("AUTH_REQUIRED", a.sendRequest("READ " + A + " " + aInbox));
                System.out.println("PASS E2E A->B and B->A exact content/headers/timestamps, Inbox/Sent refresh, retry once, isolation/spoof/hash/traversal, logout/replay");
            }
            smtpAndPop(server, storage);
            try (var files = Files.walk(storage)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) durable.put(storage.relativize(file).toString(), Files.readAllBytes(file));
            }
        }
        try (E2eTestServer restarted = new E2eTestServer(root);
             UdpMailClient a = new UdpMailClient("127.0.0.1", restarted.udp);
             UdpMailClient b = new UdpMailClient("127.0.0.1", restarted.udp)) {
            assertTrue(a.sendRequest("LOGIN " + A + " " + encode(PASSWORD)).contains(aInbox));
            assertTrue(b.sendRequest("LOGIN " + B + " " + encode(PASSWORD)).contains(bInbox));
            assertTrue(a.sendRequest("SENT " + A).contains(aSent)); assertTrue(b.sendRequest("SENT " + B).contains(bSent));
            verifyMessage(read(a, A, aInbox, false), B, A, "Re: " + SUBJECT, "Reply: " + BODY, false);
            verifyMessage(read(b, B, bInbox, false), A, B, SUBJECT, BODY, false);
            verifyMessage(read(a, A, aSent, true), A, B, SUBJECT, BODY, true);
            verifyMessage(read(b, B, bSent, true), B, A, "Re: " + SUBJECT, "Reply: " + BODY, true);
            for (var entry : durable.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(storage.resolve(entry.getKey())));
            a.logout(); b.logout();
        }
        System.out.println("PASS E2E full child JVM graceful restart: both users, Inbox/Sent and every stored byte preserved");
    }

    private void smtpAndPop(E2eTestServer server, Path storage) throws Exception {
        try (Wire smtp = new Wire(server.smtp)) {
            assertTrue(smtp.line().startsWith("220")); smtp.send("EHLO localhost");
            String line; do { line = smtp.line(); assertTrue(line.startsWith("250")); } while (!line.startsWith("250 "));
            assertTrue(smtp.command("AUTH PLAIN " + encode("\0" + A + "\0" + PASSWORD)).startsWith("235"));
            assertTrue(smtp.command("MAIL FROM:<" + A + ">").startsWith("250"));
            assertTrue(smtp.command("RCPT TO:<" + B + ">").startsWith("250"));
            assertTrue(smtp.command("DATA").startsWith("354"));
            smtp.send("From: " + A); smtp.send("To: " + B); smtp.send("Subject: SMTP E2E");
            smtp.send("Content-Type: text/plain; charset=UTF-8"); smtp.send(""); smtp.send(BODY); smtp.send("..dot body");
            assertTrue(smtp.command(".").startsWith("250")); assertTrue(smtp.command("QUIT").startsWith("221"));
        }
        String file = filename(storage, B, false, "SMTP E2E");
        verifyMessage(Files.readString(storage.resolve(B).resolve(file)), A, B, "SMTP E2E", BODY + "\r\n.dot body\r\n", false);
        try (Wire pop = new Wire(server.pop)) {
            assertTrue(pop.line().startsWith("+OK")); assertTrue(pop.command("USER " + B).startsWith("+OK"));
            assertTrue(pop.command("PASS " + PASSWORD).startsWith("+OK")); assertTrue(pop.command("STAT").startsWith("+OK 3 "));
            assertTrue(pop.command("LIST").startsWith("+OK")); assertEquals(3, pop.multi().size());
            assertTrue(pop.command("UIDL").startsWith("+OK"));
            String uid = pop.multi().stream().filter(s -> s.endsWith(" " + file)).findFirst().orElseThrow();
            assertTrue(pop.command("RETR " + uid.split(" ")[0]).startsWith("+OK"));
            String message = String.join("\r\n", pop.multi()); assertTrue(message.contains("Subject: SMTP E2E"));
            assertTrue(message.contains(BODY)); assertTrue(message.contains(".dot body"));
            assertTrue(pop.command("QUIT").startsWith("+OK"));
        }
        System.out.println("PASS E2E real TCP SMTP EHLO/AUTH/MAIL/RCPT/DATA/QUIT -> file -> POP3 USER/PASS/STAT/LIST/UIDL/RETR/QUIT");
    }
    static final class Wire implements AutoCloseable {
        final Socket socket; final BufferedReader reader; final BufferedWriter writer;
        Wire(int port) throws Exception {
            socket = new Socket(); socket.connect(new InetSocketAddress("127.0.0.1", port), 5000); socket.setSoTimeout(5000);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }
        String line() throws Exception { String value = reader.readLine(); assertNotNull(value, "TCP response"); return value; }
        void send(String value) throws Exception { writer.write(value + "\r\n"); writer.flush(); }
        String command(String value) throws Exception { send(value); return line(); }
        List<String> multi() throws Exception { List<String> lines = new ArrayList<>(); for (String s; !(s = line()).equals(".");) lines.add(s.startsWith("..") ? s.substring(1) : s); return lines; }
        public void close() throws Exception { socket.close(); }
    }
}
