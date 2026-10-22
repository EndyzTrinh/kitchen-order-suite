package kos.server;

import kos.common.Protocol;
import kos.server.ui.AdminWindow;

import javax.swing.JOptionPane;
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
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Điểm khởi động máy chủ kiêm quầy quản lý.
 * Có màn hình thì mở cửa sổ quầy quản lý (kos.server.ui.AdminWindow: thẻ Đơn hàng, Thực đơn, Thiết bị, Log);
 * không có màn hình (chạy với -Djava.awt.headless=true) thì chạy trên console như cũ.
 * Tắt máy chủ: đóng cửa sổ quầy, hoặc gõ exit ở console.
 */
public class ServerApp {

    private static final AtomicBoolean SHUTTING_DOWN = new AtomicBoolean();

    public static void main(String[] args) throws IOException {
        MenuStore menu = new MenuStore();
        menu.load();
        OrderStore orders = new OrderStore(menu);
        ServerContext ctx = new ServerContext(menu, orders);
        ctx.log("Đã nạp " + menu.getAll().size() + " món");
        restoreOrders(ctx);

        WebGateway web = new WebGateway(ctx);
        ServerSocket tcp;
        try {
            web.start();
            tcp = startTcp(ctx);
        } catch (BindException e) {
            ctx.log("LỖI: cổng " + Protocol.HTTP_PORT + " hoặc " + Protocol.TCP_PORT + " đang bị chiếm.");
            ctx.log("Thường là ServerApp lần trước chưa tắt hẳn. Mở cmd và gõ:");
            ctx.log("   netstat -ano | findstr \":" + Protocol.HTTP_PORT + " :" + Protocol.TCP_PORT + "\"");
            ctx.log("rồi tắt chương trình ở dòng LISTENING bằng: taskkill /PID <số cuối dòng> /F");
            web.stop();
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null, "Cổng " + Protocol.HTTP_PORT + " hoặc " + Protocol.TCP_PORT
                        + " đang bị chiếm.\nThường là ServerApp lần trước chưa tắt hẳn: tắt nó rồi chạy lại.",
                        "Không khởi động được máy chủ", JOptionPane.ERROR_MESSAGE);
            }
            System.exit(1);
            return;
        }
        ctx.pagers().start();
        ctx.kitchen().resume();
        List<String> addresses = printAddresses(ctx);
        if (GraphicsEnvironment.isHeadless()) {
            ctx.log("Gõ exit rồi Enter (ở dòng nhập phía dưới Output) để tắt máy chủ đúng cách.");
        } else {
            AdminWindow.open(ctx, addresses, () -> shutdown(ctx, web, tcp));
            ctx.log("Đã mở cửa sổ quầy quản lý. Tắt máy chủ: đóng cửa sổ quầy, hoặc gõ exit ở đây.");
        }

        waitForExit(ctx, web, tcp);
    }

    /**
     * Chờ lệnh exit trên console rồi tắt sạch: lưu dữ liệu, đóng cổng, dừng các luồng.
     * Dùng cách này thay cho nút dừng của NetBeans, vì nút đó đôi khi để lại tiến trình Java chạy ngầm.
     */
    private static void waitForExit(ServerContext ctx, WebGateway web, ServerSocket tcp) {
        boolean exitRequested = false;
        Scanner sc = new Scanner(System.in, StandardCharsets.UTF_8);
        while (!exitRequested && sc.hasNextLine()) {
            exitRequested = sc.nextLine().trim().equalsIgnoreCase("exit");
        }
        if (!exitRequested) {
            // Không có bàn phím (ví dụ chạy nền): giữ máy chủ chạy mãi.
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        shutdown(ctx, web, tcp);
    }

    /** Tắt sạch máy chủ. Gọi từ console (exit) hoặc khi đóng cửa sổ quầy; chỉ chạy một lần. */
    private static void shutdown(ServerContext ctx, WebGateway web, ServerSocket tcp) {
        if (!SHUTTING_DOWN.compareAndSet(false, true)) {
            return;
        }
        ctx.log("Đang tắt máy chủ...");
        ctx.save();
        ctx.kitchen().shutdown();
        ctx.pagers().shutdown();
        ctx.webHub().shutdown();
        web.stop();
        try {
            tcp.close();
        } catch (IOException ignored) {
            // bỏ qua
        }
        ctx.log("Đã tắt.");
        System.exit(0);
    }

    /** Nạp lại các đơn chưa hoàn tất từ data/orders.json (F12). File hỏng thì đổi tên và chạy với dữ liệu rỗng. */
    private static void restoreOrders(ServerContext ctx) {
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

    private static ServerSocket startTcp(ServerContext ctx) throws IOException {
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

    /** In các địa chỉ IPv4 của máy để biết cần mở địa chỉ nào trên iPad / iPhone; trả về danh sách IP. */
    private static List<String> printAddresses(ServerContext ctx) throws SocketException {
        List<String> ips = new ArrayList<>();
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                continue;
            }
            for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                if (addr instanceof Inet4Address) {
                    String ip = addr.getHostAddress();
                    ips.add(ip);
                    ctx.log(ni.getDisplayName() + ": gọi món http://" + ip + ":" + Protocol.HTTP_PORT
                            + "/order  |  thiết bị rung http://" + ip + ":" + Protocol.HTTP_PORT + "/pager?id=1");
                }
            }
        }
        return ips;
    }
}
