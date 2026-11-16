package MailClient;

import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.pop3.POP3Folder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Interactive proof using actual SMTP and POP3 providers, independent of the UDP API. */
public final class ProtocolDemo {
    private static String input(Scanner scanner, String prompt, boolean secret, boolean interactive) {
        if (secret && interactive && System.console() != null) {
            char[] value = System.console().readPassword("%s", prompt);
            if (value == null) throw new IllegalStateException("Input cancelled");
            try { return new String(value); } finally { Arrays.fill(value, '\0'); }
        }
        System.out.print(prompt); return scanner.nextLine();
    }
    public static void run(Scanner scanner) throws Exception {
        run(scanner, Integer.parseInt(System.getenv().getOrDefault("MAIL_SMTP_PORT", "2525")),
                Integer.parseInt(System.getenv().getOrDefault("MAIL_POP3_PORT", "1110")));
    }
    public static void run(Scanner scanner, int smtpPort, int popPort) throws Exception {
        run(scanner, smtpPort, popPort, false);
    }
    private static void run(Scanner scanner, int smtpPort, int popPort, boolean interactive) throws Exception {
            String sender = input(scanner, "Local sender email: ", false, interactive).trim().toLowerCase(Locale.ROOT);
            String senderPassword = input(scanner, "Sender password: ", true, interactive);
            String recipient = input(scanner, "Local recipient email: ", false, interactive).trim().toLowerCase(Locale.ROOT);
            String recipientPassword = input(scanner, "Recipient password (POP3): ", true, interactive);
            Properties props = new Properties(); props.setProperty("mail.host", "localhost"); props.setProperty("mail.user", "mailflow");
            props.setProperty("mail.smtp.localhost", "localhost"); props.setProperty("mail.smtp.auth", "true"); props.setProperty("mail.smtp.auth.mechanisms", "PLAIN");
            for (String protocol : new String[]{"smtp", "pop3"}) {
                props.setProperty("mail." + protocol + ".connectiontimeout", "5000"); props.setProperty("mail." + protocol + ".timeout", "10000");
            }
            Session session = Session.getInstance(props); String subject = "MailFlow SMTP demo " + UUID.randomUUID();
            MimeMessage message = new MimeMessage(session); message.setFrom(sender); message.setRecipients(Message.RecipientType.TO, recipient);
            message.setSubject(subject, "UTF-8"); message.setText("SMTP/POP3 localhost thật.\n.dot-stuffing demo", "UTF-8"); message.saveChanges();
            try (Transport transport = session.getTransport("smtp")) {
                transport.connect("127.0.0.1", smtpPort, sender, senderPassword); transport.sendMessage(message, message.getAllRecipients());
            }
            System.out.println("PASS SMTP: message accepted by the local SMTP server");
            boolean found = false;
            try (Store store = session.getStore("pop3")) {
                store.connect("127.0.0.1", popPort, recipient, recipientPassword);
                POP3Folder folder = (POP3Folder) store.getFolder("INBOX"); folder.open(Folder.READ_ONLY);
                for (Message item : folder.getMessages()) {
                    System.out.println("POP3 filename/UIDL: " + folder.getUID(item));
                    if (subject.equals(item.getSubject())) {
                        System.out.println("PASS POP3 RETR: " + item.getSubject()); System.out.println(item.getContent()); found = true;
                    }
                }
                folder.close(false);
            }
            if (!found) throw new IllegalStateException("Submitted mail not found via POP3");
    }
    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            run(scanner, Integer.parseInt(System.getenv().getOrDefault("MAIL_SMTP_PORT", "2525")),
                    Integer.parseInt(System.getenv().getOrDefault("MAIL_POP3_PORT", "1110")), true);
        } catch (Exception e) {
            System.err.println("FAIL protocol demo: " + e.getClass().getSimpleName() + "; check local accounts, passwords, server and ports.");
            System.exit(1);
        }
    }
}
