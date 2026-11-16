package MailServer;

import java.nio.file.*;
import java.sql.*;

/** Explicit maintenance only; never changes schema, passwords or grants automatically. */
public final class DatabaseTool {
    public static void main(String[] args) {
        try {
            if(args.length==0) throw new IllegalArgumentException();
            if(args[0].equals("dry-run")) {
                Path root=args.length>1?Path.of(args[1]):Path.of("mail_storage");
                MigrationService plan=new MigrationService(root);
                System.out.println("DRY RUN: accounts="+plan.accountCount()+", mailbox entries="+plan.entryCount()+", skipped="+plan.skippedCount()+"; no database writes"); return;
            }
            JdbcMailRepository sql=new JdbcMailRepository(DatabaseConfig.load()); sql.check();
            if(args[0].equals("check") && args.length==1) {
                DatabaseConfig config=DatabaseConfig.load();
                String query=config.isPostgreSQL()
                        ? "SELECT version(),current_database(),current_user"
                        : "SELECT VERSION(),DATABASE(),@@character_set_connection";
                try(Connection c=sql.connect(); PreparedStatement s=c.prepareStatement(query); ResultSet r=s.executeQuery()) {
                    r.next();
                    String engine=config.isPostgreSQL()?"PostgreSQL":"MySQL";
                    String label=config.isPostgreSQL()?"user":"charset";
                    System.out.println(engine+" connection/schema PASS; version="+r.getString(1)
                            +"; database="+r.getString(2)+"; "+label+"="+r.getString(3));
                }
                return;
            }
            if(args[0].equals("migrate") && args.length==4 && args[3].equals("--confirm-offline")) {
                MigrationService plan=new MigrationService(Path.of(args[1]));
                var result=plan.migrate(sql,Path.of(args[2]));
                System.out.println("Migration complete: users inserted="+result.usersInserted()+", mailbox entries inserted="+result.entriesInserted()+", skipped="+result.accountsSkipped()+"; verified backup="+result.backup()); return;
            }
            throw new IllegalArgumentException();
        } catch(IllegalArgumentException e) {
            System.err.println("Usage: DatabaseTool check | dry-run [storage] | migrate <storage> <backup-parent> --confirm-offline"); System.exit(2);
        } catch(Exception e) {
            System.err.println("Database operation failed. Verify local configuration, application permissions/schema and offline migration conflicts. No secrets logged; no file fallback."); System.exit(1);
        }
    }
}
