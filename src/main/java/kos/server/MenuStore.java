package kos.server;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import kos.common.Json;
import kos.common.MenuItem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Thực đơn của quán (F10). Đọc data/menu.json nếu có, nếu không thì dùng menu.json mẫu đóng gói sẵn.
 * Mọi thay đổi được ghi lại vào data/menu.json.
 * Sau khi sửa thực đơn, gọi ctx.menuChanged() để đẩy MENU_UPDATE tới mọi thiết bị.
 * Thẻ Thực đơn của ManagerWindow dùng addOrUpdate, delete, setSoldOut rồi gọi ctx.menuChanged().
 */
public class MenuStore {

    private final List<MenuItem> items = new ArrayList<>();

    public synchronized void load() throws IOException {
        items.clear();
        String json = DataFile.read(DataFile.MENU);
        if (json == null) {
            try (InputStream in = MenuStore.class.getResourceAsStream("/menu.json")) {
                if (in == null) {
                    throw new IOException("Không tìm thấy menu.json mẫu trong resources");
                }
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        List<MenuItem> list;
        try {
            list = Json.GSON.fromJson(json, new TypeToken<List<MenuItem>>() { }.getType());
        } catch (JsonParseException e) {
            throw new IOException("menu.json không hợp lệ: " + e.getMessage(), e);
        }
        if (list != null) {
            items.addAll(list);
        }
    }

    public synchronized void save() throws IOException {
        DataFile.write(DataFile.MENU, Json.GSON.toJson(items));
    }

    /** Bản sao danh sách món, an toàn để gửi đi. */
    public synchronized List<MenuItem> getAll() {
        List<MenuItem> copy = new ArrayList<>();
        for (MenuItem m : items) {
            MenuItem c = new MenuItem();
            c.menuId = m.menuId;
            c.name = m.name;
            c.price = m.price;
            c.prepMinutes = m.prepMinutes;
            c.soldOut = m.isSoldOut();
            copy.add(c);
        }
        return copy;
    }

    public synchronized MenuItem find(String menuId) {
        for (MenuItem m : items) {
            if (m.menuId.equals(menuId)) {
                return m;
            }
        }
        return null;
    }

    /**
     * Thêm món mới hoặc sửa món có cùng mã.
     *
     * @return thông báo lỗi, hoặc null nếu thành công
     */
    public synchronized String addOrUpdate(MenuItem m) throws IOException {
        if (m.menuId == null || m.menuId.isBlank()) {
            return "Thiếu mã món";
        }
        if (m.name == null || m.name.isBlank()) {
            return "Thiếu tên món";
        }
        if (m.price == null || m.price < 0) {
            return "Giá không hợp lệ";
        }
        if (m.prepMinutes == null || m.prepMinutes < 1 || m.prepMinutes > 120) {
            return "Thời gian chuẩn bị phải từ 1 đến 120 phút";
        }
        MenuItem old = find(m.menuId);
        if (old == null) {
            MenuItem n = new MenuItem(m.menuId.trim(), m.name.trim(), m.price, m.prepMinutes);
            n.soldOut = m.isSoldOut();
            items.add(n);
        } else {
            old.name = m.name.trim();
            old.price = m.price;
            old.prepMinutes = m.prepMinutes;
            old.soldOut = m.isSoldOut();
        }
        save();
        return null;
    }

    /** Xóa món. Món đã gọi trong các đơn đang làm không bị ảnh hưởng (đơn giữ bản sao tên món). */
    public synchronized boolean delete(String menuId) throws IOException {
        boolean removed = items.removeIf(m -> m.menuId.equals(menuId));
        if (removed) {
            save();
        }
        return removed;
    }

    /** Đánh dấu tạm hết / có lại. */
    public synchronized boolean setSoldOut(String menuId, boolean soldOut) throws IOException {
        MenuItem m = find(menuId);
        if (m == null) {
            return false;
        }
        m.soldOut = soldOut;
        save();
        return true;
    }
}
