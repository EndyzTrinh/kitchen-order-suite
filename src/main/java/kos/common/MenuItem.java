package kos.common;

/** Một món trong thực đơn. Tên trường trong JSON: menu_id, name, price, prep_minutes, sold_out. */
public class MenuItem {
    public String menuId;
    public String name;
    /** Giá bán (đồng). */
    public Long price;
    /** Thời gian chuẩn bị chuẩn (phút), dùng để đặt đồng hồ khi nhận làm. */
    public Integer prepMinutes;
    /** true: tạm hết, trang gọi món làm mờ và máy chủ từ chối đơn có món này. */
    public Boolean soldOut;

    public MenuItem() {
    }

    public MenuItem(String menuId, String name, long price, int prepMinutes) {
        this.menuId = menuId;
        this.name = name;
        this.price = price;
        this.prepMinutes = prepMinutes;
        this.soldOut = false;
    }

    public boolean isSoldOut() {
        return Boolean.TRUE.equals(soldOut);
    }

    @Override
    public String toString() {
        return menuId + " - " + name + " (" + prepMinutes + " phút)";
    }
}
