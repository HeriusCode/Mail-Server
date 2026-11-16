package MailServer;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Reject filesystem links/junctions, while accepting Windows 8.3 path aliases. */
final class SafePaths {
    private SafePaths() { }
    static Path root(Path requested, boolean create) throws IOException {
        Path absolute = requested.toAbsolutePath().normalize();
        for (Path current = absolute; current != null; current = current.getParent()) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther())
                    throw new IOException("Root must not contain linked or non-directory paths");
            }
        }
        if (create) Files.createDirectories(absolute);
        return absolute.toRealPath();
    }
}
