package kos.common;

/**
 * Một dòng món. Dùng chung cho ba trường hợp, trường nào không dùng thì để null
 * (Gson tự bỏ trường null khi chuyển sang JSON):
 * <ul>
 *   <li>Trong NEW_ORDER: menu_id, qty, note, priority (tùy chọn).</li>
 *   <li>Trong đơn đã lưu: thêm item_no, name, prep_minutes, status, started_at, deadline, extended_minutes.</li>
 *   <li>Trong lệnh của masterchef (START_ITEMS, EXTEND_ITEMS...): chỉ order_id, item_no.</li>
 * </ul>
 */
public class OrderItem {
    public Integer orderId;
    public Integer itemNo;
    public String menuId;
    public String name;
    public Integer qty;
    public String note;
    public Integer prepMinutes;
    public ItemStatus status;
    /** Thời điểm nhận làm, mili giây theo đồng hồ máy chủ. */
    public Long startedAt;
    /** Hạn giờ, mili giây theo đồng hồ máy chủ. */
    public Long deadline;
    /** Tổng số phút đã gia hạn. */
    public Integer extendedMinutes;
    /** Lý do khi hủy. */
    public String reason;
    /** true: khách xin làm trước (F14). Trong NEW_ORDER, phục vụ đặt true để ưu tiên ngay lúc gọi. */
    public Boolean priority;
    /** Thời điểm xin ưu tiên (mili giây, đồng hồ máy chủ); hàng đợi ưu tiên xếp theo trường này. */
    public Long priorityAt;

    public OrderItem() {
    }

    public boolean isPriority() {
        return Boolean.TRUE.equals(priority);
    }

    /** Tạo tham chiếu tới một món, dùng trong lệnh của masterchef. */
    public static OrderItem ref(int orderId, int itemNo) {
        OrderItem it = new OrderItem();
        it.orderId = orderId;
        it.itemNo = itemNo;
        return it;
    }
}
