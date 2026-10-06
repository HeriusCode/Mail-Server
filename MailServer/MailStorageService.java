package MailServer;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class MailStorageService {
    private static final String SENT_DIRECTORY = ".sent";
    private static final String CREDENTIAL_FILE = ".credentials";
    private static final int PASSWORD_ITERATIONS = 120_000;
    private static final int PASSWORD_KEY_LENGTH = 256;
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final String WELCOME_MESSAGE =
            "Người gửi: MailFlow System\n"
            + "Tiêu đề: Chào mừng bạn đến với MailFlow\n\n"
            + "Cảm ơn bạn đã sử dụng dịch vụ thư UDP của MailFlow.";

    private final Path storageRoot;

    public MailStorageService() {
        this(Paths.get("mail_storage"));
    }

    public MailStorageService(Path storageRoot) {
        this.storageRoot = storageRoot;
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create mail storage directory", exception);
        }
    }

    public boolean register(String username, String password) {
        String email = normalizeEmail(username);
        if (!isValidEmail(email) || !isValidPassword(password)) return false;
        Path userDirectory = storageRoot.resolve(email);
        try {
            if (Files.exists(userDirectory)) {
                Path credentialFile = userDirectory.resolve(CREDENTIAL_FILE);
                if (Files.exists(credentialFile)) return false;
                writeCredentials(credentialFile, password);
                Files.createDirectories(userDirectory.resolve(SENT_DIRECTORY));
                return true;
            }
            Files.createDirectory(userDirectory);
            Files.createDirectory(userDirectory.resolve(SENT_DIRECTORY));
            writeCredentials(userDirectory.resolve(CREDENTIAL_FILE), password);
            Files.writeString(userDirectory.resolve("new_email.txt"),
                    addTimestamp(WELCOME_MESSAGE, "Thời gian nhận", LocalDateTime.now()),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    public boolean authenticate(String username, String password) {
        String email = normalizeEmail(username);
        if (!isValidEmail(email) || password == null) return false;
        Path credentialFile = storageRoot.resolve(email).resolve(CREDENTIAL_FILE);
        if (!Files.isRegularFile(credentialFile)) return false;
        try {
            String[] fields = Files.readString(credentialFile, StandardCharsets.UTF_8)
                    .trim().split(":", 3);
            if (fields.length != 3) return false;
            int iterations = Integer.parseInt(fields[0]);
            byte[] salt = Base64.getDecoder().decode(fields[1]);
            byte[] expectedHash = Base64.getDecoder().decode(fields[2]);
            byte[] actualHash = hashPassword(password, salt, iterations, expectedHash.length * 8);
            return MessageDigest.isEqual(expectedHash, actualHash);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }

    public List<MailSummary> getMailSummaries(String username) {
        return getMailSummaries(username, false);
    }

    public List<MailSummary> getSentSummaries(String username) {
        return getMailSummaries(username, true);
    }

    private List<MailSummary> getMailSummaries(String username, boolean sent) {
        String email = normalizeEmail(username);
        if (!isValidEmail(email)) return null;
        Path userDirectory = storageRoot.resolve(email);
        if (!Files.isDirectory(userDirectory)) return null;
        Path sourceDirectory = sent ? userDirectory.resolve(SENT_DIRECTORY) : userDirectory;
        if (sent && !Files.exists(sourceDirectory)) {
            try {
                Files.createDirectory(sourceDirectory);
            } catch (IOException exception) {
                return new ArrayList<>();
            }
        }

        List<MailSummary> summaries = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(sourceDirectory, "*.txt")) {
            for (Path file : files) {
                if (!Files.isRegularFile(file)) continue;
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String subject = extractHeader(content, "Tiêu đề:");
                if (subject.isEmpty()) subject = "(Không có tiêu đề)";
                summaries.add(new MailSummary(file.getFileName().toString(), subject,
                        Files.getLastModifiedTime(file).toMillis()));
            }
        } catch (IOException exception) {
            return null;
        }
        summaries.sort(Comparator.comparingLong(MailSummary::getTimestamp).reversed());
        return summaries;
    }

    public boolean saveMail(String fromUser, String toUser, String content) {
        String sender = normalizeEmail(fromUser);
        String recipient = normalizeEmail(toUser);
        if (!isValidEmail(sender) || !isValidEmail(recipient)) return false;
        Path senderDirectory = storageRoot.resolve(sender);
        Path recipientDirectory = storageRoot.resolve(recipient);
        if (!Files.isDirectory(senderDirectory) || !Files.isDirectory(recipientDirectory)) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        String id = UUID.randomUUID().toString();
        Path inboxFile = recipientDirectory.resolve("mail_" + id + ".txt");
        Path sentDirectory = senderDirectory.resolve(SENT_DIRECTORY);
        Path sentFile = sentDirectory.resolve("sent_" + id + ".txt");
        try {
            Files.createDirectories(sentDirectory);
            Files.writeString(inboxFile, addTimestamp(content, "Thời gian nhận", now),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            try {
                Files.writeString(sentFile, addTimestamp(content, "Thời gian gửi", now),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (IOException exception) {
                Files.deleteIfExists(inboxFile);
                throw exception;
            }
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    public String readMail(String username, String fileName) {
        return readMail(username, fileName, false);
    }

    public String readSentMail(String username, String fileName) {
        return readMail(username, fileName, true);
    }

    private String readMail(String username, String fileName, boolean sent) {
        String email = normalizeEmail(username);
        if (!isValidEmail(email)) return null;
        Path userDirectory = storageRoot.resolve(email).normalize();
        Path sourceDirectory = sent
                ? userDirectory.resolve(SENT_DIRECTORY).normalize() : userDirectory;
        Path mailFile = sourceDirectory.resolve(fileName).normalize();
        if (!mailFile.startsWith(sourceDirectory) || !Files.isRegularFile(mailFile)) return null;
        try {
            return Files.readString(mailFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return null;
        }
    }

    private String addTimestamp(String content, String label, LocalDateTime time) {
        String timestampLine = label + ": " + DISPLAY_TIME.format(time);
        int bodySeparator = content.indexOf("\n\n");
        if (bodySeparator < 0) return content + "\n" + timestampLine;
        return content.substring(0, bodySeparator) + "\n" + timestampLine
                + content.substring(bodySeparator);
    }

    private String extractHeader(String content, String header) {
        for (String line : content.split("\\R")) {
            if (line.startsWith(header)) return line.substring(header.length()).trim();
        }
        return "";
    }

    private void writeCredentials(Path credentialFile, String password)
            throws IOException {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        try {
            byte[] hash = hashPassword(password, salt,
                    PASSWORD_ITERATIONS, PASSWORD_KEY_LENGTH);
            String value = PASSWORD_ITERATIONS + ":"
                    + Base64.getEncoder().encodeToString(salt) + ":"
                    + Base64.getEncoder().encodeToString(hash);
            Files.writeString(credentialFile, value, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Không thể tạo mật khẩu an toàn", exception);
        }
    }

    private byte[] hashPassword(String password, byte[] salt, int iterations,
            int keyLength) throws GeneralSecurityException {
        PBEKeySpec specification = new PBEKeySpec(
                password.toCharArray(), salt, iterations, keyLength);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification).getEncoded();
        } finally {
            specification.clearPassword();
        }
    }

    private String normalizeEmail(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isValidEmail(String email) {
        return email.matches("[a-z0-9._%+-]+@gmail\\.com");
    }

    private boolean isValidPassword(String password) {
        return password != null && password.length() >= 6 && password.length() <= 128;
    }

    public static final class MailSummary {
        private final String fileName;
        private final String subject;
        private final long timestamp;

        public MailSummary(String fileName, String subject, long timestamp) {
            this.fileName = fileName;
            this.subject = subject;
            this.timestamp = timestamp;
        }

        public String getFileName() { return fileName; }
        public String getSubject() { return subject; }
        public long getTimestamp() { return timestamp; }
    }
}
