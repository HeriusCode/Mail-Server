# BẠN CẦN THỰC HIỆN THỦ CÔNG – CẤU HÌNH GMAIL

Chưa gửi thư đến Gmail thật. Test tự động chỉ dùng SMTP peer loopback và chứng chỉ thử nghiệm trong JVM riêng.

## 1. Chuẩn bị tài khoản Google

- Bật xác minh hai bước nếu tài khoản chưa bật; tự thực hiện trong Google Account.
- Nếu đủ điều kiện, tạo App Password dành riêng cho MailFlow. Không sử dụng mật khẩu Google chính, không gửi App Password qua chat.
- Nếu không thấy App Password, xem điều kiện tài khoản tổ chức, Advanced Protection hoặc cấu hình security key tại [Google Account Help](https://support.google.com/accounts/answer/185833).
- Chọn Gmail nhận thử nghiệm do bạn kiểm soát. Không đăng ký địa chỉ nhận này thành tài khoản local trong MailFlow: tài khoản đã đăng ký local được chuyển thư nội bộ.

## 2. Dừng Server cũ và sao lưu an toàn

Đăng xuất các Client và đợi các thao tác gửi/lưu hoàn tất. Trước khi dừng/khởi động lại Server, sao lưu storage khi không còn thao tác ghi.

Tại gốc project, sao lưu vào thư mục mới; không xóa/ghi đè storage:

```powershell
$gmailBackup = Join-Path 'backups' ('before-gmail-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $gmailBackup | Out-Null
Copy-Item -LiteralPath mail_storage -Destination $gmailBackup -Recurse
```

Dừng Server bằng nút **Dừng server**, rồi đóng cửa sổ. Nếu dùng console, dừng đúng tiến trình MailFlow; không dừng toàn bộ java.exe. Với console chạy nền, xác nhận đúng tiến trình qua PID chủ sở hữu các cổng và Command line trong Task Manager trước khi kết thúc nó. Agent không tự dừng Server đang chạy.

Kiểm tra cổng 5000 UDP, 2525/1110 TCP không còn do Server cũ sử dụng. Không mở hai Server cùng storage.

## 3. Nhập cấu hình bằng biến môi trường của PowerShell hiện tại

Không dùng `setx`, không đưa mật khẩu vào command line. Lệnh sau do bạn tự thực thi; prompt App Password được che:

```powershell
$env:SMTP_HOST = 'smtp.gmail.com'
$env:SMTP_PORT = '587'
$env:SMTP_AUTH = 'true'
$env:SMTP_STARTTLS = 'true'
$env:SMTP_SSL = 'false'
$env:SMTP_USER = (Read-Host 'Gmail gửi').Trim()
$env:SMTP_FROM = $env:SMTP_USER
$gmailSecret = Read-Host 'App Password (không nhập mật khẩu Google chính)' -AsSecureString
try {
    $env:SMTP_PASSWORD = ([pscredential]::new($env:SMTP_USER, $gmailSecret)).GetNetworkCredential().Password.Replace(' ', '')
} finally {
    $gmailSecret.Dispose()
    Remove-Variable gmailSecret
}
java -cp 'target/classes;target/demo-lib/*' MailServer.MailServerGUI --start
```

Server phải được mở từ chính cửa sổ PowerShell này để nhận các biến. Biến môi trường không được mã hóa trong bộ nhớ tiến trình; sau khi kết thúc demo, đóng Server và cửa sổ PowerShell hoặc xóa các biến SMTP_* khỏi phiên đó.

Thay thế bằng file local nếu muốn: chỉ khi `config/smtp.local.properties` chưa tồn tại, sao chép `config/smtp.example.properties` rồi tự điền SMTP_USER, SMTP_FROM (cùng địa chỉ Gmail) và SMTP_PASSWORD (App Password) trong editor. File local được Git ignore; không commit hoặc đưa nội dung vào log/chat. Không ghi đè cấu hình đã có. Biến môi trường được ưu tiên hơn file.

Port 465: dùng `SMTP_SSL=true`, `SMTP_STARTTLS=false`, `SMTP_PORT=465`. Không bật cả SSL và STARTTLS. Cả hai chế độ đều kiểm tra chuỗi tin cậy và hostname chứng chỉ, không có chế độ trust-all.

## 4. Gửi đúng một thư và kiểm chứng

1. Mở Client, kết nối localhost:5000 và đăng nhập tài khoản nội bộ.
2. Soạn thư tới Gmail nhận đã chọn, dùng tiêu đề duy nhất, ví dụ `MailFlow Gmail test <thời điểm>`, kèm nội dung tiếng Việt.
3. Bấm gửi **một lần**. SMTP From dùng Gmail xác thực; Reply-To dùng tài khoản local. Địa chỉ local không phải Gmail thật nên Reply-To đó chỉ là thông tin định danh, không bảo đảm nhận được reply từ Internet.
4. `SMTP_ACCEPTED` chỉ có nghĩa SMTP đã chấp nhận. Kiểm tra Gmail Inbox/Spam, subject/body, From và Message-ID; đối chiếu bản Sent của MailFlow.
5. Nếu GUI báo kết quả chưa rõ hoặc SMTP accepted nhưng không lưu được Sent, không bấm gửi lại ngay. Kiểm tra Gmail/provider và storage trước. Không có hàng đợi tự gửi lại.
6. Chỉ khi Gmail thực sự nhận thư mới có bằng chứng hoàn thành STEP 3. Báo lại thời điểm, tiêu đề thử và kết quả nhận; không cung cấp App Password.

## 5. Nếu cần OAuth 2.0

MailFlow hiện chưa triển khai đăng nhập OAuth. Phương án tiếp theo: OAuth client/consent của Google, browser authorization với PKCE và offline access; lưu refresh token an toàn ngoài Git, tự refresh access token; cấu hình Jakarta Mail SASL XOAUTH2 (tắt password mechanisms). SMTP OAuth dùng scope `https://mail.google.com/` và có yêu cầu xác minh/chính sách tùy cách phát hành. Không thể thay App Password bằng mật khẩu Google chính hoặc chỉ nhập refresh token vào SMTP_PASSWORD rồi mong chương trình tự refresh.

Nguồn chính thức: [Gmail SMTP 587 STARTTLS /465 TLS](https://developers.google.com/workspace/gmail/imap/imap-smtp), [SASL XOAUTH2](https://developers.google.com/workspace/gmail/imap/xoauth2-protocol).

## Giới hạn chống gửi trùng

Cùng request ID trong một Server process được giữ kết quả (kể cả chưa rõ), kiểm tra payload và không gửi lại. Khi cache đạt 1000 kết quả, các yêu cầu gửi ngoài mới bị từ chối thay vì xóa lịch sử và cho retry trùng; gửi nội bộ vẫn hoạt động. Bản Sent thành công cũng giúp nhận diện retry sau khi tạo lại delivery service. Cache chưa rõ kết quả không phải journal bền vững qua crash; sau crash/restart hoặc bấm gửi mới, phải kiểm tra hộp thư trước khi quyết định gửi lại. Session cũ không còn hiệu lực sau restart.
