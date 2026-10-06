package MailServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class MailStorageService {
    private static final String WELCOME_MESSAGE =
            "Thank you for using this service. we hope that you will feel comfortable...";

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

    public boolean register(String username) {
        if (!isValidUsername(username)) {
            return false;
        }
        Path userDirectory = storageRoot.resolve(username);

        try {
            if (Files.exists(userDirectory)) {
                return false;
            }

            Files.createDirectory(userDirectory);
            Files.write(userDirectory.resolve("new_email.txt"),
                    WELCOME_MESSAGE.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    public List<String> getMailFiles(String username) {
        if (!isValidUsername(username)) {
            return null;
        }
        Path userDirectory = storageRoot.resolve(username);

        if (!Files.isDirectory(userDirectory)) {
            return null;
        }

        List<String> mailFiles = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(userDirectory, "*.txt")) {
            for (Path file : files) {
                if (Files.isRegularFile(file)) {
                    mailFiles.add(file.getFileName().toString());
                }
            }
        } catch (IOException exception) {
            return null;
        }

        Collections.sort(mailFiles);
        return mailFiles;
    }

    public boolean saveMail(String toUser, String content) {
        if (!isValidUsername(toUser)) {
            return false;
        }
        Path userDirectory = storageRoot.resolve(toUser);

        if (!Files.isDirectory(userDirectory)) {
            return false;
        }

        String fileName = "mail_" + UUID.randomUUID().toString() + ".txt";
        Path mailFile = userDirectory.resolve(fileName);
        try {
            Files.write(mailFile, content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    public String readMail(String username, String fileName) {
        if (!isValidUsername(username)) {
            return null;
        }
        Path userDirectory = storageRoot.resolve(username).normalize();
        Path mailFile = userDirectory.resolve(fileName).normalize();
        if (!mailFile.startsWith(userDirectory) || !Files.isRegularFile(mailFile)) {
            return null;
        }

        try {
            return Files.readString(mailFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return null;
        }
    }

    private boolean isValidUsername(String username) {
        return username != null && username.matches("[a-zA-Z0-9_]+");
    }
}
