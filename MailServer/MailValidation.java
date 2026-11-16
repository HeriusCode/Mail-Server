package MailServer;

import java.util.Locale;

public final class MailValidation {
    private MailValidation() { }
    public static String normalizeEmail(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
    public static boolean isValidEmail(String email) {
        return email != null && email.length() <= 254
                && email.matches("[a-z0-9._%+-]{1,64}@[a-z0-9-]+(?:\\.[a-z0-9-]+)+");
    }
    public static boolean validMailFile(String filename, boolean sent) {
        return filename != null && ((!sent && filename.equals("new_email.txt"))
                || filename.matches((sent ? "sent_" : "mail_")
                + "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.txt"));
    }
}
