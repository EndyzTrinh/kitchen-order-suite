package kos.server;

import kos.common.Message;
import kos.common.Protocol;

import java.util.List;

/**
 * Điều phối các lệnh của masterchef và sự kiện hết giờ:
 * gọi OrderStore để đổi trạng thái, đặt / hủy lịch hẹn giờ, phát ITEM_UPDATE,
 * và giao món đã xong cho PagerDispatcher để kích hoạt thiết bị rung.
 */
public class KitchenController implements KitchenTimer.DeadlineHandler {

    private final ServerContext ctx;
    private final KitchenTimer timer;

    public KitchenController(ServerContext ctx) {
        this.ctx = ctx;
        this.timer = new KitchenTimer(this);
    }

    /**
     * Xử lý START_ITEMS, EXTEND_ITEMS, FINISH_ITEMS. Người gọi đã kiểm tra quyền MASTERCHEF.
     *
     * @return ERROR để gửi lại cho masterchef, hoặc null nếu mọi món đều thành công
     */
    public Message handle(Message req, String by) {
        if (req.items == null || req.items.isEmpty()) {
            return Message.error(req.reqId, "Chưa chọn món");
        }
        OrderStore.BatchResult r;
        switch (req.command) {
            case Protocol.START_ITEMS -> {
                r = ctx.orders().startItems(req.items, System.currentTimeMillis());
                for (OrderStore.Change c : r.changed()) {
                    timer.schedule(c.order().orderId, c.item().itemNo, c.item().deadline);
                    broadcast(c, by);
                }
                if (!r.changed().isEmpty()) {
                    ctx.log(by + " nhận làm " + r.changed().size() + " món");
                }
            }
            case Protocol.EXTEND_ITEMS -> {
                int minutes = req.minutes == null ? 0 : req.minutes;
                if (minutes < 1 || minutes > Protocol.MAX_EXTEND_MINUTES) {
                    return Message.error(req.reqId, "Số phút gia hạn không hợp lệ");
                }
                r = ctx.orders().extendItems(req.items, minutes);
                for (OrderStore.Change c : r.changed()) {
                    timer.schedule(c.order().orderId, c.item().itemNo, c.item().deadline);
                    broadcast(c, by);
                    ctx.log(by + " gia hạn +" + minutes + " phút: " + c.item().name
                            + " (đơn " + c.order().orderId + ", bàn " + c.order().table + ")");
                }
            }
            case Protocol.FINISH_ITEMS -> {
                r = ctx.orders().finishItems(req.items);
                for (OrderStore.Change c : r.changed()) {
                    timer.cancel(c.order().orderId, c.item().itemNo);
                    broadcast(c, by);
                    ctx.pagers().notifyReady(c.order(), c.item());
                }
            }
            default -> {
                return Message.error(req.reqId, "Chưa hỗ trợ lệnh " + req.command);
            }
        }
        return r.errors().isEmpty() ? null : Message.error(req.reqId, String.join("; ", r.errors()));
    }

    /** Bộ hẹn giờ gọi khi đến hạn (chạy trên luồng kitchen-timer). */
    @Override
    public void onDeadline(int orderId, int itemNo, long deadline) {
        OrderStore.Change c = ctx.orders().markReadyIfDue(orderId, itemNo, deadline);
        if (c == null) {
            return; // món đã được gia hạn, xong sớm hoặc hủy
        }
        ctx.log("Hết giờ: " + c.item().name + " (đơn " + orderId + ", bàn " + c.order().table + ")");
        broadcast(c, "Hết giờ");
        ctx.pagers().notifyReady(c.order(), c.item());
    }

    /** Thiết bị rung báo phục vụ đã lấy món. */
    public void onPickedUp(int orderId, List<Integer> itemNos, String by) {
        for (OrderStore.Change c : ctx.orders().markServed(orderId, itemNos)) {
            broadcast(c, by);
        }
    }

    public void shutdown() {
        timer.shutdown();
    }

    private void broadcast(OrderStore.Change c, String by) {
        Message m = new Message(Protocol.ITEM_UPDATE);
        m.orderId = c.order().orderId;
        m.itemNo = c.item().itemNo;
        m.status = c.item().status;
        m.startedAt = c.item().startedAt;
        m.deadline = c.item().deadline;
        m.extendedMinutes = c.item().extendedMinutes;
        m.reason = c.item().reason;
        m.by = by;
        m.serverTime = System.currentTimeMillis();
        ctx.clients().broadcast(m);
    }
}
