package MailServer;

import java.io.IOException;
import java.util.List;

/** Persistence operations use the owner already authenticated by the server. */
public interface EmailRepository {
    List<MailSummary> list(String owner, boolean sent) throws IOException;
    String read(String owner, String filename, boolean sent) throws IOException;
    /** Same sender/request ID is one delivery; a conflicting payload must be rejected. */
    boolean save(String sender, String recipient, String content, String requestId) throws IOException;
    void saveSent(String owner, String content, String requestId) throws IOException;
    void archiveInbox(String owner, List<String> filenames) throws IOException;
}
