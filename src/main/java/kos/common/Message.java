package kos.common;

import java.util.List;

/**
 * Một thông điệp của giao thức. Mọi lệnh dùng chung lớp này; lệnh nào chỉ điền các trường
 * nó cần, các trường còn lại để null và không xuất hiện trong JSON.
 * Tên trường trong Java viết camelCase, trong JSON tự đổi sang snake_case (reqId -> req_id).
 */
public class Message {
    /** Tên lệnh, lấy từ {@link Protocol}. Bắt buộc. */
    public String command;
    /** Mã yêu cầu do thiết bị tạo, ví dụ "IPAD1-0007". */
    public String reqId;

    // Định danh thiết bị
    public String name;
    public String mode;

    // Đơn và món
    public Integer orderId;
    public Integer itemNo;
    public Integer table;
    public Integer pagerId;
    public ItemStatus status;
    public Long startedAt;
    public Long deadline;
    public Integer extendedMinutes;
    public Boolean priority;
    public Long priorityAt;
    public List<OrderItem> items;
    public Order order;
    public List<Order> orders;

    // Thực đơn
    public List<MenuItem> menu;
    public String menuId;

    // Lệnh của masterchef
    public Integer minutes;

    // Thiết bị rung
    public String callId;

    // Khác
    public Long serverTime;
    public Boolean kitchenOnline;
    public String message;
    public String reason;
    public String by;

    public Message() {
    }

    public Message(String command) {
        this.command = command;
    }

    /** Tạo thông điệp ERROR trả lời một yêu cầu. */
    public static Message error(String reqId, String text) {
        Message m = new Message(Protocol.ERROR);
        m.reqId = reqId;
        m.message = text;
        return m;
    }

    @Override
    public String toString() {
        return Json.toLine(this);
    }
}
