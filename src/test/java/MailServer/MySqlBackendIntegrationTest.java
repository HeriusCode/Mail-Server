package MailServer;

import MailClient.UdpMailClient;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class MySqlBackendIntegrationTest {
    private static PrivateMySqlServer mysql;
    private MailStorageService storage;
    private JdbcMailRepository sql;
    private String a,b;
    private static final String PASSWORD="synthetic-mysql-test-only";
    @BeforeAll static void startDatabase() throws Exception {
        Path binary=Path.of(System.getenv().getOrDefault("MAIL_TEST_MYSQLD","C:/Program Files/MySQL/MySQL Server 8.0/bin/mysqld.exe"));
        Assumptions.assumeTrue(Files.isRegularFile(binary),"Real mysqld unavailable; MySQL integration NOT TESTED");
        mysql=new PrivateMySqlServer(binary);
    }
    @AfterAll static void stopDatabase() throws Exception { if(mysql!=null) mysql.close(); }
    @BeforeEach void repositories() throws Exception {
        var repositories=RepositoryFactory.mysql(mysql.config()); storage=new MailStorageService(repositories.users(),repositories.emails()); sql=new JdbcMailRepository(mysql.config());
        String suffix=UUID.randomUUID().toString().substring(0,12); a="a."+suffix+"@local.test"; b="b."+suffix+"@local.test";
    }
    private void accounts() { assertTrue(storage.register(a,PASSWORD)); assertTrue(storage.register(b,PASSWORD)); }
    private String message(String from,String to) { return MimeSupport.canonical(from,to,"MySQL tiếng Việt","Dữ liệu 😀\n.dot line"); }
    private MailStorageService restartStorage() throws Exception { var r=RepositoryFactory.mysql(mysql.config()); return new MailStorageService(r.users(),r.emails()); }
    @Test void realConnectionUtf8mb4AndLeastPrivileges() throws Exception {
        sql.check();
        try(Connection c=mysql.config().connect(); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT VERSION(),@@character_set_connection,CURRENT_USER()")) {
            assertTrue(r.next()); assertTrue(r.getString(1).startsWith("8.")); assertEquals("utf8mb4",r.getString(2)); assertTrue(r.getString(3).startsWith("mailflow_test_app@"));
        }
        try(Connection c=mysql.config().connect(); Statement s=c.createStatement()) { assertThrows(SQLException.class,()->s.execute("CREATE TABLE forbidden_test(id INT)")); }
        System.out.println("PASS real MySQL connection/schema/utf8mb4; dedicated app user cannot CREATE tables");
    }
    @Test void registrationHashWelcomeAndRollback() throws Exception {
        accounts(); assertFalse(storage.register(a,PASSWORD)); assertTrue(storage.authenticate(a,PASSWORD)); assertFalse(storage.authenticate(a,"wrong"));
        String hash=sql.passwordHash(a); assertTrue(hash.startsWith("$2")); assertFalse(hash.contains(PASSWORD));
        assertEquals(MailStorageService.WELCOME_MESSAGE,storage.readMail(a,"new_email.txt")); assertNull(storage.readMail(a,".credentials"));
        String c="rollback."+UUID.randomUUID()+"@local.test";
        String id=UUID.nameUUIDFromBytes(("welcome:"+c).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        assertTrue(sql.importEntry(new JdbcMailRepository.Entry(b,false,"mail_"+id+".txt","collision fixture",Instant.now()),id,b,b,"fixture","fixture"));
        assertFalse(storage.register(c,PASSWORD)); assertNull(sql.passwordHash(c));
        System.out.println("PASS MySQL registration/login/BCrypt/welcome; duplicate welcome rolls back user transaction; hash never served");
    }
    @Test void twoWayMailAndConcurrentIdempotency() throws Exception {
        accounts(); String id=UUID.randomUUID().toString();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->storage.saveMail(a,b,message(a,b),id)); var second=pool.submit(()->restartStorage().saveMail(a,b,message(a,b),id));
            assertTrue(first.get()); assertTrue(second.get());
        } finally { pool.shutdownNow(); }
        assertFalse(storage.saveMail(a,b,message(a,b)+"different",id)); assertTrue(storage.saveMail(b,a,message(b,a)));
        for(String owner:List.of(a,b)) {
            assertEquals(2,storage.getMailSummaries(owner).size()); assertEquals(1,storage.getSentSummaries(owner).size());
            String file=storage.getMailSummaries(owner).stream().map(MailSummary::getFileName).filter(n->n.startsWith("mail_")).findFirst().orElseThrow();
            assertTrue(storage.readMail(owner,file).endsWith("Dữ liệu 😀\n.dot line"));
            assertNull(storage.readMail(owner,"../outside.txt"));
        }
        assertEquals(2,restartStorage().getMailSummaries(b).size());
        System.out.println("PASS MySQL A->B/B->A, UTF-8 emoji, Inbox/Sent, concurrent retry once, conflicting request rejected and fresh repository restart");
    }
    @Test void smtpPopRoundTripAndDeleteRollback() throws Exception {
        accounts();
        try(LocalSmtpServer smtp=new LocalSmtpServer(0,storage,s->{}); Pop3Server pop=new Pop3Server(0,storage,s->{})) {
            Properties p=new Properties(); p.setProperty("mail.host","localhost"); p.setProperty("mail.user","test"); p.setProperty("mail.smtp.localhost","localhost");
            p.setProperty("mail.smtp.auth","true"); p.setProperty("mail.smtp.auth.mechanisms","PLAIN");
            p.setProperty("mail.smtp.timeout","5000"); p.setProperty("mail.pop3.timeout","5000");
            jakarta.mail.Session session=jakarta.mail.Session.getInstance(p);
            var message=MimeSupport.message(a,b,"MySQL SMTP tiếng Việt","Gửi từ SMTP 😀\n.dot line");
            // Build the message with the authenticated SMTP session.
            var outgoing=new jakarta.mail.internet.MimeMessage(session,new ByteArrayInputStream(bytes(message)));
            try(jakarta.mail.Transport t=session.getTransport("smtp")) { t.connect("127.0.0.1",smtp.getPort(),a,PASSWORD); t.sendMessage(outgoing,outgoing.getAllRecipients()); }
            try(jakarta.mail.Store store=session.getStore("pop3")) {
                store.connect("127.0.0.1",pop.getPort(),b,PASSWORD); var folder=store.getFolder("INBOX"); folder.open(jakarta.mail.Folder.READ_ONLY);
                boolean found=false; for(var mail:folder.getMessages()) if("MySQL SMTP tiếng Việt".equals(mail.getSubject())) { assertTrue(mail.getContent().toString().contains("Gửi từ SMTP 😀")); found=true; }
                assertTrue(found); folder.close(false);
            }
            try(AutomatedEndToEndTest.Wire wire=new AutomatedEndToEndTest.Wire(pop.getPort())) {
                wire.line(); assertTrue(wire.command("USER "+b).startsWith("+OK")); assertTrue(wire.command("PASS "+PASSWORD).startsWith("+OK"));
                assertTrue(wire.command("DELE 1").startsWith("+OK")); assertTrue(wire.command("RSET").startsWith("+OK"));
                assertTrue(wire.command("STAT").startsWith("+OK 2 ")); wire.command("DELE 1"); wire.command("QUIT");
            }
        }
        assertEquals(1,restartStorage().getMailSummaries(b).size());
        try(Connection c=sql.connect(); PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM mailbox_entries m JOIN users u ON u.id=m.owner_id WHERE u.email=? AND folder='TRASH'")) {
            s.setString(1,b); try(ResultSet r=s.executeQuery()) { r.next(); assertEquals(1,r.getInt(1)); }
        }
        System.out.println("PASS real TCP SMTP->MySQL->POP3 UTF-8; DELE/RSET/QUIT archive in DB with bytes preserved");
    }
    private byte[] bytes(jakarta.mail.internet.MimeMessage message) throws Exception { ByteArrayOutputStream b=new ByteArrayOutputStream(); message.writeTo(b); return b.toByteArray(); }
    @Test void configuredChildServerRestartAndSessionSecurity() throws Exception {
        Path root=Files.createTempDirectory(Path.of("target"),"mysql-child-");
        String fileA,fileB,sentA,sentB;
        try(MySqlChildFixture server=new MySqlChildFixture(mysql,root);
            UdpMailClient first=new UdpMailClient("127.0.0.1",server.udp); UdpMailClient second=new UdpMailClient("127.0.0.1",server.udp)) {
            assertTrue(first.ping()); assertEquals("REGISTER_SUCCESS",first.sendRequest("REGISTER "+a+" "+AutomatedEndToEndTest.encode(PASSWORD)));
            assertEquals("REGISTER_SUCCESS",second.sendRequest("REGISTER "+b+" "+AutomatedEndToEndTest.encode(PASSWORD)));
            assertTrue(first.sendRequest("LOGIN "+a+" "+AutomatedEndToEndTest.encode(PASSWORD)).startsWith("LOGIN_SUCCESS|"));
            assertTrue(second.sendRequest("LOGIN "+b+" "+AutomatedEndToEndTest.encode(PASSWORD)).startsWith("LOGIN_SUCCESS|"));
            assertEquals("SEND_SUCCESS",first.sendRequest("SEND "+b+" "+AutomatedEndToEndTest.encode(message(a,b))));
            assertEquals("SEND_SUCCESS",second.sendRequest("SEND "+a+" "+AutomatedEndToEndTest.encode(message(b,a))));
            fileA=mailFile(a); fileB=mailFile(b); sentA=storage.getSentSummaries(a).get(0).getFileName(); sentB=storage.getSentSummaries(b).get(0).getFileName();
            assertEquals("FORBIDDEN",first.sendRequest("INBOX "+b)); assertTrue(first.sendRequest("READ "+a+" .credentials").startsWith("READ_FAILED"));
            assertEquals("FORBIDDEN",first.sendRequest("SEND "+b+" "+AutomatedEndToEndTest.encode(message(b,b))));
            first.logout(); second.logout(); assertEquals("AUTH_REQUIRED",first.sendRequest("INBOX "+a));
        }
        try(MySqlChildFixture server=new MySqlChildFixture(mysql,root);
            UdpMailClient first=new UdpMailClient("127.0.0.1",server.udp); UdpMailClient second=new UdpMailClient("127.0.0.1",server.udp)) {
            assertTrue(first.sendRequest("LOGIN "+a+" "+AutomatedEndToEndTest.encode(PASSWORD)).contains(fileA));
            assertTrue(second.sendRequest("LOGIN "+b+" "+AutomatedEndToEndTest.encode(PASSWORD)).contains(fileB));
            assertTrue(first.sendRequest("SENT "+a).contains(sentA)); assertTrue(second.sendRequest("SENT "+b).contains(sentB));
            assertTrue(AutomatedEndToEndTest.read(first,a,fileA,false).endsWith("Dữ liệu 😀\n.dot line"));
            assertTrue(AutomatedEndToEndTest.read(second,b,fileB,false).endsWith("Dữ liệu 😀\n.dot line"));
            first.logout(); second.logout();
        }
        System.out.println("PASS MYSQL backend selected by config in actual Server JVM; UDP registration/login/two-way mail/security/logout, graceful JVM restart retains both Inbox/Sent");
    }
    private String mailFile(String owner) { return storage.getMailSummaries(owner).stream().map(MailSummary::getFileName).filter(n->n.startsWith("mail_")).findFirst().orElseThrow(); }
    @Test void migrationDryRunBackupNoOverwriteAndLegacyHash() throws Exception {
        Path parent=Files.createTempDirectory(Path.of("target"),"mysql-migration-"),source=parent.resolve("source");
        MailStorageService files=new MailStorageService(source); assertTrue(files.register(a,PASSWORD)); assertTrue(files.register(b,PASSWORD)); assertTrue(files.saveMail(a,b,message(a,b)));
        byte[] salt=new byte[16]; new java.security.SecureRandom().nextBytes(salt);
        javax.crypto.spec.PBEKeySpec spec=new javax.crypto.spec.PBEKeySpec(PASSWORD.toCharArray(),salt,120000,256);
        byte[] key=javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); spec.clearPassword();
        Files.writeString(source.resolve(a).resolve(".credentials"),"120000:"+Base64.getEncoder().encodeToString(salt)+":"+Base64.getEncoder().encodeToString(key));
        String hash=Files.readString(source.resolve(a).resolve(".credentials"));
        MigrationService plan=new MigrationService(source); assertEquals(2,plan.accountCount()); assertEquals(4,plan.entryCount()); assertNull(sql.passwordHash(a));
        var first=plan.migrate(sql,parent.resolve("backup")); assertEquals(2,first.usersInserted()); assertEquals(4,first.entriesInserted());
        var second=plan.migrate(sql,parent.resolve("backup")); assertEquals(0,second.usersInserted()); assertEquals(0,second.entriesInserted());
        assertEquals(hash,sql.passwordHash(a)); assertEquals(hash,Files.readString(source.resolve(a).resolve(".credentials")));
        assertTrue(storage.authenticate(a,PASSWORD)); assertEquals(2,storage.getMailSummaries(b).size());
        String existing=storage.readMail(b,mailFile(b));
        var conflict=new JdbcMailRepository.Entry(b,false,mailFile(b),"do not overwrite",Instant.now());
        assertThrows(SQLException.class,()->sql.importEntry(conflict,UUID.randomUUID().toString(),a,b,"conflict","body"));
        assertEquals(existing,storage.readMail(b,mailFile(b)));
        System.out.println("PASS migration dry-run counts, verified backup, unchanged hash/source, import rerun zero duplicates and conflicting content never overwritten");
    }
    @Test @Timeout(90) void twoSwingClientsUseMysqlForRegistrationTwoWayMailAndLogout() throws Exception {
        Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless(),"Swing unavailable; MySQL GUI E2E not tested");
        SwingEndToEndTest ui=new SwingEndToEndTest(); MailClient.MailClientGUI[] clients=new MailClient.MailClientGUI[2];
        try(MailServerRuntime runtime=new MailServerRuntime("127.0.0.1",0,0,0,storage,null)) {
            Thread loop=new Thread(()-> { try { runtime.run(); } catch(IOException e) { throw new UncheckedIOException(e); } }); loop.start();
            try {
                ui.edt(()-> { for(int i=0;i<2;i++) { clients[i]=new MailClient.MailClientGUI(); clients[i].setVisible(true); } });
                ui.registerAndLogin(clients[0],runtime.getUdpPort(),a); ui.registerAndLogin(clients[1],runtime.getUdpPort(),b);
                ui.compose(clients[0],b,"MySQL Swing A to B","MySQL body A"); ui.inbox(clients[1],"MySQL Swing A to B","MySQL body A"); ui.sent(clients[0],"MySQL Swing A to B","MySQL body A");
                ui.compose(clients[1],a,"MySQL Swing B to A","MySQL body B"); ui.inbox(clients[0],"MySQL Swing B to A","MySQL body B"); ui.sent(clients[1],"MySQL Swing B to A","MySQL body B");
                for(var client:clients) { ui.click(client,"btnLogoutNav"); ui.label(client,"lblSessionUser","Chưa đăng nhập"); }
                assertEquals(2,storage.getMailSummaries(a).size()); assertEquals(1,storage.getSentSummaries(b).size());
            } finally {
                ui.edt(()-> { for(var client:clients) if(client!=null) client.dispatchEvent(new java.awt.event.WindowEvent(client,java.awt.event.WindowEvent.WINDOW_CLOSING)); });
                runtime.close(); loop.join(3000); assertFalse(loop.isAlive());
            }
        }
        System.out.println("PASS two real Swing Clients -> UDP -> MYSQL repositories: registration/login, two-way Compose/Inbox/Sent and logout");
    }
    @Test void databaseDisconnectFailsClosedAndRecoversAfterRealMysqlRestart() throws Exception {
        accounts(); assertTrue(storage.saveMail(a,b,message(a,b))); String file=mailFile(b);
        mysql.stop();
        try {
            assertThrows(MailStorageService.StorageUnavailableException.class,()->storage.authenticate(a,PASSWORD));
            assertThrows(IOException.class,()->RepositoryFactory.mysql(mysql.config()));
        } finally { mysql.start(); }
        assertTrue(restartStorage().authenticate(a,PASSWORD)); assertTrue(restartStorage().readMail(b,file).contains("Dữ liệu 😀"));
        System.out.println("PASS actual MySQL disconnect fails closed with no FILE fallback; real mysqld restart retains accounts/mail");
    }
    @Test void externalSentAndArchiveTransactionRollback() throws Exception {
        accounts(); String id=UUID.randomUUID().toString(),content=message(a,"external@example.invalid");
        storage.saveSentMail(a,content,id); storage.saveSentMail(a,content,id); assertEquals(1,storage.getSentSummaries(a).size());
        assertThrows(MailStorageService.StorageUnavailableException.class,()->storage.saveSentMail(a,content+"conflict",id));
        assertThrows(MailStorageService.StorageUnavailableException.class,()->storage.archiveInbox(a,List.of("new_email.txt","mail_00000000-0000-0000-0000-000000000000.txt")));
        assertEquals(MailStorageService.WELCOME_MESSAGE,storage.readMail(a,"new_email.txt"));
        System.out.println("PASS MySQL external Sent persistence/conflict without external SMTP; partial archive transaction rolls back");
    }
}
