package MailServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Application service: business operations depend only on repository interfaces. */
public class MailStorageService {
    public static final String WELCOME_MESSAGE = "Thank you for using this service. We hope that you will feel comfortable using our email service.";
    private final UserRepository users;
    private final EmailRepository emails;
    public MailStorageService() { this(configuredRepositories()); }
    public MailStorageService(Path root) { this(fileRepositories(root)); }
    private MailStorageService(RepositoryFactory.Repositories repositories) { this(repositories.users(), repositories.emails()); }
    public MailStorageService(UserRepository users, EmailRepository emails) {
        this.users = Objects.requireNonNull(users); this.emails = Objects.requireNonNull(emails);
    }
    private static RepositoryFactory.Repositories configuredRepositories() {
        try { return RepositoryFactory.configured(Path.of("mail_storage")); }
        catch(IOException | IllegalArgumentException e) {
            throw new StorageUnavailableException("Không thể kết nối kho dữ liệu: " + rootMessage(e), e);
        }
    }
    private static String rootMessage(Throwable error) {
        Throwable current=error;
        while(current.getCause()!=null) current=current.getCause();
        String message=current.getMessage();
        return message==null || message.isBlank()?current.getClass().getSimpleName():message;
    }
    public String backendName() {
        if(users instanceof PostgreSQLUserRepository) return "PostgreSQL repositories";
        if(users instanceof MySQLUserRepository) return "MySQL repositories";
        return users instanceof FileUserRepository ? "File repositories" : "Custom repositories";
    }
    private static RepositoryFactory.Repositories fileRepositories(Path root) {
        try { return RepositoryFactory.files(root); }
        catch (IOException e) { throw new StorageUnavailableException(); }
    }
    public synchronized boolean register(String username, String password) {
        String owner = normalizeEmail(username);
        if (!isValidEmail(owner) || !PasswordHasher.validNewPassword(password)) return false;
        try { return users.createAccount(owner, PasswordHasher.hash(password), WELCOME_MESSAGE); }
        catch (IOException e) { throw new StorageUnavailableException(); }
    }
    public boolean authenticate(String username, String password) {
        String owner = normalizeEmail(username);
        if (!isValidEmail(owner) || password == null) return false;
        try { return PasswordHasher.verify(password, users.findPasswordHash(owner)); }
        catch (IOException e) { throw new StorageUnavailableException(); }
    }
    public boolean accountExists(String username) {
        String owner = normalizeEmail(username);
        if (!isValidEmail(owner)) return false;
        try { return users.exists(owner); } catch (IOException e) { throw new StorageUnavailableException(); }
    }
    public List<MailSummary> getMailSummaries(String owner) { return list(owner, false); }
    public List<MailSummary> getSentSummaries(String owner) { return list(owner, true); }
    private List<MailSummary> list(String owner, boolean sent) {
        owner = normalizeEmail(owner);
        if (!isValidEmail(owner)) return null;
        try { return emails.list(owner, sent); } catch (IOException e) { throw new StorageUnavailableException(); }
    }
    public String readMail(String owner, String filename) { return read(owner, filename, false); }
    public String readSentMail(String owner, String filename) { return read(owner, filename, true); }
    private String read(String owner, String filename, boolean sent) {
        owner = normalizeEmail(owner);
        if (!isValidEmail(owner) || !validMailFile(filename, sent)) return null;
        try { return emails.read(owner, filename, sent); } catch (IOException e) { throw new StorageUnavailableException(); }
    }
    public boolean saveMail(String sender, String recipient, String content) { return saveMail(sender, recipient, content, UUID.randomUUID().toString()); }
    public boolean saveMail(String sender, String recipient, String content, String requestId) {
        sender = normalizeEmail(sender); recipient = normalizeEmail(recipient);
        if (!isValidEmail(sender) || !isValidEmail(recipient)) return false;
        try { return emails.save(sender, recipient, content, requestId); } catch (IOException e) { throw new StorageUnavailableException(); }
    }
    public static String normalizeEmail(String value) { return MailValidation.normalizeEmail(value); }
    public void saveSentMail(String owner, String content, String id) {
        try { emails.saveSent(owner, content, id); } catch (IOException e) { throw new StorageUnavailableException(); }
    }
    public void archiveInbox(String owner, List<String> filenames) {
        try { emails.archiveInbox(owner, filenames); } catch (IOException e) { throw new StorageUnavailableException(); }
    }
    public static boolean isValidEmail(String email) { return MailValidation.isValidEmail(email); }
    public static boolean validMailFile(String filename, boolean sent) { return MailValidation.validMailFile(filename, sent); }
    public static final class StorageUnavailableException extends IllegalStateException {
        public StorageUnavailableException() { super("Storage unavailable; check backend configuration, permissions and conflicts"); }
        public StorageUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
