import MailClient.UdpMailClient;
import MailServer.MailStorageService;
import MailServer.UdpMailServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.*;

/** Read-only audit of production code; writes only synthetic fixtures under out/audit. */
public class Step01Audit {
    private static int passed, failed;
    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static void check(String id, boolean ok, String evidence) {
        System.out.println((ok ? "PASS " : "FAIL ") + id + " | " + evidence);
        if (ok) passed++; else failed++;
    }
    private static Thread start(UdpMailServer server) {
        Thread thread = new Thread(() -> {
            try { server.run(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of("out/audit"), "fixtures-");
        System.out.println("Synthetic fixture root: " + root);
        String a = "audit.a@gmail.com", b = "audit.b@gmail.com";
        // Synthetic test secret, never an external account credential.
        String password = "audit-fixture-only";
        int port;
        UdpMailServer server = new UdpMailServer("127.0.0.1", 0,
                new MailStorageService(root), null);
        Thread thread = start(server);
        port = server.getPort();
        String mailFile;
        try (UdpMailClient first = new UdpMailClient("127.0.0.1", port);
             UdpMailClient second = new UdpMailClient("127.0.0.1", port);
             UdpMailClient anonymous = new UdpMailClient("127.0.0.1", port)) {
            check("A01 UDP ping", first.ping(), "loopback PONG");
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<String> ra = executor.submit(() -> first.sendRequest("REGISTER " + a + " " + encode(password)));
                Future<String> rb = executor.submit(() -> second.sendRequest("REGISTER " + b + " " + encode(password)));
                check("A02 concurrent registration", "REGISTER_SUCCESS".equals(ra.get()) && "REGISTER_SUCCESS".equals(rb.get()), "two independent client sockets");
            } finally { executor.shutdownNow(); }
            check("A03 duplicate rejected", first.sendRequest("REGISTER " + a + " " + encode(password)).startsWith("REGISTER_FAILED"), "REGISTER_FAILED");
            check("A04 invalid address rejected", first.sendRequest("REGISTER ../invalid " + encode(password)).startsWith("REGISTER_FAILED"), "REGISTER_FAILED");
            check("A05 account files", Files.isDirectory(root.resolve(a).resolve(".sent")) && Files.isRegularFile(root.resolve(a).resolve("new_email.txt")), "account directory, .sent and new_email.txt exist");
            String welcome = Files.readString(root.resolve(a).resolve("new_email.txt"));
            check("A06 required welcome", welcome.contains("Thank you for using this service. We hope that you will feel comfortable using our email service."), "compare against required English welcome");
            String hash = Files.readString(root.resolve(a).resolve(".credentials"));
            check("A07 password hashed", (hash.split(":").length == 3 || hash.matches("\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}")) && !hash.contains(password), "credential format PBKDF2 or BCrypt; values withheld");
            check("A08 wrong password", first.sendRequest("LOGIN " + a + " " + encode("wrong-secret")).startsWith("LOGIN_FAILED"), "LOGIN_FAILED");
            check("A09 valid login and filenames", first.sendRequest("LOGIN " + a + " " + encode(password)).startsWith("LOGIN_SUCCESS|new_email.txt;"), "LOGIN_SUCCESS with filename/subject/time metadata");
            String content = "Người gửi: " + a + "\nNgười nhận: " + b + "\nTiêu đề: Audit UTF-8\n\nNội dung thử nghiệm\n.second line";
            check("A10 local delivery", "SEND_SUCCESS".equals(first.sendRequest("SEND " + b + " " + encode(content))), "SEND_SUCCESS");
            String listing = second.sendRequest("LOGIN " + b + " " + encode(password));
            try (var files = Files.list(root.resolve(b))) {
                mailFile = files.filter(p -> p.getFileName().toString().startsWith("mail_")).findFirst().orElseThrow().getFileName().toString();
            }
            check("A11 mailbox listing", listing.contains(mailFile), "recipient login includes delivered filename");
            String expected = Files.readString(root.resolve(b).resolve(mailFile));
            String read = second.sendRequest("READ " + b + " " + mailFile);
            check("A12 UTF-8 read and metadata", read.equals("MAIL_CONTENT|" + encode(expected)) && expected.contains("Thời gian nhận:") && expected.contains("Nội dung thử nghiệm"), "wire content matches recipient file");
            check("A13 sent copy", first.sendRequest("SENT " + a).contains("sent_"), "SENT_SUCCESS contains sent filename");
            check("A14 unauthenticated read denied", !anonymous.sendRequest("READ " + b + " " + mailFile).startsWith("MAIL_CONTENT|"), "check anonymous READ cannot return content");
            check("A15 credentials inaccessible", !anonymous.sendRequest("READ " + b + " .credentials").startsWith("MAIL_CONTENT|"), "check credential file is not served; values withheld");
            check("A16 unauthenticated sent list denied", !anonymous.sendRequest("SENT " + a).startsWith("SENT_SUCCESS|"), "check anonymous SENT cannot list mail");
            check("A17 sender spoofing denied", !"SEND_SUCCESS".equals(anonymous.sendRequest("SEND " + b + " " + encode(content))), "check anonymous SEND cannot claim sender a");
            check("A18 traversal denied", first.sendRequest("READ " + a + " ../" + b + "/new_email.txt").startsWith("READ_FAILED"), "normalized path prevents ../ escape");
            check("A19 unregistered recipient", first.sendRequest("SEND absent.audit@gmail.com " + encode(content.replace("Người nhận: " + b, "Người nhận: absent.audit@gmail.com"))).startsWith("SEND_FAILED"), "no SMTP fallback; SEND_FAILED");
            try {
                first.sendRequest("X".repeat(60_001));
                check("A20 oversized request", false, "no size rejection");
            } catch (java.io.IOException expectedError) {
                check("A20 oversized request", true, "client throws IOException before sending");
            }
        } finally { server.close(); thread.join(5000); }
        try (UdpMailServer restarted = new UdpMailServer("127.0.0.1", port, new MailStorageService(root), null)) {
            Thread restartThread = start(restarted);
            try (UdpMailClient client = new UdpMailClient("127.0.0.1", port)) {
                check("A21 restart persistence", client.sendRequest("LOGIN " + b + " " + encode(password)).contains(mailFile), "new server/storage instance reads existing credentials and mail");
            } finally { restarted.close(); restartThread.join(5000); }
        }
        try (UdpMailClient disconnected = new UdpMailClient("127.0.0.1", port)) {
            try {
                disconnected.ping();
                check("A22 disconnected server", false, "unexpected response");
            } catch (java.io.IOException expectedError) {
                check("A22 disconnected server", true, expectedError.getClass().getSimpleName());
            }
        }
        System.out.println("TOTAL PASS=" + passed + " FAIL=" + failed);
        System.out.println("FAIL denotes an unmet audit expectation; see the matching STEP report for protocol changes.");
    }
}
