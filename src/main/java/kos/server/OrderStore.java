package kos.server;

import kos.common.ItemStatus;
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
 * vì được gọi từ nhiều luồng (ClientHandler, WebGateway, KitchenTimer).
 * Bản khung hiện mới có addOrder. TODO (Nam): startItems, extendItems, finishItems, soldOut,
 * markReady, cancelItem, markServed, lưu file (F05 - F07, F10, F12).
 */
public class OrderStore {

    /** Kết quả của addOrder: đơn đã tạo hoặc thông báo lỗi. */
    public record AddResult(Order order, String error, boolean duplicate) { }

    private final MenuStore menu;
    private final Map<Integer, Order> orders = new LinkedHashMap<>();
    /** req_id đã xử lý -> order_id, để gửi lại không tạo đơn trùng. */
    private final Map<String, Integer> handledRequests = new HashMap<>();
    private int nextOrderId = 1;

    public OrderStore(MenuStore menu) {
        this.menu = menu;
    }

    public synchronized AddResult addOrder(String reqId, String waiter, Integer table, Integer pagerId,
                                           List<OrderItem> requested) {
        if (reqId == null || reqId.isBlank()) {
            return new AddResult(null, "Thiếu req_id", false);
        }
        Integer existing = handledRequests.get(reqId);
        if (existing != null) {
            return new AddResult(orders.get(existing), null, true);
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

        Order order = new Order();
        order.table = table;
        order.pagerId = pagerId;
        order.waiter = waiter;
        order.createdAt = System.currentTimeMillis();
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
            order.items.add(it);
        }
        order.orderId = nextOrderId++;
        orders.put(order.orderId, order);
        handledRequests.put(reqId, order.orderId);
        return new AddResult(order, null, false);
    }

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
}
