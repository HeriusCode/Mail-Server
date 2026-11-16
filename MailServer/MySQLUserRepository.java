package MailServer;

import java.io.IOException;
import java.sql.SQLException;

public final class MySQLUserRepository implements UserRepository {
    private final JdbcMailRepository sql;
    public MySQLUserRepository(JdbcMailRepository sql) { this.sql=sql; }
    public boolean createAccount(String email,String hash,String welcome) throws IOException {
        if(!MailValidation.isValidEmail(email) || !PasswordHasher.supported(hash)) throw new IOException("Invalid account/hash");
        try { return sql.register(email,hash,welcome); } catch(SQLException e) { throw new IOException("MySQL account write failed"); }
    }
    public String findPasswordHash(String email) throws IOException {
        try { String hash=sql.passwordHash(email); return PasswordHasher.supported(hash)?hash:null; }
        catch(SQLException e) { throw new IOException("MySQL account read failed"); }
    }
    public boolean exists(String email) throws IOException { return findPasswordHash(email)!=null; }
}
