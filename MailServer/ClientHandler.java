package MailServer;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

/** Protocol boundary: identity fields must agree with the server session. */
public class ClientHandler {
    public interface Listener { void onLog(String message); }
    private final MailStorageService storage;
    private final Listener listener;
    private final SessionManager sessions;
    private final MailDeliveryService delivery;
    public ClientHandler(MailStorageService storage) { this(storage, System.out::println); }
    public ClientHandler(MailStorageService storage, Listener listener) {
        this(storage, listener, new SessionManager());
    }
    public ClientHandler(MailStorageService storage, Listener listener, SessionManager sessions) {
        this.storage = storage; this.listener = listener == null ? message -> { } : listener;
        this.sessions = sessions;
        this.delivery = MailDeliveryService.configured(storage, this.listener::onLog);
    }
    public String handleRequest(String request) { return handleRequest(request, UUID.randomUUID().toString()); }
    public String handleRequest(String request, String requestId) {
        if (request == null || request.length() > 59960 || request.isEmpty()
                || request.chars().anyMatch(c -> c < 32 || c == 127)) return "INVALID_COMMAND";
        try {
            if (request.equals("PING")) return "PONG";
            String[] parts = request.split(" ", -1);
            if (parts[0].equals("REGISTER") || parts[0].equals("LOGIN")) {
                if (parts.length != 3) return "INVALID_COMMAND";
                String owner = MailStorageService.normalizeEmail(parts[1]);
                String password = decode(parts[2], 512);
                if (!MailStorageService.isValidEmail(owner) || password == null)
                    return parts[0].equals("REGISTER") ? "REGISTER_FAILED: Invalid input" : "LOGIN_FAILED: Invalid credentials";
                if (parts[0].equals("REGISTER")) {
                    if (!PasswordHasher.validNewPassword(password)) return "REGISTER_FAILED: Invalid password (6 characters to 72 UTF-8 bytes)";
                    boolean ok = storage.register(owner, password);
                    listener.onLog(ok ? "REGISTER success" : "REGISTER rejected");
                    return ok ? "REGISTER_SUCCESS" : "REGISTER_FAILED: Account unavailable";
                }
                if (!storage.authenticate(owner, password)) {
                    listener.onLog("LOGIN rejected"); return "LOGIN_FAILED: Invalid credentials";
                }
                List<MailSummary> mails = storage.getMailSummaries(owner);
                if (mails == null) return "STORAGE_UNAVAILABLE";
                String token = sessions.create(owner);
                listener.onLog("LOGIN success");
                return "LOGIN_SUCCESS|" + token + "|" + summaries(mails);
            }
            if (!parts[0].equals("AUTH") || parts.length < 3) return "AUTH_REQUIRED";
            String owner = sessions.owner(parts[1]);
            if (owner == null) return "AUTH_REQUIRED";
            String command = parts[2];
            if (command.equals("LOGOUT") && parts.length == 3) {
                sessions.revoke(parts[1]); listener.onLog("LOGOUT success"); return "LOGOUT_SUCCESS";
            }
            if (command.equals("INBOX") || command.equals("SENT")) {
                if (parts.length != 4) return "INVALID_COMMAND";
                if (!owner.equals(MailStorageService.normalizeEmail(parts[3]))) return "FORBIDDEN";
                List<MailSummary> mails = command.equals("SENT")
                        ? storage.getSentSummaries(owner) : storage.getMailSummaries(owner);
                if (mails == null) return "STORAGE_UNAVAILABLE";
                return (command.equals("SENT") ? "SENT_SUCCESS|" : "LOGIN_SUCCESS|") + summaries(mails);
            }
            if (command.equals("READ") || command.equals("READ_SENT")) {
                if (parts.length != 5) return "INVALID_COMMAND";
                if (!owner.equals(MailStorageService.normalizeEmail(parts[3]))) return "FORBIDDEN";
                String content = command.equals("READ_SENT") ? storage.readSentMail(owner, parts[4]) : storage.readMail(owner, parts[4]);
                return content == null ? "READ_FAILED: Mail not found" : "MAIL_CONTENT|" + encode(content);
            }
            if (command.equals("SEND")) {
                if (parts.length != 5) return "INVALID_COMMAND";
                String recipient = MailStorageService.normalizeEmail(parts[3]);
                if (!MailStorageService.isValidEmail(recipient)) return "SEND_FAILED: Invalid recipient";
                String raw = decode(parts[4], 44000);
                if (raw == null) return "INVALID_COMMAND";
                int separator = raw.indexOf("\n\n");
                if (separator < 0) return "INVALID_COMMAND";
                String[] headers = raw.substring(0, separator).split("\n", -1);
                String senderPrefix = "Người gửi: ", receiverPrefix = "Người nhận: ", subjectPrefix = "Tiêu đề: ";
                if (headers.length != 3 || !headers[0].startsWith(senderPrefix)
                        || !headers[1].startsWith(receiverPrefix) || !headers[2].startsWith(subjectPrefix)) return "INVALID_COMMAND";
                if (!owner.equals(MailStorageService.normalizeEmail(headers[0].substring(senderPrefix.length())))
                        || !recipient.equals(MailStorageService.normalizeEmail(headers[1].substring(receiverPrefix.length())))) return "FORBIDDEN";
                String subject = headers[2].substring(subjectPrefix.length()).trim();
                String body = raw.substring(separator + 2);
                if (subject.isEmpty() || subject.length() > 998 || subject.indexOf('\r') >= 0 || body.isBlank()) return "INVALID_COMMAND";
                String content = senderPrefix + owner + "\n" + receiverPrefix + recipient + "\n" + subjectPrefix + subject + "\n\n" + body;
                String result = delivery.send(owner, recipient, content, requestId);
                listener.onLog(result.startsWith("SMTP_ACCEPTED") ? "SMTP relay accepted message" : result.equals("SEND_SUCCESS") ? "SEND stored locally" : result.startsWith("SEND_UNCERTAIN") ? "SMTP delivery uncertain; no automatic resubmission" : "SEND rejected");
                return result;
            }
            return "INVALID_COMMAND";
        } catch (MailStorageService.StorageUnavailableException e) {
            listener.onLog("Storage unavailable; operation not confirmed"); return "STORAGE_UNAVAILABLE";
        } catch (IllegalArgumentException e) { return "INVALID_COMMAND";
        } catch (IllegalStateException e) { return "SERVER_BUSY"; }
    }
    private static String summaries(List<MailSummary> mails) {
        StringJoiner entries = new StringJoiner(",");
        for (MailSummary mail : mails)
            entries.add(mail.getFileName() + ";" + encode(mail.getSubject()) + ";" + mail.getTimestamp());
        return entries.toString();
    }
    private static String encode(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String value, int maxBytes) {
        if (value.length() > (maxBytes + 2) / 3 * 4) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length > maxBytes) return null;
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | CharacterCodingException e) { return null; }
    }
}
