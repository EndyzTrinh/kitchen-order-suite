# Kitchen Order Suite

Đồ án Lập trình Mạng Máy Tính (CMP180) - nhóm Hoảng Loạn.
Hệ thống gọi món qua iPad / web, masterchef điều phối bếp có hẹn giờ, thiết bị rung báo phục vụ lấy món.

## Yêu cầu

- JDK 21, Apache NetBeans, Git
- iPad, iPhone và laptop chạy máy chủ ở cùng một mạng Wi-Fi

## Mở dự án

NetBeans → **File → Open Project** → chọn thư mục `kitchen-order-suite` (biểu tượng Maven).
Lần đầu NetBeans sẽ tự tải thư viện Gson; cần có Internet.

## Chạy thử

1. **Máy chủ:** chuột phải `src/main/java/kos/server/ServerApp.java` → **Run File**.
   Cửa sổ Output in ra địa chỉ để mở trên iPad / iPhone, ví dụ
   `gọi món http://192.168.1.10:8081/order | thiết bị rung http://192.168.1.10:8081/pager?id=1`.
   Lần đầu Windows hỏi tường lửa: chọn **Cho phép**, tích **mạng riêng (Private)**.
2. **Màn hình bếp:** chuột phải `kos/kitchen/MasterchefApp.java` → **Run File**.
   Chạy trên máy khác thì thêm IP máy chủ: chuột phải dự án → Properties → Run → Arguments, nhập `192.168.1.10`.
3. **Gọi món:** mở địa chỉ `/order` trên iPad, chọn bàn, món, bấm **Gửi bếp** → màn hình bếp in ra ORDER_NEW.
4. **Thiết bị rung:** mở địa chỉ `/pager?id=1` trên iPhone, bấm **Kích hoạt** rồi **Thử chuông**.
   Tắt chế độ im lặng của iPhone để nghe chuông.

Nếu chữ tiếng Việt trong Output bị lỗi dấu: chuột phải dự án → Properties → Run → VM Options, nhập
`-Dstdout.encoding=UTF-8`.

## Cấu trúc

```
src/main/java/kos/common/   Giao thức và dữ liệu dùng chung (Protocol, Message, Json, Order, OrderItem, MenuItem, ItemStatus)
src/main/java/kos/server/   Máy chủ kiêm quầy quản lý (ServerApp, ClientHandler, ClientRegistry, OrderStore, MenuStore, WebGateway)
src/main/java/kos/kitchen/  Masterchef Display (MasterchefApp, ServerConnection)
src/main/resources/         menu.json mẫu, web/order.html, web/pager.html
test-data/menu-demo.json    Thực đơn có thời gian chuẩn bị 1 - 2 phút để demo
docs/PROTOCOL.md            Đặc tả giao thức
```

## Thử nhanh với thời gian chuẩn bị 1 - 2 phút

Tạo thư mục `data` trong thư mục dự án, chép `test-data/menu-demo.json` vào đó và đổi tên thành `menu.json`.
Máy chủ cũng tự lưu `data/orders.json`; xóa file này nếu muốn bắt đầu lại từ đầu.
Thư mục `data` không đưa lên GitHub (đã có trong `.gitignore`).

Trong cửa sổ Output của MasterchefApp, gõ lệnh ở dòng nhập phía dưới:

```
nhan 1 1            nhận làm đơn 1, món 1
giahan 1 1 3        gia hạn 3 phút
xong 1 1            xong sớm
het M01 Hết nước    báo hết món M01
```

## Phân công (tìm chữ TODO trong mã)

| Người | Phần việc | Tệp chính |
| --- | --- | --- |
| Nam | Máy chủ đã xong phần lõi. Còn: kiểm thử, ảnh Wireshark, PROTOCOL.md, báo cáo, tích hợp giao diện | `kos/server/*` |
| Cường | Giao diện quầy quản lý (Swing): thẻ Đơn hàng, Thực đơn, Thiết bị, Log; trang gọi món hiện trạng thái đơn | `ServerApp` (đọc ghi chú đầu file), `web/order.html` |
| Khoa | Masterchef Display (Swing): theo đơn, gộp theo món, đồng hồ, nhận làm, gia hạn, xong sớm, báo hết món; làm đẹp trang thiết bị rung | `MasterchefApp`, `ServerConnection`, `web/pager.html` |

Quy tắc: không gõ tên lệnh trực tiếp, luôn dùng hằng số trong `Protocol.java`. Muốn đổi giao thức thì báo Nam.
Làm trên nhánh riêng (`ten/viec-dang-lam`), gộp vào `main` bằng pull request.
