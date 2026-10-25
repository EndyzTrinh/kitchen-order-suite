package kos.common;

/**
 * Hằng số dùng chung của giao thức: cổng, thời gian, tên lệnh.
 * Mọi thành viên chỉ dùng các hằng số ở đây, không gõ chuỗi lệnh trực tiếp trong mã.
 * Muốn đổi giao thức thì báo nhóm trưởng sửa file này trước.
 */
public final class Protocol {

    private Protocol() {
    }

    // ---- Cổng ----
    /** Cổng TCP cho Masterchef Display. */
    public static final int TCP_PORT = 6000;
    /** Cổng HTTP cho trang gọi món và trang thiết bị rung. */
    public static final int HTTP_PORT = 8081;

    // ---- Thời gian (mili giây) ----
    /** Chu kỳ gửi PING / heartbeat. */
    public static final int PING_INTERVAL_MS = 5_000;
    /** Không nhận được gì trong khoảng này thì coi là mất kết nối. */
    public static final int CONNECTION_TIMEOUT_MS = 15_000;
    /** Máy khách thử kết nối lại sau khoảng này. */
    public static final int RECONNECT_DELAY_MS = 3_000;
    /** Thời gian chờ kết nối TCP. */
    public static final int CONNECT_TIMEOUT_MS = 3_000;
    /** Còn bấy nhiêu thời gian thì màn hình bếp nhấp nháy cảnh báo. */
    public static final int WARN_BEFORE_DEADLINE_MS = 30_000;
    /** Thiết bị rung phải gửi PAGER_RECEIVED trong khoảng này. */
    public static final int PAGER_RECEIVED_TIMEOUT_MS = 5_000;
    /** Quá khoảng này chưa ai bấm "Đã lấy món" thì rung lại. */
    public static final int PICKUP_REMIND_MS = 60_000;

    // ---- Giới hạn ----
    /** Tổng số phút gia hạn tối đa cho một món. */
    public static final int MAX_EXTEND_MINUTES = 30;
    /** Số món ưu tiên tối đa đang chờ làm của một bàn (F14). */
    public static final int MAX_PRIORITY_PER_TABLE = 2;
    /** Hàng đợi ưu tiên dài từ mức này thì cảnh báo (dấu hiệu lạm dụng). */
    public static final int PRIORITY_ALERT_COUNT = 6;
    /** Món chờ nhận làm quá lâu: tô vàng, rồi tô đỏ trên màn hình bếp. */
    public static final int PENDING_WARN_MS = 3 * 60_000;
    public static final int PENDING_LATE_MS = 6 * 60_000;
    /** Độ dài tối đa của ghi chú món. */
    public static final int MAX_NOTE_LENGTH = 100;

    // ---- Chế độ màn hình bếp ----
    public static final String MODE_MASTERCHEF = "MASTERCHEF";
    public static final String MODE_VIEW = "VIEW";

    // ---- Lệnh: màn hình bếp <-> máy chủ (TCP) ----
    public static final String HELLO = "HELLO";
    public static final String WELCOME = "WELCOME";
    public static final String SNAPSHOT = "SNAPSHOT";
    public static final String START_ITEMS = "START_ITEMS";
    public static final String EXTEND_ITEMS = "EXTEND_ITEMS";
    public static final String FINISH_ITEMS = "FINISH_ITEMS";
    public static final String SOLD_OUT = "SOLD_OUT";

    // ---- Lệnh: trang gọi món / quầy -> máy chủ: xin ưu tiên (F14) ----
    public static final String PRIORITY_ITEMS = "PRIORITY_ITEMS";
    public static final String PING = "PING";
    public static final String PONG = "PONG";

    // ---- Lệnh: trang gọi món <-> máy chủ (HTTP) ----
    public static final String NEW_ORDER = "NEW_ORDER";
    public static final String ORDER_ACK = "ORDER_ACK";

    // ---- Lệnh máy chủ phát tới các thiết bị ----
    public static final String ORDER_NEW = "ORDER_NEW";
    public static final String ITEM_UPDATE = "ITEM_UPDATE";
    public static final String MENU_UPDATE = "MENU_UPDATE";
    public static final String ERROR = "ERROR";

    // ---- Lệnh: thiết bị rung <-> máy chủ (HTTP + SSE) ----
    public static final String PAGER_CALL = "PAGER_CALL";
    public static final String PAGER_RECEIVED = "PAGER_RECEIVED";
    public static final String PICKED_UP = "PICKED_UP";
    public static final String PAGER_CLOSE = "PAGER_CLOSE";
}
