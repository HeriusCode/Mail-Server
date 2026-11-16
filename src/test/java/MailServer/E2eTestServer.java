package MailServer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only child JVM: isolated storage, ephemeral ports, no external SMTP config. */
final class E2eTestServer implements AutoCloseable {
    final Path root;
    final Process process;
    final int udp, smtp, pop;
    private final BufferedReader output;

    E2eTestServer(Path root) throws Exception {
        this.root = root;
        StringBuilder cp = new StringBuilder("target/test-classes" + File.pathSeparator + "target/classes");
        for (Class<?> type : new Class<?>[]{org.springframework.security.crypto.bcrypt.BCrypt.class,
                jakarta.mail.Session.class, jakarta.activation.DataHandler.class,
                org.eclipse.angus.activation.MailcapRegistryProviderImpl.class}) {
            Path jar = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path copy = root.resolve(jar.getFileName());
            if (!Files.exists(copy)) Files.copy(jar, copy);
            cp.append(File.pathSeparator).append(copy);
        }
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", cp.toString(), E2eTestServer.class.getName(), root.toString());
        for (String key : new ArrayList<>(builder.environment().keySet())) {
            if (key.startsWith("SMTP_") || key.startsWith("MAIL_DB_")) builder.environment().remove(key);
        }
        builder.environment().put("MAIL_SMTP_CONFIG", root.resolve("no-external-smtp.properties").toString());
        builder.redirectError(root.resolve("child-stderr-" + UUID.randomUUID() + ".log").toFile());
        process = builder.start();
        output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        ExecutorService waiter = Executors.newSingleThreadExecutor();
        try {
            String ready = waiter.submit(output::readLine).get(20, TimeUnit.SECONDS);
            assertNotNull(ready, "child must publish readiness");
            assertTrue(ready.startsWith("READY "), "child readiness marker");
            String[] ports = ready.split(" ");
            udp = Integer.parseInt(ports[1]); smtp = Integer.parseInt(ports[2]); pop = Integer.parseInt(ports[3]);
            System.out.println("E2E child ready: UDP=" + udp + ", SMTP=" + smtp + ", POP3=" + pop + "; fixture=" + root);
        } catch (Exception | AssertionError e) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            throw e;
        } finally { waiter.shutdownNow(); }
    }

    @Override public void close() throws Exception {
        if (process.isAlive()) {
            process.getOutputStream().write("STOP\n".getBytes(StandardCharsets.US_ASCII));
            process.getOutputStream().flush();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
                fail("test child failed graceful shutdown");
            }
        }
        assertEquals(0, process.exitValue(), "test child exits cleanly");
        assertEquals("STOPPED", output.readLine(), "all listeners close and UDP thread joins");
        try (var files = Files.list(root)) {
            for (Path path : files.filter(p -> p.getFileName().toString().startsWith("child-stderr-")).toList())
                assertEquals("", Files.readString(path), "no child exception/stack trace: " + path);
        }
        output.close();
        System.out.println("PASS E2E graceful shutdown; child stderr empty");
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        UdpMailServer.Listener log = new UdpMailServer.Listener() {
            public void onClientSeen(java.net.InetSocketAddress address) { }
            public void onRequest(java.net.InetSocketAddress address) { }
            public void onLog(String text) {
                try { Files.writeString(root.resolve("server.log"), text + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
                catch (IOException e) { throw new UncheckedIOException(e); }
                if (text.startsWith("Internal request error:") || text.contains("processing failed"))
                    failure.compareAndSet(null, new IllegalStateException("Unexpected server error"));
            }
        };
        Thread loop;
        try (MailServerRuntime runtime = new MailServerRuntime("127.0.0.1", 0, 0, 0,
                new MailStorageService(root.resolve("storage")), log)) {
            loop = new Thread(() -> { try { runtime.run(); } catch (Throwable e) { failure.set(e); } }, "e2e-udp");
            loop.start();
            System.out.println("READY " + runtime.getUdpPort() + " " + runtime.getSmtpPort() + " " + runtime.getPop3Port());
            System.out.flush();
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII)).readLine();
        }
        loop.join(5000);
        if (loop.isAlive()) throw new IllegalStateException("UDP did not stop");
        if (failure.get() != null) throw new IllegalStateException("Server failure", failure.get());
        System.out.println("STOPPED");
    }
}
