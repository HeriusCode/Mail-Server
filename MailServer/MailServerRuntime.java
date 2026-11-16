package MailServer;

import java.io.IOException;

/** One lifecycle for existing UDP UI API, SMTP submission and POP3 retrieval. */
public final class MailServerRuntime implements AutoCloseable {
    private final UdpMailServer udp;
    private final LocalSmtpServer smtp;
    private final Pop3Server pop3;
    public MailServerRuntime(String host, int port, MailStorageService storage, UdpMailServer.Listener listener) throws IOException {
        this(host, port, readPort("MAIL_SMTP_PORT", 2525), readPort("MAIL_POP3_PORT", 1110), storage, listener);
    }
    public MailServerRuntime(String host, int port, int smtpPort, int popPort, MailStorageService storage, UdpMailServer.Listener listener) throws IOException {
        java.util.function.Consumer<String> log = listener == null ? System.out::println : listener::onLog;
        // Bind TCP endpoints before publishing the runtime; unwind partial startup on failure.
        smtp = new LocalSmtpServer(smtpPort, storage, log);
        Pop3Server pop = null;
        try {
            pop = new Pop3Server(popPort, storage, log);
            udp = new UdpMailServer(host, port, storage, listener); pop3 = pop;
        } catch (IOException | RuntimeException e) { smtp.close(); if (pop != null) pop.close(); throw e; }
    }
    private static int readPort(String name, int fallback) {
        int port = Integer.parseInt(System.getenv().getOrDefault(name, Integer.toString(fallback)));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid protocol port");
        return port;
    }
    public void run() throws IOException { udp.run(); }
    public int getUdpPort() { return udp.getPort(); }
    public int getSmtpPort() { return smtp.getPort(); }
    public int getPop3Port() { return pop3.getPort(); }
    @Override public void close() { udp.close(); smtp.close(); pop3.close(); }
}
