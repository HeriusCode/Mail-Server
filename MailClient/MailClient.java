package MailClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Scanner;

public class MailClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 5000;

    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            System.out.print("Nhập IP UDP MailServer (mặc định " + SERVER_HOST + "): ");
            String hostInput = scanner.nextLine().trim();
            String serverHost = hostInput.isEmpty() ? SERVER_HOST : hostInput;
            int serverPort = readPort(scanner);
            try (UdpMailClient client = new UdpMailClient(serverHost, serverPort)) {
                if (!client.ping()) {
                    System.out.println("MailServer trả về phản hồi không hợp lệ.");
                    return;
                }
                System.out.println("Đã xác nhận UDP MailServer tại "
                        + serverHost + ":" + serverPort);
                runMenu(scanner, client);
            }
        } catch (IOException exception) {
            System.out.println("Không thể giao tiếp với UDP MailServer: " + exception.getMessage());
        }
        System.out.println("Đã kết thúc MailClient.");
    }

    private static int readPort(Scanner scanner) {
        while (true) {
            System.out.print("Nhập port UDP MailServer (mặc định " + SERVER_PORT + "): ");
            String value = scanner.nextLine().trim();
            if (value.isEmpty()) return SERVER_PORT;
            try {
                int port = Integer.parseInt(value);
                if (port >= 1 && port <= 65535) return port;
            } catch (NumberFormatException exception) { }
            System.out.println("Port phải là số nguyên từ 1 đến 65535.");
        }
    }

    private static void runMenu(Scanner scanner, UdpMailClient client) throws IOException {
        boolean running = true;
        while (running) {
            printMenu();
            switch (scanner.nextLine().trim()) {
                case "1": register(scanner, client); break;
                case "2": login(scanner, client); break;
                case "3": sendMail(scanner, client); break;
                case "4": running = false; break;
                default: System.out.println("Lựa chọn không hợp lệ.");
            }
        }
    }

    private static void printMenu() {
        System.out.println("\n--- HỘP THƯ UDP ---");
        System.out.println("1. Đăng ký tài khoản");
        System.out.println("2. Đăng nhập (xem danh sách thư)");
        System.out.println("3. Gửi thư");
        System.out.println("4. Thoát");
        System.out.print("Lựa chọn: ");
    }

    private static void register(Scanner scanner, UdpMailClient client) throws IOException {
        System.out.print("Nhập tài khoản Gmail: ");
        System.out.println("Server: " + client.sendRequest("REGISTER " + scanner.nextLine().trim()));
    }

    private static void login(Scanner scanner, UdpMailClient client) throws IOException {
        System.out.print("Nhập tài khoản Gmail: ");
        String response = client.sendRequest("LOGIN " + scanner.nextLine().trim());
        if (!response.startsWith("LOGIN_SUCCESS|")) {
            System.out.println("Server: " + response);
            return;
        }
        String files = response.substring("LOGIN_SUCCESS|".length());
        System.out.println("Đăng nhập thành công. Danh sách thư:");
        if (files.isEmpty()) {
            System.out.println("Không có thư.");
            return;
        }
        String[] fileNames = files.split(",");
        for (int index = 0; index < fileNames.length; index++) {
            System.out.println((index + 1) + ". " + fileNames[index]);
        }
    }

    private static void sendMail(Scanner scanner, UdpMailClient client) throws IOException {
        System.out.print("Nhập Gmail người gửi: ");
        String sender = scanner.nextLine().trim();
        System.out.print("Nhập Gmail người nhận: ");
        String recipient = scanner.nextLine().trim();
        System.out.print("Nhập tiêu đề thư: ");
        String subject = scanner.nextLine().trim();
        System.out.print("Nhập nội dung thư: ");
        String formattedMail = "Người gửi: " + sender + "\n"
                + "Người nhận: " + recipient + "\n"
                + "Tiêu đề: " + subject + "\n\n" + scanner.nextLine();
        String encodedContent = Base64.getEncoder().encodeToString(
                formattedMail.getBytes(StandardCharsets.UTF_8));
        System.out.println("Server: " + client.sendRequest(
                "SEND " + recipient + " " + encodedContent));
    }
}
