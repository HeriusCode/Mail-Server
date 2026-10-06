package MailServer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.StringJoiner;

/** Xử lý một lệnh mail độc lập với tầng vận chuyển. */
public class ClientHandler {
    public interface Listener {
        void onLog(String message);
    }

    private static final Listener CONSOLE_LISTENER = System.out::println;
    private final MailStorageService storageService;
    private final Listener listener;

    public ClientHandler(MailStorageService storageService) {
        this(storageService, CONSOLE_LISTENER);
    }

    public ClientHandler(MailStorageService storageService, Listener listener) {
        this.storageService = storageService;
        this.listener = listener == null ? CONSOLE_LISTENER : listener;
    }

    public String handleRequest(String request) {
        String[] parts = request.trim().split(" ", 3);
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "INVALID_COMMAND";
        }
        switch (parts[0]) {
            case "PING": return "PONG";
            case "REGISTER": return handleRegister(parts);
            case "LOGIN": return handleLogin(parts);
            case "SEND": return handleSend(parts);
            case "READ": return handleRead(parts);
            default: return "INVALID_COMMAND";
        }
    }

    private String handleRegister(String[] parts) {
        if (parts.length != 2) return "INVALID_COMMAND";
        String username = parts[1];
        boolean registered = storageService.register(username);
        listener.onLog("REGISTER " + username + ": "
                + (registered ? "đã tạo tài khoản" : "thất bại hoặc đã tồn tại"));
        return registered ? "REGISTER_SUCCESS" : "REGISTER_FAILED: User already exists";
    }

    private String handleLogin(String[] parts) {
        if (parts.length != 2) return "INVALID_COMMAND";
        String username = parts[1];
        List<String> mailFiles = storageService.getMailFiles(username);
        if (mailFiles == null) {
            listener.onLog("LOGIN " + username + ": không tìm thấy tài khoản");
            return "LOGIN_FAILED: User not found";
        }
        StringJoiner fileNames = new StringJoiner(",");
        for (String fileName : mailFiles) fileNames.add(fileName);
        listener.onLog("LOGIN " + username + ": thành công");
        return "LOGIN_SUCCESS|" + fileNames;
    }

    private String handleSend(String[] parts) {
        if (parts.length != 3) return "INVALID_COMMAND";
        String content;
        try {
            content = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return "INVALID_COMMAND: Invalid Base64 content";
        }
        boolean saved = storageService.saveMail(parts[1], content);
        listener.onLog("SEND tới " + parts[1] + ": "
                + (saved ? "đã lưu thư" : "không tìm thấy người nhận"));
        return saved ? "SEND_SUCCESS" : "SEND_FAILED: Recipient not found";
    }

    private String handleRead(String[] parts) {
        if (parts.length != 3) return "INVALID_COMMAND";
        String content = storageService.readMail(parts[1], parts[2]);
        if (content == null) return "READ_FAILED: Mail not found";
        return "MAIL_CONTENT|" + Base64.getEncoder().encodeToString(
                content.getBytes(StandardCharsets.UTF_8));
    }
}
