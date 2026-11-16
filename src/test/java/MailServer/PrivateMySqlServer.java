package MailServer;

import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** A real, disposable mysqld process; never reads MySQL80 options or existing credentials. */
final class PrivateMySqlServer implements AutoCloseable {
    final Path directory,binary;
    final int port;
    final String user="mailflow_test_app",password=UUID.randomUUID().toString().replace("-","");
    private String adminPassword="";
    private Process process;
    PrivateMySqlServer(Path binary) throws Exception {
        this.binary=binary;
        directory=Files.createTempDirectory("mailflow-mysql-test-");
        try(ServerSocket reservation=new ServerSocket(0)) { port=reservation.getLocalPort(); }
        Process init=new ProcessBuilder(binary.toString(),"--no-defaults","--initialize-insecure","--basedir="+binary.getParent().getParent(),
                "--datadir="+directory.resolve("data"),"--innodb-log-file-size=8M","--innodb-buffer-pool-size=32M")
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("initialize.log").toFile()).start();
        if(!init.waitFor(45,TimeUnit.SECONDS)) { init.destroyForcibly(); fail("Private MySQL initialization timed out"); }
        assertEquals(0,init.exitValue(),"Private MySQL initialization; inspect "+directory.resolve("initialize.log"));
        try {
            start();
            try(Connection c=admin()) {
                String privateAdmin=UUID.randomUUID().toString().replace("-","");
                try(PreparedStatement s=c.prepareStatement("ALTER USER 'root'@'localhost' IDENTIFIED BY ?")) { s.setString(1,privateAdmin); s.execute(); }
                adminPassword=privateAdmin;
                try(Statement s=c.createStatement()) { s.execute("CREATE DATABASE mail_server_db_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"); }
                try(PreparedStatement s=c.prepareStatement("CREATE USER 'mailflow_test_app'@'127.0.0.1' IDENTIFIED BY ?")) { s.setString(1,password); s.execute(); }
                String schema=Files.readString(Path.of("database/schema.sql")).replaceAll("(?m)^--.*$","");
                try(Statement s=c.createStatement()) { s.execute("USE mail_server_db_test"); }
                for(String sql:schema.split(";")) {
                    String statement=sql.trim(); if(statement.isEmpty() || statement.startsWith("CREATE DATABASE") || statement.startsWith("USE ")) continue;
                    try(PreparedStatement s=c.prepareStatement(statement)) { s.execute(); }
                }
                try(Statement s=c.createStatement()) { s.execute("GRANT SELECT,INSERT,UPDATE ON mail_server_db_test.* TO 'mailflow_test_app'@'127.0.0.1'"); }
            }
            System.out.println("PASS private real MySQL started on loopback port "+port+"; independent datadir="+directory+"; separate application user");
        } catch(Exception | AssertionError e) { if(process!=null) { process.destroy(); process.waitFor(10,TimeUnit.SECONDS); if(process.isAlive()) process.destroyForcibly(); } throw e; }
    }
    String url() { return "jdbc:mysql://127.0.0.1:"+port+"/mail_server_db_test?sslMode=PREFERRED"; }
    DatabaseConfig config() { return new DatabaseConfig(url(),user,password); }
    Connection admin() throws SQLException {
        Properties p=new Properties(); p.setProperty("user","root"); p.setProperty("password",adminPassword);
        p.setProperty("connectTimeout","1000"); p.setProperty("socketTimeout","3000");
        return DriverManager.getConnection("jdbc:mysql://127.0.0.1:"+port+"/mysql?sslMode=PREFERRED",p);
    }
    void start() throws Exception {
        if(process!=null && process.isAlive()) throw new IllegalStateException("Private MySQL already running");
        process=new ProcessBuilder(binary.toString(),"--no-defaults","--console","--basedir="+binary.getParent().getParent(),"--datadir="+directory.resolve("data"),
                "--port="+port,"--bind-address=127.0.0.1","--mysqlx=OFF","--skip-log-bin",
                "--local-infile=0","--secure-file-priv=NULL","--max-connections=20","--performance-schema=OFF",
                "--innodb-buffer-pool-size=32M","--innodb-log-file-size=8M","--innodb-buffer-pool-instances=1",
                "--log-error="+directory.resolve("mysqld.log"),"--pid-file="+directory.resolve("mysqld.pid"))
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("console.log").toFile()).start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15); SQLException last=null;
        while(System.nanoTime()<deadline) {
            if(!process.isAlive()) fail("Private mysqld exited; inspect "+directory.resolve("mysqld.log"));
            try(Connection ignored=admin()) { return; } catch(SQLException e) { last=e; Thread.sleep(150); }
        }
        throw new IllegalStateException("Private MySQL connection failed: "+(last==null?"no response":last.getSQLState()+"/"+last.getErrorCode()+" "+last.getMessage()));
    }
    void stop() throws Exception {
        if(process==null || !process.isAlive()) return;
        try(Connection c=admin(); Statement s=c.createStatement()) { s.execute("SHUTDOWN"); }
        catch(SQLException e) { /* SHUTDOWN can close the connection before returning its acknowledgement. */ }
        if(!process.waitFor(15,TimeUnit.SECONDS)) { process.destroy(); if(!process.waitFor(5,TimeUnit.SECONDS)) process.destroyForcibly(); fail("Private MySQL did not shut down cleanly"); }
        assertEquals(0,process.exitValue(),"Private MySQL clean shutdown");
    }
    public void close() throws Exception { stop(); System.out.println("PASS private MySQL stopped; datadir retained, no user database/service touched"); }
}
