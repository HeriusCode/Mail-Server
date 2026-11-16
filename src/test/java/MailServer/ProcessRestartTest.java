package MailServer;

import MailClient.UdpMailClient;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessRestartTest {
    private static String encode(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    private Process start(Path root) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        // Relative project paths avoid native Windows launcher mangling Unicode classpaths.
        StringBuilder cp = new StringBuilder("target/test-classes" + File.pathSeparator + "target/classes");
        for (Class<?> type : new Class<?>[]{org.springframework.security.crypto.bcrypt.BCrypt.class, jakarta.mail.Session.class, jakarta.activation.DataHandler.class}) {
            Path source = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path jar = root.resolve(source.getFileName()); if (!Files.exists(jar)) Files.copy(source, jar);
            cp.append(File.pathSeparator).append(jar);
        }
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", cp.toString(), "MailServer.RestartFixtureMain", root.toString()).redirectErrorStream(true);
        builder.environment().put("MAIL_DB_URL", "disabled-invalid-database-url");
        return builder.start();
    }
    private int port(Process process) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            String ready = pool.submit(() -> new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).readLine()).get(10, TimeUnit.SECONDS);
            assertTrue(ready != null && ready.startsWith("READY "), "child process starts: " + ready);
            return Integer.parseInt(ready.substring(6));
        } finally { pool.shutdownNow(); }
    }
    private void stop(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
    }
    @Test void actualJvmRestartRetainsAccountsAndMail() throws Exception {
        Path root = Files.createTempDirectory(Path.of("target"), "process-restart-");
        String a = "process.a@gmail.com", b = "process.b@gmail.com", password = encode("synthetic-password");
        Process first = start(root);
        try (UdpMailClient client = new UdpMailClient("127.0.0.1", port(first))) {
            assertEquals("REGISTER_SUCCESS", client.sendRequest("REGISTER " + a + " " + password));
            assertEquals("REGISTER_SUCCESS", client.sendRequest("REGISTER " + b + " " + password));
            assertTrue(client.sendRequest("LOGIN " + a + " " + password).startsWith("LOGIN_SUCCESS|"));
            String content = "Người gửi: " + a + "\nNgười nhận: " + b + "\nTiêu đề: Persisted\n\nPersisted body";
            assertEquals("SEND_SUCCESS", client.sendRequest("SEND " + b + " " + encode(content)));
        } catch (Exception e) {
            System.out.println("Child diagnostic: " + Files.readString(root.resolve("child-diagnostic.log")));
            throw e;
        } finally { stop(first); }
        Process second = start(root);
        try (UdpMailClient client = new UdpMailClient("127.0.0.1", port(second))) {
            assertTrue(client.sendRequest("LOGIN " + b + " " + password).contains("mail_"));
            assertTrue(client.sendRequest("INBOX " + b).contains("mail_"));
            String filename;
            try (var files = Files.list(root.resolve(b))) { filename = files.filter(p -> p.getFileName().toString().startsWith("mail_")).findFirst().orElseThrow().getFileName().toString(); }
            assertTrue(client.sendRequest("READ " + b + " " + filename).startsWith("MAIL_CONTENT|"));
            System.out.println("PASS real child JVM stopped/restarted; credentials, listing and mail remain readable");
        } finally { stop(second); }
    }
}
