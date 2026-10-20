package kos.server;

import kos.common.Protocol;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Điểm khởi động máy chủ. Bản khung chạy trên console để thử mạng.
 * TODO (Cường): giao diện Swing của quầy quản lý (thẻ Đơn hàng, Thực đơn, Thiết bị, Log).
 */
public class ServerApp {

    public static void main(String[] args) throws IOException {
        MenuStore menu = new MenuStore();
        menu.load();
        OrderStore orders = new OrderStore(menu);
        ServerContext ctx = new ServerContext(menu, orders);

        new WebGateway(ctx).start();
        startTcp(ctx);

        ctx.log("Đã nạp " + menu.getAll().size() + " món");
        printAddresses(ctx);
    }

    private static void startTcp(ServerContext ctx) throws IOException {
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
        acceptor.start();
        ctx.log("Cổng TCP đang lắng nghe " + Protocol.TCP_PORT);
    }

    /** In các địa chỉ IPv4 của máy để biết cần mở địa chỉ nào trên iPad / iPhone. */
    private static void printAddresses(ServerContext ctx) throws SocketException {
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                continue;
            }
            for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                if (addr instanceof Inet4Address) {
                    String ip = addr.getHostAddress();
                    ctx.log(ni.getDisplayName() + ": gọi món http://" + ip + ":" + Protocol.HTTP_PORT
                            + "/order  |  thiết bị rung http://" + ip + ":" + Protocol.HTTP_PORT + "/pager?id=1");
                }
            }
        }
    }
}
