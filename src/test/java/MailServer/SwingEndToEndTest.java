package MailServer;

import MailClient.MailClientGUI;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual visible Swing windows; component operations execute only on the EDT. */
class SwingEndToEndTest {
    private <T> T field(Object object, String name, Class<T> type) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(object)); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    void click(MailClientGUI gui, String button) throws Exception { edt(() -> field(gui, button, JButton.class).doClick()); }
    private void await(String description, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        AtomicBoolean result = new AtomicBoolean();
        do {
            edt(() -> result.set(condition.getAsBoolean())); if (result.get()) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("Swing did not reach state: " + description);
    }
    void label(MailClientGUI gui, String name, String text) throws Exception {
        await(name + " contains " + text, () -> field(gui, name, JLabel.class).getText().contains(text));
    }
    void registerAndLogin(MailClientGUI gui, int port, String owner) throws Exception {
        edt(() -> {
            field(gui, "txtServerHost", JTextField.class).setText("127.0.0.1");
            field(gui, "txtServerPort", JTextField.class).setText(Integer.toString(port));
        });
        click(gui, "btnConnect"); label(gui, "lblConnection", "ONLINE");
        edt(() -> {
            field(gui, "txtUsername", JTextField.class).setText(owner);
            field(gui, "txtPassword", JPasswordField.class).setText(AutomatedEndToEndTest.PASSWORD);
        });
        click(gui, "btnRegister"); label(gui, "lblStatus", "Tạo tài khoản thành công");
        click(gui, "btnLogin"); label(gui, "lblSessionUser", owner);
        edt(() -> assertEquals(0, field(gui, "txtPassword", JPasswordField.class).getPassword().length));
    }
    void compose(MailClientGUI gui, String recipient, String subject, String body) throws Exception {
        click(gui, "btnComposeNav");
        edt(() -> {
            field(gui, "txtToUser", JTextField.class).setText(recipient);
            field(gui, "txtSubject", JTextField.class).setText(subject);
            field(gui, "txtContent", JTextArea.class).setText(body);
        });
        click(gui, "btnSend"); label(gui, "lblStatus", "Đã gửi thư tới " + recipient);
    }
    private void select(MailClientGUI gui, String listName, String subject) throws Exception {
        edt(() -> {
            JList<?> list = field(gui, listName, JList.class);
            for (int i = 0; i < list.getModel().getSize(); i++) {
                if (subject.equals(list.getModel().getElementAt(i).toString())) { list.setSelectedIndex(i); return; }
            }
            fail("Expected subject missing from Swing mailbox");
        });
    }
    void inbox(MailClientGUI gui, String subject, String body) throws Exception {
        click(gui, "btnInboxNav"); click(gui, "btnRefresh"); label(gui, "lblStatus", "Đã làm mới hộp thư");
        select(gui, "listFiles", subject);
        await("Inbox content", () -> field(gui, "txtMailContent", JTextArea.class).getText().endsWith(body));
    }
    void sent(MailClientGUI gui, String subject, String body) throws Exception {
        click(gui, "btnSentNav"); label(gui, "lblStatus", "Đã tải 1 thư đã gửi");
        select(gui, "listSent", subject);
        await("Sent content", () -> field(gui, "txtSentContent", JTextArea.class).getText().endsWith(body));
    }
    @Test @Timeout(120) void visibleClientsRegisterComposeTwoWayInboxSentAndLogout() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Visible Swing unavailable; visual UI NOT tested");
        Path root = Files.createTempDirectory(Path.of("target"), "swing-e2e-");
        MailClientGUI[] clients = new MailClientGUI[2];
        try (E2eTestServer server = new E2eTestServer(root)) {
            try {
                edt(() -> {
                    for (int i = 0; i < clients.length; i++) {
                        clients[i] = new MailClientGUI(); clients[i].setLocation(40 + i * 80, 40 + i * 60);
                        clients[i].setVisible(true); assertTrue(clients[i].isShowing()); assertTrue(clients[i].isDisplayable());
                    }
                });
                registerAndLogin(clients[0], server.udp, AutomatedEndToEndTest.A);
                registerAndLogin(clients[1], server.udp, AutomatedEndToEndTest.B);
                compose(clients[0], AutomatedEndToEndTest.B, AutomatedEndToEndTest.SUBJECT, AutomatedEndToEndTest.BODY);
                inbox(clients[1], AutomatedEndToEndTest.SUBJECT, AutomatedEndToEndTest.BODY);
                sent(clients[0], AutomatedEndToEndTest.SUBJECT, AutomatedEndToEndTest.BODY);
                compose(clients[1], AutomatedEndToEndTest.A, "Re: " + AutomatedEndToEndTest.SUBJECT, "Swing reply body");
                inbox(clients[0], "Re: " + AutomatedEndToEndTest.SUBJECT, "Swing reply body");
                sent(clients[1], "Re: " + AutomatedEndToEndTest.SUBJECT, "Swing reply body");
                for (MailClientGUI client : clients) {
                    click(client, "btnLogoutNav"); label(client, "lblSessionUser", "Chưa đăng nhập");
                    edt(() -> {
                        assertTrue(field(client, "listFiles", JList.class).getModel().getSize() == 0);
                        assertTrue(field(client, "listSent", JList.class).getModel().getSize() == 0);
                        assertEquals("", field(client, "txtContent", JTextArea.class).getText());
                    });
                }
                System.out.println("PASS Swing E2E: two visible displayable windows; EDT registration/login, Compose both directions, Inbox/Sent content, Logout clears mailboxes");
                System.out.println("Visual aesthetics, occlusion and physical mouse/keyboard interaction were NOT evaluated");
            } finally {
                edt(() -> { for (MailClientGUI client : clients) if (client != null) client.dispatchEvent(new WindowEvent(client, WindowEvent.WINDOW_CLOSING)); });
            }
        }
    }
}
