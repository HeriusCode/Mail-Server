package MailServer;

import java.io.IOException;
import java.nio.file.Path;

/** Server-side backend selection; explicit Path constructors continue to use files. */
public final class RepositoryFactory {
    public record Repositories(UserRepository users, EmailRepository emails) { }
    private RepositoryFactory() { }
    public static Repositories configured(Path root) throws IOException {
        return configured(root,DatabaseConfig.settings());
    }
    static Repositories configured(Path root,java.util.Properties values) throws IOException {
        String backend=values.getProperty("MAIL_STORAGE_BACKEND","FILE").trim();
        if(backend.equalsIgnoreCase("FILE")) return files(root);
        if(backend.equalsIgnoreCase("MYSQL")) return mysql(DatabaseConfig.from(values));
        if(backend.equalsIgnoreCase("POSTGRESQL") || backend.equalsIgnoreCase("POSTGRES"))
            return postgresql(DatabaseConfig.from(values));
        throw new IOException("Unknown storage backend");
    }
    public static Repositories mysql(DatabaseConfig config) throws IOException {
        JdbcMailRepository sql=new JdbcMailRepository(config);
        try { sql.check(); } catch(java.sql.SQLException e) { throw new IOException("MySQL connection/schema unavailable"); }
        return new Repositories(new MySQLUserRepository(sql),new MySQLEmailRepository(sql));
    }
    public static Repositories postgresql(DatabaseConfig config) throws IOException {
        JdbcMailRepository sql=new JdbcMailRepository(config);
        try { sql.check(); } catch(java.sql.SQLException e) { throw new IOException("PostgreSQL connection/schema unavailable",e); }
        return new Repositories(new PostgreSQLUserRepository(sql),new PostgreSQLEmailRepository(sql));
    }
    public static Repositories files(Path root) throws IOException {
        FileStoragePaths paths = new FileStoragePaths(root);
        UserRepository users = new FileUserRepository(paths);
        return new Repositories(users, new FileEmailRepository(paths, users));
    }
}
