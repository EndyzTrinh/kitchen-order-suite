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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cổng HTTP cho trang gọi món và trang thiết bị rung (F02, F03, F08, F11).
 * <pre>
 * GET  /order                      trang gọi món
 * GET  /pager?id=N                 trang thiết bị rung
 * GET  /api/menu                   MENU_UPDATE kèm thực đơn
 * GET  /api/snapshot               SNAPSHOT các đơn chưa hoàn tất
 * GET  /api/events?name=X          SSE cho trang gọi món (ORDER_NEW, ITEM_UPDATE, MENU_UPDATE)
 * POST /api/order                  NEW_ORDER -> ORDER_ACK / ERROR
 * POST /api/priority               PRIORITY_ITEMS: xin ưu tiên món đang chờ làm (F14)
 * POST /api/heartbeat              heartbeat của trang gọi món
 * GET  /api/pager/events?id=N      SSE cho thiết bị rung (PAGER_CALL, PAGER_CLOSE)
 * POST /api/pager/received         PAGER_RECEIVED
 * POST /api/pager/ack              PICKED_UP
 * POST /api/pager/heartbeat        heartbeat của thiết bị rung
 * </pre>
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
        server.createContext("/api/snapshot", this::handleSnapshot);
        server.createContext("/api/events", this::handleOrderEvents);
        server.createContext("/api/order", this::handleOrder);
        server.createContext("/api/priority", this::handlePriority);
        server.createContext("/api/heartbeat", ex -> handleHeartbeat(ex, false));
        server.createContext("/api/pager/events", this::handlePagerEvents);
        server.createContext("/api/pager/received", ex -> handlePagerReply(ex, false));
        server.createContext("/api/pager/ack", ex -> handlePagerReply(ex, true));
        server.createContext("/api/pager/heartbeat", ex -> handleHeartbeat(ex, true));
        // Nhóm luồng riêng: các kết nối SSE giữ mở không chặn yêu cầu khác.
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

    // ------------------------------------------------------------------ trang tĩnh, đọc dữ liệu

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
            if (!requireMethod(ex, "GET")) {
                return;
            }
            Message m = new Message(Protocol.MENU_UPDATE);
            m.menu = ctx.menu().getAll();
            m.serverTime = System.currentTimeMillis();
            sendJson(ex, m);
        }
    }

    private void handleSnapshot(HttpExchange ex) throws IOException {
        try (ex) {
            if (!requireMethod(ex, "GET")) {
                return;
            }
            Message m = new Message(Protocol.SNAPSHOT);
            m.orders = ctx.orders().getOpenOrders();
            m.serverTime = System.currentTimeMillis();
            sendJson(ex, m);
        }
    }

    /** GET /api/events?name=X - SSE của trang gọi món, giữ mở, không đóng exchange. */
    private void handleOrderEvents(HttpExchange ex) throws IOException {
        String name = query(ex, "name");
        if (name == null || name.isBlank()) {
            name = "WEB";
        }
        SseClient sse = SseClient.open(ex, name);
        if (sse != null) {
            ctx.webHub().add(sse);
            ctx.devices().touch(DeviceRegistry.TYPE_ORDER, name, sse.ip);
        }
    }

    // ------------------------------------------------------------------ gửi đơn

    private void handleOrder(HttpExchange ex) throws IOException {
        try (ex) {
            Message req = readMessage(ex);
            if (req == null) {
                return;
            }
            if (!Protocol.NEW_ORDER.equals(req.command)) {
                sendJson(ex, Message.error(req.reqId, "Đường dẫn này chỉ nhận NEW_ORDER"));
                return;
            }
            String ip = remoteIp(ex);
            String name = req.name == null || req.name.isBlank() ? "WEB" : req.name;
            ctx.devices().touch(DeviceRegistry.TYPE_ORDER, name, ip);
            OrderStore.AddResult r = ctx.orders().addOrder(req.reqId, name, req.table, req.pagerId, req.items);
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
                ctx.log("Đơn " + r.order().orderId + " - bàn " + r.order().table + " từ " + name + " [" + ip + "]");
                Message news = new Message(Protocol.ORDER_NEW);
                news.order = r.order();
                ctx.broadcast(news);
                boolean hasPriority = false;
                for (kos.common.OrderItem it : r.order().items) {
                    if (it.isPriority()) {
                        hasPriority = true;
                        ctx.log(name + " xin ưu tiên lúc gọi món: " + it.name + " x" + it.qty
                                + " (bàn " + r.order().table + ", đơn " + r.order().orderId + ")");
                    }
                }
                if (hasPriority) {
                    ctx.kitchen().checkPriorityQueue();
                }
                ctx.save();
            }
        }
    }

    /** POST /api/priority - phục vụ xin ưu tiên các món đang chờ làm. */
    private void handlePriority(HttpExchange ex) throws IOException {
        try (ex) {
            Message req = readMessage(ex);
            if (req == null) {
                return;
            }
            if (!Protocol.PRIORITY_ITEMS.equals(req.command)) {
                sendJson(ex, Message.error(req.reqId, "Đường dẫn này chỉ nhận PRIORITY_ITEMS"));
                return;
            }
            String name = req.name == null || req.name.isBlank() ? "WEB" : req.name;
            ctx.devices().touch(DeviceRegistry.TYPE_ORDER, name, remoteIp(ex));
            Message err = ctx.kitchen().priority(req, name);
            if (err != null) {
                sendJson(ex, err);
                return;
            }
            Message ok = new Message(Protocol.PRIORITY_ITEMS);
            ok.reqId = req.reqId;
            sendJson(ex, ok);
        }
    }

    // ------------------------------------------------------------------ thiết bị rung

    /** GET /api/pager/events?id=N - kết nối SSE giữ mở, không đóng exchange ở đây. */
    private void handlePagerEvents(HttpExchange ex) throws IOException {
        Integer id = parseInt(query(ex, "id"));
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
            Message req = readMessage(ex);
            if (req == null) {
                return;
            }
            if (req.pagerId != null) {
                ctx.devices().touch(DeviceRegistry.TYPE_PAGER, String.valueOf(req.pagerId), remoteIp(ex));
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

    // ------------------------------------------------------------------ heartbeat (F11)

    /** POST /api/heartbeat {"name":...} hoặc /api/pager/heartbeat {"pager_id":...} -> PONG. */
    private void handleHeartbeat(HttpExchange ex, boolean pager) throws IOException {
        try (ex) {
            Message req = readMessage(ex);
            if (req == null) {
                return;
            }
            String ip = remoteIp(ex);
            if (pager && req.pagerId != null) {
                ctx.devices().touch(DeviceRegistry.TYPE_PAGER, String.valueOf(req.pagerId), ip);
            } else if (!pager && req.name != null && !req.name.isBlank()) {
                ctx.devices().touch(DeviceRegistry.TYPE_ORDER, req.name, ip);
            }
            Message pong = new Message(Protocol.PONG);
            pong.serverTime = System.currentTimeMillis();
            sendJson(ex, pong);
        }
    }

    // ------------------------------------------------------------------ tiện ích

    /** Đọc thân POST thành Message; tự trả lỗi và trả về null nếu không hợp lệ. */
    private static Message readMessage(HttpExchange ex) throws IOException {
        if (!requireMethod(ex, "POST")) {
            return null;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            return Json.parseMessage(body);
        } catch (JsonParseException e) {
            sendJson(ex, Message.error(null, "Thông điệp không hợp lệ"));
            return null;
        }
    }

    private static boolean requireMethod(HttpExchange ex, String method) throws IOException {
        if (method.equals(ex.getRequestMethod())) {
            return true;
        }
        sendText(ex, 405, "text/plain", "Chỉ hỗ trợ " + method);
        return false;
    }

    private static String remoteIp(HttpExchange ex) {
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static Integer parseInt(String s) {
        try {
            return s == null ? null : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
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
