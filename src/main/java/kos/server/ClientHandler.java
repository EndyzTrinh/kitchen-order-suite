package kos.server;

import com.google.gson.JsonParseException;
import kos.common.Json;
import kos.common.Message;
import kos.common.Protocol;

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
 * Bản khung xử lý HELLO, PING; các lệnh của masterchef trả ERROR "Chưa hỗ trợ".
 * TODO (Nam): START_ITEMS, EXTEND_ITEMS, FINISH_ITEMS, SOLD_OUT (F05 - F07).
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final ServerContext ctx;
    private BufferedWriter out;
    private volatile String name;
    private volatile String mode;

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

    public boolean isRegistered() {
        return mode != null;
    }

    public String getRemoteIp() {
        return socket.getInetAddress().getHostAddress();
    }

    @Override
    public void run() {
        String ip = getRemoteIp();
        ctx.log("Kết nối TCP mới từ " + ip);
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
            ctx.log("Đã đóng kết nối " + describe());
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
        synchronized (ctx.clients()) {
            if (Protocol.MODE_MASTERCHEF.equals(msg.mode) && ctx.clients().hasOtherMasterchef(this)) {
                send(Message.error(msg.reqId, "Đã có màn hình MASTERCHEF đang kết nối"));
                return;
            }
            this.name = msg.name;
            this.mode = msg.mode;
        }
        ctx.log("HELLO: " + name + " (" + mode + ") từ " + getRemoteIp());

        Message welcome = new Message(Protocol.WELCOME);
        welcome.serverTime = System.currentTimeMillis();
        welcome.menu = ctx.menu().getAll();
        send(welcome);

        Message snap = new Message(Protocol.SNAPSHOT);
        snap.orders = ctx.orders().getOpenOrders();
        send(snap);
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
