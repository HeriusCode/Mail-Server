package MailClient;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** UDP request/response client có timeout, retry và request ID. */
public class UdpMailClient implements Closeable {
    public static final int MAX_PACKET_SIZE = 60_000;
    private static final int TIMEOUT_MILLIS = 2_000;
    private static final int MAX_ATTEMPTS = 3;
    private final DatagramSocket socket;
    private String sessionToken;
    private String sessionOwner;

    public UdpMailClient(String host, int port) throws IOException {
        socket = new DatagramSocket();
        socket.connect(InetAddress.getByName(host), port);
        socket.setSoTimeout(TIMEOUT_MILLIS);
    }

    public synchronized String sendRequest(String request) throws IOException {
        boolean login = request.startsWith("LOGIN ");
        if (login) { logout(); }
        String wireRequest = request;
        if (request.matches("^(INBOX|SENT|READ|READ_SENT|SEND|LOGOUT)( .*)?$")) {
            if (sessionToken == null) return "AUTH_REQUIRED";
            wireRequest = "AUTH " + sessionToken + " " + request;
        }
        String response = exchange(wireRequest);
        if (login && response.startsWith("LOGIN_SUCCESS|")) {
            String[] fields = response.split("\\|", 3);
            if (fields.length != 3 || !fields[1].matches("[A-Za-z0-9_-]{43}"))
                throw new IOException("Invalid authentication response");
            sessionToken = fields[1];
            sessionOwner = request.split(" ", 3)[1].trim().toLowerCase(java.util.Locale.ROOT);
            // Keep the previous response shape for the existing Swing/console callers.
            return "LOGIN_SUCCESS|" + fields[2];
        }
        if (response.equals("AUTH_REQUIRED") || request.equals("LOGOUT")) {
            sessionToken = null; sessionOwner = null;
        }
        return response;
    }

    private String exchange(String request) throws IOException {
        // SMTP submission may include TLS/authentication and take longer than a local file operation.
        int responseTimeout = request.contains(" SEND ") ? 30_000 : TIMEOUT_MILLIS;
        String requestId = UUID.randomUUID().toString();
        byte[] requestBytes = (requestId + "|" + request).getBytes(StandardCharsets.UTF_8);
        if (requestBytes.length > MAX_PACKET_SIZE) {
            throw new IOException("Yêu cầu vượt quá giới hạn 60 KB của ứng dụng UDP");
        }
        DatagramPacket requestPacket = new DatagramPacket(requestBytes, requestBytes.length);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            socket.send(requestPacket);
            try {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(responseTimeout);
                while (System.nanoTime() < deadline) {
                    socket.setSoTimeout(Math.max(1, (int) java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                    byte[] responseBuffer = new byte[MAX_PACKET_SIZE];
                    DatagramPacket responsePacket = new DatagramPacket(
                            responseBuffer, responseBuffer.length);
                    socket.receive(responsePacket);
                    String response = new String(responsePacket.getData(),
                            responsePacket.getOffset(), responsePacket.getLength(),
                            StandardCharsets.UTF_8);
                    String prefix = requestId + "|";
                    if (response.startsWith(prefix)) return response.substring(prefix.length());
                }
            } catch (SocketTimeoutException exception) {
                // Gửi lại cùng request ID để server có thể chống xử lý trùng.
            }
        }
        throw new SocketTimeoutException(
                "MailServer không phản hồi sau " + MAX_ATTEMPTS + " lần thử");
    }

    public boolean ping() throws IOException { return "PONG".equals(sendRequest("PING")); }
    public synchronized boolean isAuthenticated() { return sessionToken != null; }
    public synchronized String owner() { return sessionOwner; }
    public synchronized void logout() throws IOException {
        String token = sessionToken;
        sessionToken = null; sessionOwner = null;
        if (token != null && !socket.isClosed()) exchange("AUTH " + token + " LOGOUT");
    }
    public boolean isClosed() { return socket.isClosed(); }
    // Explicit logout is used by UI/console. close() must unblock a pending receive immediately.
    @Override public void close() { socket.close(); }
}
