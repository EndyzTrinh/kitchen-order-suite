package kos.server;

import com.google.gson.JsonParseException;
import kos.common.ItemStatus;
import kos.common.Json;
import kos.common.MenuItem;
import kos.common.Order;
import kos.common.OrderItem;
import kos.common.Protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nơi duy nhất giữ và thay đổi trạng thái đơn. Mọi phương thức thay đổi dữ liệu đều synchronized
 * vì được gọi từ nhiều luồng: ClientHandler (masterchef), WebGateway (trang web), KitchenTimer (hết giờ),
 * giao diện quầy quản lý. Nhờ vậy khi hết giờ đúng lúc masterchef bấm gia hạn, chỉ một thay đổi
 * được áp dụng trước, thay đổi sau được kiểm tra lại trạng thái.
 */
public class OrderStore {

    /** Kết quả của addOrder: đơn đã tạo hoặc thông báo lỗi. */
    public record AddResult(Order order, String error, boolean duplicate) { }

    /** Một món vừa thay đổi, kèm đơn chứa nó. */
    public record Change(Order order, OrderItem item) { }

    /** Kết quả của một lệnh áp dụng cho nhiều món: món nào đổi được, món nào lỗi. */
    public record BatchResult(List<Change> changed, List<String> errors) { }

    /** Dữ liệu ghi ra orders.json. */
    private static final class State {
        int nextOrderId;
        List<Order> orders;
        Map<String, Integer> handledRequests;
    }

    private static final long MINUTE_MS = 60_000L;

    private final MenuStore menu;
    private final Map<Integer, Order> orders = new LinkedHashMap<>();
    /** req_id đã xử lý -> order_id, để gửi lại không tạo đơn trùng. */
    private final Map<String, Integer> handledRequests = new HashMap<>();
    private int nextOrderId = 1;

    public OrderStore(MenuStore menu) {
        this.menu = menu;
    }

    // ------------------------------------------------------------------ gọi món (F03)

    public synchronized AddResult addOrder(String reqId, String waiter, Integer table, Integer pagerId,
                                           List<OrderItem> requested) {
        if (reqId == null || reqId.isBlank()) {
            return new AddResult(null, "Thiếu req_id", false);
        }
        Integer existing = handledRequests.get(reqId);
        if (existing != null) {
            Order o = orders.get(existing);
            return o == null
                    ? new AddResult(null, "Đơn này đã gửi và đã hoàn tất", false)
                    : new AddResult(o, null, true);
        }
        if (table == null || table < 1) {
            return new AddResult(null, "Số bàn không hợp lệ", false);
        }
        if (pagerId == null || pagerId < 1) {
            return new AddResult(null, "Chưa chọn thiết bị rung", false);
        }
        if (requested == null || requested.isEmpty()) {
            return new AddResult(null, "Đơn không có món", false);
        }

        int wantPriority = 0;
        for (OrderItem req : requested) {
            if (req.isPriority()) {
                wantPriority++;
            }
        }
        if (wantPriority > 0) {
            int has = countPendingPriority(table);
            if (has + wantPriority > Protocol.MAX_PRIORITY_PER_TABLE) {
                return new AddResult(null, "Bàn " + table + " đang có " + has + " món ưu tiên, tối đa "
                        + Protocol.MAX_PRIORITY_PER_TABLE + " món", false);
            }
        }

        long now = System.currentTimeMillis();
        Order order = new Order();
        order.table = table;
        order.pagerId = pagerId;
        order.waiter = waiter;
        order.createdAt = now;
        int itemNo = 1;
        for (OrderItem req : requested) {
            MenuItem m = req.menuId == null ? null : menu.find(req.menuId);
            if (m == null) {
                return new AddResult(null, "Món không có trong thực đơn: " + req.menuId, false);
            }
            if (m.isSoldOut()) {
                return new AddResult(null, "Món đang tạm hết: " + m.name, false);
            }
            if (req.qty == null || req.qty < 1) {
                return new AddResult(null, "Số lượng không hợp lệ: " + m.name, false);
            }
            if (req.note != null && req.note.length() > Protocol.MAX_NOTE_LENGTH) {
                return new AddResult(null, "Ghi chú quá dài: " + m.name, false);
            }
            OrderItem it = new OrderItem();
            it.itemNo = itemNo++;
            it.menuId = m.menuId;
            it.name = m.name;
            it.qty = req.qty;
            it.note = req.note == null ? "" : req.note;
            it.prepMinutes = m.prepMinutes;
            it.status = ItemStatus.PENDING;
            it.extendedMinutes = 0;
            if (req.isPriority()) {
                it.priority = true;
                it.priorityAt = now;
            }
            order.items.add(it);
        }
        order.orderId = nextOrderId++;
        orders.put(order.orderId, order);
        handledRequests.put(reqId, order.orderId);
        return new AddResult(order, null, false);
    }

    // ------------------------------------------------------------------ lệnh của masterchef

    /** Nhận làm (F05): PENDING -> COOKING, đặt hạn giờ = now + thời gian chuẩn bị. */
    public synchronized BatchResult startItems(List<OrderItem> refs, long now) {
        List<Change> changed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (OrderItem ref : refs) {
            Change c = locate(ref, errors);
            if (c == null) {
                continue;
            }
            OrderItem it = c.item();
            if (it.status != ItemStatus.PENDING) {
                errors.add(label(c) + " đang ở trạng thái " + it.status + ", không nhận làm được");
                continue;
            }
            int prep = it.prepMinutes == null ? 0 : it.prepMinutes;
            it.status = ItemStatus.COOKING;
            it.startedAt = now;
            it.deadline = now + prep * MINUTE_MS;
            changed.add(c);
        }
        return new BatchResult(changed, errors);
    }

    /** Gia hạn (F06): cộng thêm minutes phút vào hạn giờ của món đang làm. */
    public synchronized BatchResult extendItems(List<OrderItem> refs, int minutes) {
        List<Change> changed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (OrderItem ref : refs) {
            Change c = locate(ref, errors);
            if (c == null) {
                continue;
            }
            OrderItem it = c.item();
            if (it.status != ItemStatus.COOKING) {
                errors.add(label(c) + (it.status == ItemStatus.READY ? " đã hết giờ" : " không ở trạng thái đang làm"));
                continue;
            }
            int already = it.extendedMinutes == null ? 0 : it.extendedMinutes;
            if (already + minutes > Protocol.MAX_EXTEND_MINUTES) {
                errors.add(label(c) + " đã gia hạn " + already + " phút, tối đa " + Protocol.MAX_EXTEND_MINUTES + " phút");
                continue;
            }
            it.extendedMinutes = already + minutes;
            it.deadline = it.deadline + minutes * MINUTE_MS;
            changed.add(c);
        }
        return new BatchResult(changed, errors);
    }

    /** Xong sớm (F07): COOKING -> READY ngay. */
    public synchronized BatchResult finishItems(List<OrderItem> refs) {
        List<Change> changed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (OrderItem ref : refs) {
            Change c = locate(ref, errors);
            if (c == null) {
                continue;
            }
            if (c.item().status != ItemStatus.COOKING) {
                errors.add(label(c) + " không ở trạng thái đang làm");
                continue;
            }
            c.item().status = ItemStatus.READY;
            changed.add(c);
        }
        return new BatchResult(changed, errors);
    }

    /**
     * Xin ưu tiên (F14): đánh dấu món đang chờ làm để bếp làm trước.
     * Chỉ áp dụng cho món PENDING; mỗi bàn tối đa MAX_PRIORITY_PER_TABLE món ưu tiên đang chờ.
     * Hàng đợi ưu tiên xếp theo priority_at: bàn nào xin trước được làm trước.
     */
    public synchronized BatchResult setPriority(List<OrderItem> refs, long now) {
        List<Change> changed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (OrderItem ref : refs) {
            Change c = locate(ref, errors);
            if (c == null) {
                continue;
            }
            OrderItem it = c.item();
            if (it.status == ItemStatus.COOKING) {
                errors.add(label(c) + ": bếp đang làm món này rồi");
                continue;
            }
            if (it.status != ItemStatus.PENDING) {
                errors.add(label(c) + " không còn chờ làm");
                continue;
            }
            if (it.isPriority()) {
                errors.add(label(c) + " đã được ưu tiên");
                continue;
            }
            int has = countPendingPriority(c.order().table);
            if (has >= Protocol.MAX_PRIORITY_PER_TABLE) {
                errors.add("Bàn " + c.order().table + " đã có " + has + " món ưu tiên, tối đa "
                        + Protocol.MAX_PRIORITY_PER_TABLE + " món");
                continue;
            }
            it.priority = true;
            it.priorityAt = now;
            changed.add(c);
        }
        return new BatchResult(changed, errors);
    }

    /** Số món ưu tiên đang chờ làm của một bàn (tính trên mọi đơn của bàn đó). */
    private int countPendingPriority(int table) {
        int n = 0;
        for (Order o : orders.values()) {
            if (o.table == table) {
                for (OrderItem it : o.items) {
                    if (it.status == ItemStatus.PENDING && it.isPriority()) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** Hàng đợi ưu tiên: các món ưu tiên đang chờ làm, xếp theo thời điểm xin (sớm nhất trước). */
    public synchronized List<Change> priorityQueue() {
        List<Change> list = new ArrayList<>();
        for (Change c : itemsWithStatus(ItemStatus.PENDING)) {
            if (c.item().isPriority()) {
                list.add(c);
            }
        }
        list.sort((a, b) -> Long.compare(a.item().priorityAt, b.item().priorityAt));
        return list;
    }

    /** Báo hết món (F07): hủy mọi phần của món này còn đang chờ nhận làm; phần đang làm giữ nguyên. */
    public synchronized List<Change> cancelPendingByMenu(String menuId, String reason) {
        List<Change> changed = new ArrayList<>();
        for (Order o : orders.values()) {
            for (OrderItem it : o.items) {
                if (menuId.equals(it.menuId) && it.status == ItemStatus.PENDING) {
                    it.status = ItemStatus.CANCELLED;
                    it.reason = reason;
                    changed.add(new Change(o, it));
                }
            }
        }
        return changed;
    }

    /**
     * Quản lý hủy một món (F10). Chỉ hủy được khi bếp chưa nhận làm.
     *
     * @return thay đổi; ném IllegalStateException kèm lý do nếu không hủy được
     */
    public synchronized Change cancelItem(int orderId, int itemNo, String reason) {
        List<String> errors = new ArrayList<>();
        Change c = locate(OrderItem.ref(orderId, itemNo), errors);
        if (c == null) {
            throw new IllegalStateException(errors.get(0));
        }
        if (c.item().status != ItemStatus.PENDING) {
            throw new IllegalStateException(label(c) + (c.item().status == ItemStatus.COOKING
                    ? ": bếp đã nhận làm, không hủy được" : " đang ở trạng thái " + c.item().status));
        }
        c.item().status = ItemStatus.CANCELLED;
        c.item().reason = reason;
        return c;
    }

    /**
     * Bộ hẹn giờ gọi khi đến hạn. Chỉ chuyển READY nếu món vẫn đang làm và hạn giờ không đổi
     * (nếu masterchef đã gia hạn thì lần hẹn giờ cũ này bị bỏ qua, lần hẹn giờ mới sẽ xử lý).
     *
     * @return thay đổi, hoặc null nếu không có gì thay đổi
     */
    public synchronized Change markReadyIfDue(int orderId, int itemNo, long deadline) {
        Order o = orders.get(orderId);
        OrderItem it = o == null ? null : o.findItem(itemNo);
        if (it == null || it.status != ItemStatus.COOKING || it.deadline == null || it.deadline != deadline) {
            return null;
        }
        it.status = ItemStatus.READY;
        return new Change(o, it);
    }

    /** Phục vụ đã lấy món (hoặc quản lý xác nhận thủ công): READY -> SERVED. */
    public synchronized List<Change> markServed(int orderId, List<Integer> itemNos) {
        List<Change> changed = new ArrayList<>();
        Order o = orders.get(orderId);
        if (o == null) {
            return changed;
        }
        for (int no : itemNos) {
            OrderItem it = o.findItem(no);
            if (it != null && it.status == ItemStatus.READY) {
                it.status = ItemStatus.SERVED;
                changed.add(new Change(o, it));
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ đọc dữ liệu

    /** Các đơn chưa hoàn tất, dùng cho SNAPSHOT. */
    public synchronized List<Order> getOpenOrders() {
        List<Order> list = new ArrayList<>();
        for (Order o : orders.values()) {
            if (!o.isFinished()) {
                list.add(o);
            }
        }
        return list;
    }

    /** Bản sao sâu các đơn chưa hoàn tất, để giao diện đọc thoải mái mà không đụng dữ liệu thật. */
    public synchronized List<Order> snapshotCopy() {
        return Json.GSON.fromJson(Json.GSON.toJson(getOpenOrders()),
                new com.google.gson.reflect.TypeToken<List<Order>>() { }.getType());
    }

    /** Các món đang ở một trạng thái (dùng khi khởi động lại để đặt lại đồng hồ, gửi lại lệnh rung). */
    public synchronized List<Change> itemsWithStatus(ItemStatus status) {
        List<Change> list = new ArrayList<>();
        for (Order o : orders.values()) {
            for (OrderItem it : o.items) {
                if (it.status == status) {
                    list.add(new Change(o, it));
                }
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ lưu và khôi phục (F12)

    /** JSON của các đơn chưa hoàn tất, tạo trong lúc giữ khóa nên không bị đọc dở. */
    public synchronized String exportJson() {
        State s = new State();
        s.nextOrderId = nextOrderId;
        s.orders = getOpenOrders();
        s.handledRequests = new HashMap<>();
        for (Map.Entry<String, Integer> e : handledRequests.entrySet()) {
            Order o = orders.get(e.getValue());
            if (o != null && !o.isFinished()) {
                s.handledRequests.put(e.getKey(), e.getValue());
            }
        }
        // Dọn đơn đã hoàn tất khỏi bộ nhớ, chúng không cần nữa.
        orders.values().removeIf(Order::isFinished);
        return Json.GSON.toJson(s);
    }

    /** Nạp lại dữ liệu từ JSON. Ném JsonParseException nếu file hỏng. */
    public synchronized void importJson(String json) {
        State s = Json.GSON.fromJson(json, State.class);
        if (s == null) {
            throw new JsonParseException("File rỗng");
        }
        orders.clear();
        handledRequests.clear();
        if (s.orders != null) {
            for (Order o : s.orders) {
                orders.put(o.orderId, o);
            }
        }
        if (s.handledRequests != null) {
            handledRequests.putAll(s.handledRequests);
        }
        nextOrderId = Math.max(1, s.nextOrderId);
    }

    // ------------------------------------------------------------------

    private Change locate(OrderItem ref, List<String> errors) {
        if (ref == null || ref.orderId == null || ref.itemNo == null) {
            errors.add("Thiếu order_id hoặc item_no");
            return null;
        }
        Order o = orders.get(ref.orderId);
        OrderItem it = o == null ? null : o.findItem(ref.itemNo);
        if (it == null) {
            errors.add("Không có món " + ref.itemNo + " trong đơn " + ref.orderId);
            return null;
        }
        return new Change(o, it);
    }

    private static String label(Change c) {
        return "Món " + c.item().name + " (đơn " + c.order().orderId + ", bàn " + c.order().table + ")";
    }
}
