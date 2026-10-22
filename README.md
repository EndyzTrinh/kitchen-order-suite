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
   Cửa sổ **Quầy quản lý** mở ra; thanh dưới cùng ghi địa chỉ để mở trên iPad / iPhone, ví dụ
   `http://192.168.1.10:8081/order` và `http://192.168.1.10:8081/pager?id=1` (cửa sổ Output cũng in ra).
   Lần đầu Windows hỏi tường lửa: chọn **Cho phép**, tích **mạng riêng (Private)**.
   Tắt máy chủ: đóng cửa sổ Quầy quản lý → **Yes** (hoặc gõ `exit` ở cửa sổ Output). Không dùng nút dừng đỏ của NetBeans.
2. **Màn hình bếp:** chuột phải `kos/kitchen/MasterchefApp.java` → **Run File**.
   Chạy trên máy khác thì thêm IP máy chủ: chuột phải dự án → Properties → Run → Arguments, nhập `192.168.1.10`.
3. **Gọi món:** mở `/order?device=IPAD1` trên iPad (điện thoại thì `?device=PHONE1`), chọn bàn, thiết bị rung, món,
   bấm **Gửi bếp** → màn hình bếp nhận ORDER_NEW; khung **Đơn đã gửi** trên iPad hiện trạng thái từng món.
   Mất Wi-Fi lúc gửi thì nút đổi thành **Gửi lại** (gửi lại đúng đơn cũ, máy chủ không tạo đơn trùng).
4. **Thiết bị rung:** mở địa chỉ `/pager?id=1` trên iPhone, bấm **Kích hoạt** rồi **Thử chuông**.
   Tắt chế độ im lặng của iPhone để nghe chuông.

Nếu chữ tiếng Việt trong Output bị lỗi dấu: chuột phải dự án → Properties → Run → VM Options, nhập
`-Dstdout.encoding=UTF-8`.

## Quầy quản lý (cửa sổ của máy chủ)

| Thẻ | Dùng để |
| --- | --- |
| Đơn hàng | Xem mọi món của các đơn chưa hoàn tất (chờ nhận làm / đang làm + thời gian còn lại / chờ lấy). **Hủy món...** cho món bếp chưa nhận làm. Bảng **Lệnh rung đang mở**: món đã xong chờ phục vụ lấy; **Xác nhận đã lấy món** khi không có thiết bị rung nào phản hồi. Dòng đỏ = thiết bị rung không phản hồi hoặc chờ lấy quá 60 giây. |
| Thực đơn | Thêm, sửa (bấm một dòng để nạp vào ô nhập), xóa món, **Tạm hết / Có lại**. Thay đổi được đẩy ngay tới iPad và màn hình bếp. Không xóa được món còn trong đơn chưa hoàn tất. |
| Thiết bị | Màn hình bếp, trang gọi món, thiết bị rung: tên, IP, lần cuối nhận tin, đang kết nối / mất kết nối. |
| Log | Mọi sự kiện của máy chủ. Dòng **CẢNH BÁO** còn hiện thành băng đỏ ở đầu cửa sổ kèm tiếng bíp (bấm vào băng để tắt). |

## Kiểm thử

Kịch bản TC01 - TC22: `docs/TEST_CASES.md`. Chương trình thử (chạy từ thư mục dự án, không cần NetBeans):

```
java test-data/TestClient.java 192.168.1.10 dup        gửi 2 lần cùng req_id -> một đơn
java test-data/TestClient.java 192.168.1.10 bad-http   đơn sai -> ERROR
java test-data/TestClient.java 192.168.1.10 bad-tcp    dòng rác vào cổng 6000 -> ERROR, máy chủ vẫn chạy
java test-data/TestClient.java 192.168.1.10 race       hai lệnh nhận làm cùng một món (tắt Masterchef Display thật trước)
```

## Cấu trúc

```
src/main/java/kos/common/   Giao thức và dữ liệu dùng chung (Protocol, Message, Json, Order, OrderItem, MenuItem, ItemStatus)
src/main/java/kos/server/   Máy chủ (ServerApp, ClientHandler, ClientRegistry, OrderStore, MenuStore, WebGateway, PagerDispatcher...)
src/main/java/kos/server/ui/ Giao diện quầy quản lý (AdminWindow, OrderPanel, MenuPanel, DevicePanel, LogPanel)
src/main/java/kos/kitchen/  Masterchef Display (MasterchefApp, ServerConnection)
src/main/resources/         menu.json mẫu, web/order.html, web/pager.html
test-data/                  menu-demo.json (thời gian chuẩn bị 1 - 2 phút), orders-sample.json, dữ liệu sai, TestClient.java
docs/PROTOCOL.md            Đặc tả giao thức
docs/TEST_CASES.md          Kịch bản kiểm thử TC01 - TC22
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
| Cường | Đã xong: giao diện quầy quản lý (thẻ Đơn hàng, Thực đơn, Thiết bị, Log), trang gọi món (trạng thái đơn, gửi lại cùng req_id), kịch bản kiểm thử, dữ liệu thử, TestClient. Còn: chạy TC01 - TC22 trên thiết bị thật, điền cột kết quả | `kos/server/ui/*`, `web/order.html`, `docs/TEST_CASES.md`, `test-data/*` |
| Khoa | Masterchef Display (Swing): theo đơn, gộp theo món, đồng hồ, nhận làm, gia hạn, xong sớm, báo hết món; làm đẹp trang thiết bị rung | `MasterchefApp`, `ServerConnection`, `web/pager.html` |

Quy tắc: không gõ tên lệnh trực tiếp, luôn dùng hằng số trong `Protocol.java`. Muốn đổi giao thức thì báo Nam.
Làm trên nhánh riêng (`ten/viec-dang-lam`), gộp vào `main` bằng pull request.
