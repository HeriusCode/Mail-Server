# PostgreSQL `mail`

Server dùng file `config/database.local.properties` để kết nối database `mail`.
File này chứa mật khẩu thật và đã được Git bỏ qua. File mẫu an toàn là
`config/database.example.properties`.

Schema sử dụng ba bảng:

- `users`: tài khoản và password hash.
- `emails`: thông tin người gửi, người nhận, tiêu đề, nội dung và thời gian.
- `mailbox_entries`: Inbox, Sent và Trash của từng tài khoản.

Khởi tạo schema bằng `database/schema-postgresql.sql`. Tệp chỉ dùng
`CREATE TABLE/INDEX IF NOT EXISTS`, không xóa dữ liệu.

Kiểm tra kết nối sau khi build:

```powershell
java -cp 'target/classes;target/demo-lib/*' MailServer.DatabaseTool check
```

Server phải hiển thị `Storage: PostgreSQL repositories`. Khi PostgreSQL không
kết nối được, server báo lỗi và không tự ghi trở lại filesystem.
