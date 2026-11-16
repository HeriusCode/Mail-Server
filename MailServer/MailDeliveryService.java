package MailServer;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;

/** Internal delivery and configured SMTP submission are distinct operations. */
public final class MailDeliveryService {
    private final MailStorageService storage;
    private final SmtpSettings smtp;
    private record Outcome(String fingerprint, String result) { }
    private final Map<String, Outcome> recent = new LinkedHashMap<>();
    public MailDeliveryService(MailStorageService storage, SmtpSettings smtp) { this.storage = storage; this.smtp = smtp; }
    static MailDeliveryService configured(MailStorageService storage, java.util.function.Consumer<String> log) {
        try { return new MailDeliveryService(storage, SmtpSettings.load()); }
        catch (IllegalStateException | IllegalArgumentException e) {
            log.accept("External SMTP disabled: invalid configuration; local mail remains available");
            return new MailDeliveryService(storage, null);
        }
    }
    public synchronized String send(String owner, String recipient, String content, String requestId) {
        if (storage.accountExists(recipient)) return storage.saveMail(owner, recipient, content, requestId) ? "SEND_SUCCESS" : "SEND_FAILED: Request conflict";
        if (smtp == null) return "SEND_FAILED: SMTP_NOT_CONFIGURED";
        String key = owner + ":" + requestId, fingerprint = fingerprint(recipient + "\n" + content);
        Outcome prior = recent.get(key);
        if (prior != null) return prior.fingerprint.equals(fingerprint) ? prior.result : "SEND_FAILED: Request conflict";
        String id = UUID.nameUUIDFromBytes((owner + ":external:" + requestId).getBytes(StandardCharsets.UTF_8)).toString();
        String existing = storage.readSentMail(owner, "sent_" + id + ".txt");
        if (existing != null) return existing.startsWith(content + "\n\nTrạng thái: SMTP_ACCEPTED\n") ? "SMTP_ACCEPTED" : "SEND_FAILED: Request conflict";
        // Never evict uncertain outcomes and thereby allow an automatic UDP retry to submit twice.
        if (recent.size() >= 1000) return "SEND_FAILED: SMTP_RETRY_CACHE_FULL";
        String result = "SEND_FAILED: SMTP_CONNECTION_FAILED";
        Transport transport = null; MimeMessage message = null; boolean sending = false, accepted = false;
        try {
            Session session = Session.getInstance(smtp.mailProperties()); session.setDebug(false);
            message = new MimeMessage(session);
            message.setFrom(new InternetAddress(smtp.from)); message.setReplyTo(new Address[]{new InternetAddress(owner)});
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
            message.setSubject(MimeSupport.header(content, "Tiêu đề:"), "UTF-8"); message.setText(MimeSupport.body(content), "UTF-8");
            message.setSentDate(new Date()); message.saveChanges();
            transport = session.getTransport("smtp");
            transport.connect(smtp.host, smtp.port, smtp.user, smtp.password);
            sending = true; transport.sendMessage(message, message.getAllRecipients()); accepted = true;
        } catch (AuthenticationFailedException e) { result = "SEND_FAILED: SMTP_AUTH_FAILED";
        } catch (MessagingException e) { result = classify(e, sending);
        } catch (RuntimeException e) {
            result = sending ? "SEND_UNCERTAIN: SMTP_DELIVERY_UNCERTAIN" : "SEND_FAILED: SMTP_CONNECTION_FAILED";
        } finally {
            // A QUIT/close error cannot undo DATA/250 acceptance.
            if (transport != null) try { transport.close(); } catch (MessagingException ignored) { }
        }
        if (accepted) {
            try {
                // Record acceptance, never claim that the provider placed this in Gmail Inbox.
                storage.saveSentMail(owner, content + "\n\nTrạng thái: SMTP_ACCEPTED\nSMTP From: " + smtp.from + "\nMessage-ID: " + message.getMessageID(), id);
                result = "SMTP_ACCEPTED";
            } catch (MessagingException | RuntimeException e) { result = "SMTP_ACCEPTED_SENT_UNSAVED"; }
        }
        recent.put(key, new Outcome(fingerprint, result)); return result;
    }
    private static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String classify(MessagingException failure, boolean sending) {
        boolean tls = false, timeout = false, rejected = false;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>(); pending.add(failure);
        while (!pending.isEmpty() && seen.size() < 32) {
            Throwable e = pending.removeFirst(); if (!seen.add(e)) continue;
            tls |= e instanceof SSLException || (e.getMessage() != null
                    && (e.getMessage().contains("TLS") || e.getMessage().contains("identity of server")));
            timeout |= e instanceof SocketTimeoutException;
            if (e instanceof SMTPAddressFailedException a) rejected |= a.getReturnCode() >= 400 && a.getReturnCode() <= 599;
            if (e instanceof SMTPSendFailedException s) rejected |= s.getReturnCode() >= 400 && s.getReturnCode() <= 599;
            if (e.getCause() != null) pending.add(e.getCause());
            if (e instanceof MessagingException m && m.getNextException() != null) pending.add(m.getNextException());
        }
        if (rejected) return "SEND_FAILED: SMTP_REJECTED";
        if (sending) return timeout ? "SEND_UNCERTAIN: SMTP_TIMEOUT" : "SEND_UNCERTAIN: SMTP_DELIVERY_UNCERTAIN";
        return tls ? "SEND_FAILED: SMTP_TLS_FAILED" : timeout ? "SEND_FAILED: SMTP_TIMEOUT" : "SEND_FAILED: SMTP_CONNECTION_FAILED";
    }
}
