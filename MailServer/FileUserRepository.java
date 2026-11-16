package MailServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class FileUserRepository implements UserRepository {
    private final FileStoragePaths paths;
    public FileUserRepository(Path root) throws IOException { this(new FileStoragePaths(root)); }
    FileUserRepository(FileStoragePaths paths) { this.paths = paths; }

    @Override public synchronized boolean createAccount(String email, String hash, String welcome) throws IOException {
        Path account = paths.user(email);
        if (!PasswordHasher.supported(hash)) throw new IOException("Unsupported password hash");
        if (Files.exists(account, LinkOption.NOFOLLOW_LINKS)) return false;
        Path staged = Files.createTempDirectory(paths.root, ".register-");
        try {
            Files.createDirectory(staged.resolve(".sent"));
            Files.writeString(staged.resolve(".credentials"), hash, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.writeString(staged.resolve("new_email.txt"), welcome, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            try { Files.move(staged, account); }
            catch (FileAlreadyExistsException e) { return false; }
            return true;
        } finally {
            // Only this operation's private staging directory may be cleaned up.
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(staged.resolve("new_email.txt"));
                Files.deleteIfExists(staged.resolve(".credentials"));
                Files.deleteIfExists(staged.resolve(".sent"));
                Files.deleteIfExists(staged);
            }
        }
    }
    @Override public String findPasswordHash(String email) throws IOException {
        Path file = paths.user(email).resolve(".credentials");
        if (!paths.regularFile(file) || Files.size(file) > 1024) return null;
        String hash = Files.readString(file, StandardCharsets.UTF_8).trim();
        return PasswordHasher.supported(hash) ? hash : null;
    }
    @Override public boolean exists(String email) throws IOException { return findPasswordHash(email) != null; }
}
