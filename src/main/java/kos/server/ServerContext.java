package kos.server;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Gom các thành phần dùng chung của máy chủ để truyền cho ClientHandler và WebGateway.
 * TODO (Cường): chuyển log() sang thẻ Log của giao diện quầy quản lý.
 */
public class ServerContext {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final MenuStore menu;
    private final OrderStore orders;
    private final ClientRegistry clients = new ClientRegistry();

    public ServerContext(MenuStore menu, OrderStore orders) {
        this.menu = menu;
        this.orders = orders;
    }

    public MenuStore menu() {
        return menu;
    }

    public OrderStore orders() {
        return orders;
    }

    public ClientRegistry clients() {
        return clients;
    }

    public void log(String text) {
        System.out.println("[" + LocalTime.now().format(TIME) + "] " + text);
    }
}
