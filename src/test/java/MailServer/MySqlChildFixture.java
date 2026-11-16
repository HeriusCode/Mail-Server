package MailServer;

import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Child application JVM configured through the same server-only environment as a real demo. */
final class MySqlChildFixture implements AutoCloseable {
    final Process process;
    final int udp,smtp,pop;
    final BufferedReader output;
    final Path stderr;
    MySqlChildFixture(PrivateMySqlServer mysql,Path root) throws Exception {
        StringBuilder cp=new StringBuilder("target/classes"+File.pathSeparator+"target/test-classes");
        for(Class<?> type:new Class<?>[]{org.springframework.security.crypto.bcrypt.BCrypt.class,jakarta.mail.Session.class,
                jakarta.activation.DataHandler.class,org.eclipse.angus.activation.MailcapRegistryProviderImpl.class,
                com.mysql.cj.jdbc.Driver.class,com.google.protobuf.Message.class}) {
            Path jar=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()); Path copy=root.resolve(jar.getFileName());
            if(!Files.exists(copy)) Files.copy(jar,copy); cp.append(File.pathSeparator).append(copy);
        }
        ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",cp.toString(),MySqlChildFixture.class.getName());
        builder.environment().put("MAIL_STORAGE_BACKEND","MYSQL"); builder.environment().put("MAIL_DB_URL",mysql.url());
        builder.environment().put("MAIL_DB_USER",mysql.user); builder.environment().put("MAIL_DB_PASSWORD",mysql.password);
        builder.environment().put("MAIL_DB_CONFIG",root.resolve("no-local-db.properties").toString());
        builder.environment().put("SMTP_HOST",""); builder.environment().put("MAIL_SMTP_CONFIG",root.resolve("no-smtp.properties").toString());
        stderr=root.resolve("child-"+UUID.randomUUID()+".stderr.log"); builder.redirectError(stderr.toFile());
        process=builder.start(); output=new BufferedReader(new InputStreamReader(process.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));
        ExecutorService waiter=Executors.newSingleThreadExecutor();
        try {
            String ready=waiter.submit(output::readLine).get(15,TimeUnit.SECONDS);
            assertNotNull(ready,"MySQL child readiness"); assertTrue(ready.startsWith("READY MYSQL "),"MySQL child startup");
            String[] fields=ready.split(" "); udp=Integer.parseInt(fields[2]); smtp=Integer.parseInt(fields[3]); pop=Integer.parseInt(fields[4]);
        } catch(Exception | AssertionError e) { process.destroy(); if(!process.waitFor(5,TimeUnit.SECONDS)) process.destroyForcibly(); throw e; }
        finally { waiter.shutdownNow(); }
    }
    public void close() throws Exception {
        if(process.isAlive()) {
            process.getOutputStream().write("STOP\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); process.getOutputStream().flush();
            if(!process.waitFor(10,TimeUnit.SECONDS)) { process.destroy(); fail("MySQL application child shutdown timeout"); }
        }
        assertEquals(0,process.exitValue()); assertEquals("STOPPED",output.readLine()); assertEquals("",Files.readString(stderr)); output.close();
    }
    public static void main(String[] args) throws Exception {
        MailStorageService storage=new MailStorageService(); if(!storage.backendName().equals("MySQL repositories")) throw new IllegalStateException("Wrong backend");
        UdpMailServer.Listener quiet=new UdpMailServer.Listener() {
            public void onClientSeen(java.net.InetSocketAddress a) {} public void onRequest(java.net.InetSocketAddress a) {} public void onLog(String message) {}
        };
        Thread loop;
        try(MailServerRuntime runtime=new MailServerRuntime("127.0.0.1",0,0,0,storage,quiet)) {
            loop=new Thread(()-> { try { runtime.run(); } catch(IOException e) { throw new UncheckedIOException(e); } }); loop.start();
            System.out.println("READY MYSQL "+runtime.getUdpPort()+" "+runtime.getSmtpPort()+" "+runtime.getPop3Port()); System.out.flush();
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        }
        loop.join(3000); if(loop.isAlive()) throw new IllegalStateException("Child UDP still running"); System.out.println("STOPPED");
    }
}
