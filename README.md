# MailFlow — Mail Server demo

Java Swing Client; API UDP, SMTP localhost thật (2525), POP3 thật (1110). Server lưu tài khoản, Inbox, Sent và nội dung thư trong PostgreSQL database `mail`.

**Run Java trong VS Code:** `MailServer.java` và `MailClient.java` mở GUI mặc định; thêm `--console` nếu cần menu terminal cũ. Cấu hình `.vscode/launch.json` dùng classpath JAR để tránh lỗi đường dẫn tiếng Việt trên Windows. Nhấn F5 chọn `MailFlow: Server GUI + 2 Clients`; Server GUI mở trước, bấm Khởi động khi cổng/config đã sẵn sàng.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Build-Demo.ps1
java -cp 'target/classes;target/demo-lib/*' MailServer.MailServerGUI
java -cp 'target/classes;target/demo-lib/*' MailClient.MailClientGUI
```

Mở Client lần nữa để có hai cửa sổ. Chạy build với `-Offline` nếu dependencies đã được tải. Xem [DEMO_GUIDE.md](DEMO_GUIDE.md) để đăng ký, gửi/đọc thư, kiểm tra SMTP/POP3 và cấu hình SMTP gửi Gmail.

Đã build xong thì có thể mở Server và hai Client bằng một lệnh: `powershell -NoProfile -ExecutionPolicy Bypass -File tools/Start-Demo.ps1`.

Kết quả thực thi: [DEMO_REPORT.md](DEMO_REPORT.md). PostgreSQL được tích hợp qua `UserRepository`/`EmailRepository`; client không nhìn thấy thông tin JDBC. Cấu hình thật nằm trong `config/database.local.properties` và đã được Git bỏ qua. Schema PostgreSQL nằm tại `database/schema-postgresql.sql`.

Kiểm tra kết nối bằng `java -cp 'target/classes;target/demo-lib/*' MailServer.DatabaseTool check`. Gửi Gmail: xem [GMAIL_SETUP.md](GMAIL_SETUP.md). SMTP STARTTLS/implicit TLS đã test với peer localhost; **Gmail nhận thư thật chưa được xác minh.**
