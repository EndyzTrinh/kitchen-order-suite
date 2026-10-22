package kos.server.ui;

import com.google.gson.reflect.TypeToken;
import kos.common.ItemStatus;
import kos.common.Json;
import kos.common.Order;
import kos.server.ServerContext;

import javax.swing.JOptionPane;
import javax.swing.JTable;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.List;

/** Tiện ích dùng chung cho các thẻ của quầy quản lý. */
final class Ui {

    static final Color WHITE = Color.WHITE;
    static final Color BLUE = new Color(0xDDEBFA);
    static final Color YELLOW = new Color(0xFFF1BF);
    static final Color RED = new Color(0xF6C6C0);
    static final Color GREY = new Color(0xEDEDED);
    static final Color GREEN_TEXT = new Color(0x1E7E34);
    static final Color RED_TEXT = new Color(0xB03A2E);

    private Ui() {
    }

    /** Một thao tác có thể ném lỗi; lỗi được hiện thành hộp thoại. */
    interface Action {
        void run() throws Exception;
    }

    /**
     * Bản sao sâu các đơn chưa hoàn tất. Giữ khóa của OrderStore trong lúc chuyển sang JSON
     * để không đọc phải đơn đang bị luồng khác (bếp, hẹn giờ, trang web) sửa dở.
     */
    static List<Order> copyOpenOrders(ServerContext ctx) {
        String json;
        synchronized (ctx.orders()) {
            json = Json.GSON.toJson(ctx.orders().getOpenOrders());
        }
        return Json.GSON.fromJson(json, new TypeToken<List<Order>>() { }.getType());
    }

    static String label(ItemStatus s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case PENDING -> "Chờ nhận làm";
            case COOKING -> "Đang làm";
            case READY -> "Chờ lấy";
            case SERVED -> "Đã lấy";
            case CANCELLED -> "Đã hủy";
        };
    }

    /** Số mili giây -> "mm:ss" (âm thì coi là 0). */
    static String mmss(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    static void run(Component parent, Action a) {
        try {
            a.run();
        } catch (Exception e) {
            error(parent, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    static void error(Component parent, String text) {
        JOptionPane.showMessageDialog(parent, text, "Không thực hiện được", JOptionPane.WARNING_MESSAGE);
    }

    static void style(JTable t) {
        t.setRowHeight(28);
        t.setFont(t.getFont().deriveFont(14f));
        t.getTableHeader().setFont(t.getTableHeader().getFont().deriveFont(Font.BOLD, 13f));
        t.setFillsViewportHeight(true);
        t.getTableHeader().setReorderingAllowed(false);
    }
}
