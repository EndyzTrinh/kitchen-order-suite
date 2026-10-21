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
 * Đã có: GET /order, GET /pager, GET /api/menu, POST /api/order,
 * GET /api/pager/events (SSE), POST /api/pager/received, POST /api/pager/ack.
 * TODO (Nam): GET /api/snapshot, GET /api/events (SSE cho trang gọi món), heartbeat (F11).
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
        server.createContext("/api/pager/events", this::handlePagerEvents);
        server.createContext("/api/pager/received", ex -> handlePagerReply(ex, false));
        server.createContext("/api/pager/ack", ex -> handlePagerReply(ex, true));
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

    /** GET /api/pager/events?id=N - kết nối SSE giữ mở, không đóng exchange ở đây. */
    private void handlePagerEvents(HttpExchange ex) throws IOException {
        Integer id = queryInt(ex, "id");
        if (id == null || id < 1) {
            try (ex) {
                sendText(ex, 400, "text/plain", "Thiếu số thiết bị rung (id)");
            }
            return;
        }
        ctx.pagers().register(ex, id);
    }

    /** POST /api/pager/received (PAGER_RECEIVED) và /api/pager/ack (PICKED_UP). */
    private void handlePagerReply(HttpExchange ex, boolean pickedUp) throws IOException {
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
            boolean ok = pickedUp
                    ? ctx.pagers().onPickedUp(req.callId, req.pagerId)
                    : ctx.pagers().onReceived(req.callId, req.pagerId);
            if (!ok) {
                sendJson(ex, Message.error(req.reqId, "Lệnh " + req.callId + " đã đóng hoặc không tồn tại"));
                return;
            }
            Message reply = new Message(pickedUp ? Protocol.PICKED_UP : Protocol.PAGER_RECEIVED);
            reply.callId = req.callId;
            sendJson(ex, reply);
        }
    }

    private static Integer queryInt(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                try {
                    return Integer.parseInt(kv[1]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
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
