# Demo MailFlow trên Windows PowerShell

## 1. Build và mở ứng dụng

Dùng JDK/Maven hiện có. Chạy ở gốc project:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Build-Demo.ps1 -Offline
java -cp 'target/classes;target/demo-lib/*' MailServer.MailServerGUI
```

Nếu dependencies chưa có, bỏ `-Offline`. Bấm **Khởi động** trong Server, đợi log xác nhận SMTP `127.0.0.1:2525`, POP3 `127.0.0.1:1110` và UDP `127.0.0.1:5000`.

Hoặc, sau khi build, mở và khởi động Server cùng hai Client bằng một lệnh (không chạy đồng thời với Server đã mở):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Start-Demo.ps1
```

Launcher kiểm tra cổng, đợi PONG/greeting SMTP/POP3 và xác nhận đủ ba cửa sổ. Không tạo tài khoản mẫu hay gửi thư thật. Kết quả khởi chạy tại `evidence/demo-launch.txt`.

Mở hai terminal PowerShell khác tại gốc project; chạy cùng lệnh ở mỗi terminal:

```powershell
java -cp 'target/classes;target/demo-lib/*' MailClient.MailClientGUI
```

## 2. Tài khoản và gửi/nhận nội bộ

1. Client A: **Kết nối**, nhập `usera@local.test`, tự chọn mật khẩu tối thiểu 6 ký tự và tối đa 72 byte UTF-8, bấm **Tạo tài khoản**.
2. Client B: làm tương tự với `userb@local.test`. Đây là tài khoản local, không phải tài khoản Gmail thật.
3. Kiểm tra file trên Server:

```powershell
Get-ChildItem mail_storage -Force
Get-Content 'mail_storage/userb@local.test/new_email.txt'
```

4. Đăng nhập A, vào **Soạn thư**, gửi cho `userb@local.test` với tiêu đề/nội dung tùy chọn. Xem **Lịch sử gửi thư/Sent**.
5. Đăng nhập B; Inbox trả danh sách thư. Chọn thư mới để đọc, bấm **Làm mới** khi cần.
6. Kiểm tra file thư mới:

```powershell
Get-ChildItem 'mail_storage/userb@local.test' -Filter 'mail_*.txt'
```

7. Dừng/khởi động lại Server, đăng nhập lại B: thư và tài khoản vẫn còn. Không chạy hai Server cùng ghi một `mail_storage`.

## 3. Chứng minh SMTP và POP3 thật

Giữ Server chạy, dùng hai tài khoản đã đăng ký:

```powershell
java -cp 'target/classes;target/demo-lib/*' MailClient.ProtocolDemo
```

Nhập sender A, mật khẩu A, recipient B, mật khẩu B tại prompt. Công cụ dùng Jakarta Mail gửi qua **TCP SMTP AUTH/DATA**, rồi đọc lại bằng **TCP POP3 USER/PASS/LIST/RETR/UIDL** và in tên file/subject/body. Nó không dùng API UDP. Sau đó Client B bấm Làm mới để thấy cùng thư trong Inbox.

Kiểm thử tự động cũng dùng Jakarta Mail và socket transcript; kết quả tại `evidence/demo-runtime.txt`. POP3 DELE chỉ có hiệu lực khi QUIT; file được chuyển vào `.trash` riêng để giữ bytes cũ. Đóng kết nối trước QUIT hoặc RSET sẽ bỏ đánh dấu xóa.

## 4. Gửi Gmail — cần cấu hình riêng

```powershell
Copy-Item config/smtp.example.properties config/smtp.local.properties
```

Điền SMTP_USER, SMTP_PASSWORD, SMTP_FROM bằng tài khoản SMTP được phép sử dụng, giữ host/port/TLS phù hợp. File local được Git ignore. Không commit hoặc đưa mật khẩu vào command line/chat. Với Gmail, bản demo dùng SMTP password authentication: cần App Password nếu tài khoản đủ điều kiện; không dùng mật khẩu đăng nhập thông thường. Điều kiện App Password/2-Step Verification xem [Google](https://support.google.com/accounts/answer/185833). Demo chưa cung cấp luồng OAuth đăng nhập Google.

Khởi động lại Server sau khi cấu hình. Trong Client A, gửi tới Gmail thật **chưa đăng ký thành tài khoản local**; tài khoản đã đăng ký local luôn nhận thư nội bộ. SMTP_FROM là địa chỉ provider cho phép gửi; account SMTP độc lập với password local.

UI báo **SMTP đã chấp nhận** và có bản ghi Sent. Kiểm tra Gmail Inbox/Spam và Message-ID để xác nhận nhận thư; SMTP acceptance không bảo đảm Inbox. Hiện chưa có credentials/recipient thử nghiệm nên gửi Gmail thật **CHƯA KIỂM THỬ**.

## 5. Kết thúc và giới hạn demo

Bấm **Đăng xuất** ở hai Client, sau đó **Dừng server**. Cổng UDP đổi trong UI; SMTP/POP3 đổi qua MAIL_SMTP_PORT / MAIL_POP3_PORT trước khi mở Server và ProtocolDemo.

Các listener SMTP/POP3 chỉ bind loopback; AUTH local và UDP chưa có mã hóa, dành cho demo cùng máy. SMTP gửi ngoài hỗ trợ STARTTLS/SSL và kiểm tra tên server. Demo tập trung email text, mỗi SMTP transaction một recipient, không làm máy chủ Internet hay relay mở. Không tự retry một thư SMTP có kết quả không chắc chắn sau crash; kiểm tra hộp thư/Sent trước khi gửi lại.
