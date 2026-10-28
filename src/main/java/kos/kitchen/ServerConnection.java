package kos.kitchen;

import com.google.gson.JsonParseException;
import kos.common.Json;
import kos.common.Message;
import kos.common.Protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kết nối TCP từ Masterchef Display tới máy chủ: gửi HELLO, luồng đọc, PING định kỳ,
 * tự kết nối lại. Giao diện chỉ làm việc với mạng qua lớp này.
 * Lưu ý: listener được gọi trên luồng mạng; giao diện Swing phải chuyển về luồng giao diện
 * bằng SwingUtilities.invokeLater().
 */
public class ServerConnection {

    /** Giao diện nhận sự kiện từ kết nối. */
    public interface Listener {
        void onMessage(Message m);

        void onStatus(boolean connected, String text);

        /** Máy chủ từ chối phiên đăng nhập (hết hạn, bị khóa, vai trò không đủ). Kết nối đã dừng hẳn. */
        default void onAuthFailed(String message) {
        }
    }

    private final String host;
    private final String name;
    private final String mode;
    private final String token;
    private final Listener listener;
    private final AtomicInteger reqCounter = new AtomicInteger();
    private final ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor();

    private volatile Socket socket;
    private volatile BufferedWriter out;
    private volatile boolean running = true;
    /** Giờ máy chủ trừ giờ máy này, dùng để hiển thị đồng hồ đúng. */
    private volatile long clockOffset;

    /**
     * @param token mã phiên nhận được khi đăng nhập (F16), gửi kèm HELLO mỗi lần kết nối / kết nối lại
     */
    public ServerConnection(String host, String name, String mode, String token, Listener listener) {
        this.host = host;
        this.name = name;
        this.mode = mode;
        this.token = token;
        this.listener = listener;
    }

    /** Bắt đầu kết nối trên luồng nền; tự kết nối lại khi mất kết nối. */
    public void start() {
        Thread t = new Thread(this::connectLoop, "server-connection");
        t.setDaemon(true);
        t.start();
        pinger.scheduleAtFixedRate(() -> {
            if (out != null) {
                send(new Message(Protocol.PING));
            }
        }, Protocol.PING_INTERVAL_MS, Protocol.PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running = false;
        pinger.shutdownNow();
        closeSocket();
    }

    /** Tạo req_id mới, ví dụ "MC-0007". */
    public String nextReqId() {
        return String.format("%s-%04d", name, reqCounter.incrementAndGet());
    }

    /** Giờ hiện tại theo đồng hồ máy chủ. */
    public long serverNow() {
        return System.currentTimeMillis() + clockOffset;
    }

    public synchronized boolean send(Message m) {
        BufferedWriter w = out;
        if (w == null) {
            return false;
        }
        try {
            w.write(Json.toLine(m));
            w.write('\n');
            w.flush();
            return true;
        } catch (IOException e) {
            closeSocket();
            return false;
        }
    }

    private void connectLoop() {
        while (running) {
            try (Socket s = new Socket()) {
                listener.onStatus(false, "Đang kết nối " + host + ":" + Protocol.TCP_PORT + "...");
                s.connect(new InetSocketAddress(host, Protocol.TCP_PORT), Protocol.CONNECT_TIMEOUT_MS);
                s.setSoTimeout(Protocol.CONNECTION_TIMEOUT_MS);
                s.setTcpNoDelay(true);
                socket = s;
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

                Message hello = new Message(Protocol.HELLO);
                hello.name = name;
                hello.mode = mode;
                hello.token = token;
                send(hello);

                String line;
                while ((line = in.readLine()) != null) {
                    try {
                        Message m = Json.parseMessage(line);
                        if (m.serverTime != null) {
                            clockOffset = m.serverTime - System.currentTimeMillis();
                        }
                        if (Protocol.WELCOME.equals(m.command)) {
                            listener.onStatus(true, "Đã kết nối " + host);
                        }
                        if (Protocol.ERROR.equals(m.command) && (Protocol.ERR_AUTH.equals(m.code)
                                || Protocol.ERR_FORBIDDEN.equals(m.code))) {
                            running = false;
                            listener.onStatus(false, "Bị từ chối: " + m.message);
                            listener.onAuthFailed(m.message);
                            break;
                        }
                        listener.onMessage(m);
                    } catch (JsonParseException e) {
                        // bỏ qua dòng lỗi, không làm dừng luồng đọc
                    }
                }
            } catch (IOException e) {
                // mất kết nối hoặc quá 15 giây không nhận được gì: thử lại bên dưới
            } finally {
                out = null;
                socket = null;
            }
            if (running) {
                listener.onStatus(false, "Mất kết nối - thử lại sau " + Protocol.RECONNECT_DELAY_MS / 1000 + " giây");
                try {
                    Thread.sleep(Protocol.RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void closeSocket() {
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // bỏ qua
            }
        }
    }
}
