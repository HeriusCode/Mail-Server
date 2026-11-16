# STEP 01 — Audit Mail-Server và môi trường

Ngày kiểm tra: 03/11/2026 (Asia/Saigon). Repository: `HeriusCode/Mail-Server`, commit `ecb0e91` (`pass`).

## 1. Phạm vi và kết luận

Đã đọc toàn bộ 8 file Java, `.gitignore`, kiểm tra Git, công cụ trên máy, dịch vụ MySQL và chạy kiểm thử loopback với mã nguồn nguyên trạng. Không sửa mã nguồn Client/Server, không cài thêm công cụ, không thay đổi database máy người dùng.

Project hiện là **dịch vụ thư nội bộ bằng UDP, lưu file**, giao diện Java Swing. Chưa triển khai TCP, SMTP, POP3, Jakarta Mail, JDBC hoặc MySQL. Việc chỉ chấp nhận tên tài khoản `@gmail.com` **không phải tích hợp Gmail**; các tài khoản đó chỉ tồn tại trong thư mục lưu trữ của ứng dụng.

Biên dịch thành công trên JDK 23, có 14 cảnh báo. Bộ kiểm thử audit chạy thực tế: **17 PASS, 5 FAIL**. Bốn FAIL liên quan quyền truy cập/giả mạo và một FAIL do nội dung chào mừng không đúng đề bài. PASS của các thao tác đọc/gửi hợp lệ chỉ chứng minh chức năng hoạt động, không chứng minh hệ thống đã bảo mật hay đáp ứng toàn bộ đề bài.

## 2. Cấu trúc và kiến trúc hiện tại

```text
MailClient/
  MailClient.java          Console: cấu hình endpoint, đăng ký, đăng nhập, gửi
  UdpMailClient.java       UDP request/response, UUID, timeout, retry
  MailClientGUI.java       Swing: login/register, Inbox, Compose, Sent, logout
MailServer/
  MailServer.java          Console entry point
  MailServerGUI.java       Swing: start/stop, endpoint, nhật ký, thống kê
  UdpMailServer.java       DatagramSocket, worker pool, cache phản hồi
  ClientHandler.java       Dispatcher các lệnh ứng dụng
  MailStorageService.java  Tài khoản, PBKDF2, thư mục, file Inbox/Sent
.gitignore
```

Không có `pom.xml`, Gradle, Maven wrapper, SQL, JDBC driver, dependency bên ngoài, README hay bộ test trong bản clone. Không tìm thấy `AGENTS.md` trong project hoặc các thư mục cha đã kiểm tra.

Luồng chính: Swing/console → `UdpMailClient` → UDP → `UdpMailServer` → `ClientHandler` → `MailStorageService` → file. GUI dùng worker riêng cho mạng và cập nhật qua Swing EDT.

Lưu trữ mặc định tương đối với thư mục chạy:

```text
mail_storage/<email>/
  .credentials            iterations:salt:hash (Base64 cho salt/hash)
  new_email.txt
  mail_<UUID>.txt
  .sent/sent_<UUID>.txt
```

## 3. Môi trường thực tế

| Thành phần | Kết quả kiểm tra | Giới hạn |
|---|---|---|
| Windows | Windows 11 x64; Maven báo version 10.0 | Không thay cấu hình hệ thống |
| Java / javac | Oracle JDK 23, build 23+37-2369; `javac 23` | Biên dịch bằng JDK đã cài |
| Maven | Apache Maven 3.9.16, `E:\tool java\apache-maven-3.9.16` | Có công cụ nhưng project chưa dùng |
| VS Code | CLI 1.144.0, x64 | `code --version` có cảnh báo Crashpad Access denied; không xác minh extension Java |
| MySQL | Dịch vụ `MySQL80`: Running | WMI/CIM bị Access denied khi đọc PathName dịch vụ |
| MySQL CLI | Có tại `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe`, version 8.0.29 | Không nằm trên PATH; đây là phiên bản CLI, chưa xác nhận phiên bản server bằng SQL |
| MySQL TCP | Kết nối `127.0.0.1:3306` thành công | Chưa đăng nhập SQL, chưa kiểm tra schema, tài khoản hay quyền; không đoán mật khẩu |
| Git | Working tree sạch trước audit; commit `ecb0e91` | Chỉ thêm báo cáo/test/bằng chứng |

Không có cấu hình DB/SMTP trong repository. Không có bằng chứng đã được cấp tài khoản DB riêng hoặc thông tin xác thực SMTP. Vì thế không thể đánh dấu kiểm thử MySQL/Gmail PASS.

## 4. Bảng chức năng theo yêu cầu

| Chức năng | Hiện trạng | Vấn đề | Hướng nâng cấp |
|---|---|---|---|
| Đăng ký | Đã có; tạo thư mục, `.sent`, `.credentials`, `new_email.txt`; chống trùng thông thường | Chỉ nhận `@gmail.com`; lời chào khác đề bài; chưa DB; lỗi I/O bị gộp vào lỗi trùng; đăng ký dở dang có thể để lại dữ liệu | Tách tài khoản nội bộ và địa chỉ ngoài; transaction DB, cơ chế khôi phục file; lời chào đúng yêu cầu |
| Đăng nhập | Đã có PBKDF2WithHmacSHA256, salt ngẫu nhiên 16 byte, 120.000 vòng, hash 256 bit, so sánh `MessageDigest.isEqual` | Chỉ xác thực lệnh LOGIN; không tạo phiên; Base64 mật khẩu qua UDP không mã hóa | Tái sử dụng hashing, thêm phiên có hạn dùng và kiểm tra quyền ở mọi lệnh; TLS cho triển khai qua mạng |
| Gửi email nội bộ | Đã có Inbox/Sent bằng file, UTF-8, UUID, người gửi/nhận, tiêu đề, thời gian | UDP thay vì TCP; giả mạo sender được; không DB; không chống trùng bền vững | Xác định sender từ phiên; dùng service chung, TCP theo đề bài, JDBC và ID yêu cầu lưu bền |
| SMTP | Chưa triển khai | Không có SMTP client/server, MIME, TLS hay SMTP AUTH | Jakarta Mail làm SMTP client gửi ngoài; listener SMTP TCP riêng nếu cần nhận/gửi nội bộ bằng giao thức SMTP |
| POP3 | Chưa triển khai | READ/LOGIN tự định nghĩa không phải POP3 | POP3 server TCP dùng chung hộp thư, trạng thái AUTHORIZATION/TRANSACTION/UPDATE; test bằng POP3 client |
| Gmail | Chưa tích hợp | Regex/nhãn Gmail dễ gây hiểu nhầm; gửi tới Gmail chưa đăng ký trả SEND_FAILED | Cấu hình relay SMTP qua biến môi trường; ghi nhận SMTP_ACCEPTED riêng với xác nhận nhận thư phía Gmail |
| Database | Chưa triển khai | Không JDBC, SQL, schema, transaction hay recovery | MySQL Connector/J, PreparedStatement, bảng users/emails, khóa/index, migration và khôi phục DB/file |
| Giao diện | Đã có Swing login/register cùng form, sidebar, Inbox, Sent, Compose, xem thư, logout, trạng thái | Chưa tìm kiếm; Refresh gọi LOGIN sau khi mật khẩu đã xóa; trạng thái có thể vẫn ONLINE khi lỗi mạng | Giữ Swing, sửa luồng phiên/refresh, tìm kiếm, form đăng ký rõ ràng, trạng thái kết nối chính xác |

## 5. Giao thức, nhiều Client và độ bền

- Endpoint mặc định: UDP `0.0.0.0:5000` phía server, `localhost:5000` phía client; người dùng thay host/port được.
- Envelope `requestId|COMMAND ...`; lệnh `PING`, `REGISTER`, `LOGIN`, `SEND`, `SENT`, `READ`, `READ_SENT`.
- Nội dung và mật khẩu dùng Base64 UTF-8; đây là mã hóa biểu diễn, không bảo vệ bí mật trên đường truyền.
- Client timeout 2 giây, tối đa 3 lần; gửi lại cùng request ID. Giới hạn ứng dụng 60.000 byte, gồm cả envelope/Base64.
- Server có fixed worker pool; kiểm thử xác nhận hai socket Client cùng đăng ký và gửi/đọc được. Chưa kiểm thử hai cửa sổ GUI hoặc tải lớn.
- Cache phản hồi khóa theo endpoint và request ID, TTL 120 giây; thao tác get → xử lý → put không nguyên tử, nên retry đồng thời vẫn có nguy cơ xử lý trùng. Không bảo vệ qua restart/đổi endpoint. Chưa tái hiện race bằng kiểm thử stress.
- Cache chỉ quét bản ghi hết hạn khi size > 1.000; không có giới hạn cứng. Worker queue không giới hạn. Cần giới hạn tài nguyên khi nâng cấp.
- `saveMail` xóa Inbox nếu ghi Sent thất bại, là rollback cục bộ hữu ích; chưa có transaction hoặc recovery khi process chết giữa hai lần ghi.
- `register` có nhánh thiết lập mật khẩu cho thư mục tồn tại chưa có `.credentials`. Thiếu kiểm soát migration/khôi phục; nếu ghi credentials rồi bước sau lỗi, lần thử lại có thể bị coi là trùng. Chưa thực thi fault injection.

## 6. Lỗi ưu tiên và bằng chứng

### P0 — Quyền truy cập và giả mạo (đã tái hiện)

1. `ClientHandler.java:103` nhận trực tiếp username/filename từ READ/READ_SENT, không xác thực kết nối hoặc token. Client chưa LOGIN đọc được thư B nếu biết tên file (A14).
2. `MailStorageService.java:174` kiểm tra đường dẫn nằm trong thư mục nhưng không giới hạn loại file/thư đã lập chỉ mục. `READ <user> .credentials` trả được file hash (A15). Không đưa giá trị hash vào log/báo cáo.
3. `ClientHandler.java:67`: SENT không kiểm tra quyền; Client chưa LOGIN xem danh sách Sent tài khoản khác (A16).
4. `ClientHandler.java:88`: SEND lấy sender từ header do Client gửi. Client chưa LOGIN giả mạo sender A thành công (A17).

Kiểm tra path traversal `../` có tác dụng (A18), nhưng không thay thế xác thực và phân quyền. Khi sửa phải ràng buộc owner với phiên, cho phép truy xuất đúng mail ID, tuyệt đối không phục vụ `.credentials`.

### P1 — Chưa đạt yêu cầu và lỗi luồng (phân tích mã nguồn nếu không ghi test)

- `MailStorageService.java:32`: lời chào tiếng Việt thay vì câu tiếng Anh bắt buộc (A06 FAIL).
- `MailClientGUI.java:599`: nút Refresh gọi `login()`, trong khi đăng nhập thành công gọi `resetPasswordField()` ở dòng 698; lần Refresh tiếp theo bị `validPassword` chặn. Phát hiện từ mã nguồn, chưa thao tác GUI thực tế.
- `MailClientGUI.java:877`: lỗi request chỉ đổi OFFLINE khi socket đã đóng; mất server có thể vẫn hiện ONLINE. Backend có trả IOException khi mất server (A22), UI chưa kiểm chứng thực tế.
- Không có TCP/SMTP/POP3/MySQL. Console Client chưa có thao tác mở nội dung thư như GUI; in metadata danh sách thô.
- Chưa phân biệt lỗi I/O, dữ liệu không hợp lệ và tài khoản trùng ở nhiều response. Chưa phân trang mailbox, giới hạn subject/body hoặc cơ chế tải thư vượt 60 KB. Server trả `RESPONSE_TOO_LARGE` nếu danh sách/phản hồi vượt hạn mức.

## 7. Kiểm thử đã thực thi

Lệnh từ thư mục gốc (PowerShell):

```powershell
New-Item -ItemType Directory -Force out\audit | Out-Null
javac -encoding UTF-8 -Xlint:all -d out MailServer\*.java MailClient\*.java
javac -encoding UTF-8 -cp out -d out tools\audit\Step01Audit.java
java -Dstdout.encoding=UTF-8 -cp out Step01Audit
```

Harness chỉ dùng tài khoản giả lập, lưu trong `out/audit/fixtures-*` (được ignore bởi `out/`). Server bind loopback cổng tự cấp, đóng sau test. Không gửi thư ngoài máy và không dùng dữ liệu thật. Không thay đổi mã nguồn production để test.

| ID | Kiểm thử / yêu cầu được kiểm tra | Kết quả |
|---|---|---|
| A01 | PING UDP loopback | PASS |
| A02 | Hai Client đăng ký đồng thời | PASS |
| A03 | Từ chối đăng ký trùng | PASS |
| A04 | Từ chối địa chỉ không hợp lệ | PASS |
| A05 | Tạo thư mục, `.sent`, `new_email.txt` | PASS |
| A06 | Nội dung lời chào đúng đề bài | FAIL |
| A07 | Lưu hash thay vì mật khẩu plaintext | PASS |
| A08 | Từ chối mật khẩu sai | PASS |
| A09 | Đăng nhập đúng và trả danh sách tên file | PASS |
| A10 | Gửi thư nội bộ qua UDP | PASS |
| A11 | Danh sách B có thư mới | PASS |
| A12 | READ UTF-8 trùng nội dung file và có thời gian | PASS |
| A13 | Có bản sao trong Sent | PASS |
| A14 | Ngăn Client chưa xác thực đọc thư B | FAIL |
| A15 | Ngăn truy xuất `.credentials` | FAIL |
| A16 | Ngăn Client chưa xác thực xem Sent A | FAIL |
| A17 | Ngăn Client chưa xác thực giả mạo người gửi A | FAIL |
| A18 | Ngăn đường dẫn `../` thoát thư mục | PASS |
| A19 | Xử lý người nhận chưa đăng ký bằng SEND_FAILED | PASS |
| A20 | Client từ chối request quá 60.000 byte | PASS |
| A21 | Server/storage instance mới đọc tài khoản và thư đã lưu | PASS |
| A22 | Client báo IOException khi server đã đóng | PASS |

A19 chỉ PASS cho hành vi xử lý lỗi nội bộ hiện tại; không có nghĩa đã gửi được Gmail. A21 là khởi động lại đối tượng server và storage trong cùng JVM, chưa thử tắt/bật process hay mô phỏng crash. A22 thực tế nhận `PortUnreachableException`, chưa kiểm tra nhánh timeout/retry khi mạng âm thầm bỏ gói.

Bằng chứng: `evidence/step01-build.txt` (14 warnings, không compile error), `evidence/step01-runtime.txt` (22 kết quả, tổng 17/5), `evidence/step01-environment.txt` (phiên bản công cụ, trạng thái dịch vụ và TCP). Log runtime ban đầu có ký tự tiếng Việt bị console Windows chuyển sai; A12 so sánh UTF-8 trong JVM/file thành công, không dựa vào hiển thị console để xác nhận encoding.

Chưa chạy: SQL đăng nhập/CRUD, SMTP, Gmail nhận thư, POP3, hai GUI tương tác, race retry đồng thời, fault injection DB/file, TLS, tải lớn. Các mục này **CHƯA KIỂM THỬ**, không gắn PASS/FAIL giả.

## 8. Kế hoạch kỹ thuật STEP 02–08

Giữ Swing, mã xử lý file, hashing và giao diện hiện có làm nền. Tách service khỏi transport để TCP/SMTP/POP3 dùng chung nghiệp vụ. Chỉ thêm giao thức vì yêu cầu TCP/SMTP/POP3 không thể được đáp ứng bởi UDP tự định nghĩa hiện tại; giữ đường UDP tương thích khi nâng cấp và bắt buộc áp dụng cùng phân quyền. Không duy trì một đường UDP bỏ qua bảo mật của các đường mới.

| STEP | Công việc và file dự kiến | Điều kiện kiểm chứng / hoàn thành |
|---|---|---|
| 02 — Tài khoản/DB | Thêm `pom.xml` cấu hình UTF-8 và source layout tương thích; Connector/J; cấu hình DB qua môi trường; SQL schema `users`, `emails`, khóa/index và unique username/email/request ID; repository PreparedStatement; service tài khoản, phiên ngẫu nhiên có hết hạn, logout thu hồi; sửa Handler/Storage và Client dùng token; chặn file credentials; lời chào đúng đề bài; migration dữ liệu cũ có kiểm soát | Cần DB riêng và thông tin kết nối được cấp. Test đăng ký trùng/đồng thời, login đúng/sai, rollback, file lời chào, dữ liệu MySQL; A14–A17 phải chuyển PASS; không mang lỗi phân quyền sang bước sau |
| 03 — Mail nội bộ | Service mail dùng chung; TCP request/response có framing/giới hạn/timeout và nhiều Client; sender từ phiên; Inbox/Sent theo owner; lưu idempotency key vào DB; trạng thái PENDING → STORED; file tạm + atomic move khi phù hợp, job khôi phục theo DB; phân trang danh sách | Test hai Client TCP, sender spoofing/cross-account, gửi lại cùng ID, restart, fault injection từng bước file/DB và khôi phục; xác nhận file và SQL đồng nhất |
| 04 — SMTP | Thêm Jakarta Mail/implementation tương thích qua Maven sau khi kiểm tra tài liệu chính thức; host/port/STARTTLS/SSL/auth/timeout từ cấu hình ngoài; tách gửi local và relay external; log không chứa secret. SMTP client gửi ra ngoài khác SMTP server nhận vào. Để chứng minh SMTP nội bộ: thêm listener TCP loopback mặc định (ví dụ 2525, cấu hình được), EHLO/HELO, MAIL FROM, RCPT TO, DATA, RSET, NOOP, QUIT; chuẩn hóa MIME/CRLF/dot-stuffing, giới hạn, không open relay | Test Jakarta Mail → SMTP local → Inbox/Sent/DB; mock relay cho lỗi auth/TLS/timeout và SMTP_ACCEPTED; gửi Gmail thật chỉ khi có relay và địa chỉ kiểm thử. Chứng cứ thư nhận/Message-ID từ phía Gmail tách khỏi SMTP acceptance; nếu thiếu xác thực phải ghi chưa xác minh |
| 05 — POP3 | Listener TCP riêng loopback (ví dụ 1110, cấu hình được); USER/PASS, STAT, LIST [n], RETR n, DELE n, NOOP, QUIT; thêm RSET; state machine, snapshot ổn định và khóa mailbox; xóa đánh dấu chỉ commit khi QUIT hợp lệ, bỏ khi disconnect; CRLF/dot-stuffing, octet count theo bytes MIME; dùng mail service/repository chung | Test bằng POP3 client Jakarta Mail và socket transcript; sai state, sai password, LIST/RETR byte count, DELE/QUIT, disconnect rollback, hai session cùng mailbox, không xem thư người khác. POP3 Gmail không phải điều kiện cho POP3 localhost |
| 06 — Swing | Giữ sidebar/Inbox/Sent/Compose và phong cách hiện tại; đăng ký rõ ràng, tìm kiếm trên mailbox được phân quyền; refresh bằng phiên thay vì nhập lại mật khẩu; trạng thái mạng, lỗi dễ hiểu, ngăn phản hồi cũ cập nhật UI sau logout; cập nhật nhãn Gmail/UDP theo đúng transport | Test tương tác thật hai cửa sổ GUI: login, refresh, gửi/đọc, tìm kiếm, logout, mất kết nối và kết nối lại; UI không treo EDT; ghi ảnh/bằng chứng nếu công cụ cho phép |
| 07 — Tổng thể | Bộ integration test localhost + MySQL riêng, SMTP local và POP3 client; báo cáo 15 kịch bản gốc, evidence có thời gian; tách kiểm thử Gmail ngoài máy có cấu hình | Mỗi kịch bản có PASS/FAIL sau thực thi hoặc ghi chưa chạy và lý do; không tổng kết hoàn tất nếu còn lỗi bắt buộc; kiểm tra restart process và nhiều Client thực tế |
| 08 — Tài liệu/demo | `README.md`, `DATABASE_SETUP.md`, `SMTP_POP3_SETUP.md`, `DEMO_GUIDE.md`, `FINAL_REPORT.md`; lệnh build/run Windows, cấu hình mẫu không chứa secret, migration/recovery và kịch bản demo | Chạy lại từ hướng dẫn trên môi trường hiện có; demo server + hai Client + thư mục/file + SQL + SMTP/POP3; ghi Gmail accepted/received riêng; chỉ liệt kê hoàn tất các mục có bằng chứng |

Trước khi thêm thư viện/giao thức ở các bước triển khai phải đối chiếu tài liệu chính thức tương ứng. STEP 01 chỉ kết luận từ source code và thực thi cục bộ, chưa lựa chọn/chốt phiên bản dependency mới.

## 9. Báo cáo kết thúc STEP 01

1. **Đã thực hiện:** audit toàn bộ source, công cụ, dịch vụ MySQL; compile và chạy 22 kiểm thử trên dữ liệu tách biệt.
2. **File tạo:** `STEP01_AUDIT.md`, `tools/audit/Step01Audit.java`, ba file `evidence/step01-*.txt`. File `.class`/fixtures nằm trong `out/` được Git ignore. Không chỉnh source ứng dụng.
3. **Chức năng xác minh tồn tại:** đăng ký/hash, login, thư mục/file, danh sách thư, gửi/đọc UTF-8, Sent, nhiều socket và lưu dữ liệu qua server instance mới. Đây là nền tảng UDP/file, chưa hoàn tất yêu cầu nâng cấp.
4. **Kiểm thử:** compile thành công với 14 warnings; runtime 17 PASS/5 FAIL; MySQL service Running và TCP reachable, chưa SQL authentication.
5. **Vấn đề còn lại:** 5 FAIL, lỗi refresh/trạng thái UI từ phân tích source; chưa TCP/SMTP/POP3/MySQL/Gmail; thiếu cấu hình DB/SMTP để kiểm chứng tích hợp thật.
6. **Tiếp theo:** STEP 02 xử lý tài khoản, phân quyền, lời chào và DB trước. Không coi những lỗi audit là đã sửa hoặc bỏ qua để đánh dấu STEP sau hoàn thành. STEP 02–08 hiện là kế hoạch, chưa được triển khai trong lần audit này.
