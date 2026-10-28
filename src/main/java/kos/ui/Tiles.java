package kos.ui;

import kos.common.Protocol;
import kos.common.Role;
import kos.common.Theme;

import javax.swing.JOptionPane;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.net.URI;

/**
 * Các ô chức năng dùng chung cho trang chủ trên máy chủ và trên máy bếp (F16).
 * Mỗi ô tự quyết định được mở hay bị khóa theo vai trò.
 */
public final class Tiles {

    public static final Color ORANGE = Theme.WARNING;
    public static final Color PURPLE = new Color(147, 51, 234);
    public static final Color TEAL = new Color(8, 145, 178);
    public static final Color PINK = new Color(219, 39, 119);

    private Tiles() {
    }

    public static HomeWindow.Tile kitchen(AuthApi.Session s, Runnable open) {
        String mode = s.user().role.kitchenMode();
        String desc = Protocol.MODE_MASTERCHEF.equals(mode)
                ? "Đủ quyền: nhận làm, gia hạn, xong sớm, chưa xong, báo hết món."
                : Protocol.MODE_VIEW.equals(mode)
                ? "Chỉ xem: món chờ, món đang làm, thời gian còn lại."
                : "Theo dõi và điều phối món trong bếp.";
        return new HomeWindow.Tile("Màn hình bếp", desc, "B", Theme.SUCCESS, mode != null,
                "cần vai trò Bếp trưởng, Bếp phụ hoặc Quản lý", open);
    }

    public static HomeWindow.Tile order(AuthApi.Session s, String host, Component parent) {
        String url = "http://" + host + ":" + Protocol.HTTP_PORT + "/order";
        return new HomeWindow.Tile("Gọi món", "iPad / điện thoại phục vụ mở:<br><b>" + host + ":"
                + Protocol.HTTP_PORT + "/order</b>", "G", Theme.PRIMARY, s.user().role.canServe(),
                "cần vai trò Phục vụ hoặc Quản lý", () -> browse(parent, url));
    }

    public static HomeWindow.Tile pager(AuthApi.Session s, String host, Component parent) {
        String url = "http://" + host + ":" + Protocol.HTTP_PORT + "/pager?id=1";
        return new HomeWindow.Tile("Thiết bị rung", "Điện thoại làm thiết bị rung số 1 mở:<br><b>" + host + ":"
                + Protocol.HTTP_PORT + "/pager?id=1</b>", "R", PINK, s.user().role.canServe(),
                "cần vai trò Phục vụ hoặc Quản lý", () -> browse(parent, url));
    }

    /** Quầy quản lý chạy cùng máy chủ nên trên máy khác chỉ hiện để biết, không mở được. */
    public static HomeWindow.Tile managerRemote(AuthApi.Session s, String host) {
        boolean manager = s.user().role == Role.QUAN_LY;
        return new HomeWindow.Tile("Quầy quản lý", "Đơn hàng, thực đơn, thống kê, tài khoản. "
                + "Chạy trên máy chủ " + host + ".", "Q", ORANGE, false,
                manager ? "mở trên máy chủ" : "cần vai trò Quản lý", () -> { });
    }

    /** Mở địa chỉ web bằng trình duyệt của máy; không mở được thì hiện địa chỉ để gõ tay. */
    public static void browse(Component parent, String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return;
            }
        } catch (Exception ignored) {
            // hiện địa chỉ bên dưới
        }
        JOptionPane.showMessageDialog(parent, "Mở trình duyệt và vào địa chỉ:\n" + url);
    }
}
