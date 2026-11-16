package MailServer;

/** Transport-neutral mailbox metadata; contains no credentials or session data. */
public record MailSummary(String fileName, String subject, long timestamp) {
    public String getFileName() { return fileName; }
    public String getSubject() { return subject; }
    public long getTimestamp() { return timestamp; }
}
