# BẠN CẦN THỰC HIỆN THỦ CÔNG – TẠO MYSQL DATABASE

File Storage vẫn mặc định. MySQL chỉ được bật khi `MAIL_STORAGE_BACKEND=MYSQL`; lỗi kết nối/schema không tự chuyển sang ghi file. Client không kết nối JDBC.

## Tạo schema bằng phpMyAdmin

1. Đăng nhập phpMyAdmin bằng tài khoản quản trị hiện có. Kiểm tra nó đang kết nối đúng MySQL/port bạn muốn dùng (dịch vụ MySQL80 hiện có thường là 3306).
2. Ở trang Server, chọn **Import → Choose file**, chọn **database/schema.sql**, định dạng SQL, bấm **Import/Go**. File đã chứa `CREATE DATABASE IF NOT EXISTS mail_server_db`, `USE` và ba bảng InnoDB utf8mb4; không DROP hoặc xóa dữ liệu.
3. Nếu nhà cung cấp chỉ cho import trong database, tạo database `mail_server_db` bằng giao diện, collation `utf8mb4_unicode_ci`, rồi import cùng file với tài khoản đủ quyền. Bạn không phải tự viết SQL.

Ba bảng: `users` lưu username/email/password hash; `emails` lưu sender/recipient/subject/body/status/time; `mailbox_entries` lưu owner/folder/filename/content. Inbox và Sent cùng message được commit trong một transaction; POP3 DELE chuyển record sang TRASH khi QUIT, giữ nguyên nội dung.

Schema được tận dụng từ deferred/step09. Cột `materialized` được giữ để tương thích cấu trúc; record mới có giá trị true vì dữ liệu đã có trong database, không có dual-write filesystem/outbox. Nếu database cũ có pending records từ thử nghiệm cũ, cần kiểm tra riêng trước khi dùng.

## Tài khoản ứng dụng

Trong phpMyAdmin **User accounts → Add user account**:

- User: `mailflow_app`; Host: **Use text field → 127.0.0.1** để khớp URL mẫu.
- Tự đặt mật khẩu riêng trong giao diện. Không dùng root cho ứng dụng, không gửi mật khẩu qua chat.
- Không cấp global privileges. Cấp riêng database `mail_server_db`: SELECT, INSERT, UPDATE.
- Có thể dùng file **database/grant_app.sql** qua Import/SQL sau khi tạo account để cấp đúng ba quyền, không cần tự viết SQL.
- Nếu tài khoản phù hợp đã tồn tại, tái sử dụng nó và không đổi mật khẩu/quyền ngoài nhu cầu ứng dụng.

## Cấu hình riêng và kiểm tra

Chỉ khi file local chưa tồn tại:

```powershell
if (-not (Test-Path config/database.local.properties)) {
    Copy-Item config/database.example.properties config/database.local.properties
}
notepad config/database.local.properties
```

Tự điền trong editor, không trong source hoặc chat:

```properties
MAIL_STORAGE_BACKEND=MYSQL
MAIL_DB_URL=jdbc:mysql://127.0.0.1:3306/mail_server_db?sslMode=PREFERRED
MAIL_DB_USER=mailflow_app
MAIL_DB_PASSWORD=<nhập riêng trong file local>
```

File local được Git ignore. Có thể dùng các biến môi trường cùng tên; chúng ưu tiên hơn file. `MAIL_DB_CONFIG` chọn đường dẫn config khác. Không nhúng user/password trong URL. Java properties dùng `\\` để biểu diễn dấu backslash trong password; dùng biến môi trường nếu cần tránh escape. Với DB từ xa, dùng `sslMode=VERIFY_IDENTITY` và chứng chỉ được tin cậy.

```powershell
java -cp 'target/classes;target/demo-lib/*' MailServer.DatabaseTool check
```

Kết quả thành công phải ghi MySQL connection/schema PASS, database và charset utf8mb4. Lệnh chỉ đọc, không tự tạo schema/user/quyền và không in password. Sau đó tự logout Client, sao lưu mail_storage, dừng đúng Server cũ và mở lại Server:

```powershell
java -cp 'target/classes;target/demo-lib/*' MailServer.MailServerGUI --start
java -cp 'target/classes;target/demo-lib/*' MailClient.MailClientGUI
```

Mở Client thứ hai trong PowerShell khác. Log Server phải ghi **Storage: MySQL repositories**. Đăng ký hai tài khoản local, gửi hai chiều, xem Inbox/Sent; chạy `MailClient.ProtocolDemo` để thử SMTP→MySQL→POP3. Khi MYSQL được chọn, tên file là định danh mailbox trong DB; không tự ghi thêm mail_storage. Tài khoản/file cũ vẫn nguyên vẹn và có thể dùng lại bằng `MAIL_STORAGE_BACKEND=FILE` rồi restart Server. Hai backend là hai tập dữ liệu độc lập cho tới khi migration.

## Migration tùy chọn, không tự chạy trên dữ liệu thật

Tắt mọi Server/writer trước khi inspect/import. Dry-run chỉ đọc file, không cần kết nối DB:

```powershell
java -cp 'target/classes;target/demo-lib/*' MailServer.DatabaseTool dry-run mail_storage
```

Chỉ sau khi bạn chủ động đồng ý migration và đã kiểm tra cấu hình đích:

```powershell
java -cp 'target/classes;target/demo-lib/*' MailServer.DatabaseTool migrate mail_storage backups --confirm-offline
```

Công cụ tạo backup mới có xác minh bytes/manifest trước khi ghi DB; giữ nguyên hash, filename, content/time; không ghi đè account hoặc mail xung đột. Báo số user/mailbox entries đã thêm, số account bỏ qua và đường dẫn backup. Import từng record có transaction; lỗi giữa chừng có thể để lại các record đã import trước đó, có thể chạy lại sau khi xử lý xung đột. Không rollback bằng xóa dữ liệu. Chạy file **database/counts.sql** trong phpMyAdmin trước và sau migration để lấy số lượng chính xác; cùng một mail nội bộ có hai mailbox entries (Inbox/Sent), khác với số email duy nhất. Trash cũ được giữ trong backup/file gốc, không nhập vào Inbox.

Schema không tự upgrade các bảng đã có; lỗi cấu trúc phải được giải quyết riêng. Không tự chạy hướng dẫn JDBC cũ trong deferred/step09; dùng các file và lệnh hiện hành ở đây.
