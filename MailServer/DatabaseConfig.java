package MailServer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Server-only JDBC configuration. A selected database never silently falls back to files. */
public final class DatabaseConfig {
    private final String url,user,password;
    public DatabaseConfig(String url,String user,String password) {
        if(url==null || !url.matches("jdbc:(?:mysql|postgresql)://[^/]+/[A-Za-z0-9_]+(?:\\?.*)?")
                || user==null || user.isBlank() || password==null || password.isBlank()
                || user.equalsIgnoreCase("root")) throw new IllegalArgumentException("Incomplete/unsafe application database configuration");
        if(url.toLowerCase(Locale.ROOT).matches(".*[?&](user|password|allowloadlocalinfile|allowmultiqueries)=.*")
                || url.contains("@") || url.chars().anyMatch(c->c<32))
            throw new IllegalArgumentException("Unsafe database URL");
        this.url=url; this.user=user; this.password=password;
    }
    static Properties settings() { return settings(resolveSettingsPath(),System.getenv()); }

    /** Resolve private configuration even when an IDE starts from another project. */
    private static Path resolveSettingsPath() {
        String explicit=System.getenv("MAIL_DB_CONFIG");
        if(explicit!=null && !explicit.isBlank()) return Path.of(explicit).toAbsolutePath().normalize();

        Path workingDirectory=Path.of("").toAbsolutePath().normalize();
        Path fromWorkingDirectory=workingDirectory.resolve("config/database.local.properties");
        if(Files.isRegularFile(fromWorkingDirectory)) return fromWorkingDirectory;

        try {
            Path location=Path.of(DatabaseConfig.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
            Path directory=Files.isDirectory(location)?location:location.getParent();
            for(int level=0; directory!=null && level<5; level++,directory=directory.getParent()) {
                Path candidate=directory.resolve("config/database.local.properties");
                if(Files.isRegularFile(candidate)) return candidate;
            }
        } catch(Exception ignored) { }
        return fromWorkingDirectory;
    }
    static Properties settings(Path file,Map<String,String> environment) {
        Properties values=new Properties();
        if(Files.exists(file)) try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) { values.load(reader); }
        catch(IOException e) { throw new IllegalStateException("Cannot read database configuration"); }
        for(String key:List.of("MAIL_STORAGE_BACKEND","MAIL_DB_URL","MAIL_DB_USER","MAIL_DB_PASSWORD")) {
            String value=environment.get(key); if(value!=null) values.setProperty(key,value);
        }
        return values;
    }
    public static DatabaseConfig load() { return from(settings()); }
    static DatabaseConfig from(Properties p) { return new DatabaseConfig(p.getProperty("MAIL_DB_URL"),p.getProperty("MAIL_DB_USER"),p.getProperty("MAIL_DB_PASSWORD")); }
    public Connection connect() throws SQLException {
        Properties p=new Properties(); p.setProperty("user",user); p.setProperty("password",password);
        if(isPostgreSQL()) {
            p.setProperty("connectTimeout","5"); p.setProperty("socketTimeout","10");
            p.setProperty("ApplicationName","MailFlow Server");
        } else {
            p.setProperty("connectTimeout","3000"); p.setProperty("socketTimeout","5000");
            p.setProperty("characterEncoding","UTF-8"); p.setProperty("connectionCollation","utf8mb4_unicode_ci");
            p.setProperty("connectionTimeZone","UTC"); p.setProperty("forceConnectionTimeZoneToSession","true");
            p.setProperty("allowMultiQueries","false"); p.setProperty("allowLoadLocalInfile","false");
        }
        return DriverManager.getConnection(url,p);
    }
    public boolean isPostgreSQL() { return url.startsWith("jdbc:postgresql:"); }
}
