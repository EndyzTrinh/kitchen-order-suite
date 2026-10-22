package kos.server;

import kos.common.Message;
import kos.common.Protocol;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Gom các thành phần dùng chung của máy chủ để truyền cho ClientHandler, WebGateway, KitchenController
 * và giao diện quầy quản lý. log() vừa in ra console vừa đẩy sang thẻ Log của quầy quản lý.
 */
public class ServerContext {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_RECENT_LOG = 500;

    private final Deque<String> recentLog = new ArrayDeque<>();
    private final List<Consumer<String>> logListeners = new CopyOnWriteArrayList<>();

    private final MenuStore menu;
    private final OrderStore orders;
    private final ClientRegistry clients = new ClientRegistry();
    private final WebHub webHub;
    private final DeviceRegistry devices;
    private final PagerDispatcher pagers;
    private final KitchenController kitchen;

    public ServerContext(MenuStore menu, OrderStore orders) {
        this.menu = menu;
        this.orders = orders;
        this.webHub = new WebHub(this);
        this.devices = new DeviceRegistry(this);
        this.pagers = new PagerDispatcher(this);
        this.kitchen = new KitchenController(this);
    }

    public MenuStore menu() {
        return menu;
    }

    public OrderStore orders() {
        return orders;
    }

    /** Màn hình bếp kết nối TCP. */
    public ClientRegistry clients() {
        return clients;
    }

    /** Trang gọi món kết nối SSE. */
    public WebHub webHub() {
        return webHub;
    }

    /** Bảng thiết bị web (trang gọi món, thiết bị rung). */
    public DeviceRegistry devices() {
        return devices;
    }

    public PagerDispatcher pagers() {
        return pagers;
    }

    public KitchenController kitchen() {
        return kitchen;
    }

    /** Gửi ORDER_NEW, ITEM_UPDATE, MENU_UPDATE tới màn hình bếp (TCP) và trang gọi món (SSE). */
    public void broadcast(Message m) {
        clients.broadcast(m);
        webHub.broadcast(m);
    }

    /** Gọi sau khi sửa thực đơn: đẩy MENU_UPDATE tới mọi thiết bị. */
    public void menuChanged() {
        Message m = new Message(Protocol.MENU_UPDATE);
        m.menu = menu.getAll();
        m.serverTime = System.currentTimeMillis();
        broadcast(m);
    }

    /** Ghi các đơn chưa hoàn tất ra data/orders.json. Gọi sau mỗi thay đổi. */
    public void save() {
        try {
            DataFile.write(DataFile.ORDERS, orders.exportJson());
        } catch (IOException e) {
            log("CẢNH BÁO: không ghi được orders.json: " + e.getMessage());
        }
    }

    /**
     * Ghi một dòng log: in ra console và gửi tới các nơi đăng ký (thẻ Log của quầy quản lý).
     * Gọi được từ mọi luồng.
     */
    public void log(String text) {
        String line = "[" + LocalTime.now().format(TIME) + "] " + text;
        System.out.println(line);
        synchronized (recentLog) {
            recentLog.addLast(line);
            if (recentLog.size() > MAX_RECENT_LOG) {
                recentLog.removeFirst();
            }
        }
        for (Consumer<String> l : logListeners) {
            l.accept(line);
        }
    }

    /** Đăng ký nhận từng dòng log mới (Cường: thẻ Log của quầy quản lý). */
    public void addLogListener(Consumer<String> listener) {
        logListeners.add(listener);
    }

    /** Các dòng log gần nhất, để giao diện mở sau vẫn thấy log lúc khởi động. */
    public List<String> recentLog() {
        synchronized (recentLog) {
            return new ArrayList<>(recentLog);
        }
    }
}
