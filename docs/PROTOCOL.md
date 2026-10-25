# Đặc tả giao thức - Kitchen Order Suite (bản 0.4)

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
| HELLO | Bếp → máy chủ | name, mode (MASTERCHEF / VIEW) | Đã có |
| WELCOME | Máy chủ → bếp | server_time, menu | Đã có |
| SNAPSHOT | Máy chủ → thiết bị | orders | Đã có |
| PING / PONG | Bếp ⇄ máy chủ | server_time (trong PONG) | Đã có |
| NEW_ORDER | Trang gọi món → máy chủ | req_id, name, table, pager_id, items[menu_id, qty, note, priority] | Đã có |
| ORDER_ACK | Máy chủ → trang gọi món | req_id, order_id, kitchen_online | Đã có |
| ORDER_NEW | Máy chủ → thiết bị | order | Đã có |
| START_ITEMS | Masterchef → máy chủ | req_id, items[order_id, item_no] | Đã có |
| EXTEND_ITEMS | Masterchef → máy chủ | req_id, items[order_id, item_no], minutes | Đã có |
| FINISH_ITEMS | Masterchef → máy chủ | req_id, items[order_id, item_no] | Đã có |
| PRIORITY_ITEMS | Trang gọi món → máy chủ | req_id, name, items[order_id, item_no] | Đã có |
| SOLD_OUT | Masterchef → máy chủ | req_id, menu_id, reason | Đã có |
| ITEM_UPDATE | Máy chủ → thiết bị | order_id, item_no, status, started_at, deadline, extended_minutes, priority, priority_at, reason, by | Đã có |
| MENU_UPDATE | Máy chủ → thiết bị | menu | Đã có |
| PAGER_CALL | Máy chủ → thiết bị rung | call_id, pager_id, order_id, table, items | Đã có |
| PAGER_RECEIVED | Thiết bị rung → máy chủ | call_id, pager_id | Đã có |
| PICKED_UP | Thiết bị rung → máy chủ | call_id, pager_id | Đã có |
| PAGER_CLOSE | Máy chủ → thiết bị rung | call_id | Đã có |
| ERROR | Máy chủ → nơi gửi | req_id, message | Đã có |

## Quy tắc

- Kết nối TCP phải gửi HELLO đầu tiên; gửi lệnh khác trước HELLO thì nhận ERROR và bị đóng.
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
