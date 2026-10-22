import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chương trình thử cho các kịch bản kiểm thử cần "phá" máy chủ (TC17, TC19, TC21).
 * Không cần thư viện ngoài, chạy thẳng bằng Java 21 từ thư mục dự án:
 *
 *   java test-data/TestClient.java <IP máy chủ> dup        TC19: gửi 2 lần cùng req_id -> cùng một order_id
 *   java test-data/TestClient.java <IP máy chủ> bad-http   TC21: POST /api/order các đơn sai -> ERROR
 *   java test-data/TestClient.java <IP máy chủ> bad-tcp    TC21: gửi dòng rác vào cổng 6000 -> ERROR, máy chủ vẫn chạy
 *   java test-data/TestClient.java <IP máy chủ> race       TC17: hai lệnh nhận làm cùng một món -> chỉ một lệnh được chấp nhận
 *
 * Minh chứng Bài 4 (URL, HttpURLConnection) và Bài 5 (Socket TCP) của học phần.
 */
public class TestClient {

    static final int HTTP_PORT = 8081;
    static final int TCP_PORT = 6000;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Cách dùng: java test-data/TestClient.java <IP máy chủ> dup|bad-http|bad-tcp|race");
            return;
        }
        String host = args[0];
        switch (args[1]) {
            case "dup" -> dup(host);
            case "bad-http" -> badHttp(host);
            case "bad-tcp" -> badTcp(host);
            case "race" -> race(host);
            default -> System.out.println("Không có kịch bản " + args[1]);
        }
    }

    /** TC19: cùng một req_id gửi hai lần phải nhận cùng order_id, máy chủ chỉ có một đơn. */
    static void dup(String host) throws IOException {
        String reqId = "TEST-DUP-" + System.currentTimeMillis();
        String body = "{\"command\":\"NEW_ORDER\",\"req_id\":\"" + reqId + "\",\"name\":\"TEST\",\"table\":9,"
                + "\"pager_id\":1,\"items\":[{\"menu_id\":\"M03\",\"qty\":1,\"note\":\"\"}]}";
        String a = post(host, "/api/order", body);
        String b = post(host, "/api/order", body);
        System.out.println("Lần 1: " + a);
        System.out.println("Lần 2: " + b);
        String idA = field(a, "order_id");
        String idB = field(b, "order_id");
        System.out.println(idA != null && idA.equals(idB)
                ? "ĐẠT: hai lần gửi cùng req_id nhận cùng order_id = " + idA
                : "KHÔNG ĐẠT: order_id khác nhau hoặc bị lỗi");
    }

    /** TC21 (HTTP): mỗi dòng trong bad-orders.jsonl là một đơn sai, máy chủ phải trả ERROR. */
    static void badHttp(String host) throws IOException {
        List<String> lines = Files.readAllLines(Path.of("test-data", "bad-orders.jsonl"), StandardCharsets.UTF_8);
        int ok = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String reply = post(host, "/api/order", line);
            boolean isError = reply.contains("\"command\":\"ERROR\"");
            ok += isError ? 1 : 0;
            System.out.println((isError ? "ĐẠT  " : "LỖI  ") + line + "\n      => " + reply);
        }
        System.out.println(ok + "/" + lines.stream().filter(l -> !l.isBlank()).count() + " đơn sai bị từ chối");
    }

    /** TC21 (TCP): dòng rác nhận ERROR; lệnh trước HELLO thì nhận ERROR rồi bị đóng kết nối. */
    static void badTcp(String host) throws IOException {
        List<String> lines = Files.readAllLines(Path.of("test-data", "bad-lines.txt"), StandardCharsets.UTF_8);
        try (Socket s = connect(host);
             BufferedWriter w = writer(s);
             BufferedReader r = reader(s)) {
            for (String line : lines) {
                w.write(line);
                w.write('\n');
                w.flush();
                String reply;
                try {
                    reply = r.readLine();
                } catch (SocketTimeoutException e) {
                    reply = "(không trả lời trong 3 giây)";
                }
                System.out.println(line + "\n      => " + (reply == null ? "(máy chủ đã đóng kết nối)" : reply));
                if (reply == null) {
                    break;
                }
            }
        }
        // Máy chủ vẫn phục vụ bình thường sau khi nhận rác:
        System.out.println("Kiểm tra máy chủ còn sống: " + get(host, "/api/menu").substring(0, 40) + "...");
    }

    /**
     * TC17: tạo một đơn, kết nối làm màn hình MASTERCHEF thử, gửi liền hai START_ITEMS cho cùng một món.
     * Lệnh đầu được chấp nhận (ITEM_UPDATE COOKING), lệnh sau nhận ERROR vì món không còn PENDING.
     * Cần tắt Masterchef Display thật trước, vì máy chủ chỉ cho một màn hình MASTERCHEF.
     */
    static void race(String host) throws IOException {
        String ack = post(host, "/api/order", "{\"command\":\"NEW_ORDER\",\"req_id\":\"TEST-RACE-"
                + System.currentTimeMillis() + "\",\"name\":\"TEST\",\"table\":8,\"pager_id\":1,"
                + "\"items\":[{\"menu_id\":\"M03\",\"qty\":1,\"note\":\"\"}]}");
        String orderId = field(ack, "order_id");
        if (orderId == null) {
            System.out.println("Không tạo được đơn: " + ack);
            return;
        }
        try (Socket s = connect(host);
             BufferedWriter w = writer(s);
             BufferedReader r = reader(s)) {
            send(w, "{\"command\":\"HELLO\",\"name\":\"TEST-RACE\",\"mode\":\"MASTERCHEF\"}");
            String start = "{\"command\":\"START_ITEMS\",\"req_id\":\"R-%d\",\"items\":[{\"order_id\":" + orderId
                    + ",\"item_no\":1}]}";
            send(w, String.format(start, 1) + "\n" + String.format(start, 2)); // hai lệnh liền nhau
            int cooking = 0;
            int errors = 0;
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains("\"command\":\"ERROR\"")) {
                        System.out.println("<= " + line);
                        if (line.contains("MASTERCHEF")) {
                            System.out.println("Hãy tắt Masterchef Display thật rồi chạy lại.");
                            return;
                        }
                        errors++;
                    } else if (line.contains("ITEM_UPDATE") && line.contains("\"order_id\":" + orderId + ",")
                            && line.contains("COOKING")) {
                        System.out.println("<= " + line);
                        cooking++;
                    }
                    if (cooking + errors >= 2) {
                        break;
                    }
                }
            } catch (SocketTimeoutException e) {
                // hết thời gian chờ
            }
            System.out.println(cooking == 1 && errors == 1
                    ? "ĐẠT: một lệnh được nhận làm, lệnh còn lại bị từ chối"
                    : "KHÔNG ĐẠT: " + cooking + " lần COOKING, " + errors + " lỗi");
        }
    }

    // ------------------------------------------------------------------ tiện ích

    static String post(String host, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create("http://" + host + ":" + HTTP_PORT + path).toURL().openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(c);
    }

    static String get(String host, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create("http://" + host + ":" + HTTP_PORT + path).toURL().openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        return read(c);
    }

    static String read(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return code == 200 ? text : "HTTP " + code + " " + text;
    }

    static Socket connect(String host) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, TCP_PORT), 3000);
        s.setSoTimeout(3000);
        return s;
    }

    static BufferedWriter writer(Socket s) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
    }

    static BufferedReader reader(Socket s) throws IOException {
        return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }

    static void send(BufferedWriter w, String lines) throws IOException {
        w.write(lines);
        w.write('\n');
        w.flush();
    }

    /** Lấy giá trị số của một trường trong chuỗi JSON (đủ dùng cho chương trình thử). */
    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(\\d+)").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
