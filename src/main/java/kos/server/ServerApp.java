package kos.server;

import kos.common.Protocol;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
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

    public static void main(String[] args) throws Exception {
        boolean console = GraphicsEnvironment.isHeadless() || Arrays.asList(args).contains("--console");

        MenuStore menu = new MenuStore();
        OrderStore orders = new OrderStore(menu);
        ctx = new ServerContext(menu, orders);

        ManagerWindow[] window = new ManagerWindow[1];
        if (!console) {
            // Mở cửa sổ trước để thẻ Log nhận được mọi dòng từ lúc khởi động.
            SwingUtilities.invokeAndWait(() -> {
                window[0] = new ManagerWindow(ctx, ServerApp::shutdown);
                window[0].setVisible(true);
            });
        }

        try {
            menu.load();
        } catch (IOException e) {
            fail(console, "Không đọc được thực đơn: " + e.getMessage());
            return;
        }
        ctx.log("Đã nạp " + menu.getAll().size() + " món");
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
            SwingUtilities.invokeLater(() -> window[0].setAddresses(addresses));
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
            if (sc.nextLine().trim().equalsIgnoreCase("exit")) {
                shutdown();
                return;
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
