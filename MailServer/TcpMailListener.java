package MailServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Bounded, loopback-only protocol listener for the local demo. */
final class TcpMailListener implements AutoCloseable {
    interface Handler { void handle(Wire wire) throws Exception; }
    private final ServerSocket server;
    private final Set<Socket> active = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), r -> { Thread t = new Thread(r, "mail-protocol-worker"); t.setDaemon(true); return t; });
    private final Thread acceptor;
    TcpMailListener(int port, Handler handler, Consumer<String> log, String name) throws IOException {
        server = new ServerSocket(); server.bind(new InetSocketAddress("127.0.0.1", port));
        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept(); socket.setSoTimeout(30_000); socket.setTcpNoDelay(true); active.add(socket);
                    try { workers.execute(() -> {
                        try (socket) { handler.handle(new Wire(socket)); }
                        catch (Exception e) { if (!server.isClosed()) log.accept(name + " session ended: " + e.getClass().getSimpleName()); }
                        finally { active.remove(socket); }
                    }); } catch (RejectedExecutionException e) { active.remove(socket); socket.close(); }
                } catch (IOException e) { if (!server.isClosed()) log.accept(name + " accept failed"); }
            }
        }, name + "-acceptor");
        acceptor.setDaemon(true); acceptor.start();
        log.accept(name + " listening 127.0.0.1:" + port());
    }
    int port() { return server.getLocalPort(); }
    @Override public void close() {
        try { server.close(); } catch (IOException ignored) { }
        for (Socket socket : active) try { socket.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
        try { acceptor.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    static final class Wire {
        final InputStream in; final OutputStream out;
        Wire(Socket socket) throws IOException { in = new BufferedInputStream(socket.getInputStream()); out = new BufferedOutputStream(socket.getOutputStream()); }
        String read(int limit) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int b; (b = in.read()) != -1;) {
                if (b == '\n') {
                    byte[] value = bytes.toByteArray();
                    if (value.length == 0 || value[value.length - 1] != '\r') throw new IOException("CRLF required");
                    return new String(value, 0, value.length - 1, StandardCharsets.UTF_8);
                }
                if (bytes.size() >= limit) throw new IOException("Line too long"); bytes.write(b);
            }
            return null;
        }
        void line(String value) throws IOException { out.write((value + "\r\n").getBytes(StandardCharsets.UTF_8)); out.flush(); }
        void multiline(byte[] bytes) throws IOException {
            String text = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = text.split("\r\n", -1);
            int count = text.endsWith("\r\n") ? lines.length - 1 : lines.length;
            for (int i = 0; i < count; i++) line((lines[i].startsWith(".") ? "." : "") + lines[i]);
            line(".");
        }
    }
}
