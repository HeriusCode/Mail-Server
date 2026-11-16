package MailServer;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolIntegrationTest {
    private Path root;
    private MailStorageService storage;
    private LocalSmtpServer smtp;
    private Pop3Server pop;
    private static final String A = "protocol.a@local.test", B = "protocol.b@local.test", PASSWORD = "synthetic-protocol-only";
    @BeforeEach void start() throws Exception {
        root = Files.createTempDirectory(Path.of("target"), "protocol-"); storage = new MailStorageService(root);
        assertTrue(storage.register(A, PASSWORD)); assertTrue(storage.register(B, PASSWORD));
        smtp = new LocalSmtpServer(0, storage, s -> { }); pop = new Pop3Server(0, storage, s -> { });
    }
    @AfterEach void stop() { if (smtp != null) smtp.close(); if (pop != null) pop.close(); }
    private Properties props() {
        Properties p = new Properties(); p.setProperty("mail.smtp.auth", "true"); p.setProperty("mail.smtp.auth.mechanisms", "PLAIN");
        p.setProperty("mail.host", "localhost"); p.setProperty("mail.user", "test"); p.setProperty("mail.smtp.localhost", "localhost");
        p.setProperty("mail.smtp.connectiontimeout", "3000"); p.setProperty("mail.smtp.timeout", "3000");
        p.setProperty("mail.pop3.connectiontimeout", "3000"); p.setProperty("mail.pop3.timeout", "3000");
        return p;
    }
    private void smtpSend(String from, String recipient) throws Exception {
        Session session = Session.getInstance(props());
        MimeMessage message = new MimeMessage(session); message.setFrom(from); message.setRecipients(Message.RecipientType.TO, recipient);
        message.setSubject("Giao thức UTF-8", "UTF-8"); message.setText("Nội dung thật\n.dot line\n..two dots\nlast", "UTF-8"); message.saveChanges();
        try (Transport t = session.getTransport("smtp")) { t.connect("127.0.0.1", smtp.getPort(), A, PASSWORD); t.sendMessage(message, message.getAllRecipients()); }
    }
    @Test void jakartaSmtpToPop3RoundTrip() throws Exception {
        smtpSend(A, B);
        assertEquals(2, storage.getMailSummaries(B).size()); assertEquals(1, storage.getSentSummaries(A).size());
        Session session = Session.getInstance(props());
        try (Store store = session.getStore("pop3")) {
            store.connect("127.0.0.1", pop.getPort(), B, PASSWORD);
            Folder inbox = store.getFolder("INBOX"); inbox.open(Folder.READ_ONLY);
            assertEquals(2, inbox.getMessageCount()); boolean found = false;
            for (Message message : inbox.getMessages()) {
                if ("Giao thức UTF-8".equals(message.getSubject())) {
                    String body = (String) message.getContent();
                    assertTrue(body.contains("Nội dung thật")); assertTrue(body.contains("\r\n.dot line")); assertTrue(body.contains("\r\n..two dots")); found = true;
                }
            }
            assertTrue(found); inbox.close(false);
        }
        System.out.println("PASS Jakarta Mail SMTP AUTH/DATA -> file Inbox/Sent -> actual POP3 client LIST/RETR UTF-8 and dot-stuffing");
    }
    @Test void smtpRejectsAnonymousSpoofAndRelay() throws Exception {
        try (Conversation c = new Conversation(smtp.getPort())) {
            assertTrue(c.read().startsWith("220")); c.send("EHLO localhost"); c.until("250 ");
            assertTrue(c.command("MAIL FROM:<" + A + ">").startsWith("530"));
            String auth = Base64.getEncoder().encodeToString(("\0" + A + "\0" + PASSWORD).getBytes(StandardCharsets.UTF_8));
            assertTrue(c.command("AUTH PLAIN " + auth).startsWith("235"));
            assertTrue(c.command("MAIL FROM:<" + B + ">").startsWith("553"));
            assertTrue(c.command("MAIL FROM:<" + A + ">").startsWith("250"));
            assertTrue(c.command("RCPT TO:<someone@gmail.com>").startsWith("550"));
            assertTrue(c.command("QUIT").startsWith("221"));
        }
        assertThrows(MessagingException.class, () -> smtpSend(B, B));
        assertEquals(1, storage.getMailSummaries(B).size());
        System.out.println("PASS SMTP rejects anonymous submission, envelope spoof, MIME From spoof and external open relay");
    }
    @Test void popStateBytesAndOwnership() throws Exception {
        smtpSend(A, B);
        try (Conversation c = new Conversation(pop.getPort())) {
            assertTrue(c.read().startsWith("+OK")); assertTrue(c.command("RETR 1").startsWith("-ERR"));
            assertTrue(c.command("USER " + B).startsWith("+OK")); assertTrue(c.command("PASS wrong-password").startsWith("-ERR"));
            assertTrue(c.command("USER " + A).startsWith("+OK")); assertTrue(c.command("PASS " + PASSWORD).startsWith("+OK"));
            assertTrue(c.command("STAT").startsWith("+OK 1 "));
            assertTrue(c.command("USER " + B).startsWith("-ERR"));
            String list = c.command("LIST 1"); int expected = Integer.parseInt(list.split(" ")[2]);
            assertTrue(c.command("RETR 1").startsWith("+OK"));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (String line; !(line = c.read()).equals(".");) {
                if (line.startsWith("..")) line = line.substring(1);
                bytes.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(expected, bytes.size()); assertTrue(c.command("NOOP").startsWith("+OK"));
            c.command("QUIT");
        }
        System.out.println("PASS POP3 state machine, wrong password, owner isolation, LIST octet counts, RETR and NOOP");
    }
    @Test void popDeleteRollbackResetAndCommitArchive() throws Exception {
        try (Conversation c = loginPop(B)) { assertTrue(c.command("DELE 1").startsWith("+OK")); }
        // Wait for session lock to release after disconnect, then prove deletion was rolled back.
        Thread.sleep(100);
        try (Conversation c = loginPop(B)) {
            assertTrue(c.command("STAT").startsWith("+OK 1 "));
            c.command("DELE 1"); assertTrue(c.command("RSET").startsWith("+OK")); assertTrue(c.command("STAT").startsWith("+OK 1 "));
            c.command("DELE 1"); assertTrue(c.command("QUIT").startsWith("+OK"));
        }
        assertTrue(storage.getMailSummaries(B).isEmpty());
        try (var files = Files.list(root.resolve(B).resolve(".trash"))) { assertEquals(1, files.count()); }
        System.out.println("PASS POP3 DELE rolls back on disconnect/RSET, commits only on QUIT, preserves bytes in private .trash");
    }
    private Conversation loginPop(String owner) throws Exception {
        Conversation c = new Conversation(pop.getPort()); assertTrue(c.read().startsWith("+OK")); c.command("USER " + owner);
        assertTrue(c.command("PASS " + PASSWORD).startsWith("+OK")); return c;
    }
    @Test void configuredSmtpClientAcceptanceAndFailure() throws Exception {
        // This is a real SMTP peer, not Gmail. Unknown local recipient is routed to the configured transport.
        SmtpSettings settings = new SmtpSettings("127.0.0.1", smtp.getPort(), A, PASSWORD, A, true, false, false);
        MailDeliveryService relay = new MailDeliveryService(storage, settings);
        String content = MimeSupport.canonical(A, "not.registered@gmail.com", "External attempt", "body");
        assertTrue(relay.send(A, "not.registered@gmail.com", content, UUID.randomUUID().toString()).contains("SMTP_REJECTED"));
        SmtpSettings badAuth = new SmtpSettings("127.0.0.1", smtp.getPort(), A, "wrong-password", A, true, false, false);
        assertTrue(new MailDeliveryService(storage, badAuth).send(A, "external@gmail.com", content, UUID.randomUUID().toString()).contains("SMTP_AUTH_FAILED"));
        assertTrue(new MailDeliveryService(storage, null).send(A, "external@gmail.com", content, UUID.randomUUID().toString()).contains("SMTP_NOT_CONFIGURED"));
        System.out.println("PASS outbound SMTP client reports missing config, real peer rejection and authentication failure");
    }
    @Test void smtpAcceptingPeerProducesSentReceiptOnce() throws Exception {
        // An isolated SMTP test peer allows any RCPT to exercise external delivery without sending real email.
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        try (TcpMailListener peer = new TcpMailListener(0, wire -> {
            wire.line("220 test SMTP");
            for (String line; (line = wire.read(2048)) != null;) {
                if (line.startsWith("EHLO")) wire.line("250 test");
                else if (line.startsWith("MAIL") || line.startsWith("RCPT")) wire.line("250 OK");
                else if (line.equals("DATA")) { wire.line("354 Continue"); String data; while ((data = wire.read(2048)) != null && !data.equals(".")) { } if (data == null) return; accepted.incrementAndGet(); wire.line("250 Accepted"); }
                else if (line.equals("QUIT")) { wire.line("221 Bye"); return; } else wire.line("250 OK");
            }
        }, s -> { }, "SMTP-test-peer")) {
            MailDeliveryService delivery = new MailDeliveryService(storage, new SmtpSettings("127.0.0.1", peer.port(), null, null, A, false, false, false));
            String id = UUID.randomUUID().toString(), body = MimeSupport.canonical(A, "external@gmail.com", "Receipt", "body");
            assertEquals("SMTP_ACCEPTED", delivery.send(A, "external@gmail.com", body, id));
            assertEquals("SMTP_ACCEPTED", delivery.send(A, "external@gmail.com", body, id));
            assertEquals(1, accepted.get()); assertEquals(1, storage.getSentSummaries(A).size());
            assertTrue(storage.readSentMail(A, storage.getSentSummaries(A).get(0).getFileName()).contains("SMTP_ACCEPTED"));
        }
        System.out.println("PASS configured outbound SMTP DATA/250 acceptance, durable Sent receipt and retry deduplication using isolated test peer; Gmail NOT tested");
    }
    @Test void popMaildropLock() throws Exception {
        try (Conversation first = loginPop(B); Conversation second = new Conversation(pop.getPort())) {
            second.read(); second.command("USER " + B); assertTrue(second.command("PASS " + PASSWORD).startsWith("-ERR Maildrop locked"));
            first.command("QUIT");
        }
        System.out.println("PASS concurrent POP3 sessions cannot mutate the same maildrop");
    }
    @Test void interactiveProtocolDemoRunsWithoutPrintingSecrets() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); PrintStream previous = System.out;
        try (Scanner input = new Scanner(A + "\n" + PASSWORD + "\n" + B + "\n" + PASSWORD + "\n")) {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            MailClient.ProtocolDemo.run(input, smtp.getPort(), pop.getPort());
        } finally { System.setOut(previous); }
        String transcript = output.toString(StandardCharsets.UTF_8);
        assertTrue(transcript.contains("PASS SMTP:")); assertTrue(transcript.contains("PASS POP3 RETR:"));
        assertTrue(transcript.contains("POP3 filename/UIDL: mail_")); assertFalse(transcript.contains(PASSWORD));
        System.out.println("PASS interactive ProtocolDemo executes actual SMTP/POP3, prints filename/content and never prints passwords");
    }
    private static final class Conversation implements AutoCloseable {
        final Socket socket; final BufferedReader reader; final BufferedWriter writer;
        Conversation(int port) throws Exception { socket = new Socket("127.0.0.1", port); socket.setSoTimeout(5000); reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)); writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)); }
        String read() throws Exception { String line = reader.readLine(); assertNotNull(line, "protocol response"); return line; }
        void send(String line) throws Exception { writer.write(line + "\r\n"); writer.flush(); }
        String command(String line) throws Exception { send(line); return read(); }
        void until(String prefix) throws Exception { while (!read().startsWith(prefix)) { } }
        public void close() throws Exception { socket.close(); }
    }
}
