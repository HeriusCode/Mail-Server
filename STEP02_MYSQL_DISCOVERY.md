> Lịch sử của phạm vi cũ. Bản demo hiện tại dùng File Storage; không cần thực hiện các bước MySQL bên dưới. Xem README.md và DEMO_GUIDE.md.

# Tự kiểm tra MySQL Windows để tiếp tục STEP 02

Ngày kiểm tra: 03/11/2026 (Asia/Saigon). Đã hoàn tất tự kiểm tra trước khi yêu cầu thông tin bổ sung. Bằng chứng đã lược bỏ mọi giá trị thông tin xác thực: [step02-mysql-discovery.txt](evidence/step02-mysql-discovery.txt).

## Kết quả xác minh thực tế

| Mục | Kết quả |
|---|---|
| Dịch vụ MySQL80 | **Running** |
| MySQL Server đang phục vụ | **8.0.29**, lấy trực tiếp từ initial greeting của server tại 127.0.0.1:3306; khớp version binary mysqld.exe |
| Cổng | **3306**, kết nối TCP thành công và khớp my.ini |
| MySQL Server | `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe` |
| MySQL Client | Đã có `mysql.exe` 8.0.29 trong cùng thư mục bin; chưa có trên PATH, không cần cài lại |
| Cấu hình service | Registry service trỏ tới `C:\ProgramData\MySQL\MySQL Server 8.0\my.ini`; đã đọc các mục liên quan, không có include bổ sung |
| JDBC Driver | Đã có Connector/J **9.7.0** ở target/lib và Maven cache workspace; cache người dùng còn có 8.0.33 |
| Cấu hình dự án hợp lệ | **Chưa tìm thấy**; chỉ có file mẫu |
| Kết nối JDBC đã xác thực | **Chưa thực hiện được** do thiếu credentials; DatabaseTool check trả NOT RUN, exit code 2 |
| Database mail_server_db | **Chưa xác định**, không kết luận có/không khi chưa chạy SQL |
| Các bảng và quyền của tài khoản dự án | **Chưa xác định**; chưa thực thi DDL/CRUD |

TCP/greeting thành công chỉ chứng minh MySQL đang hoạt động; không thay thế một phiên SQL/JDBC đã xác thực.

## Những nguồn cấu hình đã tự kiểm tra

- Root workspace và thư mục MailServer, MailClient, config; các tên `.env`, `.env.local`, `config.properties`, `database.properties` và các định dạng cấu hình liên quan. Bao gồm file ẩn và file được Git ignore.
- Biến môi trường cấp **Process, User, Machine**: các tên liên quan MySQL, MAIL_DB_*, MAIL_TEST_DB_*, MAIL_CONFIG và DB_* thông thường. Không tìm được cấu hình xác thực.
- Cấu hình MySQL native ở các vị trí Windows/install/user chuẩn, gồm `.my.cnf`, `.mylogin.cnf`; chỉ tìm thấy file server my.ini nói trên, không có client authentication options.
- MySQL Workbench `connections.xml`: có hai connection, đều dùng root, không chỉ định schema mail_server_db, không có mật khẩu inline. Không suy diễn rằng Workbench không lưu mật khẩu ở nơi khác; kho thông tin xác thực đó không được truy cập.
- Dependencies đang có trong project, target/lib và Maven cache.

Không truy cập mật khẩu trình duyệt, Windows Credential Manager, Workbench vault hay ứng dụng không liên quan. Không đoán/thử hàng loạt mật khẩu, không thử root với mật khẩu rỗng và không sao chép thông tin xác thực sang file khác.

## Tận dụng cấu hình và tiếp tục phần độc lập

Giữ nguyên cài đặt/dịch vụ MySQL hiện có và Connector/J 9.7.0 của project. File mẫu `config/database.example.properties` đã dùng đúng endpoint được xác minh `127.0.0.1:3306` và database mục tiêu `mail_server_db`, nên không cần thay endpoint hoặc cài MySQL lại.

JDBC, PreparedStatement, schema utf8mb4, transaction/outbox, công cụ migration/backup và sáu integration test đã được bàn giao trong STEP 02 trước đó. Lần kiểm tra này đã gọi lại `DatabaseTool check` và `DatabaseTool inspect mail_storage`: SQL check ghi rõ thiếu cấu hình, còn workspace chưa có nguồn mail_storage thật để migration. Không đánh dấu các integration test đó PASS.

Đã xác nhận `config/database.local.properties` được Git ignore. Không tạo file local với dữ liệu xác thực đoán hoặc thiếu, vì cấu hình DB chưa đủ sẽ làm Server dừng thay vì giữ chế độ file hiện đang demo được. Không thay root password, quyền tài khoản, schema, server my.ini, dịch vụ hoặc PATH máy.

## Thông tin duy nhất còn thiếu

Cần **username và password của tài khoản SQL được phép làm việc với project**. Host, port, server version, driver và database mục tiêu đã xác định; không cần cung cấp lại các thông tin này. Hai connection root trong Workbench chỉ là metadata, không cung cấp quyền đăng nhập cho tiến trình JDBC.

Điền thông tin xác thực trong `config/database.local.properties` theo file mẫu, hoặc cho biết đường dẫn của một file cấu hình liên quan đã có để project đọc trực tiếp. Không gửi mật khẩu vào chat. Tên `mail_app` trong file mẫu chỉ là placeholder, chưa được xác minh là tài khoản tồn tại.

Khi có nguồn xác thực hợp lệ, việc tiếp theo là kiểm tra JDBC, database/bảng/quyền hiện có; tạo schema còn thiếu nếu tài khoản có quyền và không xung đột dữ liệu, chạy sáu integration test trên schema *_test riêng và kiểm chứng migration chạy lại. Không tự đổi quyền hoặc tạo/reset tài khoản để vượt qua việc thiếu credentials. Hướng dẫn hiện có: [database/README.md](database/README.md).
