package MailServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class MailServer {
    private static final String DEFAULT_BIND_HOST = "0.0.0.0";
    private static final int PORT = 5000;

    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            System.out.print("Nhập IP bind của UDP server (mặc định "
                    + DEFAULT_BIND_HOST + "): ");
            String host = scanner.nextLine().trim();
            if (host.isEmpty()) host = DEFAULT_BIND_HOST;
            int port = readPort(scanner);
            try (UdpMailServer server = new UdpMailServer(host, port,
                    new MailStorageService(), null)) {
                System.out.println("MailServer UDP đã khởi động tại " + host + ":" + port);
                server.run();
            }
        } catch (IOException exception) {
            System.err.println("MailServer UDP lỗi: " + exception.getMessage());
        }
    }

    private static int readPort(Scanner scanner) {
        while (true) {
            System.out.print("Nhập port UDP server (mặc định " + PORT + "): ");
            String value = scanner.nextLine().trim();
            if (value.isEmpty()) return PORT;
            try {
                int port = Integer.parseInt(value);
                if (port >= 1 && port <= 65535) return port;
            } catch (NumberFormatException exception) { }
            System.out.println("Port phải là số nguyên từ 1 đến 65535.");
        }
    }
}
