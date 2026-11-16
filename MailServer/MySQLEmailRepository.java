package MailServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class MySQLEmailRepository implements EmailRepository {
    private final JdbcMailRepository sql;
    public MySQLEmailRepository(JdbcMailRepository sql) { this.sql=sql; }
    public List<MailSummary> list(String owner,boolean sent) throws IOException {
        try {
            if(sql.passwordHash(owner)==null) return null;
            List<MailSummary> result=new ArrayList<>();
            for(var entry:sql.entries(owner,sent)) {
                String subject=MimeSupport.header(entry.content(),"Tiêu đề:");
                if(subject.isEmpty()) subject=entry.filename().equals("new_email.txt")?"Welcome":"(Không có tiêu đề)";
                result.add(new MailSummary(entry.filename(),subject,entry.createdAt().toEpochMilli()));
            }
            result.sort(Comparator.comparingLong(MailSummary::getTimestamp).reversed().thenComparing(MailSummary::getFileName)); return result;
        } catch(SQLException e) { throw new IOException("MySQL mailbox listing failed"); }
    }
    public String read(String owner,String filename,boolean sent) throws IOException {
        if(!MailValidation.validMailFile(filename,sent)) return null;
        try { return sql.readEntry(owner,sent,filename); } catch(SQLException e) { throw new IOException("MySQL mail read failed"); }
    }
    public boolean save(String sender,String recipient,String content,String requestId) throws IOException {
        if(!MailValidation.isValidEmail(sender) || !MailValidation.isValidEmail(recipient)) return false;
        String id=UUID.nameUUIDFromBytes((sender+":"+requestId).getBytes(StandardCharsets.UTF_8)).toString();
        String timestamp=DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").format(LocalDateTime.now());
        try { return sql.saveMail(id,sender,recipient,MimeSupport.header(content,"Tiêu đề:"),MimeSupport.body(content),
                timestamp(content,"Thời gian nhận",timestamp),timestamp(content,"Thời gian gửi",timestamp)); }
        catch(SQLException e) { throw new IOException("MySQL delivery failed"); }
    }
    private String timestamp(String content,String label,String time) {
        int i=content.indexOf("\n\n"); String line="\n"+label+": "+time;
        return i<0?content+line:content.substring(0,i)+line+content.substring(i);
    }
    public void saveSent(String owner,String content,String id) throws IOException {
        try { UUID.fromString(id); sql.saveExternalSent(owner,content,id); }
        catch(SQLException | IllegalArgumentException e) { throw new IOException("MySQL Sent write failed"); }
    }
    public void archiveInbox(String owner,List<String> filenames) throws IOException {
        for(String filename:filenames) if(!MailValidation.validMailFile(filename,false)) throw new IOException("Invalid mail filename");
        try { sql.archiveInbox(owner,filenames); } catch(SQLException e) { throw new IOException("MySQL archive failed"); }
    }
}
