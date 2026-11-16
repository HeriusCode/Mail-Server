package MailServer;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** RFC 1939 local maildrop with snapshot numbering and deletion on successful QUIT. */
public final class Pop3Server implements AutoCloseable {
    private final TcpMailListener listener;
    private final Map<String, Semaphore> locks = new ConcurrentHashMap<>();
    public Pop3Server(int port, MailStorageService storage, Consumer<String> log) throws IOException {
        listener = new TcpMailListener(port, wire -> serve(wire, storage, log), log, "POP3");
    }
    public int getPort() { return listener.port(); }
    @Override public void close() { listener.close(); }
    private void serve(TcpMailListener.Wire wire, MailStorageService storage, Consumer<String> log) throws Exception {
        wire.line("+OK MailFlow POP3 ready");
        String pending = null, owner = null; Semaphore lock = null; int attempts = 0;
        List<MailSummary> mails = List.of(); List<byte[]> bytes = new ArrayList<>(); Set<Integer> deleted = new HashSet<>();
        try {
            for (String line; (line = wire.read(1024)) != null;) {
                String[] parts = line.split(" ", 2); String command = parts[0].toUpperCase(Locale.ROOT);
                String argument = parts.length == 2 ? parts[1] : "";
                if ((command.equals("QUIT") || command.equals("CAPA")) && !argument.isEmpty()) {
                    wire.line("-ERR Command takes no arguments"); continue;
                }
                if (command.equals("QUIT")) {
                    if (owner != null && !deleted.isEmpty()) {
                        List<String> filenames = new ArrayList<>();
                        for (int index : new TreeSet<>(deleted)) filenames.add(mails.get(index).getFileName());
                        try { storage.archiveInbox(owner, filenames); }
                        catch (RuntimeException e) { wire.line("-ERR Could not commit deletions"); return; }
                    }
                    wire.line("+OK Bye"); return;
                }
                if (command.equals("CAPA")) {
                    wire.line("+OK Capabilities"); wire.line("USER"); wire.line("UIDL"); wire.line("RESP-CODES"); wire.line("."); continue;
                }
                if (owner == null) {
                    if (command.equals("USER")) {
                        pending = null;
                        if (MailValidation.isValidEmail(MailValidation.normalizeEmail(argument))) {
                            pending = MailValidation.normalizeEmail(argument); wire.line("+OK Send PASS");
                        } else wire.line("-ERR Invalid account");
                    } else if (command.equals("PASS") && pending != null) {
                        if (!storage.authenticate(pending, argument)) { pending = null; wire.line("-ERR Invalid credentials"); if (++attempts >= 3) return; continue; }
                        Semaphore candidate = locks.computeIfAbsent(pending, key -> new Semaphore(1));
                        if (!candidate.tryAcquire()) { wire.line("-ERR Maildrop locked"); continue; }
                        lock = candidate; owner = pending;
                        mails = storage.getMailSummaries(owner);
                        if (mails == null) throw new IOException("Mailbox unavailable");
                        for (MailSummary mail : mails) {
                            String content = storage.readMail(owner, mail.getFileName());
                            if (content == null) throw new IOException("Mailbox changed");
                            bytes.add(MimeSupport.popBytes(content, owner, mail));
                        }
                        wire.line("+OK Maildrop ready");
                    } else wire.line("-ERR Authenticate using USER and PASS");
                    continue;
                }
                if (command.equals("NOOP") && argument.isEmpty()) wire.line("+OK");
                else if (command.equals("RSET") && argument.isEmpty()) { deleted.clear(); wire.line("+OK Deletions reset"); }
                else if (command.equals("STAT") && argument.isEmpty()) {
                    long octets = 0; for (int i = 0; i < bytes.size(); i++) if (!deleted.contains(i)) octets += bytes.get(i).length;
                    wire.line("+OK " + (bytes.size() - deleted.size()) + " " + octets);
                } else if ((command.equals("LIST") || command.equals("UIDL")) && argument.isEmpty()) {
                    wire.line("+OK Listing");
                    for (int i = 0; i < mails.size(); i++) if (!deleted.contains(i))
                        wire.line((i + 1) + " " + (command.equals("UIDL") ? mails.get(i).getFileName() : bytes.get(i).length));
                    wire.line(".");
                } else if (Set.of("LIST", "UIDL", "RETR", "DELE").contains(command)) {
                    int index;
                    try { index = Integer.parseInt(argument) - 1; }
                    catch (NumberFormatException e) { wire.line("-ERR Invalid message number"); continue; }
                    if (index < 0 || index >= mails.size() || deleted.contains(index)) { wire.line("-ERR No such message"); continue; }
                    if (command.equals("RETR")) { wire.line("+OK " + bytes.get(index).length + " octets"); wire.multiline(bytes.get(index)); }
                    else if (command.equals("DELE")) { deleted.add(index); wire.line("+OK Marked for deletion"); }
                    else wire.line("+OK " + (index + 1) + " " + (command.equals("UIDL") ? mails.get(index).getFileName() : bytes.get(index).length));
                } else wire.line("-ERR Command not available in this state");
            }
        } finally { if (lock != null) lock.release(); log.accept("POP3 session closed"); }
    }
}
