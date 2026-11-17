package MailClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import javax.swing.JButton;
import javax.swing.JPasswordField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailClientAuthPagesTest {
    @SuppressWarnings("unchecked")
    private static <T> T field(Object target,String name,Class<T> type) throws Exception {
        Field field=target.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target));
    }

    @Test void loginAndRegistrationAreSeparateAndEyeControlsBothRegistrationFields() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        MailClientGUI[] value=new MailClientGUI[1];
        SwingUtilities.invokeAndWait(() -> value[0]=new MailClientGUI());
        MailClientGUI gui=value[0];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { field(gui,"btnOpenRegister",JButton.class).doClick(); }
                catch(Exception exception) { throw new RuntimeException(exception); }
            });
            assertTrue(field(gui,"showingRegister",Boolean.class));
            JPasswordField password=field(gui,"txtRegisterPassword",JPasswordField.class);
            JPasswordField confirmation=field(gui,"txtRegisterConfirm",JPasswordField.class);
            JToggleButton eye=field(gui,"btnToggleRegisterPassword",JToggleButton.class);
            SwingUtilities.invokeAndWait(eye::doClick);
            assertEquals(0,password.getEchoChar()); assertEquals(0,confirmation.getEchoChar());
            SwingUtilities.invokeAndWait(() -> {
                try { field(gui,"btnBackLogin",JButton.class).doClick(); }
                catch(Exception exception) { throw new RuntimeException(exception); }
            });
            assertFalse(field(gui,"showingRegister",Boolean.class));
        } finally {
            SwingUtilities.invokeAndWait(() -> gui.dispatchEvent(new WindowEvent(gui,WindowEvent.WINDOW_CLOSING)));
        }
    }
}
