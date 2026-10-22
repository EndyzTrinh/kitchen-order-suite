package kos.server;

import com.sun.net.httpserver.HttpExchange;
import kos.common.Message;
import kos.common.Order;
import kos.common.OrderItem;
import kos.common.Protocol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Điều phối thiết bị rung (F08, F09).
 * <ul>
 *   <li>Mỗi trang thiết bị rung giữ một kết nối Server-Sent Events (SSE) để máy chủ chủ động gửi lệnh.</li>
 *   <li>Món của cùng một đơn xong trong vòng 1 giây được gom thành một lệnh PAGER_CALL.</li>
 *   <li>Thiết bị phải trả PAGER_RECEIVED trong 5 giây; không có thì gửi lại một lần,
 *       vẫn không có thì chuyển lệnh cho mọi thiết bị rung đang kết nối.</li>
 *   <li>Quá 60 giây chưa ai bấm "Đã lấy món" thì rung lại.</li>
 * </ul>
 * Bộ định kỳ của lớp này cũng kiểm tra bảng thiết bị web và giữ kết nối SSE của trang gọi món.
 */
public class PagerDispatcher {

    /** Một lệnh rung đang mở (chưa ai bấm "Đã lấy món"). */
    private static final class Call {
        String callId;
        int pagerId;
        int orderId;
        int table;
        List<OrderItem> items;
        boolean received;
        boolean resent;
        boolean forwarded;
        long lastSent;
        /** Thời điểm tạo lệnh, để quầy biết món đã chờ lấy bao lâu. */
        long createdAt;
    }

    /** Bản sao một lệnh rung đang mở, cho thẻ Đơn hàng của quầy quản lý. */
    public record OpenCall(String callId, int pagerId, int orderId, int table, List<Integer> itemNos,
                           String itemsText, boolean received, boolean forwarded, long createdAt) { }

    /** Kết nối SSE kèm số thiết bị rung. */
    private record PagerConn(int pagerId, SseClient sse) { }

    private static final long BATCH_DELAY_MS = 1_000;

    private final ServerContext ctx;
    private final List<PagerConn> clients = new CopyOnWriteArrayList<>();
    /** Các lệnh đang mở theo call_id. Truy cập trong synchronized(this). */
    private final Map<String, Call> calls = new LinkedHashMap<>();
    /** Món đã xong đang chờ gom theo order_id. Truy cập trong synchronized(this). */
    private final Map<Integer, List<OrderItem>> pendingItems = new HashMap<>();
    private final Map<Integer, Order> pendingOrders = new HashMap<>();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pager-dispatcher");
        t.setDaemon(true);
        return t;
    });
    private int callCounter;

    public PagerDispatcher(ServerContext ctx) {
        this.ctx = ctx;
    }

    public void start() {
        exec.scheduleAtFixedRate(() -> safe(this::tick), 1, 1, TimeUnit.SECONDS);
        exec.scheduleAtFixedRate(() -> safe(this::keepAlive), Protocol.PING_INTERVAL_MS, Protocol.PING_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        exec.shutdownNow();
        for (PagerConn c : clients) {
            c.sse().close();
        }
    }

    // ------------------------------------------------------------------ kết nối SSE

    /** GET /api/pager/events?id=N: giữ kết nối mở, không đóng exchange. */
    public void register(HttpExchange ex, int pagerId) throws IOException {
        SseClient sse = SseClient.open(ex, String.valueOf(pagerId));
        if (sse == null) {
            return;
        }
        clients.add(new PagerConn(pagerId, sse));
        ctx.devices().touch(DeviceRegistry.TYPE_PAGER, String.valueOf(pagerId), sse.ip);
        // Gửi lại các lệnh còn mở của thiết bị này (ví dụ sau khi mất kết nối).
        synchronized (this) {
            for (Call call : calls.values()) {
                if (call.pagerId == pagerId || call.forwarded) {
                    sse.write(SseClient.event(Protocol.PAGER_CALL, toMessage(call)));
                }
            }
        }
    }

    public int connectedCount() {
        return clients.size();
    }

    // ------------------------------------------------------------------ món xong -> lệnh rung

    /** Một món vừa chuyển READY. Gom các món của cùng đơn trong 1 giây rồi gửi một lệnh. */
    public synchronized void notifyReady(Order order, OrderItem item) {
        List<OrderItem> list = pendingItems.computeIfAbsent(order.orderId, k -> new ArrayList<>());
        if (list.isEmpty()) {
            pendingOrders.put(order.orderId, order);
            exec.schedule(() -> flush(order.orderId), BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
        }
        OrderItem copy = new OrderItem();
        copy.itemNo = item.itemNo;
        copy.name = item.name;
        copy.qty = item.qty;
        copy.note = item.note;
        list.add(copy);
    }

    private synchronized void flush(int orderId) {
        List<OrderItem> items = pendingItems.remove(orderId);
        Order order = pendingOrders.remove(orderId);
        if (items == null || items.isEmpty() || order == null) {
            return;
        }
        Call call = new Call();
        call.callId = String.format("C-%04d", ++callCounter);
        call.pagerId = order.pagerId;
        call.orderId = orderId;
        call.table = order.table;
        call.items = items;
        call.createdAt = System.currentTimeMillis();
        calls.put(call.callId, call);
        int n = deliver(call);
        ctx.log("Gọi thiết bị rung " + call.pagerId + " (" + call.callId + ", bàn " + call.table + ")"
                + (n == 0 ? " - thiết bị chưa kết nối" : ""));
    }

    // ------------------------------------------------------------------ phản hồi từ thiết bị

    /** POST /api/pager/received. */
    public synchronized boolean onReceived(String callId, Integer pagerId) {
        Call call = callId == null ? null : calls.get(callId);
        if (call == null) {
            return false;
        }
        if (!call.received) {
            call.received = true;
            ctx.log("Thiết bị rung " + pagerId + " đã nhận lệnh " + callId);
        }
        return true;
    }

    /** POST /api/pager/ack: phục vụ bấm "Đã lấy món". */
    public boolean onPickedUp(String callId, Integer pagerId) {
        Call call = close(callId);
        if (call == null) {
            return false;
        }
        ctx.log("Thiết bị rung " + pagerId + " đã lấy món (" + callId + ", bàn " + call.table + ")");
        ctx.kitchen().onPickedUp(call.orderId, itemNos(call), "Thiết bị rung " + pagerId);
        return true;
    }

    /**
     * Quản lý xác nhận thủ công tại quầy khi không có thiết bị rung nào phản hồi.
     * Gọi từ nút "Xác nhận đã lấy" trên thẻ Đơn hàng.
     */
    public boolean confirmManually(String callId) {
        Call call = close(callId);
        if (call == null) {
            return false;
        }
        ctx.log("Quản lý xác nhận đã lấy món (" + callId + ", bàn " + call.table + ")");
        ctx.kitchen().onPickedUp(call.orderId, itemNos(call), "Quản lý");
        return true;
    }

    /** Danh sách lệnh rung đang mở, dạng chữ, cho giao diện quầy. */
    public synchronized List<String> describeOpenCalls() {
        List<String> list = new ArrayList<>();
        for (Call c : calls.values()) {
            list.add(c.callId + " - bàn " + c.table + " - thiết bị " + c.pagerId
                    + (c.forwarded ? " (đã chuyển tiếp)" : c.received ? " (đã nhận)" : " (chưa xác nhận)"));
        }
        return list;
    }

    /** Các lệnh rung đang mở (bản sao), cho bảng lệnh rung trên thẻ Đơn hàng. */
    public synchronized List<OpenCall> getOpenCalls() {
        List<OpenCall> list = new ArrayList<>();
        for (Call c : calls.values()) {
            StringBuilder text = new StringBuilder();
            for (OrderItem it : c.items) {
                if (text.length() > 0) {
                    text.append(", ");
                }
                text.append(it.name).append(" x").append(it.qty);
            }
            list.add(new OpenCall(c.callId, c.pagerId, c.orderId, c.table, itemNos(c), text.toString(),
                    c.received, c.forwarded, c.createdAt));
        }
        return list;
    }

    private synchronized Call close(String callId) {
        Call call = callId == null ? null : calls.remove(callId);
        if (call != null) {
            Message close = new Message(Protocol.PAGER_CLOSE);
            close.callId = callId;
            String chunk = SseClient.event(Protocol.PAGER_CLOSE, close);
            for (PagerConn c : clients) {
                if (!c.sse().write(chunk)) {
                    drop(c);
                }
            }
        }
        return call;
    }

    private static List<Integer> itemNos(Call call) {
        List<Integer> nos = new ArrayList<>();
        for (OrderItem it : call.items) {
            nos.add(it.itemNo);
        }
        return nos;
    }

    // ------------------------------------------------------------------ theo dõi định kỳ

    private void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    private synchronized void tick() {
        long now = System.currentTimeMillis();
        for (Call call : calls.values()) {
            long since = now - call.lastSent;
            if (!call.received && !call.forwarded) {
                if (since < Protocol.PAGER_RECEIVED_TIMEOUT_MS) {
                    continue;
                }
                if (!call.resent) {
                    call.resent = true;
                    ctx.log("Thiết bị rung " + call.pagerId + " chưa xác nhận " + call.callId + ", gửi lại");
                } else {
                    call.forwarded = true;
                    ctx.log("CẢNH BÁO: Thiết bị rung " + call.pagerId + " không phản hồi, chuyển lệnh "
                            + call.callId + " (bàn " + call.table + ") cho mọi thiết bị rung");
                }
                int n = deliver(call);
                if (call.forwarded && n == 0) {
                    ctx.log("CẢNH BÁO: Không có thiết bị rung nào kết nối, quầy gọi phục vụ lấy món bàn " + call.table);
                }
            } else if (since >= Protocol.PICKUP_REMIND_MS) {
                ctx.log("Chưa ai lấy món bàn " + call.table + " (" + call.callId + "), rung lại");
                deliver(call);
            }
        }
        ctx.devices().check();
    }

    private void keepAlive() {
        for (PagerConn c : clients) {
            if (!c.sse().ping()) {
                drop(c);
            }
        }
        ctx.webHub().keepAlive();
    }

    // ------------------------------------------------------------------ gửi

    /** Gửi lệnh tới thiết bị đích (hoặc mọi thiết bị nếu đã chuyển tiếp). Trả về số thiết bị đã gửi. */
    private int deliver(Call call) {
        call.lastSent = System.currentTimeMillis();
        String chunk = SseClient.event(Protocol.PAGER_CALL, toMessage(call));
        int n = 0;
        for (PagerConn c : clients) {
            if (call.forwarded || c.pagerId() == call.pagerId) {
                if (c.sse().write(chunk)) {
                    n++;
                } else {
                    drop(c);
                }
            }
        }
        return n;
    }

    private void drop(PagerConn c) {
        if (clients.remove(c)) {
            c.sse().close();
            ctx.log("Thiết bị rung " + c.pagerId() + " ngắt kết nối sự kiện [" + c.sse().ip + "]");
        }
    }

    private static Message toMessage(Call call) {
        Message m = new Message(Protocol.PAGER_CALL);
        m.callId = call.callId;
        m.pagerId = call.pagerId;
        m.orderId = call.orderId;
        m.table = call.table;
        m.items = call.items;
        return m;
    }
}
