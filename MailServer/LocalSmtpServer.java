package MailServer;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Real SMTP submission for local accounts. Authentication required; no external relay. */
public final class LocalSmtpServer implements AutoCloseable {
    private final TcpMailListener listener;
    public LocalSmtpServer(int port, MailStorageService storage, Consumer<String> log) throws IOException {
        listener = new TcpMailListener(port, wire -> serve(wire, storage, log), log, "SMTP");
    }
    public int getPort() { return listener.port(); }
    @Override public void close() { listener.close(); }
    private static void serve(TcpMailListener.Wire wire, MailStorageService storage, Consumer<String> log) throws Exception {
        wire.line("220 localhost MailFlow SMTP ready");
        String owner = null, sender = null, recipient = null; boolean greeted = false; int attempts = 0;
        for (String line; (line = wire.read(1024)) != null;) {
            String[] command = line.split(" ", 2); String verb = command[0].toUpperCase(Locale.ROOT);
            String argument = command.length == 2 ? command[1] : "";
            if ((Set.of("DATA", "RSET", "QUIT").contains(verb) && !argument.isEmpty())
                    || ((verb.equals("EHLO") || verb.equals("HELO"))
                    && (argument.isEmpty() || argument.chars().anyMatch(c -> c <= 32 || c >= 127)))) {
                wire.line("501 Invalid command arguments"); continue;
            }
            if (verb.equals("QUIT")) { wire.line("221 Bye"); return; }
            if (verb.equals("EHLO") || verb.equals("HELO")) {
                greeted = true; sender = null; recipient = null;
                if (verb.equals("EHLO")) { wire.line("250-localhost"); wire.line("250-AUTH PLAIN"); wire.line("250 SIZE 128000"); }
                else wire.line("250 localhost");
            } else if (verb.equals("AUTH")) {
                if (!greeted || owner != null) { wire.line("503 Bad sequence"); continue; }
                String[] auth = argument.split(" ", 2);
                if (!auth[0].equalsIgnoreCase("PLAIN")) { wire.line("504 Only AUTH PLAIN supported"); continue; }
                String encoded;
                if (auth.length == 2) encoded = auth[1]; else { wire.line("334 "); encoded = wire.read(1024); }
                String[] fields;
                try { fields = new String(Base64.getDecoder().decode(encoded == null ? "" : encoded), StandardCharsets.UTF_8).split("\u0000", -1); }
                catch (IllegalArgumentException e) { fields = new String[0]; }
                if (fields.length == 3 && (fields[0].isEmpty() || fields[0].equals(fields[1]))
                        && storage.authenticate(fields[1], fields[2])) {
                    owner = MailValidation.normalizeEmail(fields[1]); wire.line("235 Authentication successful");
                } else { wire.line("535 Authentication failed"); if (++attempts >= 3) return; }
            } else if (verb.equals("RSET")) { sender = null; recipient = null; wire.line("250 Reset"); }
            else if (verb.equals("NOOP")) wire.line("250 OK");
            else if (verb.equals("MAIL")) {
                sender = null; recipient = null;
                if (owner == null) { wire.line("530 Authentication required"); continue; }
                String address = address(argument, "FROM:");
                if (address == null) { wire.line("501 Invalid reverse path"); continue; }
                if (!owner.equals(address)) { wire.line("553 Sender must match authenticated account"); continue; }
                sender = owner; wire.line("250 Sender accepted");
            } else if (verb.equals("RCPT")) {
                if (sender == null) { wire.line("503 MAIL required"); continue; }
                if (recipient != null) { wire.line("452 One recipient per transaction"); continue; }
                String address = address(argument, "TO:");
                if (address == null) { wire.line("501 Invalid forward path"); continue; }
                if (!storage.accountExists(address)) { wire.line("550 Local recipient unavailable; relay denied"); continue; }
                recipient = address; wire.line("250 Recipient accepted");
            } else if (verb.equals("DATA")) {
                if (sender == null || recipient == null) { wire.line("503 MAIL and RCPT required"); continue; }
                wire.line("354 End with <CRLF>.<CRLF>");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                boolean tooLarge = false; String data;
                while ((data = wire.read(2048)) != null && !data.equals(".")) {
                    if (data.startsWith("..")) data = data.substring(1);
                    byte[] chunk = (data + "\r\n").getBytes(StandardCharsets.UTF_8);
                    if (bytes.size() + chunk.length > 128_000) tooLarge = true;
                    if (!tooLarge) bytes.write(chunk);
                }
                if (data == null) return;
                if (tooLarge) { wire.line("552 Message too large"); sender = recipient = null; continue; }
                try {
                    MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes.toByteArray()));
                    Address[] from = message.getFrom(); String body = MimeSupport.text(message), subject = message.getSubject();
                    if (from == null || from.length != 1 || !(from[0] instanceof InternetAddress)
                            || !owner.equals(MailValidation.normalizeEmail(((InternetAddress) from[0]).getAddress()))) {
                        wire.line("553 From header does not match session");
                    } else if (body != null && body.getBytes(StandardCharsets.UTF_8).length > 32_000) {
                        wire.line("552 Message body too large");
                    } else if (body == null || body.isBlank()
                            || subject == null || subject.length() > 998 || subject.indexOf('\n') >= 0 || subject.indexOf('\r') >= 0) wire.line("554 Plain text body and valid subject required");
                    else {
                        boolean stored = storage.saveMail(owner, recipient, MimeSupport.canonical(owner, recipient, subject, body));
                        wire.line(stored ? "250 Stored locally" : "451 Storage unavailable"); log.accept(stored ? "SMTP message stored" : "SMTP storage failed");
                    }
                } catch (Exception e) { wire.line("451 Message processing failed"); log.accept("SMTP processing failed: " + e.getClass().getSimpleName()); }
                sender = recipient = null;
            } else wire.line("502 Command not implemented");
        }
    }
    private static String address(String value, String prefix) {
        if (!value.regionMatches(true, 0, prefix, 0, prefix.length())) return null;
        String rest = value.substring(prefix.length()).trim();
        if (!rest.startsWith("<") || !rest.contains(">")) return null;
        int end = rest.indexOf('>'); String trailing = rest.substring(end + 1).trim();
        if (!trailing.isEmpty() && !trailing.matches("(?i)SIZE=[0-9]+")) return null;
        String address = MailValidation.normalizeEmail(rest.substring(1, end));
        return MailValidation.isValidEmail(address) ? address : null;
    }
}
