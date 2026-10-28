package kos.server;

import com.google.gson.JsonParseException;
import kos.common.Json;
import kos.common.Message;
import kos.common.Protocol;
import kos.common.UserInfo;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Phục vụ một màn hình bếp kết nối qua TCP, chạy trên một luồng riêng.
 * <ul>
 *   <li>Trước HELLO chỉ nhận LOGIN, REGISTER, LOGOUT, CHANGE_PASSWORD, PING (F16).</li>
 *   <li>HELLO phải kèm token của một phiên đăng nhập còn hiệu lực; vai trò quyết định chế độ được mở
 *       (Quản lý / Bếp trưởng: MASTERCHEF hoặc VIEW, Bếp phụ: chỉ VIEW, Phục vụ: không được).</li>
 *   <li>Sau HELLO: lệnh của masterchef (START_ITEMS, EXTEND_ITEMS, FINISH_ITEMS, SOLD_OUT, NOT_DONE).</li>
 * </ul>
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final ServerContext ctx;
    private BufferedWriter out;
    private volatile String name;
    private volatile String mode;
    private volatile String username;
    private volatile String fullName;

    public ClientHandler(Socket socket, ServerContext ctx) {
        this.socket = socket;
        this.ctx = ctx;
    }

    public String getName() {
        return name;
    }

    public String getMode() {
        return mode;
    }

    public String getUsername() {
        return username;
    }

    public String getFullName() {
        return fullName;
    }

    /** Báo lỗi phiên rồi đóng kết nối (tài khoản bị khóa, đổi vai trò...). */
    public void kick(String reason) {
        ctx.log("Ngắt màn hình bếp của " + fullName + ": " + reason);
        Message m = Message.error(null, reason);
        m.code = Protocol.ERR_AUTH;
        send(m);
        try {
            socket.close();
        } catch (IOException ignored) {
            // bỏ qua
        }
    }

    public boolean isRegistered() {
        return mode != null;
    }

    public String getRemoteIp() {
        return socket.getInetAddress().getHostAddress();
    }

    @Override
    public void run() {
        String ip = getRemoteIp();
        try (socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(Protocol.CONNECTION_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            ctx.clients().add(this);

            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Message msg;
                try {
                    msg = Json.parseMessage(line);
                } catch (JsonParseException e) {
                    ctx.log("Dòng không hợp lệ từ " + ip + ": " + line);
                    send(Message.error(null, "Thông điệp không hợp lệ"));
                    continue;
                }
                if (!isRegistered() && isAccountCommand(msg.command)) {
                    handleAccount(msg);
                    continue;
                }
                if (!isRegistered() && !Protocol.HELLO.equals(msg.command)) {
                    send(Message.error(msg.reqId, "Phải gửi HELLO trước"));
                    break; // kết nối không HELLO hợp lệ thì đóng
                }
                handle(msg);
            }
        } catch (SocketTimeoutException e) {
            ctx.log("Quá " + Protocol.CONNECTION_TIMEOUT_MS / 1000 + " giây không nhận được gì từ " + describe());
        } catch (IOException e) {
            ctx.log("Mất kết nối với " + describe() + ": " + e.getMessage());
        } finally {
            ctx.clients().remove(this);
            if (isRegistered()) {
                ctx.log("Đã đóng kết nối " + describe());
            }
        }
    }

    private void handle(Message msg) {
        switch (msg.command) {
            case Protocol.HELLO -> handleHello(msg);
            case Protocol.PING -> {
                Message pong = new Message(Protocol.PONG);
                pong.serverTime = System.currentTimeMillis();
                send(pong);
            }
            case Protocol.START_ITEMS, Protocol.EXTEND_ITEMS, Protocol.FINISH_ITEMS, Protocol.SOLD_OUT, Protocol.NOT_DONE -> {
                if (!Protocol.MODE_MASTERCHEF.equals(mode)) {
                    send(Message.error(msg.reqId, "Không có quyền: chỉ màn hình MASTERCHEF được thao tác"));
                    return;
                }
                if (Protocol.START_ITEMS.equals(msg.command) && (msg.chef == null || msg.chef.isBlank())) {
                    msg.chef = fullName;
                }
                Message err = ctx.kitchen().handle(msg, name);
                if (err != null) {
                    send(err);
                }
            }
            default -> send(Message.error(msg.reqId, "Chưa hỗ trợ lệnh " + msg.command));
        }
    }

    private void handleHello(Message msg) {
        if (isRegistered()) {
            send(Message.error(msg.reqId, "Đã HELLO rồi"));
            return;
        }
        if (msg.name == null || msg.name.isBlank()) {
            send(Message.error(msg.reqId, "Thiếu tên thiết bị"));
            return;
        }
        if (!Protocol.MODE_MASTERCHEF.equals(msg.mode) && !Protocol.MODE_VIEW.equals(msg.mode)) {
            send(Message.error(msg.reqId, "Chế độ phải là MASTERCHEF hoặc VIEW"));
            return;
        }
        // F16: phải đăng nhập, và vai trò phải được mở chế độ này
        UserInfo user = ctx.accounts().session(msg.token);
        if (user == null) {
            Message e = Message.error(msg.reqId, "Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Đăng nhập lại.");
            e.code = Protocol.ERR_AUTH;
            send(e);
            ctx.log("Từ chối màn hình bếp từ " + getRemoteIp() + ": chưa đăng nhập / phiên hết hạn");
            return;
        }
        String allowed = user.role.kitchenMode();
        if (allowed == null || Protocol.MODE_MASTERCHEF.equals(msg.mode) && !Protocol.MODE_MASTERCHEF.equals(allowed)) {
            Message e = Message.error(msg.reqId, "Vai trò " + user.role.label() + " không được mở màn hình bếp"
                    + (allowed == null ? "" : " ở chế độ đủ quyền (chỉ được xem)"));
            e.code = Protocol.ERR_FORBIDDEN;
            send(e);
            ctx.log("Từ chối " + user.fullName + " (" + user.role.label() + ") mở màn hình bếp " + msg.mode);
            return;
        }
        synchronized (ctx.clients()) {
            if (Protocol.MODE_MASTERCHEF.equals(msg.mode) && ctx.clients().hasOtherMasterchef(this)) {
                send(Message.error(msg.reqId, "Đã có màn hình MASTERCHEF đang kết nối"));
                return;
            }
            this.name = user.fullName;
            this.mode = msg.mode;
            this.username = user.username;
            this.fullName = user.fullName;
        }
        ctx.log("HELLO: " + user.fullName + " (" + user.role.label() + ", " + mode + ") từ " + getRemoteIp());

        Message welcome = new Message(Protocol.WELCOME);
        welcome.serverTime = System.currentTimeMillis();
        welcome.menu = ctx.menu().getAll();
        send(welcome);

        Message snap = new Message(Protocol.SNAPSHOT);
        snap.orders = ctx.orders().getOpenOrders();
        send(snap);
    }

    private static boolean isAccountCommand(String c) {
        return Protocol.LOGIN.equals(c) || Protocol.REGISTER.equals(c) || Protocol.LOGOUT.equals(c)
                || Protocol.CHANGE_PASSWORD.equals(c) || Protocol.PING.equals(c);
    }

    /** LOGIN, REGISTER, LOGOUT, CHANGE_PASSWORD (F16). Mật khẩu không bao giờ được ghi vào log. */
    private void handleAccount(Message msg) {
        String from = getRemoteIp();
        try {
            switch (msg.command) {
                case Protocol.LOGIN -> {
                    AccountStore.Login l = ctx.accounts().login(msg.username, msg.password, from);
                    Message ok = new Message(Protocol.LOGIN_OK);
                    ok.reqId = msg.reqId;
                    ok.token = l.token();
                    ok.user = l.user();
                    ok.serverTime = System.currentTimeMillis();
                    send(ok);
                }
                case Protocol.REGISTER -> send(ok(msg, ctx.accounts().register(msg.username, msg.fullName,
                        msg.password, msg.role, from)));
                case Protocol.LOGOUT -> {
                    ctx.accounts().logout(msg.token);
                    send(ok(msg, "Đã đăng xuất"));
                }
                case Protocol.CHANGE_PASSWORD -> {
                    ctx.accounts().changePassword(msg.token, msg.password, msg.newPassword);
                    send(ok(msg, "Đã đổi mật khẩu"));
                }
                default -> {
                    Message pong = new Message(Protocol.PONG);
                    pong.serverTime = System.currentTimeMillis();
                    send(pong);
                }
            }
        } catch (AccountStore.AuthError e) {
            Message err = Message.error(msg.reqId, e.getMessage());
            err.code = e.code();
            send(err);
        }
    }

    private static Message ok(Message req, String text) {
        Message m = new Message(Protocol.OK);
        m.reqId = req.reqId;
        m.message = text;
        return m;
    }

    /** Gửi một thông điệp thành một dòng. synchronized vì nhiều luồng có thể cùng gửi tới thiết bị này. */
    public synchronized void send(Message m) {
        if (out == null) {
            return;
        }
        try {
            out.write(Json.toLine(m));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            ctx.log("Không gửi được tới " + describe() + ": " + e.getMessage());
            try {
                socket.close(); // luồng đọc sẽ thoát và dọn dẹp
            } catch (IOException ignored) {
                // bỏ qua
            }
        }
    }

    private String describe() {
        return (name == null ? "?" : name) + " [" + getRemoteIp() + "]";
    }
}
