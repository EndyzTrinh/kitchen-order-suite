package kos.server;

import kos.common.Message;
import kos.common.Protocol;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Danh sách các màn hình bếp đang kết nối TCP và việc phát tin tới chúng.
 * Thiết bị web (trang gọi món, thiết bị rung) nằm ở DeviceRegistry.
 */
public class ClientRegistry {

    private final List<ClientHandler> clients = new CopyOnWriteArrayList<>();

    public void add(ClientHandler c) {
        clients.add(c);
    }

    public void remove(ClientHandler c) {
        clients.remove(c);
    }

    /** Đã có màn hình MASTERCHEF nào khác (không tính chính c) đang kết nối chưa. */
    public synchronized boolean hasOtherMasterchef(ClientHandler c) {
        for (ClientHandler other : clients) {
            if (other != c && Protocol.MODE_MASTERCHEF.equals(other.getMode())) {
                return true;
            }
        }
        return false;
    }

    public int kitchenCount() {
        int n = 0;
        for (ClientHandler c : clients) {
            if (c.isRegistered()) {
                n++;
            }
        }
        return n;
    }

    /** Gửi một thông điệp tới mọi màn hình bếp đã HELLO xong. */
    public void broadcast(Message m) {
        for (ClientHandler c : clients) {
            if (c.isRegistered()) {
                c.send(m);
            }
        }
    }

    /** Ngắt mọi màn hình bếp đang dùng tài khoản username (khi bị khóa, đổi vai trò...). */
    public void kick(String username, String reason) {
        for (ClientHandler c : clients) {
            if (username.equals(c.getUsername())) {
                c.kick(reason);
            }
        }
    }

    public List<ClientHandler> getAll() {
        return List.copyOf(clients);
    }
}
