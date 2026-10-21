package kos.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bộ hẹn giờ món ăn (F05, F06). Mỗi món đang làm có một lịch hẹn đến đúng hạn giờ.
 * Gia hạn thì hủy lịch cũ và đặt lịch mới. Đến hạn thì gọi DeadlineHandler trên luồng "kitchen-timer".
 * Hạn giờ do máy chủ quyết định; đồng hồ trên các màn hình chỉ để hiển thị.
 */
public class KitchenTimer {

    /** Nơi xử lý khi một món đến hạn. */
    public interface DeadlineHandler {
        void onDeadline(int orderId, int itemNo, long deadline);
    }

    private final DeadlineHandler handler;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kitchen-timer");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public KitchenTimer(DeadlineHandler handler) {
        this.handler = handler;
    }

    /** Đặt (hoặc đặt lại) lịch cho một món. */
    public void schedule(int orderId, int itemNo, long deadline) {
        cancel(orderId, itemNo);
        long delay = Math.max(0, deadline - System.currentTimeMillis());
        ScheduledFuture<?> f = exec.schedule(() -> {
            try {
                handler.onDeadline(orderId, itemNo, deadline);
            } catch (RuntimeException e) {
                // lỗi trong một món không được làm chết luồng hẹn giờ của các món khác
                e.printStackTrace();
            }
        }, delay, TimeUnit.MILLISECONDS);
        futures.put(key(orderId, itemNo), f);
    }

    public void cancel(int orderId, int itemNo) {
        ScheduledFuture<?> old = futures.remove(key(orderId, itemNo));
        if (old != null) {
            old.cancel(false);
        }
    }

    public void shutdown() {
        exec.shutdownNow();
    }

    private static String key(int orderId, int itemNo) {
        return orderId + ":" + itemNo;
    }
}
