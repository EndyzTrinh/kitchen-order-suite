# Kiểm thử F16 - Đăng nhập, đăng ký, phân quyền

Chạy máy chủ thật (cổng TCP 6000), gửi lệnh qua socket. Kết quả: 32 / 32 đạt.

| # | Trường hợp | Kết quả mong đợi |
| --- | --- | --- |
| 1 | Đăng ký qua mạng khi hệ thống chưa có tài khoản | Từ chối: "Hệ thống chưa có Quản lý..." |
| 2 | Tạo Quản lý đầu tiên trên máy chủ | Vai trò Quản lý, Hoạt động ngay |
| 3 | Đăng ký Bếp trưởng | OK, trạng thái Chờ duyệt |
| 4 | Đăng ký trùng tên (khác hoa / thường) | Từ chối |
| 5 | Tự xin vai trò Quản lý | Từ chối |
| 6 | Tên đăng nhập có dấu cách | Từ chối |
| 7 | Mật khẩu dưới 6 ký tự | Từ chối |
| 8 | Đếm tài khoản chờ duyệt | 3 |
| 9 | Đăng nhập khi chưa duyệt | Từ chối: "đang chờ quản lý duyệt" |
| 10 | users.json không chứa mật khẩu thật | Chỉ có salt + hash |
| 11 | Đăng nhập Quản lý (gõ tên chữ hoa) | LOGIN_OK có token |
| 12 | Đăng nhập Bếp trưởng, Bếp phụ, Phục vụ | Cả ba có token |
| 13 | HELLO không có token | ERROR AUTH |
| 14 | HELLO token giả | ERROR AUTH |
| 15 | Phục vụ mở màn hình bếp | ERROR FORBIDDEN |
| 16 | Bếp phụ mở MASTERCHEF | ERROR FORBIDDEN |
| 17 | Bếp phụ mở VIEW | WELCOME |
| 18 | Bếp trưởng mở MASTERCHEF | WELCOME |
| 19 | Mở MASTERCHEF thứ hai | ERROR "Đã có màn hình MASTERCHEF" |
| 20 | Quản lý khóa Bếp trưởng đang mở màn hình bếp | Màn hình bếp nhận ERROR AUTH |
| 21 | | Kết nối bị đóng |
| 22 | | Phiên bị hủy |
| 23 | Đăng nhập tài khoản bị khóa | Từ chối |
| 24 | Quản lý tự khóa mình | Từ chối |
| 25 | Hạ vai trò Quản lý duy nhất | Từ chối: "Phải còn ít nhất một Quản lý" |
| 26 | Đổi vai trò | Phiên cũ hết hiệu lực |
| 27 | Đổi mật khẩu, nhập sai mật khẩu cũ | Từ chối |
| 28 | Đổi mật khẩu đúng | OK |
| 29 | Đăng nhập bằng mật khẩu mới | LOGIN_OK |
| 30 | Sai mật khẩu 5 lần rồi nhập đúng | Vẫn bị khóa tạm 60 giây |
| 31 | Đăng xuất | Phiên bị hủy |
| 32 | Nạp lại dữ liệu (như khởi động lại máy chủ) | Phiên vẫn còn |

Kiểm thử giao diện (bấm chuột tự động):
tạo Quản lý lần đầu → đăng nhập → trang chủ đủ 8 ô; đăng nhập Phục vụ → chỉ mở Gọi món, Thiết bị rung;
máy bếp (màn hình khác) đăng nhập Bếp trưởng → mở Màn hình bếp → quản lý khóa tài khoản →
máy bếp hiện "Tài khoản của bạn đã bị khóa" và về màn hình đăng nhập.
Chạy lại luồng gọi món → nhận làm → gia hạn → chưa xong → rung → đã lấy → lịch sử: không lỗi.
