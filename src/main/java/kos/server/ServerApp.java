package kos.server;

import kos.common.Protocol;
import kos.common.Role;
import kos.common.Theme;
import kos.kitchen.MasterchefApp;
import kos.ui.AuthApi;
import kos.ui.HomeWindow;
import kos.ui.Tiles;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Điểm khởi động máy chủ kiêm quầy quản lý.
 * Mặc định mở cửa sổ quầy quản lý (ManagerWindow). Chạy với tham số --console (hoặc trên máy không có
 * màn hình) thì chỉ dùng cửa sổ Output, tắt bằng lệnh exit.
 */
public class ServerApp {

    private static ServerContext ctx;
    private static WebGateway web;
    private static ServerSocket tcp;
    private static boolean stopped;
    private static HomeWindow home;

    public static void main(String[] args) throws Exception {
        boolean console = GraphicsEnvironment.isHeadless() || Arrays.asList(args).contains("--console");

        MenuStore menu = new MenuStore();
        OrderStore orders = new OrderStore(menu);
        ctx = new ServerContext(menu, orders);

        ManagerWindow[] window = new ManagerWindow[1];
        if (!console) {
            Theme.install();
            // Tạo cửa sổ quầy quản lý trước (chưa hiện) để thẻ Log nhận được mọi dòng từ lúc khởi động.
            // F16: chỉ Quản lý mở được cửa sổ này, từ trang chủ hệ thống sau khi đăng nhập.
            SwingUtilities.invokeAndWait(() -> window[0] = new ManagerWindow(ctx, ServerApp::shutdown,
                    () -> home == null || home.session() == null ? "?" : home.session().user().username));
        }

        try {
            menu.load();
        } catch (IOException e) {
            fail(console, "Không đọc được thực đơn: " + e.getMessage());
            return;
        }
        ctx.log("Đã nạp " + menu.getAll().size() + " món");
        ctx.history().load();
        ctx.accounts().load();
        restoreOrders();

        web = new WebGateway(ctx);
        try {
            web.start();
            tcp = startTcp();
        } catch (BindException e) {
            web.stop();
            ctx.log("LỖI: cổng " + Protocol.HTTP_PORT + " hoặc " + Protocol.TCP_PORT + " đang bị chiếm.");
            ctx.log("Thường là ServerApp lần trước chưa tắt hẳn. Mở cmd và gõ:");
            ctx.log("   netstat -ano | findstr \":" + Protocol.HTTP_PORT + " :" + Protocol.TCP_PORT + "\"");
            ctx.log("rồi tắt chương trình ở dòng LISTENING bằng: taskkill /PID <số cuối dòng> /F");
            fail(console, "Cổng " + Protocol.HTTP_PORT + " hoặc " + Protocol.TCP_PORT + " đang bị chiếm.\n"
                    + "Có thể ServerApp lần trước chưa tắt hẳn. Xem hướng dẫn trong thẻ Log / cửa sổ Output.");
            return;
        }
        ctx.pagers().start();
        ctx.kitchen().resume();

        List<String> addresses = addresses();
        for (String a : addresses) {
            ctx.log(a);
        }
        if (window[0] != null) {
            ManagerWindow w = window[0];
            SwingUtilities.invokeLater(() -> {
                w.setAddresses(addresses);
                openHome(w);
            });
        }
        ctx.log("Tắt máy chủ: bấm nút \"Tắt máy chủ\" trên cửa sổ, hoặc gõ exit rồi Enter trong Output.");

        if (console) {
            waitForExit();
        } else {
            Thread t = new Thread(ServerApp::waitForExit, "console-exit");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Giao diện hệ thống chung trên máy chủ (F16): đăng nhập, rồi các ô chức năng theo vai trò. */
    private static void openHome(ManagerWindow manager) {
        String ip = firstIp();
        home = new HomeWindow("Kitchen Order Suite - Máy chủ", new LocalAuth(ctx.accounts()), new HomeWindow.Host() {
            @Override
            public List<HomeWindow.Tile> tiles(AuthApi.Session s) {
                boolean m = s.user().role.canManage();
                int pending = ctx.accounts().pendingCount();
                String lock = "cần vai trò Quản lý";
                return List.of(
                        new HomeWindow.Tile("Quầy quản lý", "Đơn đang mở, hủy món, đẩy ưu tiên, thiết bị, nhật ký máy chủ.", "Q", Tiles.ORANGE, m, lock,
                                () -> manager.showTab("Đơn hàng")),
                        Tiles.kitchen(s, () -> MasterchefApp.openFor("localhost", s, home)),
                        Tiles.order(s, ip, home),
                        Tiles.pager(s, ip, home),
                        new HomeWindow.Tile("Thực đơn", "Thêm, sửa món, thời gian chuẩn, tạm hết món.", "T", Tiles.TEAL, m, lock,
                                () -> manager.showTab("Thực đơn")),
                        new HomeWindow.Tile("Thống kê", "Chuẩn / thực tế theo món, khung giờ, người làm. Xuất CSV.", "S", Tiles.PURPLE, m, lock,
                                () -> manager.showTab("Thống kê")),
                        new HomeWindow.Tile("Tài khoản", (m && pending > 0 ? "<b style='color:#ea580c'>"
                                + pending + " tài khoản chờ duyệt.</b><br>" : "") + "Duyệt, đổi vai trò, khóa, đặt lại mật khẩu.", "P", Theme.DANGER, m, lock,
                                () -> manager.showTab("Tài khoản")),
                        new HomeWindow.Tile("Tắt máy chủ", "Lưu đơn đang làm rồi tắt. Mở lại sẽ khôi phục.",
                                "X", new Color(71, 85, 105), m, lock, manager::confirmShutdown));
            }

            @Override
            public void onLogout(AuthApi.Session s) {
                manager.setVisible(false);
                MasterchefApp.closeAll();
            }

            @Override
            public void onClose(AuthApi.Session s) {
                if (s != null && s.user().role == Role.QUAN_LY) {
                    manager.confirmShutdown();
                } else {
                    JOptionPane.showMessageDialog(home, "Máy chủ đang chạy cho bếp và phục vụ.\n"
                            + "Chỉ Quản lý được tắt máy chủ: đăng nhập tài khoản Quản lý rồi bấm \"Tắt máy chủ\".",
                            "Không tắt được", JOptionPane.INFORMATION_MESSAGE);
                }
            }
        });
        home.setVisible(true);
    }

    /** IPv4 đầu tiên của máy (để ghi địa chỉ cho iPad / điện thoại), không có thì localhost. */
    static String firstIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
            // dùng localhost
        }
        return "localhost";
    }

    private static void fail(boolean console, String text) {
        if (!console) {
            JOptionPane.showMessageDialog(null, text, "Không khởi động được máy chủ", JOptionPane.ERROR_MESSAGE);
        }
        System.exit(1);
    }

    /** Chờ lệnh exit trên console. Không có bàn phím (chạy nền) thì giữ máy chủ chạy mãi. */
    private static void waitForExit() {
        Scanner sc = new Scanner(System.in, StandardCharsets.UTF_8);
        while (sc.hasNextLine()) {
            String line = sc.nextLine().trim();
            if (line.equalsIgnoreCase("exit")) {
                shutdown();
                return;
            }
            // taoql <tên đăng nhập> <mật khẩu> <họ tên>: tạo tài khoản Quản lý đầu tiên khi chạy không giao diện
            String[] p = line.split("\\s+", 4);
            if (p[0].equalsIgnoreCase("taoql")) {
                try {
                    if (p.length < 4) {
                        throw new AccountStore.AuthError("Cú pháp: taoql <tên đăng nhập> <mật khẩu> <họ tên>");
                    }
                    if (!ctx.accounts().isEmpty()) {
                        throw new AccountStore.AuthError("Đã có tài khoản; thêm tài khoản mới trong thẻ Tài khoản");
                    }
                    ctx.log(ctx.accounts().register(p[1], p[3], p[2], null, "console", true));
                } catch (AccountStore.AuthError e) {
                    ctx.log("Lỗi: " + e.getMessage());
                }
            }
        }
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Tắt sạch: lưu dữ liệu, đóng cổng, dừng các luồng rồi thoát.
     * Dùng thay cho nút dừng của NetBeans, vì nút đó đôi khi để lại tiến trình Java chạy ngầm.
     */
    public static synchronized void shutdown() {
        if (stopped) {
            return;
        }
        stopped = true;
        ctx.log("Đang tắt máy chủ...");
        ctx.save();
        ctx.kitchen().shutdown();
        ctx.pagers().shutdown();
        ctx.webHub().shutdown();
        if (web != null) {
            web.stop();
        }
        if (tcp != null) {
            try {
                tcp.close();
            } catch (IOException ignored) {
                // bỏ qua
            }
        }
        ctx.log("Đã tắt.");
        System.exit(0);
    }

    /** Nạp lại các đơn chưa hoàn tất từ data/orders.json (F12). File hỏng thì đổi tên và chạy với dữ liệu rỗng. */
    private static void restoreOrders() {
        try {
            String json = DataFile.read(DataFile.ORDERS);
            if (json == null) {
                return;
            }
            try {
                ctx.orders().importJson(json);
                ctx.log("Đã khôi phục " + ctx.orders().getOpenOrders().size() + " đơn chưa hoàn tất");
            } catch (RuntimeException e) {
                java.nio.file.Path bad = DataFile.quarantine(DataFile.ORDERS);
                ctx.log("CẢNH BÁO: orders.json bị hỏng, đã đổi tên thành " + bad.getFileName()
                        + ", khởi động với danh sách đơn rỗng");
            }
        } catch (IOException e) {
            ctx.log("CẢNH BÁO: không đọc được orders.json: " + e.getMessage());
        }
    }

    private static ServerSocket startTcp() throws IOException {
        ServerSocket serverSocket = new ServerSocket(Protocol.TCP_PORT);
        ExecutorService pool = Executors.newCachedThreadPool();
        Thread acceptor = new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket s = serverSocket.accept();
                    pool.execute(new ClientHandler(s, ctx));
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        ctx.log("Lỗi accept(): " + e.getMessage());
                    }
                }
            }
        }, "tcp-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        ctx.log("Cổng TCP đang lắng nghe " + Protocol.TCP_PORT);
        return serverSocket;
    }

    /** Các địa chỉ IPv4 của máy, để biết cần mở địa chỉ nào trên iPad / iPhone. */
    static List<String> addresses() throws SocketException {
        List<String> list = new ArrayList<>();
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                continue;
            }
            for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                if (addr instanceof Inet4Address) {
                    String ip = addr.getHostAddress();
                    list.add(ni.getDisplayName() + ": gọi món http://" + ip + ":" + Protocol.HTTP_PORT
                            + "/order  |  thiết bị rung http://" + ip + ":" + Protocol.HTTP_PORT + "/pager?id=1");
                }
            }
        }
        return list;
    }
}
