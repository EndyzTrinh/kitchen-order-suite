package kos.server;

import com.google.gson.reflect.TypeToken;
import kos.common.Json;
import kos.common.MenuItem;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Thực đơn của quán. Đọc từ data/menu.json nếu có, nếu không thì dùng menu.json mẫu đóng gói sẵn.
 * TODO (Cường): thêm, sửa, xóa món, ghi file, báo MENU_UPDATE (F10).
 */
public class MenuStore {

    public static final Path DATA_FILE = Path.of("data", "menu.json");

    private final List<MenuItem> items = new ArrayList<>();

    public synchronized void load() throws IOException {
        items.clear();
        if (Files.exists(DATA_FILE)) {
            try (Reader r = Files.newBufferedReader(DATA_FILE, StandardCharsets.UTF_8)) {
                items.addAll(parse(r));
            }
            return;
        }
        try (InputStream in = MenuStore.class.getResourceAsStream("/menu.json")) {
            if (in == null) {
                throw new IOException("Không tìm thấy menu.json mẫu trong resources");
            }
            items.addAll(parse(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
    }

    private static List<MenuItem> parse(Reader r) {
        List<MenuItem> list = Json.GSON.fromJson(r, new TypeToken<List<MenuItem>>() { }.getType());
        return list == null ? List.of() : list;
    }

    /** Bản sao danh sách món, an toàn để gửi đi. */
    public synchronized List<MenuItem> getAll() {
        return new ArrayList<>(items);
    }

    public synchronized MenuItem find(String menuId) {
        for (MenuItem m : items) {
            if (m.menuId.equals(menuId)) {
                return m;
            }
        }
        return null;
    }
}
