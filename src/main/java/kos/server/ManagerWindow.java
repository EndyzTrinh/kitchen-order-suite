package kos.server;

import kos.common.Beeper;
import kos.common.ItemStatus;
import kos.common.Json;
import kos.common.MenuItem;
import kos.common.Order;
import kos.common.OrderItem;
import kos.common.Theme;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Giao diện quầy quản lý (F10, F11) chạy cùng máy chủ. Năm thẻ:
 * <ul>
 *   <li>Đơn hàng: các món đang mở; hủy món chưa làm, đẩy ưu tiên, xác nhận lấy món thủ công.</li>
 *   <li>Thực đơn: thêm, sửa, xóa món, tạm hết; thay đổi được đẩy ngay tới mọi thiết bị.</li>
 *   <li>Thiết bị: màn hình bếp, trang gọi món, thiết bị rung đang kết nối.</li>
 *   <li>Log: nhật ký máy chủ; dòng CẢNH BÁO tô đỏ và có chuông.</li>
 *   <li>Thống kê (F15): thời gian chuẩn / thực tế theo món, khung giờ, người làm; xuất CSV.</li>
 * </ul>
 * Dữ liệu đọc qua các bản sao (snapshotCopy, getAll) và làm mới mỗi giây bằng javax.swing.Timer,
 * nên giao diện không giữ khóa của máy chủ.
 */
public class ManagerWindow extends JFrame {

    private static final Font BASE = Theme.font(Font.PLAIN, 15);
    private static final Font BOLD = BASE.deriveFont(Font.BOLD);
    private static final Color RED = Theme.DANGER;
    private static final Color ORANGE = Theme.WARNING_SOFT;

    private final ServerContext ctx;
    private final Runnable onShutdown;
    private final JLabel addressLabel = new JLabel("Đang khởi động...");
    private final JTabbedPane tabs = new JTabbedPane();

    // Đơn hàng
    private List<String[]> orderRows = new ArrayList<>();
    private List<String> orderKeys = new ArrayList<>();
    private List<Boolean> orderPriority = new ArrayList<>();
    private final SimpleModel orderModel = new SimpleModel(
            new String[]{"Đơn", "Bàn", "Món", "SL", "Trạng thái", "Thời gian", "Ưu tiên", "Thiết bị rung", "Gửi từ"},
            () -> orderRows);
    private final JTable orderTable = new JTable(orderModel);
    private final DefaultListModel<String> callModel = new DefaultListModel<>();
    private final JList<String> callList = new JList<>(callModel);

    // Thực đơn
    private List<MenuItem> menuItems = new ArrayList<>();
    private List<String[]> menuRows = new ArrayList<>();
    private String menuSignature = "";
    private final SimpleModel menuModel = new SimpleModel(
            new String[]{"Mã", "Tên món", "Giá", "Chuẩn bị (phút)", "Tình trạng"}, () -> menuRows);
    private final JTable menuTable = new JTable(menuModel);
    private final JTextField fId = new JTextField(6);
    private final JTextField fName = new JTextField(18);
    private final JTextField fPrice = new JTextField(8);
    private final JTextField fPrep = new JTextField(4);
    private final JCheckBox fSoldOut = new JCheckBox("Tạm hết");

    // Thiết bị
    private List<String[]> deviceRows = new ArrayList<>();
    private final SimpleModel deviceModel = new SimpleModel(
            new String[]{"Loại", "Tên", "Địa chỉ IP", "Lần cuối nhận tin", "Trạng thái"}, () -> deviceRows);
    private final JTable deviceTable = new JTable(deviceModel);

    // Log
    private final JTextPane logPane = new JTextPane();
    private int unreadWarnings;

    // Tài khoản (F16)
    private final java.util.function.Supplier<String> currentUser;
    private AccountsPanel accounts;

    /**
     * @param currentUser tên đăng nhập của quản lý đang dùng cửa sổ (F16)
     */
    public ManagerWindow(ServerContext ctx, Runnable onShutdown, java.util.function.Supplier<String> currentUser) {
        super("Quầy quản lý - Kitchen Order Suite");
        this.ctx = ctx;
        this.onShutdown = onShutdown;
        this.currentUser = currentUser;
        buildUi();
        ctx.addLogListener(line -> SwingUtilities.invokeLater(() -> appendLog(line)));
        new Timer(1000, e -> refresh()).start();
    }

    public void setAddresses(List<String> addresses) {
        StringBuilder sb = new StringBuilder("<html>");
        for (String a : addresses) {
            sb.append(a.replace("&", "&amp;").replace("<", "&lt;")).append("<br>");
        }
        addressLabel.setText(sb.append("</html>").toString());
    }

    // ------------------------------------------------------------------ dựng giao diện

    private void buildUi() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // F16: đóng cửa sổ chỉ ẩn đi, quay về trang chủ hệ thống; máy chủ vẫn chạy
                setVisible(false);
            }
        });

        JPanel top = new JPanel(new BorderLayout(16, 0));
        top.setBackground(Theme.SURFACE);
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));
        addressLabel.setFont(BASE.deriveFont(13f));
        addressLabel.setForeground(Theme.MUTED);
        JLabel brand = Theme.title("Kitchen Order Suite", 20);
        brand.setForeground(Theme.PRIMARY);
        JLabel sub = new JLabel("Quầy quản lý  ·  máy chủ");
        sub.setFont(BOLD);
        sub.setForeground(Theme.TEXT);
        JPanel brandBox = new JPanel(new BorderLayout());
        brandBox.setOpaque(false);
        brandBox.add(brand, BorderLayout.NORTH);
        brandBox.add(sub, BorderLayout.SOUTH);
        top.add(brandBox, BorderLayout.WEST);
        JButton stop = new JButton("Tắt máy chủ");
        stop.setFont(BOLD);
        stop.setBackground(RED);
        stop.setForeground(Color.WHITE);
        stop.addActionListener(e -> confirmShutdown());
        top.add(addressLabel, BorderLayout.CENTER);
        top.add(stop, BorderLayout.EAST);

        tabs.setFont(BOLD);
        tabs.addTab("Đơn hàng", buildOrdersTab());
        tabs.addTab("Thực đơn", buildMenuTab());
        tabs.addTab("Thiết bị", buildDevicesTab());
        tabs.addTab("Log", buildLogTab());
        StatsPanel stats = new StatsPanel(ctx);
        tabs.addTab("Thống kê", stats);
        accounts = new AccountsPanel(ctx.accounts(), currentUser);
        tabs.addTab("Tài khoản", accounts);
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedIndex() == 3) {
                unreadWarnings = 0;
                tabs.setTitleAt(3, "Log");
            }
            if (tabs.getSelectedComponent() == stats) {
                stats.reload();
            }
        });

        getContentPane().add(top, BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout());
        body.setBorder(BorderFactory.createEmptyBorder(6, 12, 12, 12));
        body.add(tabs, BorderLayout.CENTER);
        getContentPane().add(body, BorderLayout.CENTER);
        setSize(1240, 780);
        setLocationRelativeTo(null);
    }

    private JPanel buildOrdersTab() {
        style(orderTable);
        orderTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        orderTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, f, r, c);
                setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 6));
                if (!sel) {
                    comp.setBackground(r < orderPriority.size() && orderPriority.get(r) ? ORANGE : Color.WHITE);
                }
                return comp;
            }
        });
        orderTable.getColumnModel().getColumn(4).setCellRenderer(new Theme.PillRenderer() {
            @Override
            protected Color[] colors(Object value, int row) {
                String v = String.valueOf(value);
                if (v.startsWith("Đang")) {
                    return new Color[]{Theme.PRIMARY, Color.WHITE};
                }
                if (v.startsWith("Xong") || v.startsWith("Chờ lấy")) {
                    return new Color[]{Theme.SUCCESS, Color.WHITE};
                }
                if (v.startsWith("Đã hủy")) {
                    return new Color[]{Theme.DANGER_SOFT, Theme.DANGER};
                }
                return new Color[]{new Color(226, 232, 240), new Color(51, 65, 85)};
            }

            @Override
            protected Color rowBackground(JTable t, int row, boolean selected) {
                return selected ? t.getSelectionBackground()
                        : row < orderPriority.size() && orderPriority.get(row) ? ORANGE : Color.WHITE;
            }
        });
        int[] w = {50, 50, 230, 40, 110, 90, 70, 100, 90};
        for (int i = 0; i < w.length; i++) {
            orderTable.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        }
        JButton cancel = button("Hủy món đã chọn", RED);
        cancel.addActionListener(e -> cancelSelected());
        JButton priority = button("Đẩy ưu tiên", Theme.WARNING);
        priority.addActionListener(e -> prioritizeSelected());
        JLabel hint = new JLabel("Chỉ hủy hoặc đẩy ưu tiên được món bếp chưa nhận làm.");
        hint.setFont(BASE);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        bar.add(cancel);
        bar.add(priority);
        bar.add(hint);
        JPanel upper = new JPanel(new BorderLayout());
        upper.add(new JScrollPane(orderTable), BorderLayout.CENTER);
        upper.add(bar, BorderLayout.SOUTH);

        callList.setFont(BASE);
        JButton confirm = button("Xác nhận đã lấy món", Theme.SUCCESS);
        confirm.addActionListener(e -> confirmPickup());
        JLabel callTitle = new JLabel("Lệnh rung đang mở (dùng khi thiết bị rung không phản hồi):");
        callTitle.setFont(BOLD);
        JPanel lower = new JPanel(new BorderLayout(0, 4));
        lower.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        lower.add(callTitle, BorderLayout.NORTH);
        lower.add(new JScrollPane(callList), BorderLayout.CENTER);
        JPanel confirmBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        confirmBar.add(confirm);
        lower.add(confirmBar, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, lower);
        split.setResizeWeight(0.75);
        JPanel p = new JPanel(new BorderLayout());
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildMenuTab() {
        style(menuTable);
        int[] mw = {70, 300, 120, 130, 120};
        for (int i = 0; i < mw.length; i++) {
            menuTable.getColumnModel().getColumn(i).setPreferredWidth(mw[i]);
        }
        menuTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        menuTable.getSelectionModel().addListSelectionListener(e -> {
            int i = menuTable.getSelectedRow();
            if (!e.getValueIsAdjusting() && i >= 0 && i < menuItems.size()) {
                MenuItem m = menuItems.get(i);
                fId.setText(m.menuId);
                fName.setText(m.name);
                fPrice.setText(String.valueOf(m.price));
                fPrep.setText(String.valueOf(m.prepMinutes));
                fSoldOut.setSelected(m.isSoldOut());
            }
        });
        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        for (Component c : new Component[]{label("Mã"), fId, label("Tên"), fName, label("Giá (đ)"), fPrice,
                label("Phút"), fPrep, fSoldOut}) {
            c.setFont(BASE);
            form.add(c);
        }
        JButton save = button("Thêm / Cập nhật", Theme.SUCCESS);
        save.addActionListener(e -> saveMenuItem());
        JButton toggle = button("Tạm hết / Có lại", Theme.WARNING);
        toggle.addActionListener(e -> toggleSoldOut());
        JButton delete = button("Xóa món", RED);
        delete.addActionListener(e -> deleteMenuItem());
        JButton clear = button("Nhập món mới", null);
        clear.addActionListener(e -> {
            menuTable.clearSelection();
            for (JTextField f : List.of(fId, fName, fPrice, fPrep)) {
                f.setText("");
            }
            fSoldOut.setSelected(false);
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        buttons.add(save);
        buttons.add(toggle);
        buttons.add(delete);
        buttons.add(clear);
        JPanel south = new JPanel(new GridLayout(2, 1));
        south.add(form);
        south.add(buttons);
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(menuTable), BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildDevicesTab() {
        style(deviceTable);
        int[] dw = {220, 120, 140, 160, 130};
        for (int i = 0; i < dw.length; i++) {
            deviceTable.getColumnModel().getColumn(i).setPreferredWidth(dw[i]);
        }
        deviceTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, f, r, c);
                setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 6));
                boolean offline = r < deviceRows.size() && deviceRows.get(r)[4].startsWith("Mất");
                comp.setForeground(offline ? RED : Theme.TEXT);
                return comp;
            }
        });
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(deviceTable), BorderLayout.CENTER);
        return p;
    }

    private JScrollPane buildLogTab() {
        logPane.setEditable(false);
        logPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        logPane.setMargin(new java.awt.Insets(8, 10, 8, 10));
        return new JScrollPane(logPane);
    }

    private void style(JTable t) {
        t.setFont(BASE);
        Theme.table(t, 34);
        t.getTableHeader().setFont(BOLD);
        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean f, int r, int c) {
                Component comp = super.getTableCellRendererComponent(tb, v, sel, f, r, c);
                setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 6));
                return comp;
            }
        });
    }

    private JButton button(String text, Color color) {
        JButton b = new JButton(text);
        b.setFont(BOLD);
        b.setFocusable(false);
        if (color != null) {
            b.setBackground(color);
            b.setForeground(Color.WHITE);
        }
        return b;
    }

    private static JLabel label(String text) {
        return new JLabel(text);
    }

    // ------------------------------------------------------------------ làm mới (mỗi giây)

    private void refresh() {
        if (accounts != null) {
            accounts.refresh();
            int pending = ctx.accounts().pendingCount();
            int idx = tabs.indexOfComponent(accounts);
            tabs.setTitleAt(idx, pending > 0 ? "Tài khoản (" + pending + " chờ duyệt)" : "Tài khoản");
            tabs.setForegroundAt(idx, pending > 0 ? Theme.WARNING : null);
        }
        long now = System.currentTimeMillis();

        // Đơn hàng
        Set<String> selected = new LinkedHashSet<>();
        for (int i : orderTable.getSelectedRows()) {
            if (i < orderKeys.size()) {
                selected.add(orderKeys.get(i));
            }
        }
        List<Order> orders = ctx.orders().snapshotCopy();
        List<OrderItem> queue = new ArrayList<>();
        for (Order o : orders) {
            for (OrderItem it : o.items) {
                if (it.status == ItemStatus.PENDING && it.isPriority()) {
                    queue.add(it);
                }
            }
        }
        queue.sort(Comparator.comparingLong(it -> it.priorityAt));
        Map<OrderItem, Integer> rank = new HashMap<>();
        for (int i = 0; i < queue.size(); i++) {
            rank.put(queue.get(i), i + 1);
        }
        List<String[]> rows = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<Boolean> prio = new ArrayList<>();
        for (Order o : orders) {
            for (OrderItem it : o.items) {
                if (it.status == null || it.status.isFinished()) {
                    continue;
                }
                rows.add(new String[]{
                        String.valueOf(o.orderId), String.valueOf(o.table), it.name, String.valueOf(it.qty),
                        statusText(it.status), timeText(o, it, now),
                        rank.containsKey(it) ? "#" + rank.get(it) : "",
                        String.valueOf(o.pagerId), o.waiter == null ? "" : o.waiter});
                keys.add(o.orderId + ":" + it.itemNo);
                prio.add(rank.containsKey(it));
            }
        }
        orderRows = rows;
        orderKeys = keys;
        orderPriority = prio;
        orderModel.fireTableDataChanged();
        for (int i = 0; i < keys.size(); i++) {
            if (selected.contains(keys.get(i))) {
                orderTable.getSelectionModel().addSelectionInterval(i, i);
            }
        }

        // Lệnh rung đang mở
        String selectedCall = callList.getSelectedValue();
        List<String> calls = ctx.pagers().describeOpenCalls();
        callModel.clear();
        for (String c : calls) {
            callModel.addElement(c);
        }
        if (selectedCall != null) {
            callList.setSelectedValue(selectedCall, false);
        }

        // Thực đơn: chỉ vẽ lại khi có thay đổi, để không mất dòng đang chọn
        List<MenuItem> menu = ctx.menu().getAll();
        String sig = Json.toLine(menu);
        if (!sig.equals(menuSignature)) {
            int sel = menuTable.getSelectedRow();
            menuSignature = sig;
            menuItems = menu;
            List<String[]> mr = new ArrayList<>();
            for (MenuItem m : menu) {
                mr.add(new String[]{m.menuId, m.name, String.format("%,d", m.price), String.valueOf(m.prepMinutes),
                        m.isSoldOut() ? "Tạm hết" : "Đang bán"});
            }
            menuRows = mr;
            menuModel.fireTableDataChanged();
            if (sel >= 0 && sel < mr.size()) {
                menuTable.getSelectionModel().setSelectionInterval(sel, sel);
            }
        }

        // Thiết bị
        List<String[]> dr = new ArrayList<>();
        for (ClientHandler c : ctx.clients().getAll()) {
            if (c.isRegistered()) {
                dr.add(new String[]{"Màn hình bếp (" + c.getMode() + ")", c.getName(), c.getRemoteIp(),
                        "kết nối TCP", "Đang kết nối"});
            }
        }
        for (DeviceRegistry.Device d : ctx.devices().getAll()) {
            dr.add(new String[]{d.type(), d.name(), d.ip(), (now - d.lastSeen()) / 1000 + " giây trước",
                    d.online() ? "Đang kết nối" : "Mất kết nối"});
        }
        deviceRows = dr;
        deviceModel.fireTableDataChanged();
    }

    private static String statusText(ItemStatus s) {
        return switch (s) {
            case PENDING -> "Chờ làm";
            case COOKING -> "Đang làm";
            case READY -> "Chờ lấy";
            case SERVED -> "Đã lấy";
            case CANCELLED -> "Đã hủy";
        };
    }

    private static String timeText(Order o, OrderItem it, long now) {
        return switch (it.status) {
            case PENDING -> "chờ " + mmss(now - (o.createdAt == null ? now : o.createdAt));
            case COOKING -> it.deadline == null ? "" : "còn " + mmss(it.deadline - now);
            default -> "";
        };
    }

    private static String mmss(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    // ------------------------------------------------------------------ thao tác

    private List<int[]> selectedItems() {
        List<int[]> list = new ArrayList<>();
        for (int i : orderTable.getSelectedRows()) {
            if (i < orderKeys.size()) {
                String[] k = orderKeys.get(i).split(":");
                list.add(new int[]{Integer.parseInt(k[0]), Integer.parseInt(k[1])});
            }
        }
        return list;
    }

    private void cancelSelected() {
        List<int[]> items = selectedItems();
        if (items.isEmpty()) {
            info("Chọn món cần hủy trong bảng.");
            return;
        }
        String reason = JOptionPane.showInputDialog(this, "Hủy " + items.size() + " món. Lý do:", "Khách hủy");
        if (reason == null) {
            return;
        }
        List<String> errors = new ArrayList<>();
        for (int[] it : items) {
            String err = ctx.kitchen().cancelItem(it[0], it[1], reason, "Quản lý");
            if (err != null) {
                errors.add(err);
            }
        }
        report(errors);
        refresh();
    }

    private void prioritizeSelected() {
        List<int[]> items = selectedItems();
        if (items.isEmpty()) {
            info("Chọn món cần đẩy ưu tiên trong bảng.");
            return;
        }
        List<String> errors = new ArrayList<>();
        for (int[] it : items) {
            String err = ctx.kitchen().prioritize(it[0], it[1], "Quản lý");
            if (err != null) {
                errors.add(err);
            }
        }
        report(errors);
        refresh();
    }

    private void confirmPickup() {
        String sel = callList.getSelectedValue();
        if (sel == null) {
            info("Chọn một lệnh rung trong danh sách.");
            return;
        }
        String callId = sel.split(" - ")[0];
        if (!ctx.pagers().confirmManually(callId)) {
            info("Lệnh " + callId + " đã được đóng.");
        }
        refresh();
    }

    private void saveMenuItem() {
        MenuItem m = new MenuItem();
        m.menuId = fId.getText().trim();
        m.name = fName.getText().trim();
        try {
            m.price = Long.parseLong(fPrice.getText().trim().replace(",", "").replace(".", ""));
            m.prepMinutes = Integer.parseInt(fPrep.getText().trim());
        } catch (NumberFormatException e) {
            info("Giá và số phút phải là số.");
            return;
        }
        m.soldOut = fSoldOut.isSelected();
        try {
            String err = ctx.menu().addOrUpdate(m);
            if (err != null) {
                info(err);
                return;
            }
        } catch (IOException e) {
            info("Không ghi được menu.json: " + e.getMessage());
            return;
        }
        ctx.log("Quản lý cập nhật món " + m.menuId + " - " + m.name);
        ctx.menuChanged();
        refresh();
    }

    private void toggleSoldOut() {
        MenuItem m = selectedMenuItem();
        if (m == null) {
            return;
        }
        try {
            ctx.menu().setSoldOut(m.menuId, !m.isSoldOut());
        } catch (IOException e) {
            info("Không ghi được menu.json: " + e.getMessage());
            return;
        }
        ctx.log("Quản lý đánh dấu " + m.name + (m.isSoldOut() ? " có lại" : " tạm hết"));
        ctx.menuChanged();
        refresh();
    }

    private void deleteMenuItem() {
        MenuItem m = selectedMenuItem();
        if (m == null) {
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Xóa món \"" + m.name + "\" khỏi thực đơn?\n"
                + "Các món đã gọi trong đơn đang làm không bị ảnh hưởng.", "Xóa món",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            ctx.menu().delete(m.menuId);
        } catch (IOException e) {
            info("Không ghi được menu.json: " + e.getMessage());
            return;
        }
        ctx.log("Quản lý xóa món " + m.menuId + " - " + m.name);
        ctx.menuChanged();
        refresh();
    }

    private MenuItem selectedMenuItem() {
        int i = menuTable.getSelectedRow();
        if (i < 0 || i >= menuItems.size()) {
            info("Chọn một món trong bảng thực đơn.");
            return null;
        }
        return menuItems.get(i);
    }

    /** Mở cửa sổ ở thẻ có tiêu đề bắt đầu bằng title (ví dụ "Thống kê", "Tài khoản"). */
    public void showTab(String title) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getTitleAt(i).startsWith(title)) {
                tabs.setSelectedIndex(i);
            }
        }
        setVisible(true);
        setExtendedState(NORMAL);
        toFront();
    }

    public void confirmShutdown() {
        if (JOptionPane.showConfirmDialog(this, "Tắt máy chủ? Các đơn đang làm được lưu lại và khôi phục khi mở lại.",
                "Tắt máy chủ", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            new Thread(onShutdown, "shutdown").start();
        }
    }

    private void report(List<String> errors) {
        if (!errors.isEmpty()) {
            info(String.join("\n", errors));
        }
    }

    private void info(String text) {
        JOptionPane.showMessageDialog(this, text);
    }

    // ------------------------------------------------------------------ log

    private void appendLog(String line) {
        boolean warn = line.contains("CẢNH BÁO") || line.contains("LỖI");
        SimpleAttributeSet style = new SimpleAttributeSet();
        if (warn) {
            StyleConstants.setForeground(style, RED);
            StyleConstants.setBold(style, true);
        }
        StyledDocument doc = logPane.getStyledDocument();
        try {
            doc.insertString(doc.getLength(), line + "\n", style);
        } catch (BadLocationException ignored) {
            // không xảy ra
        }
        logPane.setCaretPosition(doc.getLength());
        if (warn) {
            Beeper.warning();
            if (tabs.getSelectedIndex() != 3) {
                unreadWarnings++;
                tabs.setTitleAt(3, "Log (" + unreadWarnings + " cảnh báo)");
                tabs.setForegroundAt(3, RED);
            }
        }
    }

    /** Bảng chỉ đọc lấy dữ liệu từ một danh sách mảng chuỗi. */
    private static final class SimpleModel extends AbstractTableModel {
        private final String[] cols;
        private final java.util.function.Supplier<List<String[]>> data;

        SimpleModel(String[] cols, java.util.function.Supplier<List<String[]>> data) {
            this.cols = cols;
            this.data = data;
        }

        @Override
        public int getRowCount() {
            return data.get().size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            return data.get().get(r)[c];
        }
    }
}
