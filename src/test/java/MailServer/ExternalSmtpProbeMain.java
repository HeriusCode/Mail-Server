package MailServer;

import java.nio.file.*;
import java.util.UUID;

/** Test-only SMTP sender in a separate JVM with its own TLS trust store. */
public final class ExternalSmtpProbeMain {
    static final String USER="smtp.sender@example.invalid", PASSWORD="synthetic-smtp-test-only";
    static final String OWNER="owner@local.test", RECIPIENT="recipient@example.invalid";
    static final String SUBJECT="Gửi thử tiếng Việt", BODY="Xin chào Việt Nam.\n.dòng có dấu chấm\n";
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]); String host=args[1]; int port=Integer.parseInt(args[2]);
        if(!host.equals("localhost") && !host.equals("127.0.0.1")) throw new IllegalArgumentException("Only loopback test peers allowed");
        boolean ssl=args[3].equals("ssl"), failSent=args[4].equals("fail-sent");
        MailStorageService storage=failSent ? new MailStorageService(root.resolve("storage")) {
            @Override public void saveSentMail(String owner,String content,String id) { throw new StorageUnavailableException(); }
        } : new MailStorageService(root.resolve("storage"));
        if(!storage.register(OWNER,"synthetic-local-test-only")) throw new IllegalStateException("Fixture registration failed");
        SmtpSettings settings=new SmtpSettings(host,port,USER,PASSWORD,USER,true,!ssl,ssl);
        MailDeliveryService delivery=new MailDeliveryService(storage,settings);
        String content=MimeSupport.canonical(OWNER,RECIPIENT,SUBJECT,BODY),id=UUID.randomUUID().toString();
        String result=delivery.send(OWNER,RECIPIENT,content,id);
        if(!result.equals(delivery.send(OWNER,RECIPIENT,content,id))) throw new IllegalStateException("Retry outcome changed");
        if(!delivery.send(OWNER,RECIPIENT,content+"different",id).contains("Request conflict")) throw new IllegalStateException("Conflicting retry accepted");
        if(result.equals("SMTP_ACCEPTED") && !new MailDeliveryService(storage,settings).send(OWNER,RECIPIENT,content,id).equals(result))
            throw new IllegalStateException("Durable Sent receipt did not prevent resubmission");
        if(result.equals("SMTP_ACCEPTED")) {
            String receipt=storage.readSentMail(OWNER,storage.getSentSummaries(OWNER).get(0).getFileName());
            if(!receipt.startsWith(content+"\n\nTrạng thái: SMTP_ACCEPTED\n")
                    || !receipt.contains("SMTP From: "+USER+"\nMessage-ID: <"))
                throw new IllegalStateException("Sent receipt missing content/provider From/Message-ID");
        }
        System.out.println("RESULT "+result);
        System.out.println("SENT_COUNT "+storage.getSentSummaries(OWNER).size());
    }
}
