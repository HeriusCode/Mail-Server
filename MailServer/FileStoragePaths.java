package MailServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Shared filesystem guard for the two file repositories. */
final class FileStoragePaths {
    final Path root;
    FileStoragePaths(Path root) throws IOException { this.root = SafePaths.root(root, true); }
    Path user(String email) throws IOException {
        if (!MailValidation.isValidEmail(email) || !email.equals(MailValidation.normalizeEmail(email)))
            throw new IOException("Invalid account");
        return checkedDirectory(root.resolve(email));
    }
    Path folder(String email, boolean sent) throws IOException {
        Path user = user(email);
        return sent ? checkedDirectory(user.resolve(".sent")) : user;
    }
    Path trash(String email) throws IOException { return checkedDirectory(user(email).resolve(".trash")); }
    private Path checkedDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path)))
            throw new IOException("Unsafe mailbox directory");
        return path;
    }
    boolean regularFile(Path file) throws IOException {
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && file.toRealPath().equals(file);
    }
    /** Publish complete bytes without replacing a pre-existing target. */
    void writeOnce(Path file, String content) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!regularFile(file) || !Files.readString(file, StandardCharsets.UTF_8).equals(content))
                throw new IOException("File conflict; existing data preserved");
            return;
        }
        Path temporary = Files.createTempFile(file.getParent(), ".pending-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, file);
        } finally { Files.deleteIfExists(temporary); }
    }
}
