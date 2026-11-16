package MailServer;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real TLS/authentication against loopback-only SMTP peers, never Gmail. */
class GmailSmtpIntegrationTest {
    private static Path certificateRoot,trustStore;
    private static SSLContext serverTls;
    private static final String STORE_PASSWORD="synthetic-keystore-test-only";
    @BeforeAll static void certificates() throws Exception {
        certificateRoot=Files.createTempDirectory(Path.of("target"),"step3-tls-");
        Path key=certificateRoot.resolve("server.p12");
        keytool("-genkeypair","-alias","server","-keyalg","RSA","-keysize","2048","-validity","2",
                "-dname","CN=localhost","-ext","SAN=dns:localhost","-storetype","PKCS12","-keystore",key.toString(),
                "-storepass:env","MAILFLOW_TEST_STORE_PASSWORD","-keypass:env","MAILFLOW_TEST_STORE_PASSWORD","-noprompt");
        KeyStore keys=KeyStore.getInstance("PKCS12");
        try(InputStream input=Files.newInputStream(key)) { keys.load(input,STORE_PASSWORD.toCharArray()); }
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys,STORE_PASSWORD.toCharArray());
        serverTls=SSLContext.getInstance("TLS"); serverTls.init(km.getKeyManagers(),null,new SecureRandom());
        // Public test certificate only; no global/default JVM or Windows trust store is changed.
        KeyStore trust=KeyStore.getInstance("JKS"); trust.load(null,null); trust.setCertificateEntry("test-server",keys.getCertificate("server"));
        trustStore=certificateRoot.resolve("public-test-trust.jks");
        try(OutputStream output=Files.newOutputStream(trustStore)) { trust.store(output,STORE_PASSWORD.toCharArray()); }
    }
    private static void keytool(String... arguments) throws Exception {
        List<String> command=new ArrayList<>(); command.add(Path.of(System.getProperty("java.home"),"bin","keytool.exe").toString()); command.addAll(List.of(arguments));
        ProcessBuilder builder=new ProcessBuilder(command); builder.environment().put("MAILFLOW_TEST_STORE_PASSWORD",STORE_PASSWORD);
        builder.redirectErrorStream(true).redirectOutput(certificateRoot.resolve("keytool.log").toFile());
        Process process=builder.start();
        if(!process.waitFor(20,TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Test certificate generation timed out"); }
        assertEquals(0,process.exitValue(),"test certificate generation");
    }
    @Test void gmailConfigurationAndStrictSecurityFlags() throws Exception {
        Path fixture=Files.createTempDirectory(Path.of("target"),"step3-config-"); Path file=fixture.resolve("smtp.properties");
        assertNull(SmtpSettings.load(file,Map.of()));
        Map<String,String> values=new HashMap<>(Map.of("SMTP_HOST","smtp.gmail.com","SMTP_USER","synthetic.sender@gmail.com",
                "SMTP_PASSWORD",ExternalSmtpProbeMain.PASSWORD,"SMTP_FROM","synthetic.sender@gmail.com","SMTP_PORT","587"));
        SmtpSettings settings=SmtpSettings.load(file,values); assertNotNull(settings);
        Properties mail=settings.mailProperties();
        assertEquals("true",mail.getProperty("mail.smtp.starttls.required")); assertEquals("true",mail.getProperty("mail.smtp.ssl.checkserveridentity"));
        assertNull(mail.getProperty("mail.smtp.ssl.trust")); assertEquals("synthetic.sender@gmail.com",mail.getProperty("mail.smtp.from"));
        assertThrows(IllegalArgumentException.class,()->new SmtpSettings("smtp.gmail.com",587,"synthetic.sender@gmail.com","x","other@gmail.com",true,true,false));
        for(String flag:List.of("SMTP_AUTH","SMTP_STARTTLS","SMTP_SSL")) {
            Map<String,String> bad=new HashMap<>(values); bad.put(flag,"typo"); assertThrows(IllegalArgumentException.class,()->SmtpSettings.load(file,bad));
        }
        for(String name:List.of("SMTP_AUTH","SMTP_STARTTLS")) {
            Map<String,String> bad=new HashMap<>(values); bad.put(name,"false"); assertThrows(IllegalArgumentException.class,()->SmtpSettings.load(file,bad));
        }
        assertThrows(IllegalArgumentException.class,()->new SmtpSettings("smtp.gmail.com",587,"synthetic.sender@gmail.com","x","synthetic.sender@gmail.com",true,true,true));
        assertThrows(IllegalArgumentException.class,()->new SmtpSettings("remote.example.invalid",587,"u","x","u@example.invalid",true,false,false));
        assertThrows(IllegalArgumentException.class,()->new SmtpSettings("smtp.gmail.com",0,"u","x","u@example.invalid",true,true,false));
        assertThrows(IllegalArgumentException.class,()->new SmtpSettings("smtp.gmail.com",587,"u","","u@example.invalid",true,true,false));
        values.put("SMTP_PORT","465"); values.put("SMTP_SSL","true"); values.put("SMTP_STARTTLS","false"); assertTrue(SmtpSettings.load(file,values).ssl);
        // Synthetic file only: verify defaults and env precedence without inspecting real config.
        Properties synthetic=new Properties(); synthetic.setProperty("SMTP_HOST","smtp.gmail.com"); synthetic.setProperty("SMTP_USER","synthetic.sender@gmail.com");
        synthetic.setProperty("SMTP_PASSWORD",ExternalSmtpProbeMain.PASSWORD);
        try(Writer writer=Files.newBufferedWriter(file)) { synthetic.store(writer,"synthetic fixture only"); }
        assertEquals("synthetic.sender@gmail.com",SmtpSettings.load(file,Map.of()).from);
        assertEquals(465,SmtpSettings.load(file,Map.of("SMTP_PORT","465","SMTP_SSL","true","SMTP_STARTTLS","false")).port);
        System.out.println("PASS Gmail 587 STARTTLS /465 TLS config, auth required, From=user, strict flags, hostname verification and isolated precedence");
    }
    @Test void starttlsAuthenticationUtf8HeadersSentAndRetry() throws Exception { success(false,"accept",false); }
    @Test void implicitTlsAuthenticationUtf8HeadersSentAndRetry() throws Exception { success(true,"accept",false); }
    @Test void acceptedDataSurvivesQuitConnectionFailure() throws Exception { success(false,"quit-drop",false); }
    @Test void acceptedDataWithSentFailureIsNotResubmitted() throws Exception { success(false,"accept",true); }
    private void success(boolean ssl,String mode,boolean failSent) throws Exception {
        try(Peer peer=new Peer(ssl,mode)) {
            String result=probe(peer,"localhost",true,failSent);
            assertTrue(result.contains("RESULT "+(failSent?"SMTP_ACCEPTED_SENT_UNSAVED":"SMTP_ACCEPTED")+"\n"),result);
            assertTrue(result.contains("SENT_COUNT "+(failSent?0:1)),result);
            assertEquals(1,peer.auth.get()); assertEquals(1,peer.transactions.get()); assertNotNull(peer.data);
            MimeMessage message=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(peer.data));
            assertEquals(ExternalSmtpProbeMain.USER,((InternetAddress)message.getFrom()[0]).getAddress());
            assertEquals(ExternalSmtpProbeMain.OWNER,((InternetAddress)message.getReplyTo()[0]).getAddress());
            assertEquals(ExternalSmtpProbeMain.RECIPIENT,((InternetAddress)message.getAllRecipients()[0]).getAddress());
            assertEquals(ExternalSmtpProbeMain.SUBJECT,message.getSubject());
            assertEquals(ExternalSmtpProbeMain.BODY,((String)message.getContent()).replace("\r\n","\n"));
            assertEquals("MAIL FROM:<"+ExternalSmtpProbeMain.USER+">",peer.mailFrom);
            assertTrue(peer.tlsEstablished); assertTrue(peer.authAfterTls);
        }
        System.out.println("PASS real "+(ssl?"implicit TLS":"STARTTLS")+" + AUTH, UTF-8, envelope/From/Reply-To, "+mode+", Sent="+!failSent+", same-ID retry submitted once");
    }
    @Test void rejectsUntrustedCertificateWithoutAuthentication() throws Exception { tlsFailure("localhost",false,"accept"); }
    @Test void rejectsWrongCertificateHostnameWithoutAuthentication() throws Exception { tlsFailure("127.0.0.1",true,"accept"); }
    @Test void requiresStarttlsBeforeSendingCredentials() throws Exception { tlsFailure("localhost",true,"no-starttls"); }
    private void tlsFailure(String host,boolean trusted,String mode) throws Exception {
        try(Peer peer=new Peer(false,mode.equals("accept")?"certificate-reject":mode)) {
            String result=probe(peer,host,trusted,false); assertTrue(result.contains("RESULT SEND_FAILED: SMTP_TLS_FAILED"),result);
            assertEquals(0,peer.auth.get()); assertEquals(0,peer.transactions.get()); assertTrue(result.contains("SENT_COUNT 0"));
        }
        System.out.println("PASS TLS fail-closed: "+mode+", trust="+trusted+", hostname="+host+"; zero AUTH/DATA");
    }
    @Test void authenticationFailureIsDistinctAndNoSent() throws Exception { failure("auth-reject","SEND_FAILED: SMTP_AUTH_FAILED",0); }
    @Test void smtpRejectionIsDistinctAndNoSent() throws Exception { failure("reject","SEND_FAILED: SMTP_REJECTED",0); }
    @Test void dataRejectionIsDistinctAndNoSent() throws Exception { failure("data-reject","SEND_FAILED: SMTP_REJECTED",1); }
    @Test void missingDataAcknowledgementIsUncertainAndNotRetried() throws Exception { failure("data-drop","SEND_UNCERTAIN: SMTP_DELIVERY_UNCERTAIN",1); }
    @Test void dataResponseTimeoutIsUncertainAndNotRetried() throws Exception { failure("data-timeout","SEND_UNCERTAIN: SMTP_TIMEOUT",1); }
    @Test void greetingTimeoutIsDefiniteAndNotRetried() throws Exception { failure("greeting-timeout","SEND_FAILED: SMTP_TIMEOUT",0); }
    private void failure(String mode,String expected,int transactions) throws Exception {
        try(Peer peer=new Peer(false,mode)) {
            String result=probe(peer,"localhost",true,false); assertTrue(result.contains("RESULT "+expected),result);
            assertTrue(result.contains("SENT_COUNT 0"),result); assertEquals(transactions,peer.transactions.get());
        }
        System.out.println("PASS "+mode+": "+expected+"; no false Sent and same-ID retry does not resubmit");
    }
    private String probe(Peer peer,String host,boolean trusted,boolean failSent) throws Exception {
        Path root=Files.createTempDirectory(Path.of("target"),"step3-probe-");
        StringBuilder cp=new StringBuilder("target/test-classes"+File.pathSeparator+"target/classes");
        for(Class<?> type:new Class<?>[]{org.springframework.security.crypto.bcrypt.BCrypt.class,Session.class,jakarta.activation.DataHandler.class,org.eclipse.angus.activation.MailcapRegistryProviderImpl.class}) {
            Path jar=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()); Path copy=root.resolve(jar.getFileName()); Files.copy(jar,copy); cp.append(File.pathSeparator).append(copy);
        }
        List<String> args=new ArrayList<>(); args.add(Path.of(System.getProperty("java.home"),"bin","java.exe").toString());
        if(trusted) { args.add("-Djavax.net.ssl.trustStore="+trustStore); args.add("-Djavax.net.ssl.trustStoreType=JKS"); }
        args.addAll(List.of("-cp",cp.toString(),ExternalSmtpProbeMain.class.getName(),root.toString(),host,Integer.toString(peer.port()),peer.ssl?"ssl":"starttls",failSent?"fail-sent":"normal"));
        ProcessBuilder builder=new ProcessBuilder(args); builder.redirectOutput(root.resolve("result.txt").toFile()); builder.redirectError(root.resolve("stderr.txt").toFile());
        Process process=builder.start();
        if(!process.waitFor(25,TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Loopback SMTP probe timed out"); }
        assertEquals(0,process.exitValue(),"probe JVM; inspect "+root.resolve("stderr.txt"));
        assertEquals("",Files.readString(root.resolve("stderr.txt")),"no unexpected probe stack trace");
        peer.finished.get(10,TimeUnit.SECONDS);
        return Files.readString(root.resolve("result.txt")).replace("\r\n","\n");
    }
    static final class Peer implements AutoCloseable {
        final ServerSocket listener; final ExecutorService worker=Executors.newSingleThreadExecutor(); final Future<?> finished;
        final boolean ssl; final String mode; final AtomicInteger auth=new AtomicInteger(),transactions=new AtomicInteger();
        volatile boolean tlsEstablished,authAfterTls; volatile byte[] data; volatile String mailFrom; volatile Socket active;
        Peer(boolean ssl,String mode) throws Exception {
            this.ssl=ssl; this.mode=mode; listener=new ServerSocket(); listener.bind(new InetSocketAddress("127.0.0.1",0)); listener.setSoTimeout(15000);
            finished=worker.submit(()-> { try { serve(); } catch(IOException e) { if(!mode.equals("certificate-reject")) throw new RuntimeException(e); }
                catch(Exception e) { throw new RuntimeException(e); } });
        }
        int port() { return listener.getLocalPort(); }
        private Socket tls(Socket socket) throws Exception {
            SSLSocket secure=(SSLSocket)serverTls.getSocketFactory().createSocket(socket,"localhost",socket.getPort(),true);
            secure.setUseClientMode(false); secure.setSoTimeout(10000); secure.startHandshake(); tlsEstablished=true; return secure;
        }
        private void serve() throws Exception {
            try(Socket accepted=listener.accept()) {
                active=accepted; accepted.setSoTimeout(10000); Socket socket=ssl?tls(accepted):accepted;
                BufferedReader in=reader(socket); BufferedWriter out=writer(socket);
                if(mode.equals("greeting-timeout")) { new CountDownLatch(1).await(6500,TimeUnit.MILLISECONDS); return; }
                line(out,"220 loopback test SMTP");
                for(String command;(command=in.readLine())!=null;) {
                    if(command.startsWith("EHLO")) {
                        line(out,"250-localhost"); if(!tlsEstablished && !mode.equals("no-starttls")) line(out,"250-STARTTLS"); line(out,"250 AUTH PLAIN");
                    } else if(command.equals("STARTTLS")) {
                        line(out,"220 Begin TLS"); socket=tls(socket); active=socket; in=reader(socket); out=writer(socket);
                    } else if(command.startsWith("AUTH PLAIN ")) {
                        auth.incrementAndGet(); authAfterTls=tlsEstablished;
                        String[] fields=new String(Base64.getDecoder().decode(command.substring(11)),StandardCharsets.UTF_8).split("\u0000",-1);
                        if(fields.length!=3 || !(fields[0].isEmpty() || fields[0].equals(ExternalSmtpProbeMain.USER))
                                || !fields[1].equals(ExternalSmtpProbeMain.USER) || !fields[2].equals(ExternalSmtpProbeMain.PASSWORD))
                            throw new IllegalStateException("Unexpected synthetic AUTH");
                        line(out,mode.equals("auth-reject")?"535 Invalid credentials":"235 Authenticated");
                    } else if(command.startsWith("MAIL FROM:")) { mailFrom=command; line(out,"250 Sender OK"); }
                    else if(command.startsWith("RCPT TO:")) line(out,mode.equals("reject")?"550 Recipient rejected":"250 Recipient OK");
                    else if(command.equals("DATA")) {
                        line(out,"354 Send data"); ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                        for(String value;(value=in.readLine())!=null && !value.equals(".");) {
                            if(value.startsWith("..")) value=value.substring(1); bytes.write((value+"\r\n").getBytes(StandardCharsets.UTF_8));
                        }
                        data=bytes.toByteArray(); transactions.incrementAndGet();
                        if(mode.equals("data-drop")) return;
                        if(mode.equals("data-timeout")) { new CountDownLatch(1).await(6500,TimeUnit.MILLISECONDS); return; }
                        line(out,mode.equals("data-reject")?"550 Message rejected":"250 Message accepted");
                    } else if(command.equals("QUIT")) { if(!mode.equals("quit-drop")) line(out,"221 Bye"); return; }
                    else line(out,"250 OK");
                }
            }
        }
        private BufferedReader reader(Socket s) throws Exception { return new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8)); }
        private BufferedWriter writer(Socket s) throws Exception { return new BufferedWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8)); }
        private void line(BufferedWriter out,String value) throws Exception { out.write(value+"\r\n"); out.flush(); }
        public void close() throws Exception { listener.close(); if(active!=null) active.close(); worker.shutdownNow(); assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS)); }
    }
}
