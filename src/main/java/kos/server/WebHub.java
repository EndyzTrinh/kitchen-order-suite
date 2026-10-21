package kos.server;

import kos.common.Message;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Các trang gọi món đang giữ kết nối SSE GET /api/events.
 * Nhận ORDER_NEW, ITEM_UPDATE, MENU_UPDATE giống màn hình bếp (tên sự kiện = tên lệnh).
 */
public class WebHub {

    private final ServerContext ctx;
    private final List<SseClient> clients = new CopyOnWriteArrayList<>();

    public WebHub(ServerContext ctx) {
        this.ctx = ctx;
    }

    void add(SseClient c) {
        clients.add(c);
    }

    public void broadcast(Message m) {
        String chunk = SseClient.event(m.command, m);
        for (SseClient c : clients) {
            if (!c.write(chunk)) {
                drop(c);
            }
        }
    }

    /** Gọi định kỳ để phát hiện trình duyệt đã đóng. */
    public void keepAlive() {
        for (SseClient c : clients) {
            if (!c.ping()) {
                drop(c);
            }
        }
    }

    public void shutdown() {
        for (SseClient c : clients) {
            c.close();
        }
    }

    private void drop(SseClient c) {
        if (clients.remove(c)) {
            c.close();
            ctx.log("Trang gọi món " + c.name + " ngắt kết nối sự kiện [" + c.ip + "]");
        }
    }
}
