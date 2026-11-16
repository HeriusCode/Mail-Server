package MailServer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.security.crypto.bcrypt.BCrypt;

/** BCrypt for new accounts; bounded verification of the existing PBKDF2 format. */
public final class PasswordHasher {
    private PasswordHasher() { }

    public static boolean validNewPassword(String password) {
        return password != null && password.length() >= 6 && password.indexOf('\0') < 0
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    public static String hash(String password) {
        if (!validNewPassword(password)) throw new IllegalArgumentException("Invalid password length");
        return BCrypt.hashpw(password, BCrypt.gensalt(12));
    }

    public static boolean supported(String hash) {
        if (hash == null) return false;
        if (hash.matches("\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}")) return true;
        try {
            String[] fields = hash.split(":", -1);
            if (fields.length != 3 || !fields[0].equals("120000")) return false;
            return Base64.getDecoder().decode(fields[1]).length == 16
                    && Base64.getDecoder().decode(fields[2]).length == 32;
        } catch (IllegalArgumentException e) { return false; }
    }

    public static boolean verify(String password, String stored) {
        if (password == null || password.length() > 128 || !supported(stored)) return false;
        try {
            if (stored.startsWith("$2")) {
                return validNewPassword(password) && BCrypt.checkpw(password, stored);
            }
            String[] fields = stored.split(":");
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(),
                    Base64.getDecoder().decode(fields[1]), 120_000, 256);
            try {
                byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).getEncoded();
                return MessageDigest.isEqual(actual, Base64.getDecoder().decode(fields[2]));
            } finally { spec.clearPassword(); }
        } catch (Exception e) { return false; }
    }
}
