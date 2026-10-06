package MailServer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.StringJoiner;

/** Xử lý một lệnh mail độc lập với tầng vận chuyển UDP. */
public class ClientHandler {
    public interface Listener { void onLog(String message); }

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
        if (parts.length == 0 || parts[0].isEmpty()) return "INVALID_COMMAND";
        switch (parts[0]) {
            case "PING": return "PONG";
            case "REGISTER": return handleRegister(parts);
            case "LOGIN": return handleLogin(parts);
            case "SENT": return handleSent(parts);
            case "SEND": return handleSend(parts);
            case "READ": return handleRead(parts, false);
            case "READ_SENT": return handleRead(parts, true);
            default: return "INVALID_COMMAND";
        }
    }

    private String handleRegister(String[] parts) {
        if (parts.length != 2) return "INVALID_COMMAND";
        boolean registered = storageService.register(parts[1]);
        listener.onLog("REGISTER " + parts[1] + ": "
                + (registered ? "đã tạo tài khoản" : "thất bại hoặc đã tồn tại"));
        return registered ? "REGISTER_SUCCESS" : "REGISTER_FAILED: User already exists";
    }

    private String handleLogin(String[] parts) {
        if (parts.length != 2) return "INVALID_COMMAND";
        List<MailStorageService.MailSummary> mails =
                storageService.getMailSummaries(parts[1]);
        if (mails == null) {
            listener.onLog("LOGIN " + parts[1] + ": không tìm thấy tài khoản");
            return "LOGIN_FAILED: User not found";
        }
        listener.onLog("LOGIN " + parts[1] + ": thành công");
        return serializeSummaries("LOGIN_SUCCESS|", mails);
    }

    private String handleSent(String[] parts) {
        if (parts.length != 2) return "INVALID_COMMAND";
        List<MailStorageService.MailSummary> mails =
                storageService.getSentSummaries(parts[1]);
        if (mails == null) return "SENT_FAILED: User not found";
        listener.onLog("SENT " + parts[1] + ": " + mails.size() + " thư");
        return serializeSummaries("SENT_SUCCESS|", mails);
    }

    private String serializeSummaries(String prefix,
            List<MailStorageService.MailSummary> mails) {
        StringJoiner entries = new StringJoiner(",");
        for (MailStorageService.MailSummary mail : mails) {
            String encodedSubject = Base64.getEncoder().encodeToString(
                    mail.getSubject().getBytes(StandardCharsets.UTF_8));
            entries.add(mail.getFileName() + ";" + encodedSubject + ";"
                    + mail.getTimestamp());
        }
        return prefix + entries;
    }

    private String handleSend(String[] parts) {
        if (parts.length != 3) return "INVALID_COMMAND";
        String content;
        try {
            content = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return "INVALID_COMMAND: Invalid Base64 content";
        }
        String sender = extractHeader(content, "Người gửi:");
        boolean saved = storageService.saveMail(sender, parts[1], content);
        listener.onLog("SEND từ " + sender + " tới " + parts[1] + ": "
                + (saved ? "đã lưu thư" : "tài khoản không hợp lệ"));
        return saved ? "SEND_SUCCESS" : "SEND_FAILED: Sender or recipient not found";
    }

    private String handleRead(String[] parts, boolean sent) {
        if (parts.length != 3) return "INVALID_COMMAND";
        String content = sent
                ? storageService.readSentMail(parts[1], parts[2])
                : storageService.readMail(parts[1], parts[2]);
        if (content == null) return "READ_FAILED: Mail not found";
        return "MAIL_CONTENT|" + Base64.getEncoder().encodeToString(
                content.getBytes(StandardCharsets.UTF_8));
    }

    private String extractHeader(String content, String header) {
        for (String line : content.split("\\R")) {
            if (line.startsWith(header)) return line.substring(header.length()).trim();
        }
        return "";
    }
}
