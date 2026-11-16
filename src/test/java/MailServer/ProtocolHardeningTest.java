package MailServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded local-only protocol tests. Never connects to the user's running listeners. */
class ProtocolHardeningTest {
    private static final String A = "step2.a@local.test", B = "step2.b@local.test", PASSWORD = "step2-synthetic-only";
    private Path root;
    private MailStorageService storage;
    private LocalSmtpServer smtp;
    private Pop3Server pop;
    private final List<String> logs = new CopyOnWriteArrayList<>();
    @BeforeEach void start() throws Exception {
        root = Files.createTempDirectory(Path.of("target"), "step2-protocol-"); storage = new MailStorageService(root);
        assertTrue(storage.register(A, PASSWORD)); assertTrue(storage.register(B, PASSWORD));
        smtp = new LocalSmtpServer(0, storage, logs::add); pop = new Pop3Server(0, storage, logs::add);
    }
    @AfterEach void stop() { if (smtp != null) smtp.close(); if (pop != null) pop.close(); }
    private Wire smtpLogin() throws Exception {
        Wire wire = new Wire(smtp.getPort()); wire.code(wire.read(), "220"); wire.ehlo();
        wire.code(wire.command("AUTH PLAIN " + Base64.getEncoder().encodeToString(("\0" + A + "\0" + PASSWORD).getBytes(StandardCharsets.UTF_8))), "235");
        return wire;
    }
    private Wire popLogin(String user) throws Exception {
        Wire wire = new Wire(pop.getPort()); wire.code(wire.read(), "+OK");
        wire.code(wire.command("USER " + user), "+OK"); wire.code(wire.command("PASS " + PASSWORD), "+OK"); return wire;
    }
    private void envelope(Wire wire) throws Exception {
        wire.code(wire.command("MAIL FROM:<" + A + ">"), "250"); wire.code(wire.command("RCPT TO:<" + B + ">"), "250");
    }
    @Test void smtpSyntaxAndResetState() throws Exception {
        try (Wire wire = new Wire(smtp.getPort())) {
            wire.code(wire.read(), "220"); wire.code(wire.command("EHLO"), "501");
            wire.code(wire.command("HELO"), "501"); wire.code(wire.command("HELO localhost"), "250");
            wire.code(wire.command("NOOP demo"), "250"); wire.code(wire.command("UNKNOWN"), "502");
            wire.code(wire.command("MAIL FROM:<" + A + ">"), "530");
            wire.code(wire.command("AUTH LOGIN"), "504");
            wire.code(wire.command("AUTH PLAIN"), "334");
            wire.code(wire.command(Base64.getEncoder().encodeToString(("\0" + A + "\0" + PASSWORD).getBytes(StandardCharsets.UTF_8))), "235");
            wire.code(wire.command("MAIL nonsense"), "501");
            envelope(wire); wire.code(wire.command("RSET extra"), "501");
            wire.code(wire.command("RSET"), "250"); wire.code(wire.command("DATA"), "503");
            wire.code(wire.command("RCPT TO:<" + B + ">"), "503");
            wire.code(wire.command("QUIT extra"), "501"); wire.code(wire.command("QUIT"), "221");
        }
        assertEquals(1, storage.getMailSummaries(B).size());
        System.out.println("PASS SMTP HELO/EHLO syntax, AUTH challenge, NOOP, unknown command, unauthenticated rejection, RSET/QUIT state and status codes");
    }
    @Test void smtpDataArgumentsCannotStartDataOrCreateDuplicates() throws Exception {
        try (Wire wire = smtpLogin()) {
            envelope(wire); wire.code(wire.command("DATA extra"), "501");
            wire.code(wire.command("DATA"), "354");
            wire.send("From: " + A); wire.send("To: " + B); wire.send("Subject: exactly one DATA"); wire.send(""); wire.send("body");
            wire.code(wire.command("."), "250"); wire.code(wire.command("DATA"), "503"); wire.code(wire.command("QUIT"), "221");
        }
        assertEquals(2, storage.getMailSummaries(B).size()); assertEquals(1, storage.getSentSummaries(A).size());
        System.out.println("PASS SMTP DATA syntax; exactly one file/Sent per accepted transaction; repeated DATA without MAIL rejected");
    }
    @Test void popQuitArgumentsMustNotCommitDeletions() throws Exception {
        try (Wire wire = popLogin(B)) {
            wire.code(wire.command("DELE 1"), "+OK"); wire.code(wire.command("QUIT extra"), "-ERR");
            wire.code(wire.command("RSET"), "+OK"); wire.code(wire.command("STAT"), "+OK 1 ");
            wire.code(wire.command("NOOP"), "+OK"); wire.code(wire.command("QUIT"), "+OK");
        }
        assertEquals(1, storage.getMailSummaries(B).size());
        System.out.println("PASS POP3 invalid QUIT cannot commit deletion; RSET and normal QUIT preserve mailbox");
    }
    @Test void popInvalidUserClearsPendingIdentity() throws Exception {
        try (Wire wire = new Wire(pop.getPort())) {
            wire.code(wire.read(), "+OK"); wire.code(wire.command("RETR 1"), "-ERR");
            wire.code(wire.command("USER " + A), "+OK"); wire.code(wire.command("USER ../outside"), "-ERR");
            wire.code(wire.command("PASS " + PASSWORD), "-ERR");
            wire.code(wire.command("USER " + B), "+OK"); wire.code(wire.command("PASS wrong"), "-ERR");
            wire.code(wire.command("PASS " + PASSWORD), "-ERR"); wire.code(wire.command("QUIT"), "+OK");
        }
        System.out.println("PASS POP3 invalid USER/path traversal clears pending identity; wrong PASS cannot reuse previous USER");
    }

    @Test void smtpOversizedBodyHasSizeFailureAndResetsTransaction() throws Exception {
        try (Wire wire = smtpLogin()) {
            envelope(wire); wire.code(wire.command("DATA"), "354");
            wire.send("From: " + A); wire.send("To: " + B); wire.send("Subject: oversized"); wire.send("");
            for (int i=0; i<66; i++) wire.send("x".repeat(500));
            wire.code(wire.command("."), "552"); wire.code(wire.command("DATA"), "503");
            wire.code(wire.command("NOOP"), "250"); wire.code(wire.command("QUIT"), "221");
        }
        assertEquals(1, storage.getMailSummaries(B).size()); assertTrue(storage.getSentSummaries(A).isEmpty());
        System.out.println("PASS SMTP body >32000 UTF-8 bytes returns 552, resets transaction and creates no mail");
    }

    @Test void smtpWireSizeAndBrokenDataNeverPersist() throws Exception {
        try (Wire wire = smtpLogin()) {
            envelope(wire); wire.code(wire.command("DATA"), "354");
            wire.send("From: " + A); wire.send("To: " + B); wire.send("Subject: wire limit");
            for (int i=0; i<72; i++) wire.send("X-Fill: " + "x".repeat(1800));
            wire.send(""); wire.send("body"); wire.code(wire.command("."), "552");
            wire.code(wire.command("NOOP"), "250");
        }
        try (Wire wire = smtpLogin()) {
            envelope(wire); wire.code(wire.command("DATA"), "354"); wire.send("From: " + A);
            wire.send("Subject: disconnected before dot"); wire.send(""); wire.send("partial");
        }
        // A subsequent valid session proves the listener is still usable.
        try (Wire wire=smtpLogin()) { wire.code(wire.command("NOOP"), "250"); wire.code(wire.command("QUIT"), "221"); }
        assertEquals(1, storage.getMailSummaries(B).size()); assertTrue(storage.getSentSummaries(A).isEmpty());
        System.out.println("PASS SMTP >128000 wire bytes ->552; disconnect during DATA creates no Inbox/Sent; listener recovers");
    }

    @Test void smtpAuthenticationAndSessionIsolation() throws Exception {
        try (Wire wire=new Wire(smtp.getPort())) {
            wire.read(); wire.ehlo();
            for(int i=0;i<3;i++) wire.code(wire.command("AUTH PLAIN invalid-base64!"),"535");
            assertNull(wire.reader.readLine(),"three failed AUTH attempts close connection");
        }
        try (Wire owner=smtpLogin(); Wire other=new Wire(smtp.getPort())) {
            other.read(); other.ehlo(); other.code(other.command("MAIL FROM:<"+A+">"),"530");
            owner.code(owner.command("MAIL FROM:<"+B+">"),"553");
            owner.code(owner.command("MAIL FROM:<"+A+">"),"250");
            owner.code(owner.command("RCPT TO:<outside@example.invalid>"),"550");
            owner.code(owner.command("RCPT TO:<../outside>"),"501");
            owner.code(owner.command("RCPT TO:<"+B+">"),"250");
            owner.code(owner.command("DATA"),"354"); owner.send("From: "+B); owner.send("Subject: spoof"); owner.send(""); owner.send("body");
            owner.code(owner.command("."),"553"); owner.code(owner.command("QUIT"),"221");
        }
        assertEquals(1,storage.getMailSummaries(B).size());
        System.out.println("PASS SMTP malformed AUTH/three failures, connection isolation, envelope/MIME spoof, open relay and unsafe paths rejected");
    }

    @Test void popCapabilitiesAndInvalidMessageNumbers() throws Exception {
        try(Wire wire=new Wire(pop.getPort())) {
            wire.read(); wire.code(wire.command("CAPA"),"+OK"); assertTrue(wire.multi().containsAll(List.of("USER","UIDL")));
            wire.code(wire.command("CAPA extra"),"-ERR"); wire.code(wire.command("STAT"),"-ERR");
            wire.code(wire.command("USER "+A),"+OK"); wire.code(wire.command("PASS "+PASSWORD),"+OK");
            wire.code(wire.command("USER "+B),"-ERR");
            for(String command:List.of("LIST 0","UIDL -1","RETR ../outside","DELE 999","STAT extra","NOOP extra","UNKNOWN"))
                wire.code(wire.command(command),"-ERR");
            wire.code(wire.command("UIDL"),"+OK"); assertEquals(List.of("1 new_email.txt"),wire.multi());
            wire.code(wire.command("LIST 1"),"+OK 1 "); wire.code(wire.command("UIDL 1"),"+OK 1 new_email.txt");
            wire.code(wire.command("CAPA"),"+OK"); assertTrue(wire.multi().contains("UIDL"));
            wire.code(wire.command("QUIT"),"+OK");
        }
        System.out.println("PASS POP3 CAPA before/after login, invalid commands/numbers/path rejected, session owner cannot switch, credential file never listed");
    }

    @Test @Timeout(25) void fourConcurrentSmtpTransactionsStoreOnceEach() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> jobs=new ArrayList<>();
            for(int index=0;index<4;index++) { final int id=index; jobs.add(()-> {
                try(Wire wire=smtpLogin()) {
                    envelope(wire); wire.code(wire.command("DATA"),"354");
                    wire.send("From: "+A); wire.send("To: "+B); wire.send("Subject: concurrent "+id); wire.send(""); wire.send("body "+id);
                    wire.code(wire.command("."),"250"); wire.code(wire.command("QUIT"),"221"); return true;
                }
            }); }
            for(Future<Boolean> result:pool.invokeAll(jobs)) assertTrue(result.get());
        } finally { pool.shutdownNow(); }
        assertEquals(5,storage.getMailSummaries(B).size()); assertEquals(4,storage.getSentSummaries(A).size());
        System.out.println("PASS four concurrent authenticated SMTP transactions: exactly four Inbox files and four Sent files");
    }

    @Test @Timeout(45) void idleTimeoutClosesBothProtocols() throws Exception {
        try(Wire mail=new Wire(smtp.getPort()); Wire retrieval=new Wire(pop.getPort())) {
            mail.read(); retrieval.read(); mail.socket.setSoTimeout(36000); retrieval.socket.setSoTimeout(36000);
            long start=System.nanoTime();
            assertNull(mail.reader.readLine(),"SMTP idle timeout closes connection");
            assertNull(retrieval.reader.readLine(),"POP3 idle timeout closes connection");
            long seconds=TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start);
            assertTrue(seconds>=28 && seconds<36,"actual idle timeout near 30 seconds");
        }
        assertTrue(logs.stream().anyMatch(s->s.contains("SMTP session ended: SocketTimeoutException")));
        assertTrue(logs.stream().anyMatch(s->s.contains("POP3 session ended: SocketTimeoutException")));
        try(Wire wire=smtpLogin()) { wire.code(wire.command("QUIT"),"221"); }
        System.out.println("PASS actual 30-second idle timeout on SMTP and POP3; sockets closed and listener still healthy");
    }

    @Test void badFramingLineLimitsAndListenerCleanup() throws Exception {
        try(Wire wire=new Wire(smtp.getPort())) {
            wire.read(); wire.writer.write("NOOP\n"); wire.writer.flush(); assertNull(wire.reader.readLine());
        }
        try(Wire wire=new Wire(pop.getPort())) {
            wire.read(); wire.send("X".repeat(1100)); assertNull(wire.reader.readLine());
        }
        int smtpPort=smtp.getPort(),popPort=pop.getPort();
        try(Wire mail=new Wire(smtpPort); Wire retrieval=new Wire(popPort)) {
            mail.read(); retrieval.read(); smtp.close(); pop.close();
            assertNull(mail.reader.readLine()); assertNull(retrieval.reader.readLine());
        }
        for(Object protocol:List.of(smtp,pop)) {
            var listenerField=protocol.getClass().getDeclaredField("listener"); listenerField.setAccessible(true); Object listener=listenerField.get(protocol);
            var workersField=TcpMailListener.class.getDeclaredField("workers"); workersField.setAccessible(true);
            ExecutorService workers=(ExecutorService)workersField.get(listener); assertTrue(workers.awaitTermination(3,TimeUnit.SECONDS));
            var activeField=TcpMailListener.class.getDeclaredField("active"); activeField.setAccessible(true); assertTrue(((Set<?>)activeField.get(listener)).isEmpty());
            var acceptorField=TcpMailListener.class.getDeclaredField("acceptor"); acceptorField.setAccessible(true); assertFalse(((Thread)acceptorField.get(listener)).isAlive());
        }
        try(ServerSocket s=new ServerSocket(); ServerSocket p=new ServerSocket()) {
            s.bind(new InetSocketAddress("127.0.0.1",smtpPort)); p.bind(new InetSocketAddress("127.0.0.1",popPort));
        }
        System.out.println("PASS invalid CRLF/oversized command closes session; shutdown releases active sockets, workers, acceptors and listening ports");
    }

    @Test void smtpPopTranscriptAndRestart() throws Exception {
        List<String> transcript=new ArrayList<>();
        String body="Xin chào Việt Nam.\r\n.dòng bắt đầu dấu chấm\r\n..hai dấu chấm\r\n";
        var message=MimeSupport.message(A,B,"STEP2 Tiếng Việt UTF-8",body);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); message.writeTo(bytes);
        try(Wire wire=new Wire(smtp.getPort()).trace("SMTP",transcript)) {
            wire.code(wire.read(),"220"); wire.ehlo();
            wire.code(wire.command("AUTH PLAIN "+Base64.getEncoder().encodeToString(("\0"+A+"\0"+PASSWORD).getBytes(StandardCharsets.UTF_8))),"235");
            envelope(wire); wire.code(wire.command("DATA"),"354");
            String raw=bytes.toString(StandardCharsets.UTF_8).replace("\r\n","\n");
            String[] lines=raw.split("\n",-1); int count=raw.endsWith("\n")?lines.length-1:lines.length;
            for(int i=0;i<count;i++) wire.send((lines[i].startsWith(".")?".":"")+lines[i]);
            wire.code(wire.command("."),"250"); wire.code(wire.command("QUIT"),"221");
        }
        String filename=storage.getMailSummaries(B).stream().filter(m->m.getSubject().equals("STEP2 Tiếng Việt UTF-8")).findFirst().orElseThrow().getFileName();
        assertEquals(body,MimeSupport.body(storage.readMail(B,filename)));
        String uid;
        try(Wire wire=new Wire(pop.getPort()).trace("POP3",transcript)) {
            wire.read(); wire.code(wire.command("USER "+B),"+OK"); wire.code(wire.command("PASS "+PASSWORD),"+OK");
            wire.code(wire.command("STAT"),"+OK 2 "); wire.code(wire.command("LIST"),"+OK"); assertEquals(2,wire.multi().size());
            wire.code(wire.command("UIDL"),"+OK"); uid=wire.multi().stream().filter(s->s.endsWith(" "+filename)).findFirst().orElseThrow();
            wire.code(wire.command("RETR "+uid.split(" ")[0]),"+OK");
            byte[] mime=(String.join("\r\n",wire.multi())+"\r\n").getBytes(StandardCharsets.UTF_8);
            var received=new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new Properties()),new ByteArrayInputStream(mime));
            assertEquals("STEP2 Tiếng Việt UTF-8",received.getSubject()); assertEquals(body,MimeSupport.text(received));
            wire.code(wire.command("QUIT"),"+OK");
        }
        smtp.close(); pop.close(); storage=new MailStorageService(root);
        smtp=new LocalSmtpServer(0,storage,logs::add); pop=new Pop3Server(0,storage,logs::add);
        try(Wire wire=popLogin(B)) {
            wire.code(wire.command("UIDL"),"+OK"); String after=wire.multi().stream().filter(s->s.endsWith(" "+filename)).findFirst().orElseThrow();
            assertEquals(uid,after); wire.code(wire.command("RETR "+after.split(" ")[0]),"+OK");
            var received=new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new Properties()),new ByteArrayInputStream((String.join("\r\n",wire.multi())+"\r\n").getBytes(StandardCharsets.UTF_8)));
            assertEquals(body,MimeSupport.text(received)); wire.code(wire.command("QUIT"),"+OK");
        }
        String log=String.join("\n",transcript);
        assertFalse(log.contains(PASSWORD)); assertFalse(log.contains(Base64.getEncoder().encodeToString(("\0"+A+"\0"+PASSWORD).getBytes(StandardCharsets.UTF_8))));
        Files.createDirectories(Path.of("evidence/step2")); Files.writeString(Path.of("evidence/step2/protocol-transcript.txt"),log+"\n",StandardCharsets.UTF_8);
        System.out.println("PASS TCP SMTP->File Storage->TCP POP3 exact UTF-8/body/dot-stuffing, stable UIDL and RETR after restart; sanitized transcript in evidence/step2/protocol-transcript.txt");
    }

    static final class Wire implements AutoCloseable {
        final Socket socket; final BufferedReader reader; final BufferedWriter writer;
        private List<String> trace; private String protocol; private boolean authContinuation;
        Wire(int port) throws Exception {
            socket = new Socket(); socket.connect(new InetSocketAddress("127.0.0.1", port), 3000); socket.setSoTimeout(5000);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }
        Wire trace(String name,List<String> lines) { protocol=name; trace=lines; return this; }
        String read() throws Exception { String line = reader.readLine(); assertNotNull(line, "protocol response must not close unexpectedly"); if(trace!=null) trace.add(protocol+" S: "+line); return line; }
        void send(String value) throws Exception {
            if(trace!=null) {
                String safe=authContinuation?"[REDACTED AUTH RESPONSE]":value.startsWith("AUTH ")?"AUTH PLAIN [REDACTED]":value.startsWith("PASS ")?"PASS [REDACTED]":value;
                trace.add(protocol+" C: "+safe); authContinuation=value.equals("AUTH PLAIN");
            }
            writer.write(value + "\r\n"); writer.flush();
        }
        String command(String value) throws Exception { send(value); return read(); }
        void code(String value, String expected) { assertTrue(value.startsWith(expected), "expected " + expected + ", got " + value); }
        void ehlo() throws Exception { send("EHLO localhost"); String line; do { line=read(); code(line,"250"); } while(!line.startsWith("250 ")); }
        List<String> multi() throws Exception { List<String> values=new ArrayList<>(); for(String line; !(line=read()).equals(".");) values.add(line.startsWith("..") ? line.substring(1) : line); return values; }
        public void close() throws Exception { socket.close(); }
    }
}
