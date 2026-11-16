package MailServer;

import MailClient.MailClientGUI;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Verify GUI outcome handling over UDP with synthetic responses; no SMTP/Gmail is contacted. */
class GmailGuiStatusTest {
    private <T> T field(Object object,String name,Class<T> type) {
        try { Field f=object.getClass().getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(object)); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private void click(MailClientGUI gui,String name) throws Exception { edt(()->field(gui,name,JButton.class).doClick()); }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10); AtomicBoolean done=new AtomicBoolean();
        do { edt(()->done.set(condition.getAsBoolean())); if(done.get()) return; Thread.sleep(25); } while(System.nanoTime()<deadline);
        fail("GUI did not display expected SMTP outcome");
    }
    @Test @Timeout(40) void distinguishesAcceptanceUncertaintyAndFailuresWithoutResending() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),"Swing display unavailable");
        AtomicReference<String> outcome=new AtomicReference<>(),failure=new AtomicReference<>(); AtomicInteger sends=new AtomicInteger();
        try(DatagramSocket socket=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))) {
            Thread server=new Thread(()-> {
                try {
                    while(!socket.isClosed()) {
                        DatagramPacket packet=new DatagramPacket(new byte[60001],60001); socket.receive(packet);
                        String request=new String(packet.getData(),0,packet.getLength(),StandardCharsets.UTF_8);
                        int separator=request.indexOf('|'); String id=request.substring(0,separator),body=request.substring(separator+1);
                        String result=body.equals("PING")?"PONG":body.startsWith("LOGIN ")?"LOGIN_SUCCESS|"+"A".repeat(43)+"|"
                                :body.contains(" SEND ")?outcome.get():body.endsWith(" LOGOUT")?"LOGOUT_SUCCESS":"INVALID_COMMAND";
                        if(body.contains(" SEND ")) sends.incrementAndGet();
                        byte[] response=(id+"|"+result).getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(response,response.length,packet.getSocketAddress()));
                    }
                } catch(Exception e) { if(!socket.isClosed()) failure.set(e.getClass().getSimpleName()); }
            },"step3-synthetic-ui-server"); server.setDaemon(true); server.start();
            MailClientGUI[] gui=new MailClientGUI[1];
            try {
                edt(()-> {
                    gui[0]=new MailClientGUI();
                    field(gui[0],"txtServerHost",JTextField.class).setText("127.0.0.1");
                    field(gui[0],"txtServerPort",JTextField.class).setText(Integer.toString(socket.getLocalPort()));
                });
                click(gui[0],"btnConnect"); await(()->field(gui[0],"lblConnection",JLabel.class).getText().contains("ONLINE"));
                edt(()-> { field(gui[0],"txtUsername",JTextField.class).setText("gui@local.test"); field(gui[0],"txtPassword",JPasswordField.class).setText("synthetic-ui-only"); });
                click(gui[0],"btnLogin"); await(()->field(gui[0],"lblSessionUser",JLabel.class).getText().equals("gui@local.test"));
                String[][] cases={
                    {"SMTP_ACCEPTED","SMTP đã chấp nhận","clear"},
                    {"SMTP_ACCEPTED_SENT_UNSAVED","chưa lưu được Sent","clear"},
                    {"SEND_UNCERTAIN: SMTP_TIMEOUT","Chưa xác định Gmail","keep"},
                    {"SEND_FAILED: SMTP_AUTH_FAILED","App Password","keep"},
                    {"SEND_FAILED: SMTP_TLS_FAILED","TLS thất bại","keep"},
                    {"SEND_FAILED: SMTP_REJECTED","từ chối thư","keep"},
                    {"SEND_FAILED: SMTP_TIMEOUT","chưa được gửi","keep"},
                    {"SEND_SUCCESS","Đã gửi thư tới","clear"}
                };
                for(String[] scenario:cases) {
                    outcome.set(scenario[0]);
                    edt(()-> {
                        field(gui[0],"txtToUser",JTextField.class).setText("recipient@example.invalid");
                        field(gui[0],"txtSubject",JTextField.class).setText("Synthetic GUI test");
                        field(gui[0],"txtContent",JTextArea.class).setText("synthetic body");
                    });
                    int previous=sends.get(); click(gui[0],"btnSend");
                    await(()->field(gui[0],"lblStatus",JLabel.class).getText().contains(scenario[1]));
                    edt(()->assertEquals(scenario[2].equals("clear")?"":"synthetic body",field(gui[0],"txtContent",JTextArea.class).getText()));
                    assertEquals(previous+1,sends.get(),"GUI must not resubmit automatically");
                }
                System.out.println("PASS Swing SMTP statuses: acceptance vs Gmail delivery, Sent failure, uncertain timeout, auth/TLS/rejection/pre-send timeout, local success; one submission each");
            } finally {
                edt(()-> { if(gui[0]!=null) gui[0].dispatchEvent(new WindowEvent(gui[0],WindowEvent.WINDOW_CLOSING)); });
                socket.close(); server.join(2000); assertFalse(server.isAlive()); assertNull(failure.get());
            }
        }
    }
}
