package MailServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class FileEmailRepository implements EmailRepository {
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private final FileStoragePaths paths;
    private final UserRepository users;
    public FileEmailRepository(Path root, UserRepository users) throws IOException { this(new FileStoragePaths(root), users); }
    FileEmailRepository(FileStoragePaths paths, UserRepository users) { this.paths = paths; this.users = Objects.requireNonNull(users); }

    @Override public List<MailSummary> list(String owner, boolean sent) throws IOException {
        Path folder = paths.folder(owner, sent);
        if (!users.exists(owner)) return null;
        if (!Files.exists(folder)) return new ArrayList<>();
        List<MailSummary> summaries = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.txt")) {
            for (Path file : files) {
                if (!MailValidation.validMailFile(file.getFileName().toString(), sent) || !paths.regularFile(file)) continue;
                String subject = header(Files.readString(file, StandardCharsets.UTF_8), "Tiêu đề:");
                if (subject.isEmpty()) subject = file.getFileName().toString().equals("new_email.txt") ? "Welcome" : "(Không có tiêu đề)";
                summaries.add(new MailSummary(file.getFileName().toString(), subject, Files.getLastModifiedTime(file).toMillis()));
            }
        }
        summaries.sort(Comparator.comparingLong(MailSummary::getTimestamp).reversed().thenComparing(MailSummary::getFileName));
        return summaries;
    }
    @Override public String read(String owner, String filename, boolean sent) throws IOException {
        if (!MailValidation.validMailFile(filename, sent)) return null;
        Path folder = paths.folder(owner, sent);
        Path file = folder.resolve(filename).normalize();
        if (!file.startsWith(folder) || !users.exists(owner) || !paths.regularFile(file)) return null;
        return Files.readString(file, StandardCharsets.UTF_8);
    }
    @Override public synchronized boolean save(String sender, String recipient, String content, String requestId) throws IOException {
        Path senderDirectory, recipientDirectory, sentDirectory;
        try {
            senderDirectory = paths.user(sender); recipientDirectory = paths.user(recipient);
            sentDirectory = paths.folder(sender, true);
        } catch (IOException e) { return false; }
        if (!Files.isDirectory(senderDirectory) || !Files.isDirectory(recipientDirectory)
                || !users.exists(sender) || !users.exists(recipient)) return false;
        String id = UUID.nameUUIDFromBytes((sender + ":" + requestId).getBytes(StandardCharsets.UTF_8)).toString();
        Path inboxFile = recipientDirectory.resolve("mail_" + id + ".txt");
        Path sentFile = sentDirectory.resolve("sent_" + id + ".txt");
        Files.createDirectories(sentDirectory);
        LocalDateTime now = LocalDateTime.now();
        if (Files.exists(inboxFile, LinkOption.NOFOLLOW_LINKS) || Files.exists(sentFile, LinkOption.NOFOLLOW_LINKS)) {
            String prior = Files.exists(inboxFile, LinkOption.NOFOLLOW_LINKS)
                    ? read(recipient, inboxFile.getFileName().toString(), false)
                    : read(sender, sentFile.getFileName().toString(), true);
            if (prior == null || !prior.replaceFirst("(?m)^Thời gian (nhận|gửi): .*\\n", "").equals(content)) return false;
            if (Files.exists(inboxFile, LinkOption.NOFOLLOW_LINKS) && Files.exists(sentFile, LinkOption.NOFOLLOW_LINKS)) {
                String sent = read(sender, sentFile.getFileName().toString(), true);
                return sent != null && sent.replaceFirst("(?m)^Thời gian (nhận|gửi): .*\\n", "").equals(content);
            }
            if (!Files.exists(inboxFile, LinkOption.NOFOLLOW_LINKS)) paths.writeOnce(inboxFile, addTimestamp(content, "Thời gian nhận", now));
            if (!Files.exists(sentFile, LinkOption.NOFOLLOW_LINKS)) paths.writeOnce(sentFile, addTimestamp(content, "Thời gian gửi", now));
            return true;
        }
        paths.writeOnce(inboxFile, addTimestamp(content, "Thời gian nhận", now));
        try { paths.writeOnce(sentFile, addTimestamp(content, "Thời gian gửi", now)); }
        catch (IOException e) { Files.deleteIfExists(inboxFile); throw e; }
        return true;
    }
    private static String addTimestamp(String content, String label, LocalDateTime time) {
        String line = label + ": " + DISPLAY_TIME.format(time);
        int separator = content.indexOf("\n\n");
        return separator < 0 ? content + "\n" + line : content.substring(0, separator) + "\n" + line + content.substring(separator);
    }
    @Override public synchronized void saveSent(String owner, String content, String requestId) throws IOException {
        if (!users.exists(owner)) throw new IOException("Unknown sender");
        Path directory = paths.folder(owner, true);
        Files.createDirectories(directory);
        paths.writeOnce(directory.resolve("sent_" + requestId + ".txt"), content);
    }
    @Override public synchronized void archiveInbox(String owner, List<String> filenames) throws IOException {
        Path directory = paths.folder(owner, false), trash = paths.trash(owner);
        Files.createDirectories(trash);
        Map<Path, Path> moved = new LinkedHashMap<>();
        try {
            for (String filename : filenames) {
                if (!MailValidation.validMailFile(filename, false)) throw new IOException("Invalid mail filename");
                Path source = directory.resolve(filename);
                if (!paths.regularFile(source)) throw new IOException("Mail changed during POP3 session");
                Path destination = trash.resolve(UUID.randomUUID() + "_" + filename);
                Files.move(source, destination); moved.put(source, destination);
            }
        } catch (IOException e) {
            for (Map.Entry<Path, Path> entry : moved.entrySet()) Files.move(entry.getValue(), entry.getKey());
            throw e;
        }
    }
    private static String header(String content, String prefix) {
        for (String line : content.split("\\R")) {
            if (line.isEmpty()) break;
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return "";
    }
}
