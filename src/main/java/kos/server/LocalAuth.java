package kos.server;

import kos.common.Role;
import kos.ui.AuthApi;

/** Đăng nhập trên chính máy chủ: gọi thẳng kho tài khoản, không qua mạng (F16). */
public class LocalAuth implements AuthApi {

    private final AccountStore store;

    public LocalAuth(AccountStore store) {
        this.store = store;
    }

    @Override
    public Session login(String username, String password) throws AuthException {
        try {
            AccountStore.Login l = store.login(username, password, "máy chủ");
            return new Session(l.token(), l.user());
        } catch (AccountStore.AuthError e) {
            throw new AuthException(e.getMessage());
        }
    }

    @Override
    public String register(String username, String fullName, String password, Role role) throws AuthException {
        try {
            return store.register(username, fullName, password, role, "máy chủ", true);
        } catch (AccountStore.AuthError e) {
            throw new AuthException(e.getMessage());
        }
    }

    @Override
    public void logout(String token) {
        store.logout(token);
    }

    @Override
    public void changePassword(String token, String oldPassword, String newPassword) throws AuthException {
        try {
            store.changePassword(token, oldPassword, newPassword);
        } catch (AccountStore.AuthError e) {
            throw new AuthException(e.getMessage());
        }
    }

    @Override
    public boolean needsFirstManager() {
        return store.isEmpty();
    }

    @Override
    public boolean isValid(String token) {
        return store.session(token) != null;
    }

    @Override
    public String serverLabel() {
        return "Đang chạy trên máy chủ";
    }

    @Override
    public String host() {
        return "localhost";
    }
}
