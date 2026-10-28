package kos.common;

/**
 * Thông tin công khai của một tài khoản (không có mật khẩu), gửi qua mạng trong LOGIN_OK
 * và hiển thị ở thẻ Tài khoản.
 */
public class UserInfo {
    public static final String PENDING = "CHO_DUYET";
    public static final String ACTIVE = "HOAT_DONG";
    public static final String LOCKED = "KHOA";

    public String username;
    public String fullName;
    public Role role;
    public String status;
    public Long createdAt;
    public Long lastLogin;

    public static String statusLabel(String s) {
        if (PENDING.equals(s)) {
            return "Chờ duyệt";
        }
        if (LOCKED.equals(s)) {
            return "Đã khóa";
        }
        return "Hoạt động";
    }
}
