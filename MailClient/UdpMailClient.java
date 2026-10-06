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

    public UdpMailClient(String host, int port) throws IOException {
        socket = new DatagramSocket();
        socket.connect(InetAddress.getByName(host), port);
        socket.setSoTimeout(TIMEOUT_MILLIS);
    }

    public synchronized String sendRequest(String request) throws IOException {
        String requestId = UUID.randomUUID().toString();
        byte[] requestBytes = (requestId + "|" + request).getBytes(StandardCharsets.UTF_8);
        if (requestBytes.length > MAX_PACKET_SIZE) {
            throw new IOException("Yêu cầu vượt quá giới hạn 60 KB của ứng dụng UDP");
        }
        DatagramPacket requestPacket = new DatagramPacket(requestBytes, requestBytes.length);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            socket.send(requestPacket);
            try {
                while (true) {
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
    public boolean isClosed() { return socket.isClosed(); }
    @Override public void close() { socket.close(); }
}
