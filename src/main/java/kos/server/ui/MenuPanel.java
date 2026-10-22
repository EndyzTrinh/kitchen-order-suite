package kos.server.ui;

import kos.common.Json;
import kos.common.MenuItem;
import kos.common.Order;
import kos.common.OrderItem;
import kos.server.ServerContext;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Thẻ Thực đơn (F10 phần quản lý món ăn): thêm, sửa, xóa món, đánh dấu tạm hết / có lại.
 * Sau mỗi thay đổi gọi ctx.menuChanged() để máy chủ đẩy MENU_UPDATE tới iPad và màn hình bếp.
 * Bảng tự đọc lại thực đơn mỗi 2 giây, nên khi masterchef báo hết món từ bếp, quầy cũng thấy ngay.
 */
class MenuPanel extends JPanel {

    private static final String[] COLS = {"Mã", "Tên món", "Giá (đ)", "Chuẩn bị (phút)", "Tình trạng"};
    private static final Pattern ID = Pattern.compile("[A-Z0-9]{2,10}");

    private final ServerContext ctx;
    private List<MenuItem> rows = new ArrayList<>();
    private String lastJson = "";

    private final AbstractTableModel model = new AbstractTableModel() {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLS[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            MenuItem m = rows.get(r);
            return switch (c) {
                case 0 -> m.menuId;
                case 1 -> m.name;
                case 2 -> String.format(java.util.Locale.US, "%,d", m.price == null ? 0 : m.price).replace(',', '.');
                case 3 -> m.prepMinutes;
                default -> m.isSoldOut() ? "Tạm hết" : "Đang bán";
            };
        }
    };

    private final JTable table = new JTable(model);
    private final JTextField idField = new JTextField(6);
    private final JTextField nameField = new JTextField(18);
    private final JTextField priceField = new JTextField(8);
    private final JTextField prepField = new JTextField(4);
    private final JButton addBtn = new JButton("Thêm món");
    private final JButton saveBtn = new JButton("Lưu sửa");
    private final JButton deleteBtn = new JButton("Xóa món");
    private final JButton soldOutBtn = new JButton("Tạm hết / Có lại");

    MenuPanel(ServerContext ctx) {
        super(new BorderLayout(8, 8));
        this.ctx = ctx;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        Ui.style(table);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, f, r, c);
                if (!sel && r < rows.size()) {
                    comp.setBackground(rows.get(r).isSoldOut() ? Ui.GREY : Ui.WHITE);
                    comp.setForeground(rows.get(r).isSoldOut() ? Ui.RED_TEXT : java.awt.Color.BLACK);
                }
                return comp;
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                fillForm();
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        form.add(new JLabel("Mã"));
        form.add(idField);
        form.add(new JLabel("Tên món"));
        form.add(nameField);
        form.add(new JLabel("Giá (đ)"));
        form.add(priceField);
        form.add(new JLabel("Chuẩn bị (phút)"));
        form.add(prepField);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        buttons.add(addBtn);
        buttons.add(saveBtn);
        buttons.add(deleteBtn);
        buttons.add(soldOutBtn);
        buttons.add(new JLabel("Thay đổi được đẩy ngay tới iPad và màn hình bếp."));
        JPanel south = new JPanel(new GridLayout(2, 1));
        south.add(form);
        south.add(buttons);
        add(south, BorderLayout.SOUTH);

        addBtn.addActionListener(e -> Ui.run(this, this::addItem));
        saveBtn.addActionListener(e -> Ui.run(this, this::saveItem));
        deleteBtn.addActionListener(e -> Ui.run(this, this::deleteItem));
        soldOutBtn.addActionListener(e -> Ui.run(this, this::toggleSoldOut));

        new Timer(2000, e -> reload(false)).start();
        reload(true);
    }

    // ------------------------------------------------------------------ thao tác

    private void addItem() throws Exception {
        MenuItem m = readForm();
        if (ctx.menu().find(m.menuId) != null) {
            throw new IllegalArgumentException("Mã món " + m.menuId + " đã tồn tại. Muốn sửa thì bấm \"Lưu sửa\".");
        }
        apply(m, "thêm món " + m.menuId + " - " + m.name + " (" + m.price + "đ, " + m.prepMinutes + " phút)");
    }

    private void saveItem() throws Exception {
        MenuItem m = readForm();
        MenuItem old = ctx.menu().find(m.menuId);
        if (old == null) {
            throw new IllegalArgumentException("Không có món " + m.menuId + ". Muốn tạo món mới thì bấm \"Thêm món\".");
        }
        m.soldOut = old.isSoldOut(); // giữ nguyên tình trạng tạm hết
        apply(m, "sửa món " + m.menuId + " - " + m.name + " (" + m.price + "đ, " + m.prepMinutes + " phút)");
    }

    private void deleteItem() throws Exception {
        MenuItem m = selected();
        if (m == null) {
            throw new IllegalArgumentException("Chọn món cần xóa trong bảng");
        }
        int busy = countUnfinished(m.menuId);
        if (busy > 0) {
            throw new IllegalStateException(m.name + " còn " + busy + " phần trong các đơn chưa hoàn tất.\n"
                    + "Hãy dùng \"Tạm hết\" thay vì xóa.");
        }
        if (JOptionPane.showConfirmDialog(this, "Xóa " + m.menuId + " - " + m.name + " khỏi thực đơn?",
                "Xóa món", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        if (!ctx.menu().delete(m.menuId)) {
            throw new IllegalStateException("Món " + m.menuId + " không còn trong thực đơn");
        }
        ctx.log("Quản lý xóa món " + m.menuId + " - " + m.name);
        ctx.menuChanged();
        clearForm();
        reload(true);
    }

    private void toggleSoldOut() throws Exception {
        MenuItem m = selected();
        if (m == null) {
            throw new IllegalArgumentException("Chọn món trong bảng");
        }
        boolean soldOut = !m.isSoldOut();
        ctx.menu().setSoldOut(m.menuId, soldOut);
        ctx.log("Quản lý đánh dấu " + m.name + (soldOut ? " tạm hết" : " có lại"));
        ctx.menuChanged();
        reload(true);
    }

    private void apply(MenuItem m, String logText) throws Exception {
        String err = ctx.menu().addOrUpdate(m);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        ctx.log("Quản lý " + logText);
        ctx.menuChanged();
        reload(true);
        select(m.menuId);
    }

    // ------------------------------------------------------------------ form

    /** Đọc và kiểm tra ô nhập; MenuStore kiểm tra lại tên, giá, thời gian chuẩn bị. */
    private MenuItem readForm() {
        MenuItem m = new MenuItem();
        m.menuId = idField.getText().trim().toUpperCase();
        if (!ID.matcher(m.menuId).matches()) {
            throw new IllegalArgumentException("Mã món gồm 2 - 10 chữ in hoa hoặc số, ví dụ M07");
        }
        m.name = nameField.getText().trim();
        String price = priceField.getText().trim().replace(".", "").replace(",", "").replace("đ", "");
        try {
            m.price = Long.parseLong(price);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Giá phải là số, ví dụ 45000");
        }
        try {
            m.prepMinutes = Integer.parseInt(prepField.getText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Thời gian chuẩn bị phải là số phút, ví dụ 5");
        }
        m.soldOut = false;
        return m;
    }

    private void fillForm() {
        MenuItem m = selected();
        if (m == null) {
            return;
        }
        idField.setText(m.menuId);
        nameField.setText(m.name);
        priceField.setText(String.valueOf(m.price));
        prepField.setText(String.valueOf(m.prepMinutes));
    }

    private void clearForm() {
        idField.setText("");
        nameField.setText("");
        priceField.setText("");
        prepField.setText("");
    }

    // ------------------------------------------------------------------ dữ liệu

    /** Đọc lại thực đơn; chỉ vẽ lại khi có thay đổi để không làm mất dòng đang chọn. */
    private void reload(boolean force) {
        List<MenuItem> list = ctx.menu().getAll();
        String json = Json.GSON.toJson(list);
        if (!force && json.equals(lastJson)) {
            return;
        }
        String sel = selected() == null ? null : selected().menuId;
        lastJson = json;
        rows = list;
        model.fireTableDataChanged();
        if (sel != null) {
            select(sel);
        }
    }

    private void select(String menuId) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).menuId.equals(menuId)) {
                table.setRowSelectionInterval(i, i);
                return;
            }
        }
    }

    private MenuItem selected() {
        int r = table.getSelectedRow();
        return r < 0 || r >= rows.size() ? null : rows.get(r);
    }

    /** Số phần của món này còn trong các đơn chưa hoàn tất (chờ nhận làm, đang làm, chờ lấy). */
    private int countUnfinished(String menuId) {
        int n = 0;
        for (Order o : Ui.copyOpenOrders(ctx)) {
            for (OrderItem it : o.items) {
                if (menuId.equals(it.menuId) && it.status != null && !it.status.isFinished()) {
                    n += it.qty == null ? 1 : it.qty;
                }
            }
        }
        return n;
    }
}
