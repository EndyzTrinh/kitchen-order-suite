package kos.server;

import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kos.common.Json;
import kos.common.Message;
import kos.common.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cổng HTTP cho trang gọi món và trang thiết bị rung.
 * Bản khung có: GET /order, GET /pager, GET /api/menu, POST /api/order.
 * TODO (Nam): GET /api/snapshot, GET /api/events và /api/pager/events (SSE),
 * POST /api/pager/received, /api/pager/ack, heartbeat (F08, F09, F11).
 */
public class WebGateway {

    private final ServerContext ctx;
    private HttpServer server;
    private ExecutorService pool;

    public WebGateway(ServerContext ctx) {
        this.ctx = ctx;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(Protocol.HTTP_PORT), 0);
        server.createContext("/order", ex -> servePage(ex, "/web/order.html"));
        server.createContext("/pager", ex -> servePage(ex, "/web/pager.html"));
        server.createContext("/api/menu", this::handleMenu);
        server.createContext("/api/order", this::handleOrder);
        // Nhóm luồng riêng: các kết nối SSE giữ mở sau này không chặn yêu cầu khác.
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.start();
        ctx.log("Cổng HTTP đang lắng nghe " + Protocol.HTTP_PORT);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void servePage(HttpExchange ex, String resource) throws IOException {
        try (ex; InputStream in = WebGateway.class.getResourceAsStream(resource)) {
            if (in == null) {
                sendText(ex, 404, "text/plain", "Không tìm thấy " + resource);
                return;
            }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
        }
    }

    private void handleMenu(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"GET".equals(ex.getRequestMethod())) {
                sendText(ex, 405, "text/plain", "Chỉ hỗ trợ GET");
                return;
            }
            Message m = new Message(Protocol.MENU_UPDATE);
            m.menu = ctx.menu().getAll();
            m.serverTime = System.currentTimeMillis();
            sendJson(ex, m);
        }
    }

    private void handleOrder(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod())) {
                sendText(ex, 405, "text/plain", "Chỉ hỗ trợ POST");
                return;
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Message req;
            try {
                req = Json.parseMessage(body);
            } catch (JsonParseException e) {
                sendJson(ex, Message.error(null, "Thông điệp không hợp lệ"));
                return;
            }
            if (!Protocol.NEW_ORDER.equals(req.command)) {
                sendJson(ex, Message.error(req.reqId, "Đường dẫn này chỉ nhận NEW_ORDER"));
                return;
            }
            OrderStore.AddResult r = ctx.orders().addOrder(req.reqId, req.name, req.table, req.pagerId, req.items);
            if (r.error() != null) {
                sendJson(ex, Message.error(req.reqId, r.error()));
                return;
            }
            Message ack = new Message(Protocol.ORDER_ACK);
            ack.reqId = req.reqId;
            ack.orderId = r.order().orderId;
            ack.kitchenOnline = ctx.clients().kitchenCount() > 0;
            sendJson(ex, ack);

            if (!r.duplicate()) {
                ctx.log("Đơn " + r.order().orderId + " - bàn " + r.order().table + " từ "
                        + req.name + " [" + ex.getRemoteAddress().getAddress().getHostAddress() + "]");
                Message news = new Message(Protocol.ORDER_NEW);
                news.order = r.order();
                ctx.clients().broadcast(news);
            }
        }
    }

    private static void sendJson(HttpExchange ex, Message m) throws IOException {
        sendText(ex, 200, "application/json", Json.toLine(m));
    }

    private static void sendText(HttpExchange ex, int code, String type, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
