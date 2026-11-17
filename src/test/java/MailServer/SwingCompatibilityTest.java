package MailServer;

import MailClient.MailClientGUI;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SwingCompatibilityTest {
    private <T> T field(Object instance, String name, Class<T> type) throws Exception {
        Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(instance));
    }
    private void click(MailClientGUI gui, String field) throws Exception { SwingUtilities.invokeAndWait(() -> {
        try { field(gui, field, JButton.class).doClick(); } catch (Exception e) { throw new RuntimeException(e); }
    }); }
    private void waitFor(MailClientGUI gui, String label, String contains) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        AtomicBoolean done = new AtomicBoolean();
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> {
                try { done.set(field(gui, label, JLabel.class).getText().contains(contains)); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            if (done.get()) return;
            Thread.sleep(20);
        }
        fail("GUI did not reach expected state for " + label);
    }
    private void waitForInboxSize(MailClientGUI gui, int expectedSize) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        AtomicBoolean done = new AtomicBoolean();
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    done.set(field(gui, "listFiles", JList.class).getModel().getSize()
                            == expectedSize);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            if (done.get()) return;
            Thread.sleep(20);
        }
        fail("Inbox did not automatically reach " + expectedSize + " message(s)");
    }
    private void login(MailClientGUI gui, int port, String owner) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                field(gui, "txtServerHost", JTextField.class).setText("127.0.0.1");
                field(gui, "txtServerPort", JTextField.class).setText(Integer.toString(port));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        click(gui, "btnConnect"); waitFor(gui, "lblConnection", "ONLINE");
        SwingUtilities.invokeAndWait(() -> {
            try {
                field(gui, "txtUsername", JTextField.class).setText(owner);
                field(gui, "txtPassword", JPasswordField.class).setText("synthetic-password");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        click(gui, "btnLogin"); waitFor(gui, "lblSessionUser", owner);
    }
    @Test void twoSwingClientsReceiveAutomaticallyReadAndLogout() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Swing display unavailable");
        Path root = Files.createTempDirectory(Path.of("target"), "swing-");
        MailStorageService storage = new MailStorageService(root);
        assertTrue(storage.register("swing.a@gmail.com", "synthetic-password"));
        assertTrue(storage.register("swing.b@gmail.com", "synthetic-password"));
        UdpMailServer server = new UdpMailServer("127.0.0.1", 0, storage, null);
        Thread thread = new Thread(() -> { try { server.run(); } catch (Exception e) { throw new RuntimeException(e); } }); thread.start();
        MailClientGUI[] clients = new MailClientGUI[2];
        try {
            // Construct real Swing windows on EDT, keep them hidden to avoid interrupting the user.
            SwingUtilities.invokeAndWait(() -> { clients[0] = new MailClientGUI(); clients[1] = new MailClientGUI(); });
            login(clients[0], server.getPort(), "swing.a@gmail.com"); login(clients[1], server.getPort(), "swing.b@gmail.com");
            click(clients[0], "btnRefresh"); waitFor(clients[0], "lblStatus", "Đã làm mới hộp thư");
            System.out.println("PASS Swing refresh uses session after password cleared");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    field(clients[0], "txtToUser", JTextField.class).setText("swing.b@gmail.com");
                    field(clients[0], "txtSubject", JTextField.class).setText("Swing test");
                    field(clients[0], "txtContent", JTextArea.class).setText("Swing test body");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            click(clients[0], "btnSend"); waitFor(clients[0], "lblStatus", "Đã gửi thư");
            waitForInboxSize(clients[1], 1);
            waitFor(clients[1], "lblStatus", "thư mới");
            System.out.println("PASS recipient receives new mail automatically without Refresh");
            SwingUtilities.invokeAndWait(() -> {
                try { field(clients[1], "listFiles", JList.class).setSelectedIndex(0); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            waitFor(clients[1], "lblStatus", "Đang xem:");
            AtomicBoolean content = new AtomicBoolean();
            SwingUtilities.invokeAndWait(() -> {
                try { content.set(field(clients[1], "txtMailContent", JTextArea.class).getText().contains("Swing test body")); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            assertTrue(content.get()); System.out.println("PASS two actual Swing clients send/auto-receive/read through UDP session adapter");
            click(clients[0], "btnLogoutNav"); waitFor(clients[0], "lblSessionUser", "Chưa đăng nhập");
            System.out.println("PASS Swing logout clears current account");
        } finally {
            SwingUtilities.invokeAndWait(() -> { for (MailClientGUI client : clients) if (client != null) client.dispatchEvent(new WindowEvent(client, WindowEvent.WINDOW_CLOSING)); });
            server.close(); thread.join(3000);
        }
    }
}
