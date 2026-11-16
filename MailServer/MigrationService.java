package MailServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;

/** Offline migration. Stop all servers/writers before calling inspect or migrate. */
public final class MigrationService {
    private record Account(String email, String hash, List<JdbcMailRepository.Entry> entries) { }
    public record Result(int usersInserted, int entriesInserted, int accountsSkipped, Path backup) { }
    private final List<Account> accounts;
    private final Path root;
    private final int skipped;

    public MigrationService(Path source) throws IOException {
        root = SafePaths.root(source, false);
        accounts = new ArrayList<>();
        int skippedCount = 0;
        // Validate the entire tree before backup/import. Never follow symlinks/junctions.
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (!path.toRealPath().equals(path) || Files.isSymbolicLink(path))
                    throw new IOException("Migration refuses linked paths");
            }
        }
        try (var children = Files.list(root)) {
            for (Path directory : children.sorted().toList()) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                String owner = directory.getFileName().toString();
                Path credentials = directory.resolve(".credentials");
                if (!MailStorageService.isValidEmail(owner) || !owner.equals(MailStorageService.normalizeEmail(owner))
                        || !Files.isRegularFile(credentials, LinkOption.NOFOLLOW_LINKS) || Files.size(credentials) > 1024) {
                    skippedCount++; continue;
                }
                String hash = Files.readString(credentials, StandardCharsets.UTF_8).trim();
                if (!PasswordHasher.supported(hash)) { skippedCount++; continue; }
                List<JdbcMailRepository.Entry> entries = new ArrayList<>();
                for (boolean sent : new boolean[]{false, true}) {
                    Path folder = sent ? directory.resolve(".sent") : directory;
                    if (!Files.isDirectory(folder)) continue;
                    try (var files = Files.list(folder)) {
                        for (Path file : files.sorted().toList()) {
                            if (!MailStorageService.validMailFile(file.getFileName().toString(), sent)) continue;
                            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 8_000_000)
                                throw new IOException("Invalid or oversized legacy mail");
                            entries.add(new JdbcMailRepository.Entry(owner, sent, file.getFileName().toString(),
                                    Files.readString(file, StandardCharsets.UTF_8), Files.getLastModifiedTime(file).toInstant()));
                        }
                    }
                }
                accounts.add(new Account(owner, hash, List.copyOf(entries)));
            }
        }
        skipped = skippedCount;
    }
    public int accountCount() { return accounts.size(); }
    public int skippedCount() { return skipped; }
    public int entryCount() { return accounts.stream().mapToInt(a -> a.entries.size()).sum(); }

    public Path backup(Path backupParent) throws Exception {
        Path parent = backupParent.toAbsolutePath().normalize();
        if (parent.startsWith(root) || root.startsWith(parent)) throw new IOException("Backup must be separate from source tree");
        parent = SafePaths.root(parent, true);
        if (parent.startsWith(root) || root.startsWith(parent)) throw new IOException("Backup must be separate from source tree");
        Path backup = Files.createTempDirectory(parent, "mail-backup-");
        List<String> manifest = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path source : paths.sorted().toList()) {
                if (!source.toRealPath().equals(source)) throw new IOException("Source changed; abort migration");
                Path relative = root.relativize(source), target = backup.resolve(relative);
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target);
                else {
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                    byte[] original = Files.readAllBytes(source), copied = Files.readAllBytes(target);
                    if (!Arrays.equals(original, copied)) throw new IOException("Backup verification failed");
                    manifest.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(copied)) + "  " + relative);
                }
            }
        }
        Files.write(backup.resolve("backup-manifest.sha256"), manifest, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return backup;
    }
    public Result migrate(JdbcMailRepository repository, Path backupParent) throws Exception {
        repository.check();
        Path backup = backup(backupParent);
        // Ensure source files still match the inspected plan before any database writes.
        for (Account account : accounts) {
            String current = Files.readString(root.resolve(account.email).resolve(".credentials")).trim();
            if (!current.equals(account.hash)) throw new IOException("Credentials changed; rerun offline inspection");
            for (JdbcMailRepository.Entry entry : account.entries) {
                Path file = root.resolve(entry.owner());
                if (entry.sent()) file = file.resolve(".sent");
                if (!Files.readString(file.resolve(entry.filename())).equals(entry.content())) throw new IOException("Mail changed; rerun offline inspection");
            }
        }
        int users = 0, mails = 0;
        for (Account account : accounts) if (repository.importUser(account.email, account.hash)) users++;
        for (Account account : accounts) for (JdbcMailRepository.Entry entry : account.entries) {
            String id = entry.filename().equals("new_email.txt")
                    ? UUID.nameUUIDFromBytes(("welcome:" + entry.owner()).getBytes(StandardCharsets.UTF_8)).toString()
                    : entry.filename().substring(entry.filename().indexOf('_') + 1, entry.filename().length() - 4);
            // Reject malformed UUIDs instead of inventing ownership or credentials.
            UUID.fromString(id);
            String sender = header(entry.content(), "Người gửi:");
            String receiver = header(entry.content(), "Người nhận:");
            if (receiver.isEmpty() && !entry.sent()) receiver = entry.owner();
            String subject = header(entry.content(), "Tiêu đề:");
            if (subject.isEmpty()) subject = "Welcome";
            int separator = entry.content().indexOf("\n\n");
            String body = separator >= 0 ? entry.content().substring(separator + 2) : entry.content();
            if (repository.importEntry(entry, id, sender, receiver, subject, body)) mails++;
        }
        return new Result(users, mails, skipped, backup);
    }
    private static String header(String content, String name) {
        for (String line : content.split("\\R")) {
            if (line.isEmpty()) break;
            if (line.startsWith(name)) return line.substring(name.length()).trim();
        }
        return "";
    }
}
