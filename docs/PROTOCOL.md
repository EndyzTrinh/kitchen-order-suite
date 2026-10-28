# Đặc tả giao thức - Kitchen Order Suite (bản 0.6)

Mọi thông điệp là một đối tượng JSON (UTF-8). Tên trường viết snake_case. Trường nào không dùng thì bỏ.
Hằng số tương ứng nằm trong `kos/common/Protocol.java`.

## Kênh truyền

| Kênh | Cổng | Ai dùng | Đóng khung thông điệp |
| --- | --- | --- | --- |
| TCP | 6000 | Masterchef Display | Mỗi thông điệp một dòng, kết thúc bằng `\n` |
| HTTP | 8081 | Trang gọi món, thiết bị rung | Thân yêu cầu / phản hồi (Content-Length) |
| SSE trên HTTP | 8081 | Trang gọi món, thiết bị rung | Mỗi sự kiện: dòng `event:`, các dòng `data:`, một dòng trống |

## Đường dẫn HTTP

| Đường dẫn | Phương thức | Nội dung | Trạng thái |
| --- | --- | --- | --- |
| `/order` | GET | Trang gọi món | Đã có |
| `/pager?id=N` | GET | Trang thiết bị rung số N | Đã có |
| `/api/menu` | GET | MENU_UPDATE kèm thực đơn | Đã có |
| `/api/order` | POST | Nhận NEW_ORDER, trả ORDER_ACK hoặc ERROR | Đã có |
| `/api/snapshot` | GET | SNAPSHOT các đơn chưa hoàn tất | Đã có |
| `/api/events?name=X` | GET (SSE) | ORDER_NEW, ITEM_UPDATE, MENU_UPDATE cho trang gọi món | Đã có |
| `/api/priority` | POST | Nhận PRIORITY_ITEMS, trả PRIORITY_ITEMS (đã nhận) hoặc ERROR | Đã có |
| `/api/heartbeat` | POST | Heartbeat của trang gọi món | Đã có |
| `/api/pager/events?id=N` | GET (SSE) | PAGER_CALL, PAGER_CLOSE cho thiết bị N | Đã có |
| `/api/pager/received` | POST | PAGER_RECEIVED | Đã có |
| `/api/pager/ack` | POST | PICKED_UP | Đã có |
| `/api/pager/heartbeat` | POST | Heartbeat của thiết bị rung | Đã có |

## Lệnh

| Lệnh | Chiều | Trường | Trạng thái |
| --- | --- | --- | --- |
| LOGIN | Máy khách → máy chủ (TCP, trước HELLO) | username, password | Đã có |
| LOGIN_OK | Máy chủ → máy khách | token, user[username, full_name, role, status], server_time | Đã có |
| REGISTER | Máy khách → máy chủ (TCP, trước HELLO) | username, full_name, password, role | Đã có |
| LOGOUT | Máy khách → máy chủ | token | Đã có |
| CHANGE_PASSWORD | Máy khách → máy chủ | token, password (cũ), new_password | Đã có |
| OK | Máy chủ → máy khách | message (trả lời REGISTER, LOGOUT, CHANGE_PASSWORD) | Đã có |
| HELLO | Bếp → máy chủ | name, mode (MASTERCHEF / VIEW), token | Đã có |
| WELCOME | Máy chủ → bếp | server_time, menu | Đã có |
| SNAPSHOT | Máy chủ → thiết bị | orders | Đã có |
| PING / PONG | Bếp ⇄ máy chủ | server_time (trong PONG) | Đã có |
| NEW_ORDER | Trang gọi món → máy chủ | req_id, name, table, pager_id, items[menu_id, qty, note, priority] | Đã có |
| ORDER_ACK | Máy chủ → trang gọi món | req_id, order_id, kitchen_online | Đã có |
| ORDER_NEW | Máy chủ → thiết bị | order | Đã có |
| START_ITEMS | Masterchef → máy chủ | req_id, items[order_id, item_no], chef | Đã có |
| EXTEND_ITEMS | Masterchef → máy chủ | req_id, items[order_id, item_no], minutes, reason | Đã có |
| NOT_DONE | Masterchef → máy chủ | req_id, items[order_id, item_no], minutes, reason | Đã có |
| FINISH_ITEMS | Masterchef → máy chủ | req_id, items[order_id, item_no] | Đã có |
| PRIORITY_ITEMS | Trang gọi món → máy chủ | req_id, name, items[order_id, item_no] | Đã có |
| SOLD_OUT | Masterchef → máy chủ | req_id, menu_id, reason | Đã có |
| ITEM_UPDATE | Máy chủ → thiết bị | order_id, item_no, status, started_at, deadline, extended_minutes, priority, priority_at, chef, finished_at, reason, by | Đã có |
| MENU_UPDATE | Máy chủ → thiết bị | menu | Đã có |
| PAGER_CALL | Máy chủ → thiết bị rung | call_id, pager_id, order_id, table, items | Đã có |
| PAGER_RECEIVED | Thiết bị rung → máy chủ | call_id, pager_id | Đã có |
| PICKED_UP | Thiết bị rung → máy chủ | call_id, pager_id | Đã có |
| PAGER_CLOSE | Máy chủ → thiết bị rung | call_id | Đã có |
| ERROR | Máy chủ → nơi gửi | req_id, message, code (AUTH / FORBIDDEN, có thể thiếu) | Đã có |

## Quy tắc

- Trước HELLO, kết nối TCP chỉ được gửi LOGIN, REGISTER, LOGOUT, CHANGE_PASSWORD, PING; lệnh khác thì nhận ERROR và bị đóng.
- HELLO phải kèm token còn hiệu lực; vai trò phải được mở chế độ xin (xem mục F16).
- Chỉ một màn hình MASTERCHEF tại một thời điểm.
- Không nhận được gì trong 15 giây thì đóng kết nối; máy khách gửi PING mỗi 5 giây và thử kết nối lại mỗi 3 giây.
- Gửi lại NEW_ORDER cùng req_id thì máy chủ trả lại ORDER_ACK cũ, không tạo đơn mới.
- Hạn giờ (deadline) do máy chủ quyết định, tính bằng mili giây theo đồng hồ máy chủ.

## Ví dụ

```
→ {"command":"HELLO","name":"MC","mode":"MASTERCHEF"}
← {"command":"WELCOME","menu":[...],"server_time":1792501914098}
← {"command":"SNAPSHOT","orders":[]}

POST /api/order
{"command":"NEW_ORDER","req_id":"IPAD1-0007","name":"IPAD1","table":5,"pager_id":1,
 "items":[{"menu_id":"M03","qty":2,"note":"ít cay"}]}
← {"command":"ORDER_ACK","req_id":"IPAD1-0007","order_id":12,"kitchen_online":true}
```

## Lưu trữ (F12)

- `data/menu.json`: thực đơn; ghi lại mỗi khi quản lý sửa món hoặc masterchef báo hết món.
- `data/orders.json`: các đơn chưa hoàn tất, bộ đếm số đơn, các req_id đã xử lý. Ghi sau mỗi thay đổi
  (ghi file tạm rồi đổi tên). Khởi động lại: món đang làm được đặt lại đồng hồ theo deadline đã lưu,
  món đã quá hạn chuyển READY và gọi thiết bị rung ngay. File hỏng được đổi tên thành `orders.json.loi-<thời điểm>`.

## Món ưu tiên (F14)

- Phục vụ đánh dấu `"priority": true` cho món trong NEW_ORDER, hoặc gửi PRIORITY_ITEMS cho món đã gửi.
- Máy chủ chỉ chấp nhận món đang PENDING; mỗi bàn tối đa `MAX_PRIORITY_PER_TABLE` (= 2) món ưu tiên đang chờ.
  Món đã COOKING trả ERROR "bếp đang làm món này rồi"; món đã ưu tiên trả ERROR "đã được ưu tiên".
- Máy chủ ghi `priority_at` = thời điểm xin (đồng hồ máy chủ) và phát ITEM_UPDATE (priority, priority_at).
- Hàng đợi ưu tiên = các món PENDING có priority, xếp tăng dần theo `priority_at`; số thứ tự #1, #2...
  được các màn hình tự tính từ thứ tự này nên mọi màn hình thấy cùng một thứ tự.
- Món ưu tiên được nhận làm thì rời hàng đợi (vẫn giữ priority = true để tra cứu).
- Hàng đợi từ `PRIORITY_ALERT_COUNT` (= 6) món: máy chủ ghi CẢNH BÁO vào log, màn hình bếp tô đỏ tiêu đề.

## Thời gian thực tế (F15)

- START_ITEMS có `chef` (tên người làm; thiếu thì máy chủ ghi "Không rõ"). Máy chủ ghi `chef` và `batch_qty`
  (tổng số phần cùng món nhận làm trong một lượt) vào từng món.
- EXTEND_ITEMS có `reason`; máy chủ cộng dồn `extend_count` và `extend_reasons` (ví dụ "Đông khách (+3); Thiếu nguyên liệu (+1)").
- Món chuyển READY thì máy chủ ghi `finished_at` và `finish_mode`: hết giờ thì `finished_at` = deadline, "Hết giờ";
  FINISH_ITEMS thì `finished_at` = lúc bấm, "Xong sớm".
- NOT_DONE: chỉ nhận món đang READY. Món về COOKING, deadline = bây giờ + minutes, `extended_minutes` += minutes,
  `not_done_count` + 1, xóa `finished_at`; lý do ghi thành "Chưa xong: ...". Máy chủ thu hồi món khỏi lệnh rung đang mở:
  đóng lệnh cũ (PAGER_CLOSE) và gửi lệnh mới cho các món còn lại của lệnh đó (nếu có). Món không ở READY thì trả ERROR.
- PICKED_UP: máy chủ ghi `served_at` rồi thêm một dòng vào `data/lich-su.csv` (UTF-8 có BOM, phân cách dấu phẩy):

```
ngay,gio_nhan_lam,don,ban,ma_mon,mon,so_luong,lam_gop,chef,uu_tien,chuan_giay,thuc_te_giay,chenh_lech_giay,
so_lan_gia_han,gia_han_phut,so_lan_chua_xong,ly_do,ket_thuc,cho_lay_giay
```

  thuc_te_giay = finished_at - started_at; chenh_lech_giay = thực tế - chuẩn; cho_lay_giay = served_at - finished_at.

## Đăng nhập và phân quyền (F16)

- Vai trò (`role`): `QUAN_LY`, `BEP_TRUONG`, `BEP_PHU`, `PHUC_VU`. Trạng thái (`status`): `CHO_DUYET`, `HOAT_DONG`, `KHOA`.
- Máy khách mở một kết nối TCP ngắn cho mỗi lệnh tài khoản: gửi một dòng, đọc một dòng trả lời, đóng.
- LOGIN đúng: máy chủ tạo token ngẫu nhiên 192 bit, lưu vào `data/sessions.json`, trả LOGIN_OK. Sai: ERROR
  "Sai tên đăng nhập hoặc mật khẩu" (không nói rõ sai phần nào). Sai 5 lần liên tiếp: khóa tạm 60 giây.
  Tài khoản Chờ duyệt / Đã khóa: ERROR tương ứng.
- REGISTER: tên đăng nhập 3 - 20 ký tự `[a-z0-9._]` (máy chủ đổi về chữ thường), mật khẩu ≥ 6 ký tự, role khác `QUAN_LY`.
  Tài khoản mới ở `CHO_DUYET`. Khi hệ thống chưa có tài khoản nào, REGISTER qua mạng bị từ chối:
  tài khoản Quản lý đầu tiên chỉ tạo được trên chính máy chủ.
- HELLO: máy chủ tra token → tài khoản. Không có / hết hạn / bị khóa: ERROR code `AUTH`.
  `QUAN_LY`, `BEP_TRUONG` được MASTERCHEF hoặc VIEW; `BEP_PHU` chỉ VIEW; `PHUC_VU` không được: ERROR code `FORBIDDEN`.
  Nhận ERROR có code thì màn hình bếp dừng kết nối lại và đưa người dùng về màn hình đăng nhập.
- START_ITEMS không có `chef`: máy chủ lấy họ tên người đăng nhập.
- Quản lý khóa, đổi vai trò, đặt lại mật khẩu hoặc xóa tài khoản: máy chủ xóa mọi phiên của tài khoản đó, gửi ERROR code `AUTH`
  tới màn hình bếp đang dùng tài khoản đó rồi đóng kết nối.
- Mật khẩu lưu trong `data/users.json` dạng `salt` (16 byte ngẫu nhiên) + `hash` = PBKDF2WithHmacSHA256(mật khẩu, salt, 65536 vòng, 256 bit),
  so sánh bằng `MessageDigest.isEqual`. Mật khẩu không bao giờ được ghi vào log.

```
→ {"command":"LOGIN","username":"anhba","password":"******"}
← {"command":"LOGIN_OK","token":"q3V...","user":{"username":"anhba","full_name":"Nguyễn Văn Ba","role":"BEP_TRUONG","status":"HOAT_DONG"},"server_time":1793...}
→ {"command":"HELLO","name":"MC","mode":"MASTERCHEF","token":"q3V..."}
← {"command":"WELCOME", ...}
```
