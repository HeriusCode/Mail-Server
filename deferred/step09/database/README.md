# MySQL — STEP 02

Project giữ Java Swing/UDP và thêm backend JDBC. Không có cấu hình DB thì chạy FILE mode, có thông báo lúc khởi động. Khi đã cung cấp bất kỳ cấu hình DB nào, cấu hình thiếu hoặc DB lỗi phải dừng/báo lỗi, không tự chuyển sang ghi file. Chỉ MySQL mode mới đáp ứng phần lưu DB của đề bài.

## Chuẩn bị bằng công cụ đã cài

MySQL80 đã được kiểm tra Running, TCP `127.0.0.1:3306` reachable; Server đang phục vụ được xác minh từ initial greeting là **8.0.29**, khớp binary mysqld.exe. CLI có tại `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe`, version CLI 8.0.29. Đã tự kiểm tra cấu hình workspace, môi trường Process/User/Machine, native option files và metadata Workbench nhưng chưa tìm được credentials dùng cho JDBC. Chi tiết tại `STEP02_MYSQL_DISCOVERY.md`. Không thay đổi/đoán mật khẩu root.

Đăng nhập bằng tài khoản quản trị **hiện có**, nhập password ở prompt (không đặt password trong command line):

```powershell
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' --host=127.0.0.1 --user=<tai_khoan_quan_tri> --password
```

Mở `database/schema.sql` bằng **Open SQL Script** của MySQL Workbench và Execute trên kết nối quản trị hiện có. Hoặc copy nội dung SQL vào MySQL client đã đăng nhập. Schema dùng `CREATE ... IF NOT EXISTS`, không DROP hoặc xóa dữ liệu.

Nhờ quản trị viên tạo/cấp tài khoản ứng dụng riêng với quyền SELECT, INSERT, UPDATE trên `mail_server_db.*`. Ứng dụng không cần root, CREATE USER, DROP hoặc quyền toàn máy. Nếu cần tạo tài khoản mới, quản trị viên chọn mật khẩu riêng trong công cụ quản trị; không đưa mật khẩu đó vào repository hoặc báo cáo.

```sql
-- Chỉ cấp quyền cho tài khoản ĐÃ được quản trị viên tạo ở host phù hợp:
GRANT SELECT, INSERT, UPDATE ON mail_server_db.* TO 'mail_app'@'localhost';
```

## Cấu hình riêng

```powershell
Copy-Item config/database.example.properties config/database.local.properties
```

Điền `MAIL_DB_URL`, `MAIL_DB_USER`, `MAIL_DB_PASSWORD` trong file cục bộ; file đã được Git ignore. Đây là Java `.properties`: nếu mật khẩu chứa dấu `\`, phải viết `\\`; ưu tiên biến môi trường hoặc dùng công cụ ghi Properties phù hợp để tránh lỗi escape. Biến môi trường cùng tên ưu tiên hơn file. `MAIL_CONFIG` có thể trỏ tới file khác. Không gửi mật khẩu trong chat hoặc commit file thật.

URL mẫu hướng tới `mail_server_db`, chọn UTF-8 và collation `utf8mb4_unicode_ci`. Không nhúng user/password vào URL. `sslMode=PREFERRED` trong mẫu dành cho localhost; với DB ở máy khác phải cấu hình TLS xác thực server (`VERIFY_IDENTITY`) và trust store phù hợp. Không bật autoReconnect hoặc fallback âm thầm. Timeout connect 3 giây/socket 5 giây được thiết lập qua JDBC properties.

## Build, chạy và kiểm tra

JDK hiện có: 23; Maven hiện có: 3.9.16. Build target Java 17. Không cần cài thêm JDK/Maven.

```powershell
mvn '-Dmaven.repo.local=out/m2' test dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/lib'
java -cp 'target/classes;target/lib/*' MailServer.DatabaseTool check
java -cp 'target/classes;target/lib/*' MailServer.MailServerGUI
java -cp 'target/classes;target/lib/*' MailClient.MailClientGUI
```

Mở lệnh Client lần nữa để có hai Client. Console entry point vẫn có: `MailServer.MailServer`, `MailClient.MailClient`. Server mặc định bind `127.0.0.1`, port UDP 5000. Chạy từ gốc project để `mail_storage` và file cấu hình nằm đúng vị trí. Đường dẫn `mail_storage` được chuẩn hóa và chặn symlink/junction. Không chạy hai process server cùng ghi vào cùng thư mục hoặc cùng DB/storage.

`DatabaseTool init` là thao tác DDL chủ động, thực thi schema tương tự Workbench. Nó dùng đúng cấu hình JDBC; để tạo DB chưa tồn tại cần tài khoản có quyền CREATE và tạm dùng URL kết nối không chỉ định tên database. Sau đó đổi lại URL `.../mail_server_db` và tài khoản ứng dụng ít quyền. Server thường không tự tạo database. Không có credentials thì `check/init` trả exit code 2 và ghi NOT RUN.

## Schema và cơ chế nhất quán

- `users`: id, username (= email trong phiên bản này), email, password_hash, created_at; unique username/email. New hash BCrypt cost 12, chỉ lưu ở DB khi dùng MySQL mode. PBKDF2 120.000 vòng của bản cũ vẫn được xác minh khi migration; không tự đặt lại mật khẩu.
- `emails`: UUID id, sender_id/address, receiver_id/address, subject, body, status, created_at; sender/receiver có FK và index thời gian. ID gửi được suy ra từ chủ phiên + request ID để cùng request không tạo bản ghi mới.
- `mailbox_entries`: owner_id, INBOX/SENT, filename, email_id, content, materialized; unique `(owner_id, folder, filename)`. FK về users/emails. Content giữ nguyên bản file riêng cho Inbox và Sent, kể cả timestamp cũ.

Transaction đăng ký ghi user + thư chào + entry; transaction gửi ghi email + cả hai entry. Commit DB ở `PENDING_FILES` trước, sau đó ghi file qua file tạm và move không overwrite; đánh dấu materialized và `STORED` khi các entry hoàn thành. Nếu bước file thất bại, trả `STORAGE_UNAVAILABLE`, dữ liệu trong DB còn để khôi phục. File có nội dung khác hoặc đường dẫn không an toàn gây xung đột, không bị thay thế.

Server khôi phục pending khi startup và trước đọc/gửi. Có thể chạy offline:

```powershell
java -cp 'target/classes;target/lib/*' MailServer.DatabaseTool recover mail_storage
```

Không chuyển FILE mode khi DB mất kết nối. Khi dùng DB, danh sách/nội dung chỉ phục vụ entry thuộc owner đã xác thực và đã materialize. Đây là outbox có thể phục hồi, không phải transaction phân tán nguyên tử giữa filesystem và DB. File tạm `.pending-*.tmp` có thể còn sau process crash; chúng không được API phục vụ. Cần kiểm tra/backup trước khi dọn thủ công.

## Migration an toàn

1. Dừng tất cả server và tiến trình ghi thư. Backup/import cần nguồn không thay đổi. Không chạy song song migration.
2. Kiểm tra trước mà không kết nối DB:

```powershell
java -cp 'target/classes;target/lib/*' MailServer.DatabaseTool inspect mail_storage
```

3. Xử lý account bị skip: thiếu `.credentials`, hash không thuộc định dạng hỗ trợ hoặc tên không hợp lệ. Không tự nhận quyền sở hữu thư mục hay tạo password mới. Nếu cần reset, phải xác minh chủ tài khoản rồi quản trị viên thực hiện luồng reset riêng; STEP 02 chưa cung cấp endpoint reset.
4. Sau khi schema/cấu hình hợp lệ:

```powershell
java -cp 'target/classes;target/lib/*' MailServer.DatabaseTool migrate mail_storage backups
```

Migration tạo thư mục backup mới ngoài nguồn, copy toàn bộ dữ liệu (gồm hash/những file không import), kiểm chứng bytes và manifest SHA-256, rồi mới ghi DB. Bảo vệ quyền filesystem của cả nguồn/backup; backup không được đưa vào Git. Nguồn không bị sửa/xóa; lời chào cũ được giữ nguyên để tránh ghi đè dữ liệu.

Chỉ nhập user với hash đã xác định; chỉ nhập `new_email.txt`, `mail_<UUID>.txt`, `.sent/sent_<UUID>.txt`. Những file khác vẫn có trong backup/nguồn nhưng không được API đọc. Inbox/Sent có cùng UUID dùng chung email; unique owner/folder/filename giúp chạy lại không thêm trùng. Xung đột hash, content hay metadata dừng migration; các transaction trước đó có thể đã commit, có thể sửa xung đột và chạy lại an toàn. Không sửa dữ liệu cũ để ép nhập.

## Kiểm thử MySQL riêng

Tạo schema **riêng để test**, ví dụ `mail_server_db_test`, và cấp tài khoản test quyền CREATE/SELECT/INSERT/UPDATE trong schema đó. Không dùng database sản xuất. Thiết lập `MAIL_TEST_DB_URL`, `MAIL_TEST_DB_USER`, `MAIL_TEST_DB_PASSWORD` trong môi trường của terminal chạy Maven, rồi:

```powershell
mvn '-Dmaven.repo.local=out/m2' '-Dtest=MySqlIntegrationTest' test
```

Test từ chối URL có tên schema không kết thúc `_test`. Bộ test tạo bảng nếu thiếu, không DROP dữ liệu, dùng account fixture ngẫu nhiên và giữ lại fixture để xem bằng chứng. Bao gồm connection/utf8mb4, user/email round trip, idempotency qua storage restart, outbox conflict/recovery, migration chạy hai lần, rollback transaction.

Chưa có cấu hình test DB thì 6 bài test được ghi **SKIPPED**, không coi là PASS. Bài connection-refused trong bộ test cục bộ chỉ chứng minh xử lý DB không truy cập được; không thay thế kiểm thử MySQL thật đang hoạt động rồi mất kết nối.

Tài liệu tham chiếu: [Spring password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html), [Spring Security 6.5.7](https://github.com/spring-projects/spring-security/releases/tag/6.5.7), [Connector/J 9.7.0](https://dev.mysql.com/doc/relnotes/connector-j/en/news-9-7-0.html). Chọn Connector/J 9.7 để làm việc với MySQL 8.0 hiện có; không tự nâng cấp MySQL Server.
