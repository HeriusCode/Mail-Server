package MailServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class PostgreSQLEmailRepository implements EmailRepository {
    private final JdbcMailRepository sql;
    public PostgreSQLEmailRepository(JdbcMailRepository sql) { this.sql=sql; }
    @Override public List<MailSummary> list(String owner,boolean sent) throws IOException {
        try {
            if(sql.passwordHash(owner)==null) return null;
            List<MailSummary> result=new ArrayList<>();
            for(var entry:sql.entries(owner,sent)) {
                String subject=MimeSupport.header(entry.content(),"Tiêu đề:");
                if(subject.isEmpty()) subject=entry.filename().equals("new_email.txt")?"Welcome":"(Không có tiêu đề)";
                result.add(new MailSummary(entry.filename(),subject,entry.createdAt().toEpochMilli()));
            }
            result.sort(Comparator.comparingLong(MailSummary::getTimestamp).reversed()
                    .thenComparing(MailSummary::getFileName));
            return result;
        } catch(SQLException e) { throw new IOException("PostgreSQL mailbox listing failed",e); }
    }
    @Override public String read(String owner,String filename,boolean sent) throws IOException {
        if(!MailValidation.validMailFile(filename,sent)) return null;
        try { return sql.readEntry(owner,sent,filename); }
        catch(SQLException e) { throw new IOException("PostgreSQL mail read failed",e); }
    }
    @Override public boolean save(String sender,String recipient,String content,String requestId) throws IOException {
        if(!MailValidation.isValidEmail(sender) || !MailValidation.isValidEmail(recipient)) return false;
        String id=UUID.nameUUIDFromBytes((sender+":"+requestId).getBytes(StandardCharsets.UTF_8)).toString();
        String time=DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").format(LocalDateTime.now());
        try { return sql.saveMail(id,sender,recipient,MimeSupport.header(content,"Tiêu đề:"),MimeSupport.body(content),
                timestamp(content,"Thời gian nhận",time),timestamp(content,"Thời gian gửi",time)); }
        catch(SQLException e) { throw new IOException("PostgreSQL delivery failed",e); }
    }
    private static String timestamp(String content,String label,String time) {
        int index=content.indexOf("\n\n"); String line="\n"+label+": "+time;
        return index<0?content+line:content.substring(0,index)+line+content.substring(index);
    }
    @Override public void saveSent(String owner,String content,String id) throws IOException {
        try { UUID.fromString(id); sql.saveExternalSent(owner,content,id); }
        catch(SQLException | IllegalArgumentException e) { throw new IOException("PostgreSQL Sent write failed",e); }
    }
    @Override public void archiveInbox(String owner,List<String> filenames) throws IOException {
        for(String filename:filenames)
            if(!MailValidation.validMailFile(filename,false)) throw new IOException("Invalid mail filename");
        try { sql.archiveInbox(owner,filenames); }
        catch(SQLException e) { throw new IOException("PostgreSQL archive failed",e); }
    }
}
