package kos.ui;

import kos.common.Role;
import kos.common.UserInfo;

/**
 * Cách giao diện hệ thống đăng nhập / đăng ký. Hai cách làm:
 * trên máy chủ gọi thẳng kho tài khoản (kos.server.LocalAuth),
 * trên máy khác gửi lệnh LOGIN / REGISTER qua TCP (RemoteAuth).
 */
public interface AuthApi {

    /** Phiên đăng nhập: mã phiên và thông tin tài khoản. */
    record Session(String token, UserInfo user) { }

    /** Lỗi hiển thị thẳng cho người dùng. */
    class AuthException extends Exception {
        public AuthException(String message) {
            super(message);
        }
    }

    Session login(String username, String password) throws AuthException;

    /** @return thông báo cho người đăng ký */
    String register(String username, String fullName, String password, Role role) throws AuthException;

    void logout(String token);

    void changePassword(String token, String oldPassword, String newPassword) throws AuthException;

    /** Hệ thống chưa có tài khoản nào: hiện màn hình tạo Quản lý đầu tiên (chỉ trên máy chủ). */
    default boolean needsFirstManager() {
        return false;
    }

    /** Phiên còn hiệu lực không (máy chủ kiểm tra mỗi vài giây để đăng xuất người bị khóa). */
    default boolean isValid(String token) {
        return true;
    }

    /** Ví dụ "Máy chủ: 192.168.1.5". */
    String serverLabel();

    /** Địa chỉ IP / tên máy chủ, dùng để mở trang web và màn hình bếp. */
    String host();
}
