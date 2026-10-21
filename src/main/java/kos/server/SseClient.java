package kos.server;

import com.sun.net.httpserver.HttpExchange;
import kos.common.Json;
import kos.common.Message;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Một kết nối Server-Sent Events (SSE) đang giữ mở với trình duyệt.
 * Máy chủ ghi sự kiện bất cứ lúc nào; ghi lỗi nghĩa là trình duyệt đã ngắt kết nối.
 */
final class SseClient {

    final String name;
    final String ip;
    private final HttpExchange exchange;
    private final OutputStream out;

    private SseClient(String name, HttpExchange exchange) {
        this.name = name;
        this.exchange = exchange;
        this.out = exchange.getResponseBody();
        this.ip = exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    /** Gửi tiêu đề SSE và trả về kết nối, hoặc null nếu trình duyệt đã ngắt ngay. */
    static SseClient open(HttpExchange ex, String name) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        SseClient c = new SseClient(name, ex);
        if (!c.write(": connected\n\n")) {
            c.close();
            return null;
        }
        return c;
    }

    /** Định dạng một sự kiện SSE: dòng event, dòng data, một dòng trống. */
    static String event(String eventName, Message m) {
        return "event: " + eventName + "\ndata: " + Json.toLine(m) + "\n\n";
    }

    synchronized boolean write(String chunk) {
        try {
            out.write(chunk.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    boolean ping() {
        return write(": ping\n\n");
    }

    void close() {
        try {
            exchange.close();
        } catch (RuntimeException ignored) {
            // bỏ qua
        }
    }
}
