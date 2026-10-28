package kos.ui;

import kos.common.Json;
import kos.common.Message;
import kos.common.Protocol;
import kos.common.Role;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Đăng nhập / đăng ký từ máy khác qua TCP cổng 6000 (F16).
 * Mỗi lệnh mở một kết nối ngắn: gửi một dòng JSON, đọc một dòng trả lời, đóng.
 */
public class RemoteAuth implements AuthApi {

    private final String host;

    public RemoteAuth(String host) {
        this.host = host;
    }

    private Message call(Message req) throws AuthException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, Protocol.TCP_PORT), Protocol.CONNECT_TIMEOUT_MS);
            s.setSoTimeout(Protocol.CONNECTION_TIMEOUT_MS);
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.write(Json.toLine(req));
            out.write('\n');
            out.flush();
            String line = in.readLine();
            if (line == null) {
                throw new AuthException("Máy chủ đóng kết nối");
            }
            Message reply = Json.parseMessage(line);
            if (Protocol.ERROR.equals(reply.command)) {
                throw new AuthException(reply.message);
            }
            return reply;
        } catch (IOException e) {
            throw new AuthException("Không kết nối được máy chủ " + host + ":" + Protocol.TCP_PORT
                    + ". Kiểm tra địa chỉ IP và ServerApp đã chạy chưa.");
        }
    }

    @Override
    public Session login(String username, String password) throws AuthException {
        Message m = new Message(Protocol.LOGIN);
        m.username = username;
        m.password = password;
        Message r = call(m);
        return new Session(r.token, r.user);
    }

    @Override
    public String register(String username, String fullName, String password, Role role) throws AuthException {
        Message m = new Message(Protocol.REGISTER);
        m.username = username;
        m.fullName = fullName;
        m.password = password;
        m.role = role;
        return call(m).message;
    }

    @Override
    public void logout(String token) {
        Message m = new Message(Protocol.LOGOUT);
        m.token = token;
        try {
            call(m);
        } catch (AuthException ignored) {
            // mất mạng thì phiên tự hết hạn
        }
    }

    @Override
    public void changePassword(String token, String oldPassword, String newPassword) throws AuthException {
        Message m = new Message(Protocol.CHANGE_PASSWORD);
        m.token = token;
        m.password = oldPassword;
        m.newPassword = newPassword;
        call(m);
    }

    @Override
    public String serverLabel() {
        return "Máy chủ: " + host;
    }

    @Override
    public String host() {
        return host;
    }
}
