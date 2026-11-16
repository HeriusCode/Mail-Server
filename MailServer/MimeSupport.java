package MailServer;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class MimeSupport {
    static String canonical(String sender, String recipient, String subject, String body) {
        return "Người gửi: " + sender + "\nNgười nhận: " + recipient + "\nTiêu đề: " + subject + "\n\n" + body;
    }
    static String header(String raw, String prefix) {
        for (String line : raw.split("\\R")) { if (line.isEmpty()) break; if (line.startsWith(prefix)) return line.substring(prefix.length()).trim(); }
        return "";
    }
    static String body(String raw) { int i = raw.indexOf("\n\n"); return i < 0 ? raw : raw.substring(i + 2); }
    static String text(Part part) throws Exception { return text(part, 0); }
    private static String text(Part part, int depth) throws Exception {
        if (depth > 5) throw new MessagingException("MIME nesting limit");
        if (part.isMimeType("text/plain")) return (String) part.getContent();
        if (part.isMimeType("multipart/*")) {
            Multipart multi = (Multipart) part.getContent();
            for (int i = 0; i < Math.min(multi.getCount(), 20); i++) {
                Part child = multi.getBodyPart(i);
                if (!Part.ATTACHMENT.equalsIgnoreCase(child.getDisposition())) {
                    String value = text(child, depth + 1); if (value != null) return value;
                }
            }
        }
        return null;
    }
    static MimeMessage message(String from, String to, String subject, String body) throws Exception {
        Properties properties = new Properties(); properties.setProperty("mail.host", "localhost"); properties.setProperty("mail.user", "mailflow");
        MimeMessage message = new MimeMessage(Session.getInstance(properties));
        message.setFrom(new InternetAddress(from)); message.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        message.setSubject(subject, "UTF-8"); message.setText(body, "UTF-8"); message.setSentDate(new Date()); message.saveChanges();
        return message;
    }
    static byte[] popBytes(String raw, String owner, MailSummary summary) throws Exception {
        String from = header(raw, "Người gửi:");
        if (!MailValidation.isValidEmail(from)) from = "noreply@mailflow.local";
        MimeMessage message = message(from, owner, summary.getSubject(), body(raw));
        message.setSentDate(new Date(summary.getTimestamp()));
        message.setHeader("Message-ID", "<" + summary.getFileName() + "." + owner + ">");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); message.writeTo(bytes);
        String wire = bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
        if (!wire.endsWith("\r\n")) wire += "\r\n";
        return wire.getBytes(StandardCharsets.UTF_8);
    }
}
