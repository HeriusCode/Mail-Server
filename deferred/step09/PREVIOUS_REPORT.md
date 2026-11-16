# STEP 02 — Security, Authentication & MySQL

Ngày thực hiện: 03/11/2026, Asia/Saigon. Tiếp nối `STEP01_AUDIT.md`, không xây lại project. Giữ Java Swing, console Client/Server, UDP và mô hình thư mục Inbox/Sent. Không triển khai SMTP, POP3, Gmail hay TCP trong bước này.

## 1. Kết quả và mức độ hoàn thành

**Đã triển khai và kiểm chứng phần phân quyền, phiên, hashing, file chào mừng và tương thích luồng Client.** Chạy lại 22 kiểm thử STEP 01: **22 PASS, 0 FAIL**; năm ca trước đây FAIL đều chuyển PASS.

JUnit: **19 bài, 13 PASS, 0 FAIL/error, 6 SKIPPED**. Các bài đã chạy gồm thao tác UDP thật, hai Swing Client thật được giữ ẩn, dừng/khởi động lại JVM Server thật, đọc dữ liệu sau restart, kiểm tra backup và JDBC connection-refused. Xem `evidence/step02-runtime.txt`, `evidence/step02-step01-regression.txt`, `evidence/step02-maven.txt`.

**Đã viết backend JDBC/MySQL, schema, outbox file và công cụ migration; chưa xác minh thành công với MySQL thật.** Không có `MAIL_DB_*`, `MAIL_TEST_DB_*` hoặc file cấu hình xác thực được cung cấp. `DatabaseTool check` thực thi trả `NOT RUN` / exit code 2. Sáu bài MySQL không được tính là PASS. Vì vậy STEP 02 chưa được coi là hoàn tất phần nghiệm thu MySQL/migration DB.

## 2. Môi trường và kiểm tra trước khi sửa code

- Đọc lại audit, runtime STEP 01 và source hiện có; cây Git ban đầu chỉ có tài liệu/test audit chưa commit từ bước trước.
- MySQL80: Running; kết nối TCP `127.0.0.1:3306` thực hiện thành công. Đọc các mục không nhạy cảm trong `C:\ProgramData\MySQL\MySQL Server 8.0\my.ini`: port 3306 và datadir. Không đọc/xuất dữ liệu tài khoản trong thư mục Data.
- CLI hiện có: `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe`, version CLI 8.0.29. Chưa chạy SQL `SELECT VERSION()` vì thiếu credentials.
- Không thay/đặt lại mật khẩu root, không thử mật khẩu mặc định, không dừng dịch vụ MySQL80, không tạo tài khoản/schema trên server thật khi chưa xác thực.
- Không có thư mục `mail_storage` thật trước migration; kiểm tra lại bằng `DatabaseTool inspect mail_storage` trả không có nguồn để chuyển đổi. Không xóa/chuyển dữ liệu người dùng.
- Tái sử dụng JDK 23 và Maven 3.9.16 đã cài. Thêm Maven build target Java 17, không di chuyển source khỏi thư mục hiện tại.
- Dependencies: Spring Security Crypto 6.5.7 (dùng BCrypt thuần, không đưa Spring framework vào kiến trúc), Connector/J 9.7.0; JUnit 5.11.4 ở test scope. Maven cache trong `out/m2`, thư viện runtime trong `target/lib`.
- Đã đối chiếu [Spring password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html), [bản phát hành Spring 6.5.7](https://github.com/spring-projects/spring-security/releases/tag/6.5.7), [Connector/J 9.7.0](https://dev.mysql.com/doc/relnotes/connector-j/en/news-9-7-0.html). Không tự nâng phiên bản MySQL Server.

## 3. Những lỗ hổng đã xử lý

| Vấn đề STEP 01 | Thay đổi | Bằng chứng |
|---|---|---|
| READ khi chưa đăng nhập | Lệnh nhạy cảm cần `AUTH <token>`; Server xác thực trước khi gọi storage | A14 PASS; raw socket anonymous READ bị từ chối |
| Đọc `.credentials` | Chỉ cho tên `new_email.txt`, `mail_<UUID>.txt`, `sent_<UUID>.txt` đúng folder; không phục vụ file khác | A15 PASS; READ `.credentials` với token của chính chủ cũng thất bại |
| Xem Sent tài khoản khác | Lấy owner từ session, username cũ phải khớp owner; từ chối sửa username | A16 PASS; test authenticated SENT/INBOX/READ chéo owner |
| Giả mạo người gửi | Header gửi phải khớp owner và người nhận; Server dựng lại header từ dữ liệu đã xác thực | A17 PASS; raw anonymous SEND và authenticated spoof đều bị chặn |
| Path traversal / linked folders | Filename whitelist, đường dẫn chuẩn hóa, chặn symlink/junction ở root và thư mục hộp thư; không nhận quyền sở hữu thư mục không credentials | A18 PASS; test `../`, junction root, unsafe `.sent`, orphan account |
| Cache có thể trả dữ liệu sau thu hồi phiên | Không cache phản hồi đăng nhập/đọc/gửi nhạy cảm; kiểm tra session mỗi lần. REGISTER có cache giới hạn, fingerprint chống đổi payload cùng request ID | Test revoked token/replay và expiry PASS; retry SEND dùng ID bền ở tầng lưu trữ |
| File chào sai nội dung | File mới chứa đúng câu tiếng Anh, không thêm header/timestamp vào file chào | A06 PASS; so sánh toàn bộ file chính xác |

Hash mật khẩu không được đưa vào response, token/password không ghi log. Log server chỉ ghi loại thao tác/kết quả và lỗi nội bộ dạng tên lớp, không ghi request nguyên văn hay JDBC exception message.

## 4. Phiên và giao thức tương thích

`SessionManager` tạo token ngẫu nhiên mật mã 32 byte bằng SecureRandom (Base64url không padding, 43 ký tự). Mỗi token gắn với owner và thời điểm hết hạn phía Server; mặc định 30 phút. Hết hạn hoặc logout làm token không còn dùng được. Tối đa 10.000 phiên, dọn phiên hết hạn khi tạo/tra cứu. Phiên ở RAM và không tồn tại sau restart Server. IP/port không phải bằng chứng định danh; test token từ endpoint khác vẫn phải kiểm tra bằng token và quyền owner.

Wire UDP mới:

```text
<UUID>|REGISTER <email> <base64-password>
<UUID>|LOGIN <email> <base64-password>
LOGIN_SUCCESS|<session-token>|<mail-summaries>    (payload response)
<UUID>|AUTH <session-token> READ <email> <filename>
<UUID>|AUTH <session-token> READ_SENT <email> <filename>
<UUID>|AUTH <session-token> INBOX <email>
<UUID>|AUTH <session-token> SENT <email>
<UUID>|AUTH <session-token> SEND <recipient> <base64-content>
<UUID>|AUTH <session-token> LOGOUT
```

Placeholder trong ví dụ không phải token thật. PING/REGISTER/LOGIN không cần phiên; mọi thao tác nhạy cảm đều cần phiên. Username trong lệnh cũ chỉ được kiểm tra tương thích, không quyết định danh tính. Request có kiểm tra UUID envelope, UTF-8, số tham số, ký tự điều khiển, Base64, kích thước gói/nội dung và header. Worker queue/cache có giới hạn; lỗi request không làm worker im lặng mất phản hồi.

`UdpMailClient.sendRequest` giữ signature và dạng response LOGIN cũ cho các caller trong project. Adapter giữ token trong RAM, tự thêm AUTH, bỏ token khỏi response trả về console/Swing. Hai Client đã chạy gửi/nhận bằng adapter mới. **Binary Client dùng UdpMailClient cũ hoặc raw wire cũ không tự có quyền tương thích**: phải build lại; không giữ đường truy cập không xác thực để hỗ trợ binary cũ.

GUI giữ layout hiện có. Nút Làm mới dùng INBOX với phiên, không gọi lại LOGIN sau khi mật khẩu đã xóa. Logout gửi thu hồi phiên trên network worker, xóa state/UI và bỏ callback request cũ bằng epoch/client check. Lỗi I/O đổi trạng thái OFFLINE. Console gọi logout khi chọn Thoát. Nếu process đóng hoặc mất mạng trước khi gửi logout được, phiên phía Server tự hết hạn; không thể bảo đảm thu hồi qua mạng đang mất kết nối.

Server mặc định bind loopback `127.0.0.1:5000`; có thể nhập địa chỉ bind khác như trước. **UDP chưa mã hóa**: mật khẩu Base64, token và nội dung có thể bị nghe lén nếu đưa lên mạng không tin cậy. Token là bearer token, không ngăn kẻ đã đánh cắp token. Không gọi đây là kênh truyền an toàn. Bước sau cần transport có TLS/xác thực server và giao thức phù hợp; hiện ưu tiên demo localhost. Chưa thêm rate limit đăng nhập theo tài khoản hoặc bảo vệ DoS đầy đủ.

## 5. Đăng ký, hashing và bảo toàn file

- Tài khoản mới dùng BCrypt cost 12, salt do thư viện sinh; mật khẩu tối thiểu 6 ký tự, tối đa 72 byte UTF-8, không chứa NUL. Không cắt mật khẩu dài rồi hash. UI đăng ký kiểm tra giới hạn này; login vẫn nhận độ dài cũ để hỗ trợ PBKDF2.
- Hash PBKDF2 cũ hỗ trợ đúng định dạng ban đầu: 120.000 vòng, salt 16 byte, hash 32 byte. Dùng constant-time comparison. Hash hỏng/khác tham số không được tự nhận là hợp lệ. Không tự tạo/reset mật khẩu hoặc rehash khi chưa có mật khẩu gốc.
- Backend dùng email chuẩn hóa làm username/email; ASCII email/path-safe, giới hạn độ dài. Swing vẫn giữ đầu vào Gmail như trước; đây vẫn là tài khoản nội bộ, không phải chứng minh chủ sở hữu Gmail.
- File mode ghi credentials bằng CREATE_NEW, tạo thư mục riêng và `.sent`. Không tự nhận thư mục cũ thiếu credentials. Đăng ký dở dang có rollback giới hạn ở thư mục mới vừa tạo, không xóa cây dữ liệu cũ.
- `new_email.txt` mới có nội dung chính xác: `Thank you for using this service. We hope that you will feel comfortable using our email service.`
- File cũ, kể cả lời chào cũ, không bị ghi đè để ép khớp nội dung mới. Migration giữ nguyên bytes/metadata của dữ liệu cũ.

## 6. Database, transaction và migration

Schema tại `database/schema.sql`, hướng dẫn đầy đủ tại `database/README.md`, cấu hình mẫu tại `config/database.example.properties`. File `config/*.local.properties`, `out/`, `target/`, `mail_storage/`, `backups/` được Git ignore. URL không được chứa user/password; biến môi trường ưu tiên file. Khi đã cấu hình DB, không fallback âm thầm sang file khi DB lỗi.

| Bảng | Nội dung và ràng buộc |
|---|---|
| users | id, username, email, password_hash, created_at; unique username/email |
| emails | UUID id, sender_id/address, receiver_id/address, subject, body, status, created_at; FK sender/receiver và index thời gian |
| mailbox_entries | owner_id, folder, filename, email_id, content, materialized; PK owner/folder/filename, FK owner/email; outbox tạo file và bảo vệ owner |

Tất cả bảng InnoDB/utf8mb4. JDBC dùng PreparedStatement cho tham số, rollback/commit các nhóm ghi. Transaction đăng ký tạo user + welcome + entry; transaction gửi tạo mail + Inbox/Sent entries. ID gửi lấy từ owner + request ID để gửi lại cùng thao tác không tạo mail mới; request ID mới được coi là email mới.

DB giữ content authoritative, ghi PENDING_FILES trước, materialize file qua file tạm rồi move không overwrite, sau đó đánh dấu STORED. Sau crash/lỗi I/O, pending còn để khôi phục lúc startup/trước thao tác hoặc bằng `DatabaseTool recover`. Xung đột file giữ nguyên file và báo lỗi, không tự thay thế. Cơ chế này đã viết và có integration test, nhưng **chưa được thực thi với DB thật** trong phiên này. Không tuyên bố đạt tính nhất quán DB/file chỉ từ các test file backend.

Migration là công cụ offline chủ động, không chạy tự động khi Server lên:

1. Dừng tất cả writer; inspect nguồn, từ chối linked paths, bỏ qua account thiếu hash/hỏng hash. Không chiếm quyền tài khoản thiếu mật khẩu.
2. Copy toàn bộ nguồn sang backup mới ngoài cây nguồn, so sánh bytes, tạo manifest SHA-256. Backup chứa dữ liệu nhạy cảm ở dạng hash/thư và cần quyền filesystem phù hợp.
3. Kiểm tra nguồn còn khớp plan trước nhập DB. Nhập user với hash đã xác định, không sinh password mới.
4. Mail UUID chung giữa Inbox/Sent được liên kết; entry có khóa duy nhất, content/hash/metadata xung đột thì dừng. Transaction đã commit có thể còn khi bước sau lỗi; có thể xử lý xung đột rồi chạy lại, không xóa nguồn.

Đã chạy inspect/backup trên fixture thật, kiểm chứng manifest, bảo toàn file, backup lần hai và trường hợp thiếu hash. Chưa chạy import/migration DB vì thiếu credentials. Không có dữ liệu mail_storage thật để chuyển đổi.

## 7. Kết quả theo 20 yêu cầu kiểm thử

| # | Ca kiểm thử | Kết quả thực tế / phạm vi |
|---|---|---|
| 1 | Đăng ký hợp lệ | PASS qua UDP và file |
| 2 | Username trùng | PASS, gồm hai Client đăng ký đồng thời chỉ một thành công |
| 3 | Thư mục tài khoản | PASS, có `.sent` |
| 4 | File chào mừng | PASS, so sánh đúng toàn bộ nội dung |
| 5 | Login đúng | PASS, BCrypt và PBKDF2 legacy |
| 6 | Login sai | PASS, không tạo phiên |
| 7 | Anonymous READ | PASS, raw UDP bị từ chối |
| 8 | Đọc password hash | PASS, raw anonymous và chính chủ có token đều không đọc `.credentials` |
| 9 | Sent người khác | PASS, bị FORBIDDEN khi có token tài khoản khác |
| 10 | Giả mạo sender | PASS, cả anonymous và authenticated spoof |
| 11 | Path traversal | PASS, `../`, filename whitelist, junction và unsafe directory |
| 12 | Session hết hạn | PASS, clock của Server trong test tăng 30 giây + 1 mili giây qua deadline 30 giây; lệnh bị từ chối trên UDP thật |
| 13 | Logout thu hồi | PASS, token cũ/replay không dùng được; Client xóa phiên |
| 14 | MySQL connection | SKIPPED/NOT RUN: không có credentials; TCP reachable không thay thế SQL authentication |
| 15 | MySQL user round trip | SKIPPED: test đã viết, chưa chạy DB thật |
| 16 | MySQL email round trip | SKIPPED: test đã viết, chưa chạy DB thật |
| 17 | Dữ liệu sau restart | PASS với file backend và dừng/khởi động lại JVM thật; MySQL restart test SKIPPED |
| 18 | MySQL đang hoạt động rồi mất kết nối | CHƯA CHẠY: thiếu kết nối hợp lệ ban đầu; chỉ PASS nhánh JDBC connect tới cổng thực tế đã đóng và không fallback |
| 19 | Migration DB chạy lại không trùng | SKIPPED: integration test đã viết; backup rerun trên fixture PASS không được tính là migration DB PASS |
| 20 | Client cũ | PASS các luồng hiện có sau khi build lại với adapter mới; hai Swing Client thực tế gửi/refresh/đọc/logout; không hỗ trợ binary dùng wire cũ không token |

Sáu bài MySQL bị skip: connection/utf8mb4, user round trip, email round trip/restart/idempotency, outbox conflict/recovery, migration rerun, rollback registration transaction. Test chỉ cho phép DB có tên kết thúc `_test` và không dùng DROP/xóa dữ liệu cũ.

Harness STEP 01 được giữ đủ 22 ca. Điều chỉnh A07 để công nhận BCrypt thay cho chỉ định dạng PBKDF2, A19 để header người nhận trùng recipient envelope theo kiểm tra mới. Sửa các mô tả evidence vốn chỉ đúng ở bản lỗi cũ. Không đổi điều kiện bảo mật A14–A17 để ép PASS; có thêm raw-socket tests phía Server để tránh chỉ kiểm chứng việc adapter từ chối cục bộ.

Các lỗi trong quá trình kiểm thử đã xử lý trước khi chốt: đường dẫn Windows 8.3/đường dẫn temp ngoài sandbox, classpath Unicode của child JVM, fixture lấy port sau socket đã đóng và copy JAR đang bị Windows khóa. Chạy cross-process UDP ngoài sandbox mới xác minh được restart thực tế. Test cuối ghi nhận thành công, không gộp các lượt thất bại trước đó thành PASS giả. Test Swing là thao tác chương trình trên các component thật, chưa đánh giá bằng mắt/screenshot.

## 8. File thay đổi và bổ sung

| Nhóm | File |
|---|---|
| Sửa Server hiện có | `ClientHandler.java`, `MailStorageService.java`, `UdpMailServer.java`, `MailServer.java`, `MailServerGUI.java` trong MailServer/ |
| Thêm Server | `PasswordHasher.java`, `SessionManager.java`, `SafePaths.java`, `DatabaseConfig.java`, `JdbcMailRepository.java`, `MigrationService.java`, `DatabaseTool.java` trong MailServer/ |
| Sửa Client | `MailClient/UdpMailClient.java`, `MailClient/MailClientGUI.java`, `MailClient/MailClient.java` |
| Build/config | `pom.xml`, `.gitignore`, `config/database.example.properties` |
| Schema/tài liệu | `database/schema.sql`, `database/README.md`, `STEP02_REPORT.md` |
| Test | `src/test/java/MailServer/{SecurityRegressionTest,MigrationAndConfigurationTest,MySqlIntegrationTest,ProcessRestartTest,RestartFixtureMain,SwingCompatibilityTest}.java` |
| Harness/script | `tools/audit/Step01Audit.java` (tương thích hash/protocol mới), `tools/Verify-Step02.ps1` |
| Evidence | `evidence/step02-runtime.txt`, `evidence/step02-step01-regression.txt`, `evidence/step02-maven.txt` |

Tài liệu/evidence STEP 01 vẫn giữ nguyên kết quả gốc. Không commit hoặc push; build artifacts và fixture nằm trong các thư mục được ignore.

## 9. Chạy lại và phần cần hoàn tất

```powershell
# Dùng thư viện đã tải, chạy test và xuất evidence; Bypass chỉ áp dụng process này.
powershell -NoProfile -ExecutionPolicy Bypass -File tools/Verify-Step02.ps1 -Offline

# Server/Client; mở Client hai lần để demo localhost.
java -cp 'target/classes;target/lib/*' MailServer.MailServerGUI
java -cp 'target/classes;target/lib/*' MailClient.MailClientGUI
```

Nếu máy chưa tải dependency, bỏ `-Offline`. Không cần đổi execution policy toàn máy. Kiểm thử process cần mạng loopback được phép; trong sandbox hạn chế mạng cần chạy test ngoài sandbox.

Phần cần người dùng/quản trị viên cung cấp: schema/tài khoản riêng cho `mail_server_db`, cấu hình local hoặc biến môi trường và một database test riêng `*_test`. Xem `database/README.md`. Sau đó chạy check, sáu integration test, thử DB mất kết nối trong lúc Server đang chạy, và migration hai lần trên bản sao dữ liệu đã backup. Không gửi mật khẩu trong chat.

Các giới hạn còn lại: UDP plaintext, chưa có TLS/rate limit đầy đủ; mailbox chưa phân trang nên response lớn có thể trả RESPONSE_TOO_LARGE; file backend đơn lẻ chưa có journal phục hồi crash tương đương DB outbox; yêu cầu idempotency chỉ cho cùng owner/request ID, không gộp hai lần gửi có ID khác; đóng process/mất mạng chỉ bảo đảm phiên hết hạn nếu logout chưa tới Server; migration phải offline; không hỗ trợ nhiều Server cùng ghi vào cùng storage. Đây là các giới hạn được ghi nhận, không phải các mục đã kiểm chứng hoàn tất.

Không triển khai STEP 03 hoặc đánh dấu hoàn thành DB để bỏ qua những phần còn thiếu credentials. Phần độc lập của STEP 02 đã thực hiện và có bằng chứng; phần MySQL được bàn giao ở trạng thái đã viết nhưng còn chờ xác minh thực tế.

## 10. Cập nhật sau yêu cầu tự tìm cấu hình MySQL trên Windows

Đã hoàn tất tự kiểm tra registry service, server/client binary, my.ini, greeting MySQL qua TCP, cấu hình workspace kể cả file ignored, biến môi trường Process/User/Machine, các native option file chuẩn và metadata Workbench. Server đang phục vụ được xác minh là **8.0.29 tại 127.0.0.1:3306**. Không tìm thấy nguồn xác thực SQL hợp lệ; hai connection Workbench đều dùng root và không chứa mật khẩu inline. Không truy cập credential vault hoặc đoán mật khẩu.

Đã chạy lại DatabaseTool check: NOT RUN/exit 2, và inspect mail_storage: không có dữ liệu thật để migration. Database, bảng và quyền vẫn chưa xác minh; sáu integration test không được chuyển thành PASS. Không thay cấu hình máy hoặc cài lại MySQL. Chi tiết: `STEP02_MYSQL_DISCOVERY.md`; bằng chứng: `evidence/step02-mysql-discovery.txt`. Chỉ còn thiếu credentials của tài khoản SQL được cấp cho project; endpoint/driver đã biết, file mẫu đã khớp và file local được Git ignore.
