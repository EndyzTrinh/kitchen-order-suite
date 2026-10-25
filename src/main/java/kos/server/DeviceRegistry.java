package kos.server;

import kos.common.Protocol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bảng thiết bị web (trang gọi món, thiết bị rung) cho thẻ Thiết bị của quầy quản lý (F11).
 * Trang web gửi heartbeat mỗi 5 giây; quá 15 giây không có tin thì coi là mất kết nối.
 * Màn hình bếp (TCP) nằm ở ClientRegistry.
 * Thẻ Thiết bị của ManagerWindow đọc getAll() mỗi giây.
 */
public class DeviceRegistry {

    /** Một dòng trên bảng thiết bị. */
    public record Device(String type, String name, String ip, long lastSeen, boolean online) { }

    public static final String TYPE_ORDER = "Trang gọi món";
    public static final String TYPE_PAGER = "Thiết bị rung";

    private final ServerContext ctx;
    private final Map<String, Device> devices = new LinkedHashMap<>();

    public DeviceRegistry(ServerContext ctx) {
        this.ctx = ctx;
    }

    /** Ghi nhận thiết bị vừa gửi tin (heartbeat, kết nối SSE, gửi đơn...). */
    public synchronized void touch(String type, String name, String ip) {
        String key = type + "|" + name;
        Device old = devices.get(key);
        devices.put(key, new Device(type, name, ip, System.currentTimeMillis(), true));
        if (old == null) {
            ctx.log(type + " " + name + " xuất hiện [" + ip + "]");
        } else if (!old.online()) {
            ctx.log(type + " " + name + " kết nối lại [" + ip + "]");
        }
    }

    /** Gọi định kỳ: đánh dấu mất kết nối các thiết bị quá 15 giây không gửi tin. */
    public synchronized void check() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Device> e : devices.entrySet()) {
            Device d = e.getValue();
            if (d.online() && now - d.lastSeen() > Protocol.CONNECTION_TIMEOUT_MS) {
                e.setValue(new Device(d.type(), d.name(), d.ip(), d.lastSeen(), false));
                ctx.log("CẢNH BÁO: " + d.type() + " " + d.name() + " mất kết nối [" + d.ip() + "]");
            }
        }
    }

    public synchronized boolean isOnline(String type, String name) {
        Device d = devices.get(type + "|" + name);
        return d != null && d.online();
    }

    public synchronized List<Device> getAll() {
        List<Device> list = new ArrayList<>(devices.values());
        list.sort(Comparator.comparing(Device::type).thenComparing(Device::name));
        return list;
    }
}
