package kos.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Đọc / ghi file dữ liệu trong thư mục data (F12).
 * Ghi vào file tạm rồi đổi tên, để file luôn nguyên vẹn kể cả khi máy chủ tắt đúng lúc đang ghi.
 */
public final class DataFile {

    public static final Path DIR = Path.of("data");
    public static final Path ORDERS = DIR.resolve("orders.json");
    public static final Path MENU = DIR.resolve("menu.json");

    private DataFile() {
    }

    public static synchronized void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Nội dung file, hoặc null nếu chưa có file. */
    public static synchronized String read(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
    }

    /** Đổi tên file hỏng để giữ lại tra cứu, rồi chạy tiếp với dữ liệu rỗng. */
    public static synchronized Path quarantine(Path file) throws IOException {
        Path bad = file.resolveSibling(file.getFileName() + ".loi-" + System.currentTimeMillis());
        Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
        return bad;
    }
}
