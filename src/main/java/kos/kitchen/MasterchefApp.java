package kos.kitchen;

import kos.common.ItemStatus;
import kos.common.Json;
import kos.common.Message;
import kos.common.OrderItem;
import kos.common.Protocol;

import java.util.List;
import java.util.Scanner;

/**
 * Masterchef Display. Bản khung chạy trên console để thử luồng bếp trước khi có giao diện.
 * Cách chạy: tham số 1 = IP máy chủ (mặc định localhost), tham số 2 = MASTERCHEF hoặc VIEW.
 * Lệnh gõ tắt:
 *   nhan  &lt;đơn&gt; &lt;món&gt;          nhận làm (START_ITEMS)
 *   giahan &lt;đơn&gt; &lt;món&gt; &lt;phút&gt;  gia hạn (EXTEND_ITEMS)
 *   xong  &lt;đơn&gt; &lt;món&gt;          xong sớm (FINISH_ITEMS)
 * hoặc gõ thẳng một dòng JSON.
 * TODO (Khoa): giao diện Swing - xem theo đơn, gộp theo món, nhận làm, đồng hồ, gia hạn,
 * xong sớm, báo hết món (F04 - F07). Dùng lại ServerConnection như ở đây.
 */
public class MasterchefApp {

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        String mode = args.length > 1 ? args[1] : Protocol.MODE_MASTERCHEF;
        String name = Protocol.MODE_MASTERCHEF.equals(mode) ? "MC" : "VIEW";

        final ServerConnection[] holder = new ServerConnection[1];
        ServerConnection conn = new ServerConnection(host, name, mode, new ServerConnection.Listener() {
            @Override
            public void onMessage(Message m) {
                if (Protocol.PONG.equals(m.command)) {
                    return;
                }
                if (Protocol.ITEM_UPDATE.equals(m.command)) {
                    String left = "";
                    if (m.status == ItemStatus.COOKING && m.deadline != null && holder[0] != null) {
                        long s = Math.max(0, (m.deadline - holder[0].serverNow()) / 1000);
                        left = String.format(" | còn %d:%02d", s / 60, s % 60);
                    }
                    System.out.println("<< Món " + m.orderId + "/" + m.itemNo + " -> " + m.status + left
                            + " (" + m.by + ")");
                    return;
                }
                System.out.println("<< " + Json.toLine(m));
            }

            @Override
            public void onStatus(boolean connected, String text) {
                System.out.println("[" + (connected ? "OK" : "--") + "] " + text);
            }
        });
        holder[0] = conn;
        conn.start();

        System.out.println("Lệnh: nhan <đơn> <món> | giahan <đơn> <món> <phút> | xong <đơn> <món> | exit");
        try (Scanner sc = new Scanner(System.in, "UTF-8")) {
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("exit")) {
                    break;
                }
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    Message m = line.startsWith("{") ? Json.parseMessage(line) : shortcut(line, conn);
                    System.out.println(conn.send(m) ? ">> đã gửi " + m.command : ">> chưa kết nối");
                } catch (RuntimeException e) {
                    System.out.println(">> lệnh không hợp lệ: " + e.getMessage());
                }
            }
        }
        conn.stop();
    }

    private static Message shortcut(String line, ServerConnection conn) {
        String[] p = line.split("\\s+");
        String cmd = switch (p[0].toLowerCase()) {
            case "nhan" -> Protocol.START_ITEMS;
            case "giahan" -> Protocol.EXTEND_ITEMS;
            case "xong" -> Protocol.FINISH_ITEMS;
            default -> throw new IllegalArgumentException("không biết lệnh " + p[0]);
        };
        Message m = new Message(cmd);
        m.reqId = conn.nextReqId();
        m.items = List.of(OrderItem.ref(Integer.parseInt(p[1]), Integer.parseInt(p[2])));
        if (Protocol.EXTEND_ITEMS.equals(cmd)) {
            m.minutes = Integer.parseInt(p[3]);
        }
        return m;
    }
}
