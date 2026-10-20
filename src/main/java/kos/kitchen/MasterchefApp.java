package kos.kitchen;

import kos.common.Json;
import kos.common.Message;
import kos.common.Protocol;

import java.util.Scanner;

/**
 * Masterchef Display. Bản khung chạy trên console: kết nối máy chủ và in mọi thông điệp nhận được.
 * Cách chạy: tham số 1 = IP máy chủ (mặc định localhost), tham số 2 = MASTERCHEF hoặc VIEW.
 * TODO (Khoa): giao diện Swing - xem theo đơn, gộp theo món, nhận làm, đồng hồ, gia hạn,
 * xong sớm, báo hết món (F04 - F07).
 */
public class MasterchefApp {

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        String mode = args.length > 1 ? args[1] : Protocol.MODE_MASTERCHEF;
        String name = Protocol.MODE_MASTERCHEF.equals(mode) ? "MC" : "VIEW";

        ServerConnection conn = new ServerConnection(host, name, mode, new ServerConnection.Listener() {
            @Override
            public void onMessage(Message m) {
                if (!Protocol.PONG.equals(m.command)) {
                    System.out.println("<< " + Json.toLine(m));
                }
            }

            @Override
            public void onStatus(boolean connected, String text) {
                System.out.println("[" + (connected ? "OK" : "--") + "] " + text);
            }
        });
        conn.start();

        System.out.println("Gõ một dòng JSON để gửi thử tới máy chủ, gõ 'exit' để thoát.");
        try (Scanner sc = new Scanner(System.in, "UTF-8")) {
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("exit")) {
                    break;
                }
                if (!line.isEmpty()) {
                    try {
                        System.out.println(conn.send(Json.parseMessage(line)) ? ">> đã gửi" : ">> chưa kết nối");
                    } catch (RuntimeException e) {
                        System.out.println(">> JSON không hợp lệ: " + e.getMessage());
                    }
                }
            }
        }
        conn.stop();
    }
}
