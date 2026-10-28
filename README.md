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
   Cửa sổ "Kitchen Order Suite - Máy chủ" mở ra. **Lần đầu** (chưa có tài khoản) cửa sổ yêu cầu tạo tài khoản
   **Quản lý**; sau đó đăng nhập. Trang chủ có các ô: Quầy quản lý, Màn hình bếp, Gọi món, Thiết bị rung,
   Thực đơn, Thống kê, Tài khoản, Tắt máy chủ. Ô Gọi món / Thiết bị rung ghi địa chỉ để mở trên iPad / iPhone,
   ví dụ `192.168.1.10:8081/order`.
   Lần đầu Windows hỏi tường lửa: chọn **Cho phép**, tích **mạng riêng (Private)**.
   **Tắt máy chủ bằng ô / nút "Tắt máy chủ"** (chỉ Quản lý), hoặc gõ `exit` trong Output; không dùng nút ô vuông đỏ của NetBeans.
2. **Màn hình bếp trên máy khác:** chuột phải `kos/kitchen/MasterchefApp.java` → **Run File**. Nhập IP máy chủ
   (cùng máy thì để `localhost`) → đăng nhập (hoặc **Đăng ký** rồi chờ quản lý duyệt) → ô **Màn hình bếp**.
   Bếp trưởng / Quản lý mở đủ quyền, Bếp phụ chỉ xem. Trên chính máy chủ thì mở từ ô Màn hình bếp, không cần chạy riêng.
3. **Gọi món:** mở địa chỉ `/order` trên iPad / iPhone. Nhập tên thiết bị và số thiết bị rung một lần (trang tự nhớ).
4. **Thiết bị rung:** mở địa chỉ `/pager?id=1` trên iPhone, bấm **Kích hoạt**.
   Tắt chế độ im lặng, đặt Tự động khóa = Không bao giờ khi demo.

Chạy máy chủ không có cửa sổ (chỉ Output): thêm tham số `--console` (chuột phải dự án → Properties → Run → Arguments).
Khi đó tạo tài khoản Quản lý đầu tiên bằng lệnh `taoql <tên đăng nhập> <mật khẩu> <họ tên>` trong Output.
Bản console của màn hình bếp để thử giao thức: `kos/kitchen/MasterchefConsole.java` (tham số: IP, tên đăng nhập, mật khẩu).

Nếu chữ tiếng Việt trong Output bị lỗi dấu: chuột phải dự án → Properties → Run → VM Options, nhập
`-Dstdout.encoding=UTF-8`.

## Cấu trúc

```
src/main/java/kos/common/   Giao thức và dữ liệu dùng chung (Protocol, Message, Json, Order, OrderItem, MenuItem, ItemStatus, Beeper)
                            và giao diện phẳng tự vẽ cho các cửa sổ Swing (Theme)
src/main/java/kos/server/   Máy chủ (ServerApp, ClientHandler, OrderStore, MenuStore, KitchenController, KitchenTimer,
                            PagerDispatcher, WebGateway, WebHub, DeviceRegistry, DataFile, HistoryStore,
                            AccountStore, LocalAuth) và quầy quản lý (ManagerWindow, StatsPanel, AccountsPanel)
src/main/java/kos/kitchen/  Masterchef Display (MasterchefApp), bản console (MasterchefConsole), kết nối TCP (ServerConnection)
src/main/java/kos/ui/       Giao diện hệ thống chung: đăng nhập / đăng ký / trang chủ (HomeWindow, Tiles), đăng nhập qua TCP (RemoteAuth)
src/main/resources/         menu.json mẫu, web/order.html, web/pager.html
test-data/menu-demo.json    Thực đơn có thời gian chuẩn bị 1 - 2 phút để demo
docs/PROTOCOL.md            Đặc tả giao thức
```

## Thử nhanh với thời gian chuẩn bị 1 - 2 phút

Tạo thư mục `data` trong thư mục dự án, chép `test-data/menu-demo.json` vào đó và đổi tên thành `menu.json`.
Máy chủ tự lưu `data/orders.json`; xóa file này nếu muốn bắt đầu lại từ đầu.
Lịch sử thời gian làm món ghi vào `data/lich-su.csv` (giữ lại để thống kê; xóa nếu muốn làm sạch số liệu).
Thư mục `data` không đưa lên GitHub (đã có trong `.gitignore`).

## Thao tác trên màn hình bếp

| Việc | Cách làm |
| --- | --- |
| Chọn tên người làm | Ô **Người làm** góc trên bên phải: chọn tên hoặc gõ tên mới rồi Enter (máy tự nhớ). Chưa có tên thì không nhận làm được |
| Nhận làm món ưu tiên tiếp theo | Nút cam trên dải ưu tiên, hoặc phím **Space** |
| Nhận làm món bất kỳ | Chọn dòng (giữ Ctrl để chọn nhiều) → **Nhận làm**. Chế độ "Gộp theo món": chọn một dòng là nhận làm mọi phần đang chờ của món đó |
| Gia hạn | Chọn món đang làm → **+1 / +3 / +5 phút** → chọn lý do (tối đa 30 phút mỗi món) |
| Xong sớm | Chọn món đang làm → **Xong sớm** (thiết bị rung kêu ngay) |
| Chưa xong | Món đã hết giờ (Xong - chờ lấy) nhưng thực ra chưa xong → **Chưa xong** → chọn thêm 1 / 3 / 5 phút và lý do. Món về Đang làm, lệnh rung của món đó bị thu hồi |
| Báo hết món | Chọn món → **Báo hết món** → nhập lý do. Các phần chưa làm bị hủy, trang gọi món làm mờ món đó |

Màu dòng: cam = ưu tiên, xanh dương = đang làm, đỏ nhấp nháy = còn dưới 30 giây, xanh lá = xong chờ lấy,
vàng / đỏ = chờ nhận làm quá 3 / 6 phút.

## Món ưu tiên (F14)

Phục vụ đánh dấu **★ Ưu tiên** khi gọi món, hoặc bấm **★ Đẩy ưu tiên** trong thẻ "Đơn đã gửi" (quầy quản lý cũng đẩy được).
Chỉ món chưa nhận làm; mỗi bàn tối đa 2 món ưu tiên cùng lúc. Bếp làm theo thứ tự xin: bàn xin trước làm trước (#1, #2...).
Hàng đợi từ 6 món trở lên thì màn hình bếp và Log của quầy cảnh báo.

## Thanh thời gian và thống kê (F15)

Cột **Tiến độ** trên màn hình bếp có hai thanh:

- Thanh trên (xám): thời gian chuẩn của món trong thực đơn, không đổi.
- Thanh dưới: thời gian thực tế. Đang làm thì khung là tổng thời gian dự kiến (chuẩn + gia hạn), phần tô là thời gian đã trôi qua;
  xong rồi thì phần tô là thời gian làm thật. Gia hạn / Chưa xong làm thanh dài ra, Xong sớm làm thanh ngắn lại.
  Phần vượt thời gian chuẩn tô đỏ.

Mỗi món được phục vụ bấm "Đã lấy món" thì máy chủ ghi một dòng vào `data/lich-su.csv` (mở được bằng Excel):
ngày, giờ nhận làm, bàn, món, số phần, người làm, thời gian chuẩn, thực tế, chênh lệch, số lần gia hạn và lý do,
số lần Chưa xong, cách kết thúc (Hết giờ / Xong sớm), thời gian món chờ phục vụ lấy.

Thẻ **Thống kê** của quầy quản lý: chọn khoảng ngày (hoặc Hôm nay / 7 ngày / 30 ngày) và cách nhóm
(theo món, theo khung giờ, theo người làm). Bảng cho số món, thời gian chuẩn / thực tế trung bình, % trễ, số lần gia hạn,
lý do hay gặp, thời gian chờ lấy; biểu đồ so chuẩn với thực tế. **Xuất CSV...** lưu các dòng đã lọc ra file riêng.
"Trễ" luôn so với thời gian chuẩn, kể cả khi đã gia hạn, để gia hạn không che được việc làm chậm.

## Giao diện

- Cửa sổ Swing (bếp, quầy quản lý) dùng giao diện phẳng tự vẽ trong `kos/common/Theme.java`, không cần thư viện ngoài:
  nút bo góc, thẻ gạch chân, trạng thái dạng viên màu, chữ to.
- Trang gọi món: thực đơn dạng thẻ, ô tìm món (gõ không dấu cũng được), nút − / + chọn số bàn, thanh tiến độ cho món đang làm.
- Trang thiết bị rung: nền tối, số thiết bị lớn, thẻ món cần lấy và nút "Đã lấy món" to.

## Đăng nhập, đăng ký, phân quyền (F16)

| Vai trò | Được dùng |
| --- | --- |
| Quản lý | Mọi ô: quầy quản lý, thực đơn, thống kê, tài khoản, màn hình bếp (đủ quyền), gọi món, thiết bị rung, tắt máy chủ |
| Bếp trưởng | Màn hình bếp đủ quyền (nhận làm, gia hạn, xong sớm, chưa xong, báo hết món) |
| Bếp phụ | Màn hình bếp chỉ xem |
| Phục vụ | Gọi món, thiết bị rung |

- Ô không được dùng vẫn hiện nhưng bị khóa, ghi rõ cần vai trò nào. Máy chủ cũng tự kiểm tra lại quyền:
  màn hình bếp gửi HELLO kèm mã phiên, Bếp phụ xin chế độ đủ quyền hay Phục vụ mở màn hình bếp đều bị từ chối.
- **Đăng ký:** nhân viên tự đăng ký, chọn vai trò Bếp trưởng / Bếp phụ / Phục vụ. Tài khoản ở trạng thái **Chờ duyệt**;
  quản lý vào ô **Tài khoản** để Duyệt (có thể đổi vai trò khi duyệt) hoặc Từ chối. Không tự xin được vai trò Quản lý.
- **Thẻ Tài khoản:** Duyệt, Đổi vai trò, Khóa / Mở khóa, Đặt lại mật khẩu, Xóa. Khóa, đổi vai trò hoặc đặt lại mật khẩu
  thì người đó bị đăng xuất ngay, màn hình bếp đang mở bằng tài khoản đó bị ngắt.
  Không tự khóa / xóa chính mình; hệ thống luôn còn ít nhất một Quản lý.
- **Bảo mật:** mật khẩu ít nhất 6 ký tự, lưu dạng băm PBKDF2 có muối trong `data/users.json` (không lưu mật khẩu thật).
  Sai mật khẩu 5 lần liên tiếp thì khóa tạm 60 giây. Phiên đăng nhập lưu ở `data/sessions.json`,
  khởi động lại máy chủ không bắt đăng nhập lại; 12 giờ không dùng thì hết hạn.
- Người làm (F15) mặc định là người đang đăng nhập màn hình bếp.
- Quên mật khẩu Quản lý duy nhất: tắt máy chủ, xóa `data/users.json` và `data/sessions.json`, mở lại để tạo Quản lý mới
  (các tài khoản khác phải đăng ký lại).
- Trang gọi món và thiết bị rung trên iPad / iPhone vẫn dùng như cũ, không cần đăng nhập.
