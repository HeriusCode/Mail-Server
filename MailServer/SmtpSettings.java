package MailServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.util.Map;
import java.util.Locale;

/** Secrets stay outside source, logs and UI. No implicit Gmail credentials. */
public final class SmtpSettings {
    final String host, user, password, from;
    final int port;
    final boolean auth, starttls, ssl;
    public SmtpSettings(String host, int port, String user, String password, String from, boolean auth, boolean starttls, boolean ssl) {
        this.host = host == null ? "" : host.trim().toLowerCase(Locale.ROOT); this.port = port;
        this.user = user == null ? null : user.trim(); this.password = password;
        this.from = MailValidation.normalizeEmail(from);
        this.auth = auth; this.starttls = starttls; this.ssl = ssl;
        if (port < 1 || port > 65535 || !MailValidation.isValidEmail(this.from) || this.host.isBlank()
                || this.host.chars().anyMatch(c -> c <= 32 || c >= 127)
                || (auth && (this.user == null || this.user.isBlank() || password == null || password.isBlank())))
            throw new IllegalArgumentException("Incomplete SMTP configuration");
        if (starttls && ssl) throw new IllegalArgumentException("Choose STARTTLS or implicit TLS, not both");
        if (auth && !starttls && !ssl && !this.host.equals("127.0.0.1") && !this.host.equals("localhost"))
            throw new IllegalArgumentException("Remote SMTP authentication requires TLS");
        if (this.host.equals("smtp.gmail.com") && (!auth || !this.from.equalsIgnoreCase(this.user)
                || !((port == 587 && starttls && !ssl) || (port == 465 && ssl && !starttls))))
            throw new IllegalArgumentException("Gmail requires authenticated From=user and 587 STARTTLS or 465 TLS");
    }
    public static SmtpSettings load() {
        Path file = Path.of(System.getenv().getOrDefault("MAIL_SMTP_CONFIG", "config/smtp.local.properties"));
        return load(file, System.getenv());
    }
    /** Explicit inputs allow isolated tests without reading real local credentials. */
    static SmtpSettings load(Path file, Map<String, String> environment) {
        Properties values = new Properties();
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); }
            catch (IOException e) { throw new IllegalStateException("Cannot read SMTP configuration"); }
        }
        for (String name : new String[]{"SMTP_HOST","SMTP_PORT","SMTP_USER","SMTP_PASSWORD","SMTP_FROM","SMTP_AUTH","SMTP_STARTTLS","SMTP_SSL"}) {
            String value = environment.get(name); if (value != null) values.setProperty(name, value);
        }
        if (values.getProperty("SMTP_HOST", "").isBlank()) return null;
        return new SmtpSettings(values.getProperty("SMTP_HOST"), Integer.parseInt(values.getProperty("SMTP_PORT", "587")),
                values.getProperty("SMTP_USER"), values.getProperty("SMTP_PASSWORD"),
                values.getProperty("SMTP_FROM", values.getProperty("SMTP_USER", "")),
                flag(values, "SMTP_AUTH", "true"), flag(values, "SMTP_STARTTLS", "true"), flag(values, "SMTP_SSL", "false"));
    }
    private static boolean flag(Properties values, String name, String fallback) {
        String value = values.getProperty(name, fallback).trim();
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("Invalid SMTP security flag");
        return Boolean.parseBoolean(value);
    }
    Properties mailProperties() {
        Properties values = new Properties();
        values.setProperty("mail.host", "localhost"); values.setProperty("mail.user", "mailflow");
        values.setProperty("mail.smtp.localhost", "localhost"); values.setProperty("mail.smtp.from", from);
        values.setProperty("mail.smtp.auth", Boolean.toString(auth));
        values.setProperty("mail.smtp.starttls.enable", Boolean.toString(starttls));
        values.setProperty("mail.smtp.starttls.required", Boolean.toString(starttls));
        values.setProperty("mail.smtp.ssl.enable", Boolean.toString(ssl));
        values.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        values.setProperty("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        values.setProperty("mail.smtp.connectiontimeout", "5000"); values.setProperty("mail.smtp.timeout", "5000"); values.setProperty("mail.smtp.writetimeout", "5000");
        return values;
    }
}
