package kos.server;

import kos.common.Json;
import kos.common.Protocol;
import kos.common.Role;
import kos.common.UserInfo;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tài khoản, đăng nhập và phân quyền (F16).
 * <ul>
 *   <li>data/users.json: tài khoản. Mật khẩu không lưu thẳng mà lưu muối (salt) ngẫu nhiên + giá trị băm
 *       PBKDF2-HmacSHA256, nên đọc được file cũng không biết mật khẩu.</li>
 *   <li>data/sessions.json: phiên đăng nhập. Đăng nhập đúng thì máy chủ cấp một mã phiên (token) ngẫu nhiên;
 *       màn hình bếp gửi token trong HELLO thay vì gửi lại mật khẩu. Phiên lưu ra file nên khởi động lại
 *       máy chủ không bắt bếp đăng nhập lại; 12 giờ không dùng thì hết hạn.</li>
 *   <li>Người tự đăng ký ở trạng thái Chờ duyệt; quản lý duyệt mới đăng nhập được.</li>
 *   <li>Sai mật khẩu 5 lần liên tiếp: khóa tạm tài khoản đó 60 giây.</li>
 *   <li>Khóa, đổi vai trò, đặt lại mật khẩu hoặc xóa tài khoản thì mọi phiên của tài khoản đó bị hủy ngay
 *       và màn hình bếp đang mở bằng tài khoản đó bị ngắt.</li>
 * </ul>
 * Mọi phương thức đều synchronized vì được gọi từ luồng mạng và luồng giao diện.
 */
public class AccountStore {

    public static final Path USERS = DataFile.DIR.resolve("users.json");
    public static final Path SESSIONS = DataFile.DIR.resolve("sessions.json");
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._]{3,20}");
    private static final int ITERATIONS = 65_536;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Một tài khoản như lưu trong users.json. */
    static final class Account {
        String username;
        String fullName;
        Role role;
        String status;
        String salt;
        String hash;
        Long createdAt;
        Long lastLogin;

        UserInfo info() {
            UserInfo u = new UserInfo();
            u.username = username;
            u.fullName = fullName;
            u.role = role;
            u.status = status;
            u.createdAt = createdAt;
            u.lastLogin = lastLogin;
            return u;
        }
    }

    /** Một phiên đăng nhập. */
    static final class Session {
        String token;
        String username;
        Long expiresAt;
    }

    private static final class UsersFile {
        List<Account> users = new ArrayList<>();
    }

    private static final class SessionsFile {
        List<Session> sessions = new ArrayList<>();
    }

    /** Kết quả đăng nhập thành công. */
    public record Login(String token, UserInfo user) { }

    /** Lỗi nghiệp vụ, thông điệp hiển thị thẳng cho người dùng. */
    public static class AuthError extends Exception {
        private final String code;

        public AuthError(String message) {
            this(message, null);
        }

        public AuthError(String message, String code) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final ServerContext ctx;
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Integer> fails = new HashMap<>();
    private final Map<String, Long> lockedUntil = new HashMap<>();
    private volatile int version;

    public AccountStore(ServerContext ctx) {
        this.ctx = ctx;
    }

    /** Tăng mỗi khi danh sách tài khoản đổi; thẻ Tài khoản dùng để biết khi nào vẽ lại. */
    public int version() {
        return version;
    }

    // ------------------------------------------------------------------ nạp / lưu

    public synchronized void load() {
        accounts.clear();
        sessions.clear();
        try {
            String json = DataFile.read(USERS);
            if (json != null) {
                UsersFile f = Json.fromJson(json, UsersFile.class);
                if (f != null && f.users != null) {
                    for (Account a : f.users) {
                        accounts.put(a.username, a);
                    }
                }
            }
            String sj = DataFile.read(SESSIONS);
            if (sj != null) {
                SessionsFile f = Json.fromJson(sj, SessionsFile.class);
                long now = System.currentTimeMillis();
                if (f != null && f.sessions != null) {
                    for (Session s : f.sessions) {
                        if (s.expiresAt != null && s.expiresAt > now && accounts.containsKey(s.username)) {
                            sessions.put(s.token, s);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            ctx.log("CẢNH BÁO: không đọc được users.json / sessions.json: " + e.getMessage());
        }
        version++;
        ctx.log("Đã nạp " + accounts.size() + " tài khoản" + (pendingCount() > 0 ? ", " + pendingCount() + " chờ duyệt" : ""));
        if (accounts.isEmpty()) {
            ctx.log("Chưa có tài khoản nào: mở cửa sổ máy chủ để tạo tài khoản Quản lý đầu tiên.");
        }
    }

    private void saveUsers() {
        version++;
        UsersFile f = new UsersFile();
        f.users = new ArrayList<>(accounts.values());
        try {
            DataFile.write(USERS, Json.GSON.toJson(f));
        } catch (IOException e) {
            ctx.log("CẢNH BÁO: không ghi được users.json: " + e.getMessage());
        }
    }

    private void saveSessions() {
        SessionsFile f = new SessionsFile();
        f.sessions = new ArrayList<>(sessions.values());
        try {
            DataFile.write(SESSIONS, Json.GSON.toJson(f));
        } catch (IOException e) {
            ctx.log("CẢNH BÁO: không ghi được sessions.json: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ mật khẩu

    private static String hash(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, 256);
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(h);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setPassword(Account a, String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        a.salt = Base64.getEncoder().encodeToString(salt);
        a.hash = hash(password, a.salt);
    }

    private static boolean matches(Account a, String password) {
        if (a.salt == null || a.hash == null || password == null) {
            return false;
        }
        // so sánh thời gian không đổi để không lộ thông tin qua thời gian trả lời
        return MessageDigest.isEqual(hash(password, a.salt).getBytes(), a.hash.getBytes());
    }

    private static String newToken() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String norm(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    private static void checkPassword(String password) throws AuthError {
        if (password == null || password.length() < 6) {
            throw new AuthError("Mật khẩu phải có ít nhất 6 ký tự");
        }
    }

    // ------------------------------------------------------------------ đăng ký / đăng nhập

    public synchronized boolean isEmpty() {
        return accounts.isEmpty();
    }

    public synchronized int pendingCount() {
        int n = 0;
        for (Account a : accounts.values()) {
            n += UserInfo.PENDING.equals(a.status) ? 1 : 0;
        }
        return n;
    }

    /**
     * Đăng ký. Tài khoản đầu tiên của hệ thống (khi chưa có ai) là Quản lý và dùng được ngay;
     * các tài khoản sau ở trạng thái Chờ duyệt và không được tự xin vai trò Quản lý.
     *
     * @return thông báo cho người đăng ký
     */
    public synchronized String register(String username, String fullName, String password, Role role, String from)
            throws AuthError {
        return register(username, fullName, password, role, from, false);
    }

    /**
     * @param local true nếu gọi từ chính máy chủ (cửa sổ máy chủ hoặc lệnh console). Chỉ khi đó mới được tạo
     *              tài khoản Quản lý đầu tiên; đăng ký qua mạng lúc hệ thống chưa có ai sẽ bị từ chối.
     */
    public synchronized String register(String username, String fullName, String password, Role role, String from,
                                        boolean local) throws AuthError {
        if (accounts.isEmpty() && !local) {
            throw new AuthError("Hệ thống chưa có Quản lý. Tạo tài khoản Quản lý trên máy chủ trước.");
        }
        String u = norm(username);
        if (!USERNAME.matcher(u).matches()) {
            throw new AuthError("Tên đăng nhập 3 - 20 ký tự, chỉ gồm chữ không dấu, số, dấu chấm, gạch dưới");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new AuthError("Chưa nhập họ tên");
        }
        checkPassword(password);
        if (accounts.containsKey(u)) {
            throw new AuthError("Tên đăng nhập \"" + u + "\" đã có người dùng");
        }
        boolean first = accounts.isEmpty();
        if (!first && (role == null || role == Role.QUAN_LY)) {
            throw new AuthError("Chọn vai trò Bếp trưởng, Bếp phụ hoặc Phục vụ. Vai trò Quản lý do quản lý cấp.");
        }
        Account a = new Account();
        a.username = u;
        a.fullName = fullName.trim();
        a.role = first ? Role.QUAN_LY : role;
        a.status = first ? UserInfo.ACTIVE : UserInfo.PENDING;
        a.createdAt = System.currentTimeMillis();
        setPassword(a, password);
        accounts.put(u, a);
        saveUsers();
        if (first) {
            ctx.log("Đã tạo tài khoản Quản lý đầu tiên: " + u + " (" + a.fullName + ")");
            return "Đã tạo tài khoản Quản lý. Hãy đăng nhập.";
        }
        ctx.log("CẢNH BÁO: tài khoản mới chờ duyệt: " + u + " (" + a.fullName + ", xin vai trò " + a.role.label()
                + ") từ " + from + " - vào thẻ Tài khoản để duyệt");
        return "Đã gửi đăng ký. Chờ quản lý duyệt rồi đăng nhập.";
    }

    public synchronized Login login(String username, String password, String from) throws AuthError {
        String u = norm(username);
        long now = System.currentTimeMillis();
        Long until = lockedUntil.get(u);
        if (until != null && until > now) {
            throw new AuthError("Sai mật khẩu nhiều lần. Thử lại sau " + ((until - now) / 1000 + 1) + " giây");
        }
        Account a = accounts.get(u);
        if (a == null || !matches(a, password)) {
            int n = fails.merge(u, 1, Integer::sum);
            if (n >= Protocol.MAX_LOGIN_FAILS) {
                fails.remove(u);
                lockedUntil.put(u, now + Protocol.LOGIN_LOCK_MS);
                ctx.log("CẢNH BÁO: \"" + u + "\" sai mật khẩu " + n + " lần từ " + from + ", khóa tạm "
                        + Protocol.LOGIN_LOCK_MS / 1000 + " giây");
            } else {
                ctx.log("Đăng nhập sai: \"" + u + "\" từ " + from);
            }
            // không nói rõ sai tên hay sai mật khẩu
            throw new AuthError("Sai tên đăng nhập hoặc mật khẩu");
        }
        fails.remove(u);
        if (UserInfo.PENDING.equals(a.status)) {
            throw new AuthError("Tài khoản đang chờ quản lý duyệt");
        }
        if (UserInfo.LOCKED.equals(a.status)) {
            throw new AuthError("Tài khoản đã bị khóa. Liên hệ quản lý.");
        }
        Session s = new Session();
        s.token = newToken();
        s.username = u;
        s.expiresAt = now + Protocol.SESSION_TTL_MS;
        sessions.put(s.token, s);
        a.lastLogin = now;
        saveUsers();
        saveSessions();
        ctx.log("Đăng nhập: " + a.fullName + " (" + a.role.label() + ") từ " + from);
        return new Login(s.token, a.info());
    }

    /** Tài khoản của một phiên còn hiệu lực, hoặc null. Gia hạn phiên thêm 12 giờ mỗi lần dùng. */
    public synchronized UserInfo session(String token) {
        if (token == null) {
            return null;
        }
        Session s = sessions.get(token);
        long now = System.currentTimeMillis();
        if (s == null || s.expiresAt == null || s.expiresAt < now) {
            if (s != null) {
                sessions.remove(token);
                saveSessions();
            }
            return null;
        }
        Account a = accounts.get(s.username);
        if (a == null || !UserInfo.ACTIVE.equals(a.status)) {
            return null;
        }
        if (s.expiresAt - now < Protocol.SESSION_TTL_MS / 2) {
            s.expiresAt = now + Protocol.SESSION_TTL_MS;
            saveSessions();
        }
        return a.info();
    }

    public synchronized void logout(String token) {
        Session s = sessions.remove(token);
        if (s != null) {
            saveSessions();
            Account a = accounts.get(s.username);
            ctx.log("Đăng xuất: " + (a == null ? s.username : a.fullName));
        }
    }

    public synchronized void changePassword(String token, String oldPassword, String newPassword) throws AuthError {
        Session s = token == null ? null : sessions.get(token);
        Account a = s == null ? null : accounts.get(s.username);
        if (a == null) {
            throw new AuthError("Phiên đăng nhập đã hết hạn, đăng nhập lại", Protocol.ERR_AUTH);
        }
        if (!matches(a, oldPassword)) {
            throw new AuthError("Mật khẩu hiện tại không đúng");
        }
        checkPassword(newPassword);
        setPassword(a, newPassword);
        saveUsers();
        ctx.log(a.fullName + " đã đổi mật khẩu");
    }

    // ------------------------------------------------------------------ quản lý (thẻ Tài khoản)

    public synchronized List<UserInfo> list() {
        List<UserInfo> list = new ArrayList<>();
        for (Account a : accounts.values()) {
            list.add(a.info());
        }
        // chờ duyệt lên đầu, rồi theo vai trò, rồi theo tên
        list.sort(Comparator.comparingInt((UserInfo u) -> UserInfo.PENDING.equals(u.status) ? 0 : 1)
                .thenComparing(u -> u.role).thenComparing(u -> u.username));
        return list;
    }

    private Account get(String username) throws AuthError {
        Account a = accounts.get(username);
        if (a == null) {
            throw new AuthError("Không còn tài khoản " + username);
        }
        return a;
    }

    private int activeManagers(String except) {
        int n = 0;
        for (Account a : accounts.values()) {
            if (a.role == Role.QUAN_LY && UserInfo.ACTIVE.equals(a.status) && !a.username.equals(except)) {
                n++;
            }
        }
        return n;
    }

    private void guardSelf(String target, String by, String what) throws AuthError {
        if (target.equals(by)) {
            throw new AuthError("Không thể " + what + " chính tài khoản đang đăng nhập");
        }
    }

    /** Hủy mọi phiên của một tài khoản và ngắt màn hình bếp đang dùng tài khoản đó. */
    private void revoke(String username, String reason) {
        boolean changed = sessions.values().removeIf(s -> s.username.equals(username));
        if (changed) {
            saveSessions();
        }
        ctx.clients().kick(username, reason);
    }

    public synchronized void approve(String username, Role role, String by) throws AuthError {
        Account a = get(username);
        a.status = UserInfo.ACTIVE;
        if (role != null) {
            a.role = role;
        }
        saveUsers();
        ctx.log(by + " đã duyệt tài khoản " + username + " (" + a.role.label() + ")");
    }

    public synchronized void setRole(String username, Role role, String by) throws AuthError {
        Account a = get(username);
        guardSelf(username, by, "đổi vai trò của");
        if (a.role == Role.QUAN_LY && role != Role.QUAN_LY && activeManagers(username) == 0) {
            throw new AuthError("Phải còn ít nhất một Quản lý");
        }
        a.role = role;
        saveUsers();
        revoke(username, "Vai trò của bạn vừa được đổi thành " + role.label() + ". Đăng nhập lại.");
        ctx.log(by + " đổi vai trò " + username + " thành " + role.label());
    }

    public synchronized void setLocked(String username, boolean locked, String by) throws AuthError {
        Account a = get(username);
        guardSelf(username, by, locked ? "khóa" : "mở khóa");
        if (locked && a.role == Role.QUAN_LY && activeManagers(username) == 0) {
            throw new AuthError("Phải còn ít nhất một Quản lý");
        }
        a.status = locked ? UserInfo.LOCKED : UserInfo.ACTIVE;
        saveUsers();
        if (locked) {
            revoke(username, "Tài khoản của bạn đã bị khóa.");
        }
        ctx.log(by + (locked ? " đã khóa " : " đã mở khóa ") + username);
    }

    public synchronized void resetPassword(String username, String newPassword, String by) throws AuthError {
        Account a = get(username);
        checkPassword(newPassword);
        setPassword(a, newPassword);
        lockedUntil.remove(username);
        saveUsers();
        if (!username.equals(by)) {
            revoke(username, "Mật khẩu của bạn vừa được đặt lại. Đăng nhập lại.");
        }
        ctx.log(by + " đặt lại mật khẩu cho " + username);
    }

    public synchronized void delete(String username, String by) throws AuthError {
        Account a = get(username);
        guardSelf(username, by, "xóa");
        if (a.role == Role.QUAN_LY && UserInfo.ACTIVE.equals(a.status) && activeManagers(username) == 0) {
            throw new AuthError("Phải còn ít nhất một Quản lý");
        }
        accounts.remove(username);
        saveUsers();
        revoke(username, "Tài khoản của bạn đã bị xóa.");
        ctx.log(by + " đã xóa tài khoản " + username);
    }
}
