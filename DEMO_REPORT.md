# Kết quả bản demo

Backend File Storage mặc định vẫn không cần database. STEP 4 đã tích hợp MySQLUserRepository/MySQLEmailRepository với Connector/J, tái sử dụng JDBC/schema cũ trong `deferred/step09`. MySQL chỉ bật khi cấu hình MYSQL rõ ràng, không tự migration hoặc fallback.

| Chức năng | Kết quả thực thi |
|---|---|
| Đăng ký, thư mục riêng, lời chào đúng, hash BCrypt | PASS |
| Đăng nhập, logout, hết hạn/thu hồi phiên | PASS |
| Chặn anonymous/cross-account Inbox/Sent, đọc hash, path traversal, spoof sender | PASS |
| Gửi/đọc UTF-8, danh sách tên file, Sent và lưu sau JVM restart | PASS |
| Hai Swing Client gửi/refresh/đọc/logout | PASS (component thật, cửa sổ giữ ẩn) |
| Launcher mở Server tự khởi động và hai Client trên Windows | PASS: xác nhận ba cửa sổ, UDP PONG, SMTP/POP3 greeting; evidence/demo-launch.txt |
| SMTP AUTH/DATA bằng Jakarta Mail, chống spoof/open relay | PASS |
| POP3 bằng Jakarta Mail và socket: state, LIST/RETR/octet/dot-stuffing, DELE/RSET/QUIT, maildrop lock | PASS |
| Outbound SMTP STARTTLS/implicit TLS, certificate/hostname checks, acceptance/Sent, authentication/rejection/timeout và retry | PASS với SMTP test peer localhost và JVM trust store riêng, không phải Gmail |
| Gmail gửi và nhận thật | CHƯA CHẠY: thiếu cấu hình SMTP và địa chỉ test được cấp |
| MySQL 8.0.29 thực, tài khoản ứng dụng riêng, utf8mb4, transaction/rollback | PASS trên instance test riêng; không thay đổi MySQL80 |
| Hai Swing Client qua MySQL, SMTP→MySQL→POP3, restart JVM/MySQL, migration fixture | PASS; File E2E vẫn PASS |

Kết quả cập nhật ngày 16/11/2026: JUnit **62 PASS / 0 FAIL / 0 ERROR / 0 SKIPPED**. Hồi quy STEP 01: **22 PASS / 0 FAIL**. Bằng chứng mới: `evidence/step4/full-build.txt`, `evidence/step4/runtime.txt`, `evidence/step4/regression.txt`. MySQL test dùng instance độc lập trên cổng động, được dừng sau test; MySQL80/schema demo hiện có chưa được tự cấu hình. Hướng dẫn `MYSQL_SETUP.md`. Gmail nhận thư thật chưa được xác minh; cấu hình thủ công tại `GMAIL_SETUP.md`.

Kiểm tra launcher bổ sung: chạy `tools/Start-Demo.ps1`, giữ Server và hai Client mở để sử dụng. Dùng javaw trực tiếp từ JDK để theo dõi đúng PID/cửa sổ trên Windows; launcher từ chối cổng đang bận và không can thiệp ứng dụng đã mở. Không tự tạo tài khoản hoặc gửi thư thật.

Thay đổi chính: tách UserRepository/EmailRepository và triển khai file; giữ SessionManager/BCrypt và UI; thêm LocalSmtpServer, Pop3Server, MailDeliveryService, SmtpSettings, MimeSupport, MailServerRuntime và ProtocolDemo; bỏ JDBC khỏi dependencies/runtime; thêm script build và test giao thức. File mail_storage thật không bị xóa/ghi đè khi nâng cấp; test dùng fixture riêng trong target/out.

Giới hạn: chưa kiểm chứng Gmail/TLS với provider thật; local protocols chưa TLS; không có attachment UI, full Internet MTA, persistent external SMTP queue hoặc pagination mailbox lớn. Không coi SMTP 250 là bằng chứng Gmail Inbox. Chỉ dùng demo localhost; không chạy nhiều Server cùng storage. Hướng dẫn ngắn tại `DEMO_GUIDE.md`.
