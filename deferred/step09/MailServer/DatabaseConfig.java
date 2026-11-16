package MailServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class DatabaseConfig {
    private final String url, user, password;
    public DatabaseConfig(String url, String user, String password) {
        if (url == null || !url.startsWith("jdbc:mysql://") || user == null || user.isBlank() || password == null)
            throw new IllegalArgumentException("Incomplete MAIL_DB configuration");
        // Credentials belong in JDBC properties, never a URL that could appear in diagnostics.
        if (url.toLowerCase(java.util.Locale.ROOT).matches(".*[?&](user|password)=.*"))
            throw new IllegalArgumentException("Credentials must not be in MAIL_DB_URL");
        this.url = url; this.user = user; this.password = password;
    }
    public static DatabaseConfig load() {
        Properties values = new Properties();
        Path path = Path.of(System.getenv().getOrDefault("MAIL_CONFIG", "config/database.local.properties"));
        if (Files.exists(path)) {
            try (var input = Files.newInputStream(path)) { values.load(input); }
            catch (IOException e) { throw new IllegalStateException("Cannot read local database configuration"); }
        }
        for (String name : new String[]{"MAIL_DB_URL", "MAIL_DB_USER", "MAIL_DB_PASSWORD"}) {
            if (System.getenv(name) != null) values.setProperty(name, System.getenv(name));
        }
        if (values.isEmpty()) return null;
        try {
            return new DatabaseConfig(values.getProperty("MAIL_DB_URL"), values.getProperty("MAIL_DB_USER"), values.getProperty("MAIL_DB_PASSWORD"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid database configuration; no file fallback");
        }
    }
    public Connection connect() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user); properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "3000"); properties.setProperty("socketTimeout", "5000");
        properties.setProperty("characterEncoding", "UTF-8");
        properties.setProperty("connectionCollation", "utf8mb4_unicode_ci");
        return DriverManager.getConnection(url, properties);
    }
}
