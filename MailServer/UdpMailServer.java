package MailServer;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UdpMailServer implements Closeable {
    public static final int MAX_PACKET_SIZE = 60_000;
    private static final long CACHE_TTL_MILLIS = 120_000;

    public interface Listener {
        void onClientSeen(InetSocketAddress address);
        void onRequest(InetSocketAddress address);
        void onLog(String message);
    }

    private static final Listener CONSOLE_LISTENER = new Listener() {
        public void onClientSeen(InetSocketAddress address) { }
        public void onRequest(InetSocketAddress address) { }
        public void onLog(String message) { System.out.println(message); }
    };

    private final DatagramSocket socket;
    private final ClientHandler handler;
    private final Listener listener;
    private final ExecutorService workers = new java.util.concurrent.ThreadPoolExecutor(2, 4,
            30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(128));
    private final Map<String, CachedResponse> responseCache = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public UdpMailServer(String host, int port, MailStorageService storageService,
            Listener listener) throws IOException {
        this(host, port, storageService, listener, new SessionManager());
    }

    public UdpMailServer(String host, int port, MailStorageService storageService,
            Listener listener, SessionManager sessions) throws IOException {
        this.listener = listener == null ? CONSOLE_LISTENER : listener;
        this.handler = new ClientHandler(storageService, this.listener::onLog, sessions);
        this.socket = new DatagramSocket(new InetSocketAddress(
                InetAddress.getByName(host), port));
        this.listener.onLog("Storage: " + storageService.backendName());
    }

    public void run() throws IOException {
        listener.onLog("UDP server đang lắng nghe tại "
                + socket.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort());
        while (running) {
            byte[] buffer = new byte[MAX_PACKET_SIZE + 1];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketException exception) {
                if (!running || socket.isClosed()) break;
                throw exception;
            }
            if (packet.getLength() > MAX_PACKET_SIZE) continue;
            byte[] requestBytes = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), packet.getOffset(), requestBytes, 0,
                    packet.getLength());
            InetSocketAddress client = new InetSocketAddress(packet.getAddress(), packet.getPort());
            listener.onClientSeen(client);
            try { workers.submit(() -> processPacket(client, requestBytes)); }
            catch (java.util.concurrent.RejectedExecutionException e) { send(client, "0|SERVER_BUSY"); }
        }
    }

    private void processPacket(InetSocketAddress client, byte[] requestBytes) {
        String envelope;
        try {
            envelope = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(requestBytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) { send(client, "0|INVALID_PACKET"); return; }
        int separator = envelope.indexOf('|');
        if (separator <= 0 || separator == envelope.length() - 1) {
            send(client, "0|INVALID_PACKET");
            return;
        }
        String requestId = envelope.substring(0, separator);
        if (!requestId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            send(client, "0|INVALID_PACKET"); return;
        }
        String request = envelope.substring(separator + 1);
        listener.onRequest(client);
        // Never cache authenticated responses: every replay must recheck expiry/revocation.
        if (!request.startsWith("REGISTER ")) {
            send(client, requestId + "|" + safeHandle(request, requestId));
            return;
        }
        String cacheKey = client + "|" + requestId;
        String fingerprint;
        try {
            fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(requestBytes));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        synchronized (responseCache) {
        long now = System.currentTimeMillis();
        CachedResponse cached = responseCache.get(cacheKey);
        if (cached != null && now - cached.createdAt < CACHE_TTL_MILLIS) {
            send(client, cached.fingerprint.equals(fingerprint) ? cached.payload : requestId + "|REQUEST_CONFLICT");
            return;
        }
        String response = requestId + "|" + safeHandle(request, requestId);
        if (responseCache.size() > 1_000) {
            responseCache.entrySet().removeIf(entry ->
                    now - entry.getValue().createdAt >= CACHE_TTL_MILLIS);
        }
        if (responseCache.size() < 5_000) responseCache.put(cacheKey, new CachedResponse(response, now, fingerprint));
        send(client, response);
        }
    }

    private String safeHandle(String request, String requestId) {
        try { return handler.handleRequest(request, requestId); }
        catch (RuntimeException | LinkageError e) {
            listener.onLog("Internal request error: " + e.getClass().getSimpleName());
            return "SERVER_ERROR";
        }
    }

    private void send(InetSocketAddress client, String response) {
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        if (responseBytes.length > MAX_PACKET_SIZE) {
            int separator = response.indexOf('|');
            String requestId = separator > 0 ? response.substring(0, separator) : "0";
            responseBytes = (requestId + "|RESPONSE_TOO_LARGE")
                    .getBytes(StandardCharsets.UTF_8);
        }
        try {
            synchronized (socket) {
                if (!socket.isClosed()) {
                    socket.send(new DatagramPacket(responseBytes, responseBytes.length, client));
                }
            }
        } catch (IOException exception) {
            if (running) listener.onLog("Không thể gửi phản hồi tới " + client + ": "
                    + exception.getMessage());
        }
    }

    public int getPort() {
        return socket.getLocalPort();
    }

    @Override
    public void close() {
        running = false;
        socket.close();
        workers.shutdownNow();
    }

    private static final class CachedResponse {
        private final String payload;
        private final long createdAt;
        private final String fingerprint;
        private CachedResponse(String payload, long createdAt, String fingerprint) {
            this.payload = payload;
            this.createdAt = createdAt;
            this.fingerprint = fingerprint;
        }
    }
}
