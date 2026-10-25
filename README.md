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

1. **Máy chủ kiêm quầy quản lý:** chuột phải `src/main/java/kos/server/ServerApp.java` → **Run File**.
   Cửa sổ "Quầy quản lý" mở ra; dòng trên cùng là địa chỉ để mở trên iPad / iPhone, ví dụ
   `gọi món http://192.168.1.10:8081/order | thiết bị rung http://192.168.1.10:8081/pager?id=1`.
   Lần đầu Windows hỏi tường lửa: chọn **Cho phép**, tích **mạng riêng (Private)**.
   **Tắt máy chủ bằng nút "Tắt máy chủ"** (hoặc gõ `exit` trong Output), không dùng nút ô vuông đỏ của NetBeans.
2. **Màn hình bếp:** chuột phải `kos/kitchen/MasterchefApp.java` → **Run File**. Cửa sổ hỏi IP máy chủ
   (cùng máy thì để `localhost`) và chế độ: Masterchef (đủ quyền) hoặc Chỉ xem.
3. **Gọi món:** mở địa chỉ `/order` trên iPad / iPhone. Nhập tên thiết bị và số thiết bị rung một lần (trang tự nhớ).
4. **Thiết bị rung:** mở địa chỉ `/pager?id=1` trên iPhone, bấm **Kích hoạt**.
   Tắt chế độ im lặng, đặt Tự động khóa = Không bao giờ khi demo.

Chạy máy chủ không có cửa sổ (chỉ Output): thêm tham số `--console` (chuột phải dự án → Properties → Run → Arguments).
Bản console của màn hình bếp để thử giao thức: `kos/kitchen/MasterchefConsole.java`.

Nếu chữ tiếng Việt trong Output bị lỗi dấu: chuột phải dự án → Properties → Run → VM Options, nhập
`-Dstdout.encoding=UTF-8`.

## Cấu trúc

```
src/main/java/kos/common/   Giao thức và dữ liệu dùng chung (Protocol, Message, Json, Order, OrderItem, MenuItem, ItemStatus, Beeper)
src/main/java/kos/server/   Máy chủ (ServerApp, ClientHandler, OrderStore, MenuStore, KitchenController, KitchenTimer,
                            PagerDispatcher, WebGateway, WebHub, DeviceRegistry, DataFile) và quầy quản lý (ManagerWindow)
src/main/java/kos/kitchen/  Masterchef Display (MasterchefApp), bản console (MasterchefConsole), kết nối TCP (ServerConnection)
src/main/resources/         menu.json mẫu, web/order.html, web/pager.html
test-data/menu-demo.json    Thực đơn có thời gian chuẩn bị 1 - 2 phút để demo
docs/PROTOCOL.md            Đặc tả giao thức
```

## Thử nhanh với thời gian chuẩn bị 1 - 2 phút

Tạo thư mục `data` trong thư mục dự án, chép `test-data/menu-demo.json` vào đó và đổi tên thành `menu.json`.
Máy chủ tự lưu `data/orders.json`; xóa file này nếu muốn bắt đầu lại từ đầu.
Thư mục `data` không đưa lên GitHub (đã có trong `.gitignore`).

## Thao tác trên màn hình bếp

| Việc | Cách làm |
| --- | --- |
| Nhận làm món ưu tiên tiếp theo | Nút cam trên dải ưu tiên, hoặc phím **Space** |
| Nhận làm món bất kỳ | Chọn dòng (giữ Ctrl để chọn nhiều) → **Nhận làm**. Chế độ "Gộp theo món": chọn một dòng là nhận làm mọi phần đang chờ của món đó |
| Gia hạn | Chọn món đang làm → **+1 / +3 / +5 phút** (tối đa 30 phút mỗi món) |
| Xong sớm | Chọn món đang làm → **Xong sớm** (thiết bị rung kêu ngay) |
| Báo hết món | Chọn món → **Báo hết món** → nhập lý do. Các phần chưa làm bị hủy, trang gọi món làm mờ món đó |

Màu dòng: cam = ưu tiên, xanh dương = đang làm, đỏ nhấp nháy = còn dưới 30 giây, xanh lá = xong chờ lấy,
vàng / đỏ = chờ nhận làm quá 3 / 6 phút.

## Món ưu tiên (F14)

Phục vụ đánh dấu **★ Ưu tiên** khi gọi món, hoặc bấm **★ Đẩy ưu tiên** trong thẻ "Đơn đã gửi" (quầy quản lý cũng đẩy được).
Chỉ món chưa nhận làm; mỗi bàn tối đa 2 món ưu tiên cùng lúc. Bếp làm theo thứ tự xin: bàn xin trước làm trước (#1, #2...).
Hàng đợi từ 6 món trở lên thì màn hình bếp và Log của quầy cảnh báo.
