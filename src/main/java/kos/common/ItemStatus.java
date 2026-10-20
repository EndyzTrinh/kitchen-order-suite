package kos.common;

/** Trạng thái của một món trong đơn (xem mục 5 của đề cương). */
public enum ItemStatus {
    /** Chờ masterchef nhận làm, chưa có đồng hồ. */
    PENDING,
    /** Đang làm, đồng hồ đếm ngược tới deadline. */
    COOKING,
    /** Hết giờ hoặc xong sớm, thiết bị rung đã được kích hoạt. */
    READY,
    /** Phục vụ đã lấy món. Trạng thái kết thúc. */
    SERVED,
    /** Đã hủy (quản lý hủy hoặc hết món). Trạng thái kết thúc. */
    CANCELLED;

    public boolean isFinished() {
        return this == SERVED || this == CANCELLED;
    }
}
