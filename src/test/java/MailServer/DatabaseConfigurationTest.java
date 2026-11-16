package MailServer;

import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseConfigurationTest {
    @Test void fileDefaultIgnoresUnselectedDbAndMysqlFailsClosed() throws Exception {
        Path root=Files.createTempDirectory(Path.of("target"),"backend-config-"); Properties p=new Properties();
        p.setProperty("MAIL_DB_URL","invalid-unused-url");
        var file=RepositoryFactory.configured(root.resolve("file"),p); assertInstanceOf(FileUserRepository.class,file.users());
        p.setProperty("MAIL_STORAGE_BACKEND","unknown"); assertThrows(java.io.IOException.class,()->RepositoryFactory.configured(root,p));
        int port; try(ServerSocket s=new ServerSocket(0)) { port=s.getLocalPort(); }
        p.setProperty("MAIL_STORAGE_BACKEND","MYSQL"); p.setProperty("MAIL_DB_URL","jdbc:mysql://127.0.0.1:"+port+"/mail_server_db");
        p.setProperty("MAIL_DB_USER","synthetic-app"); p.setProperty("MAIL_DB_PASSWORD","synthetic-config-only");
        assertThrows(java.io.IOException.class,()->RepositoryFactory.configured(root.resolve("not-created"),p));
        assertFalse(Files.exists(root.resolve("not-created")));
        System.out.println("PASS default FILE independent of DB config; MYSQL connection failure does not silently write to FILE");
    }
    @Test void credentialsRootAndUnsafeUrlsRejectedAndConfigPrecedence() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new DatabaseConfig("jdbc:mysql://localhost/mail_server_db","root","synthetic"));
        assertThrows(IllegalArgumentException.class,()->new DatabaseConfig("jdbc:mysql://localhost/mail_server_db?password=forbidden","app","synthetic"));
        assertThrows(IllegalArgumentException.class,()->new DatabaseConfig("jdbc:mysql://localhost/mail_server_db?allowMultiQueries=true","app","synthetic"));
        Path root=Files.createTempDirectory(Path.of("target"),"db-settings-"); Path file=root.resolve("synthetic.properties");
        Files.writeString(file,"MAIL_STORAGE_BACKEND=FILE\nMAIL_DB_USER=old-app\n");
        Properties settings=DatabaseConfig.settings(file,Map.of("MAIL_STORAGE_BACKEND","MYSQL","MAIL_DB_USER","new-app"));
        assertEquals("MYSQL",settings.getProperty("MAIL_STORAGE_BACKEND")); assertEquals("new-app",settings.getProperty("MAIL_DB_USER"));
        System.out.println("PASS DB root/URL secrets/unsafe options rejected; isolated local config and environment precedence");
    }
    @Test void migrationDryRunIsOfflineAndPreservesSource() throws Exception {
        Path root=Files.createTempDirectory(Path.of("target"),"dry-run-").resolve("mail");
        MailStorageService files=new MailStorageService(root); assertTrue(files.register("dry@local.test","synthetic-dryrun"));
        String credential=Files.readString(root.resolve("dry@local.test/.credentials"));
        MigrationService plan=new MigrationService(root); assertEquals(1,plan.accountCount()); assertEquals(1,plan.entryCount());
        assertEquals(credential,Files.readString(root.resolve("dry@local.test/.credentials")));
        assertEquals(MailStorageService.WELCOME_MESSAGE,files.readMail("dry@local.test","new_email.txt"));
        assertThrows(java.io.IOException.class,()->plan.backup(root.resolve("unsafe-backup")));
        System.out.println("PASS offline migration dry-run preserves all source bytes and refuses backup inside source");
    }
}
