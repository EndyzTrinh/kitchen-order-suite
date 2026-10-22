# Kịch bản kiểm thử - Kitchen Order Suite

Theo mục 9 của đề cương v2 (TC01 - TC22). Mỗi TC gồm tiền điều kiện, các bước bấm cụ thể và kết quả nhìn thấy được.
Cột "Kết quả thực tế" và "Đạt" điền khi chạy thử trước buổi demo.

## Chuẩn bị chung

1. Chép `test-data/menu-demo.json` thành `data/menu.json` (thời gian chuẩn bị 1 - 2 phút). Xóa `data/orders.json` để bắt đầu sạch.
2. Laptop A chạy **ServerApp** → cửa sổ **Quầy quản lý** mở ra; thanh dưới cùng ghi địa chỉ, ví dụ `http://192.168.1.10:8081/order`.
3. Laptop B chạy **MasterchefApp** chế độ MASTERCHEF (nhập IP laptop A). Trong lúc chưa có giao diện của Khoa, dùng lệnh console:
   `nhan <đơn> <món>`, `giahan <đơn> <món> <phút>`, `xong <đơn> <món>`, `het <mã món> <lý do>`.
4. iPad mở `http://<IP>:8081/order?device=IPAD1`. iPhone 1 mở `/pager?id=1`, iPhone 2 mở `/pager?id=2`, cả hai bấm **Kích hoạt**,
   tắt chế độ im lặng, đặt Tự động khóa = Không bao giờ.
5. Wireshark trên laptop A, bộ lọc `tcp.port == 6000 || tcp.port == 8081`.
6. Chương trình thử: `java test-data/TestClient.java <IP> <kịch bản>` chạy từ thư mục dự án.

Ký hiệu: **Quầy** = cửa sổ Quầy quản lý trên laptop A, **Bếp** = Masterchef Display.

## Bảng kịch bản

| Mã | Chức năng | Tiền điều kiện | Các bước | Kết quả mong đợi | Kết quả thực tế | Đạt |
| --- | --- | --- | --- | --- | --- | --- |
| TC01 | F01, F11 | Chuẩn bị chung bước 1 - 4, thêm một Masterchef Display chế độ VIEW | 1. Mở Quầy → thẻ **Thiết bị**. | Đủ 5 dòng: Màn hình bếp (MASTERCHEF), Màn hình bếp (VIEW), Trang gọi món IPAD1, Thiết bị rung Số 1, Số 2; đúng IP; cột Trạng thái "Đang kết nối" màu xanh. Thẻ **Log** có "Cổng HTTP đang lắng nghe 8081", "Cổng TCP đang lắng nghe 6000". | | |
| TC02 | F02, F03 | TC01 | 1. iPad: nhập Bàn 5, Thiết bị rung 1. 2. Bấm **+** ở Phở bò tái 2 lần, gõ ghi chú "ít cay". 3. Bấm **Gửi bếp**. | iPad hiện "Đã gửi - đơn số N" (chữ xanh); khung "Đơn đã gửi" có Đơn N - bàn 5, "Chờ nhận làm". Bếp nhận ORDER_NEW trong 1 giây kèm chuông. Quầy thẻ **Đơn hàng** có dòng Phở bò tái x2, ghi chú "ít cay". | | |
| TC03 | F04 | TC02 | 1. iPad gọi thêm Phở bò tái cho bàn 2 (x1) và bàn 9 (x2). 2. Bếp chuyển sang chế độ gộp theo món. | Bếp hiện "Phở bò tái - 5 phần - bàn 2, 5, 9"; tổng khớp chế độ theo đơn và khớp thẻ Đơn hàng của Quầy. | | |
| TC04 | F05 | TC03 | 1. Bếp nhận làm cả nhóm Phở bò tái. | Quầy: 3 dòng Phở chuyển "Đang làm" (nền xanh nhạt), cột Thời gian "còn 01:00" giống nhau. iPad: "Đang làm · còn 01:00". Hai màn hình bếp cùng đồng hồ. | | |
| TC05 | F05 | TC04 | 1. Chờ đồng hồ còn 30 giây. | Dòng món trên Bếp nhấp nháy cảnh báo. | | |
| TC06 | F06 | Có món đang làm | 1. Bếp gia hạn +3 phút một món. | Quầy và iPad: thời gian còn lại cộng 3 phút, Quầy hiện "(+3')". Thẻ Log: "MC gia hạn +3 phút: ...". | | |
| TC07 | F06 | TC06 | 1. Màn hình VIEW gửi gia hạn. 2. Bếp gia hạn cùng món liên tục tới quá 30 phút. | Cả hai bị từ chối: "Không có quyền..." và "đã gia hạn ... phút, tối đa 30 phút". | | |
| TC08 | F07, F08 | Có món đang làm, đơn gán thiết bị rung 1 | 1. Để đồng hồ về 0. | Món chuyển "Chờ lấy" (Quầy nền vàng). iPhone 1 đổ chuông, nháy đỏ, hiện bàn và món. Quầy: bảng **Lệnh rung đang mở** có lệnh C-xxxx. | | |
| TC09 | F08 | TC08 | 1. iPhone 1 bấm **Đã lấy món**. | iPhone tắt chuông; lệnh biến khỏi bảng Lệnh rung; món chuyển "Đã lấy" và rời màn hình bếp; iPad hiện "Đã lấy". | | |
| TC10 | F07 | Món còn khoảng 2 phút | 1. Bếp bấm **Xong sớm**. | Món chuyển "Chờ lấy" ngay, thiết bị rung được kích hoạt. | | |
| TC11 | F07, F10 | Lẩu thái: 1 phần chờ nhận làm, 1 phần đang làm | 1. Bếp báo hết Lẩu thái (`het M01 Hết nước lẩu`). | Phần chờ nhận làm → "Đã hủy (Hết nước lẩu)"; phần đang làm giữ nguyên. Quầy thẻ **Thực đơn**: Lẩu thái "Tạm hết" (chữ đỏ). iPad làm mờ Lẩu thái, hiện "Thực đơn vừa được cập nhật". | | |
| TC12 | F09 | Đơn gán thiết bị 1, có món sắp hết giờ | 1. Khóa màn hình iPhone 1. 2. Để món hết giờ. | Khoảng 10 giây sau iPhone 2 đổ chuông thay. Quầy: băng đỏ "CẢNH BÁO: Thiết bị rung 1 không phản hồi, chuyển lệnh ...", tiếng bíp, dòng món và lệnh rung tô đỏ. | | |
| TC13 | F09 | TC08, không ai bấm Đã lấy món | 1. Chờ 60 giây. | Thiết bị rung lại; Quầy tô đỏ dòng món và lệnh rung (cột "Đã chờ" ≥ 01:00); Log "Chưa ai lấy món bàn ..., rung lại". | | |
| TC14 | F09 | Đóng cả hai trang thiết bị rung | 1. Để một món hết giờ. 2. Quầy chọn lệnh trong bảng Lệnh rung → **Xác nhận đã lấy món** → Yes. | Bước 1: băng đỏ "Không có thiết bị rung nào kết nối, quầy gọi phục vụ lấy món bàn ..." kèm tiếng bíp. Bước 2: món chuyển "Đã lấy", lệnh biến mất, Log "Quản lý xác nhận đã lấy món". | | |
| TC15 | F10 | Quầy thẻ Thực đơn | 1. Chọn Phở bò tái, sửa Chuẩn bị = 7 → **Lưu sửa**. 2. Chọn Mực chiên giòn → **Tạm hết / Có lại**. 3. iPad thử gọi Mực chiên giòn. | iPad cập nhật ngay không cần tải lại (7 phút, Mực mờ). Đơn Phở gọi sau khi sửa dùng 7 phút (đơn đã gọi trước giữ thời gian cũ). Nút + của Mực không bấm được; nếu đơn có Mực gửi lên thì máy chủ trả "Món đang tạm hết". | | |
| TC16 | F10 | Một món chờ nhận làm, một món đang làm | 1. Quầy chọn món chờ → **Hủy món...** → lý do "Khách hủy" → OK. 2. Chọn món đang làm. | Bước 1: món "Đã hủy (Khách hủy)", rời màn hình bếp, iPad cập nhật; Log "Quản lý hủy ...". Bước 2: nút Hủy món bị mờ (máy chủ cũng từ chối "bếp đã nhận làm, không hủy được"). | | |
| TC17 | F05 | Tắt Masterchef Display thật | 1. `java test-data/TestClient.java <IP> race` | In "ĐẠT: một lệnh được nhận làm, lệnh còn lại bị từ chối". | | |
| TC18 | F11 | Món đang chạy đồng hồ | 1. Tắt Wi-Fi laptop bếp 20 giây. 2. Bật lại. | Bước 1: trong 15 giây Quầy thẻ Thiết bị mất dòng màn hình bếp, Log "Quá 15 giây không nhận được gì từ ...". Bước 2: bếp tự kết nối lại, đồng hồ đúng thời gian còn lại. | | |
| TC19 | F03, F11 | iPad đã chọn bàn và món | 1. Tắt Wi-Fi iPad, bấm **Gửi bếp**. 2. Bật Wi-Fi, bấm **Gửi lại**. 3. `java test-data/TestClient.java <IP> dup` | Bước 1: sau tối đa 5 giây "Chưa gửi được...", nút đổi thành **Gửi lại** (cam), đơn nháp còn nguyên, bấm +/- bị chặn. Bước 2: "Đã gửi - đơn số N", chỉ một đơn trên Quầy. Bước 3: "ĐẠT: hai lần gửi cùng req_id nhận cùng order_id". | | |
| TC20 | F12 | Có món đang chạy đồng hồ (hoặc chép `test-data/orders-sample.json` thành `data/orders.json`) | 1. Đóng cửa sổ Quầy → Yes (tắt máy chủ). 2. Chờ 1 phút. 3. Chạy lại ServerApp. | Log: "Đã khôi phục N đơn chưa hoàn tất", "Khôi phục: x món đang làm, y món chờ lấy". Món chưa hết giờ tiếp tục đếm đúng; món quá hạn chuyển "Chờ lấy" và thiết bị rung kêu ngay. | | |
| TC21 | F13 | Máy chủ đang chạy | 1. `java test-data/TestClient.java <IP> bad-tcp` 2. `java test-data/TestClient.java <IP> bad-http` | Bước 1: mỗi dòng rác nhận ERROR; lệnh trước HELLO nhận "Phải gửi HELLO trước" rồi bị đóng; dòng cuối "Kiểm tra máy chủ còn sống" in thực đơn. Bước 2: "6/6 đơn sai bị từ chối". Log Quầy ghi "Dòng không hợp lệ ...". | | |
| TC22 | Toàn hệ thống | Wireshark đang bắt | 1. Chạy một vòng: gọi món → nhận làm → hết giờ → rung → đã lấy. | Thấy bắt tay 3 bước tới cổng 6000, các dòng JSON (HELLO, ORDER_NEW, START_ITEMS, ITEM_UPDATE), PING / PONG; phiên HTTP POST /api/order và luồng `text/event-stream` (PAGER_CALL) trên cổng 8081. | | |

## Dữ liệu thử nghiệm (`test-data/`)

| File | Dùng cho | Nội dung |
| --- | --- | --- |
| `menu-demo.json` | Mọi TC có hết giờ | Thực đơn có thời gian chuẩn bị 1 - 2 phút |
| `orders-sample.json` | TC03, TC20 | 3 đơn bàn 2, 5, 9 cùng gọi Phở bò tái; một món đang làm đã quá hạn để thử khôi phục |
| `bad-lines.txt` | TC21 | Dòng rác gửi vào cổng 6000 |
| `bad-orders.jsonl` | TC21 | Mỗi dòng một NEW_ORDER sai: thiếu bàn, số lượng 0, món không có, thiếu thiết bị rung, đơn rỗng, không phải JSON |
| `TestClient.java` | TC17, TC19, TC21 | Chương trình thử dùng URL / HttpURLConnection và Socket |
