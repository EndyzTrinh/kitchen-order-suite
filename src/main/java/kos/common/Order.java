package kos.common;

import java.util.ArrayList;
import java.util.List;

/** Một đơn gọi món của một bàn. */
public class Order {
    public Integer orderId;
    public Integer table;
    /** Số thiết bị rung nhận báo khi món xong. */
    public Integer pagerId;
    /** Tên thiết bị đã gửi đơn, ví dụ "IPAD1". */
    public String waiter;
    /** Thời điểm tạo đơn, mili giây theo đồng hồ máy chủ. */
    public Long createdAt;
    public List<OrderItem> items = new ArrayList<>();

    /** Đơn hoàn tất khi mọi món đều SERVED hoặc CANCELLED. */
    public boolean isFinished() {
        for (OrderItem it : items) {
            if (it.status == null || !it.status.isFinished()) {
                return false;
            }
        }
        return true;
    }

    public OrderItem findItem(int itemNo) {
        for (OrderItem it : items) {
            if (it.itemNo != null && it.itemNo == itemNo) {
                return it;
            }
        }
        return null;
    }
}
