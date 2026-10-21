package kos.server;

import kos.common.ItemStatus;
import kos.common.MenuItem;
import kos.common.Message;
import kos.common.Protocol;

import java.io.IOException;
import java.util.List;

/**
 * Điều phối các lệnh của masterchef, quản lý và sự kiện hết giờ:
 * gọi OrderStore để đổi trạng thái, đặt / hủy lịch hẹn giờ, phát ITEM_UPDATE,
 * giao món đã xong cho PagerDispatcher và lưu dữ liệu sau mỗi thay đổi.
 */
public class KitchenController implements KitchenTimer.DeadlineHandler {

    private final ServerContext ctx;
    private final KitchenTimer timer;

    public KitchenController(ServerContext ctx) {
        this.ctx = ctx;
        this.timer = new KitchenTimer(this);
    }

    /**
     * Xử lý START_ITEMS, EXTEND_ITEMS, FINISH_ITEMS, SOLD_OUT. Người gọi đã kiểm tra quyền MASTERCHEF.
     *
     * @return ERROR để gửi lại cho masterchef, hoặc null nếu mọi món đều thành công
     */
    public Message handle(Message req, String by) {
        if (Protocol.SOLD_OUT.equals(req.command)) {
            return soldOut(req, by);
        }
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
        if (!r.changed().isEmpty()) {
            ctx.save();
        }
        return r.errors().isEmpty() ? null : Message.error(req.reqId, String.join("; ", r.errors()));
    }

    /** SOLD_OUT: đánh dấu tạm hết trong thực đơn và hủy các phần chưa làm. */
    private Message soldOut(Message req, String by) {
        MenuItem m = req.menuId == null ? null : ctx.menu().find(req.menuId);
        if (m == null) {
            return Message.error(req.reqId, "Không có món " + req.menuId + " trong thực đơn");
        }
        try {
            ctx.menu().setSoldOut(m.menuId, true);
        } catch (IOException e) {
            ctx.log("Không ghi được menu.json: " + e.getMessage());
        }
        String reason = req.reason == null || req.reason.isBlank() ? "Hết món" : req.reason;
        List<OrderStore.Change> cancelled = ctx.orders().cancelPendingByMenu(m.menuId, reason);
        for (OrderStore.Change c : cancelled) {
            broadcast(c, by);
        }
        ctx.log(by + " báo hết " + m.name + ": hủy " + cancelled.size() + " phần chưa làm");
        ctx.menuChanged();
        ctx.save();
        return null;
    }

    /**
     * Quản lý hủy một món tại quầy (F10). Chỉ hủy được món bếp chưa nhận làm.
     *
     * @return thông báo lỗi, hoặc null nếu đã hủy
     */
    public String cancelItem(int orderId, int itemNo, String reason, String by) {
        OrderStore.Change c;
        try {
            c = ctx.orders().cancelItem(orderId, itemNo, reason == null || reason.isBlank() ? "Khách hủy" : reason);
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
        broadcast(c, by);
        ctx.log(by + " hủy " + c.item().name + " (đơn " + orderId + ", bàn " + c.order().table + ")");
        ctx.save();
        return null;
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
        ctx.save();
    }

    /** Thiết bị rung báo phục vụ đã lấy món, hoặc quản lý xác nhận thủ công. */
    public void onPickedUp(int orderId, List<Integer> itemNos, String by) {
        List<OrderStore.Change> changes = ctx.orders().markServed(orderId, itemNos);
        for (OrderStore.Change c : changes) {
            broadcast(c, by);
        }
        if (!changes.isEmpty()) {
            ctx.save();
        }
    }

    /**
     * Sau khi nạp lại dữ liệu từ file: đặt lại đồng hồ cho món đang làm
     * (món đã quá hạn sẽ chuyển READY ngay) và gửi lại lệnh rung cho món đang chờ lấy.
     */
    public void resume() {
        List<OrderStore.Change> cooking = ctx.orders().itemsWithStatus(ItemStatus.COOKING);
        for (OrderStore.Change c : cooking) {
            timer.schedule(c.order().orderId, c.item().itemNo, c.item().deadline);
        }
        List<OrderStore.Change> ready = ctx.orders().itemsWithStatus(ItemStatus.READY);
        for (OrderStore.Change c : ready) {
            ctx.pagers().notifyReady(c.order(), c.item());
        }
        if (!cooking.isEmpty() || !ready.isEmpty()) {
            ctx.log("Khôi phục: " + cooking.size() + " món đang làm, " + ready.size() + " món chờ lấy");
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
        ctx.broadcast(m);
    }
}
