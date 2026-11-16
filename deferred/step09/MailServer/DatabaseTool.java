package MailServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

/** Explicit maintenance entry point; never logs configuration values or SQL exception details. */
public final class DatabaseTool {
    public static void main(String[] args) {
        try {
            if (args.length == 0) throw new IllegalArgumentException();
            String action = args[0];
            Path root = args.length > 1 ? Path.of(args[1]) : Path.of("mail_storage");
            if (action.equals("inspect")) {
                if (!Files.exists(root)) { System.out.println("No legacy storage directory; nothing to migrate."); return; }
                MigrationService migration = new MigrationService(root);
                System.out.println("Validated legacy accounts=" + migration.accountCount() + ", entries=" + migration.entryCount() + ", skipped accounts=" + migration.skippedCount());
                return;
            }
            DatabaseConfig config = DatabaseConfig.load();
            if (config == null) {
                System.err.println("NOT RUN: missing MAIL_DB_URL / MAIL_DB_USER / MAIL_DB_PASSWORD or local config.");
                System.exit(2); return;
            }
            JdbcMailRepository repository = new JdbcMailRepository(config);
            switch (action) {
                case "check" -> { repository.check(); System.out.println("MySQL connection and users schema: PASS"); }
                case "init" -> {
                    try (Connection c = config.connect()) {
                        String sql = Files.readString(Path.of("database/schema.sql"));
                        // Explicit DDL only; ordinary runtime never creates databases/users.
                        sql = sql.replaceAll("(?m)^--.*$", "");
                        for (String statement : sql.split(";")) if (!statement.isBlank()) {
                            try (var s = c.prepareStatement(statement)) { s.execute(); }
                        }
                    }
                    System.out.println("mail_server_db schema initialized without dropping existing tables.");
                }
                case "recover" -> {
                    new MailStorageService(root, repository).recoverPending();
                    System.out.println("Pending file outbox recovered.");
                }
                case "migrate" -> {
                    if (args.length != 3) throw new IllegalArgumentException();
                    MigrationService.Result result = new MigrationService(root).migrate(repository, Path.of(args[2]));
                    System.out.println("Migration complete: users inserted=" + result.usersInserted() + ", entries inserted=" + result.entriesInserted()
                            + ", skipped accounts=" + result.accountsSkipped() + "; verified backup=" + result.backup());
                }
                default -> throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Usage: DatabaseTool check|init|inspect [storage] | recover [storage] | migrate <storage> <backup-parent>");
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Maintenance failed; no success claimed. Check DB permissions/schema, file paths and migration conflicts. Source files are preserved.");
            System.exit(1);
        }
    }
}
