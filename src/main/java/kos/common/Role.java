package kos.common;

/**
 * Vai trò tài khoản (F16). Mỗi vai trò mở được một nhóm chức năng khác nhau trên giao diện hệ thống.
 */
public enum Role {
    QUAN_LY("Quản lý", "Toàn quyền: quầy quản lý, thực đơn, thống kê, duyệt tài khoản, màn hình bếp"),
    BEP_TRUONG("Bếp trưởng", "Màn hình bếp đủ quyền: nhận làm, gia hạn, xong sớm, báo hết món"),
    BEP_PHU("Bếp phụ", "Màn hình bếp chế độ chỉ xem"),
    PHUC_VU("Phục vụ", "Trang gọi món và thiết bị rung");

    private final String label;
    private final String description;

    Role(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** Quầy quản lý, thực đơn, thống kê, tài khoản. */
    public boolean canManage() {
        return this == QUAN_LY;
    }

    /** Chế độ màn hình bếp được phép: MASTERCHEF, VIEW, hoặc null nếu không được mở. */
    public String kitchenMode() {
        return switch (this) {
            case QUAN_LY, BEP_TRUONG -> Protocol.MODE_MASTERCHEF;
            case BEP_PHU -> Protocol.MODE_VIEW;
            case PHUC_VU -> null;
        };
    }

    /** Trang gọi món, thiết bị rung. */
    public boolean canServe() {
        return this == QUAN_LY || this == PHUC_VU;
    }

    @Override
    public String toString() {
        return label;
    }
}
