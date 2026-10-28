package kos.kitchen;

import kos.common.Beeper;
import kos.common.ItemStatus;
import kos.common.MenuItem;
import kos.common.Message;
import kos.common.Order;
import kos.common.OrderItem;
import kos.common.Protocol;
import kos.common.Theme;
import kos.ui.AuthApi;
import kos.ui.HomeWindow;
import kos.ui.RemoteAuth;
import kos.ui.Tiles;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Masterchef Display (F04 - F07, F14): màn hình bếp kết nối TCP tới máy chủ.
 * <ul>
 *   <li>Dải ưu tiên màu cam trên cùng: món khách xin làm trước, xếp theo thứ tự xin (#1 làm trước).
 *       Nút lớn hoặc phím Space: nhận làm món ưu tiên tiếp theo.</li>
 *   <li>Hai chế độ xem: theo đơn, và gộp theo món (cộng số phần của cùng một món ở các bàn).</li>
 *   <li>Nhận làm, gia hạn +1 / +3 / +5 phút, xong sớm, báo hết món (chỉ chế độ MASTERCHEF).</li>
 *   <li>Đồng hồ đếm ngược theo giờ máy chủ; còn 30 giây thì nhấp nháy đỏ.</li>
 *   <li>F15: ô "Người làm" ghi tên chef nhận món; cột Tiến độ có hai thanh: thanh trên là thời gian chuẩn,
 *       thanh dưới là thời gian thực tế (dài ra khi gia hạn / Chưa xong, ngắn lại khi Xong sớm,
 *       phần vượt chuẩn tô đỏ). Gia hạn và Chưa xong phải chọn lý do.</li>
 * </ul>
 * Cách chạy: Run File. Tham số (không bắt buộc): IP máy chủ; không có thì cửa sổ hỏi địa chỉ khi mở.
 * F16: main() mở giao diện hệ thống chung (đăng nhập / đăng ký qua TCP); màn hình bếp mở từ ô "Màn hình bếp",
 * chế độ đủ quyền hay chỉ xem do vai trò quyết định, tên người đăng nhập là người làm mặc định.
 * Mọi dữ liệu chỉ được sửa trên luồng giao diện (EDT); sự kiện mạng được chuyển vào bằng invokeLater.
 */
public class MasterchefApp extends JFrame implements ServerConnection.Listener {

    // ------------------------------------------------------------------ dữ liệu

    /** Một món trên màn hình bếp. */
    static final class Row {
        int orderId;
        int itemNo;
        int table;
        String menuId;
        String name;
        int qty;
        String note;
        ItemStatus status;
        long createdAt;
        Long deadline;
        int extended;
        boolean priority;
        long priorityAt;
        int prep;
        String chef;
        Long startedAt;
        Long finishedAt;

        String key() {
            return orderId + ":" + itemNo;
        }

        OrderItem ref() {
            return OrderItem.ref(orderId, itemNo);
        }

        boolean inPriorityQueue() {
            return priority && status == ItemStatus.PENDING;
        }
    }

    /** Một dòng ở chế độ gộp theo món. */
    static final class Group {
        String menuId;
        String name;
        int pendingQty;
        int cookingQty;
        int priorityCount;
        long firstPriorityAt = Long.MAX_VALUE;
        long firstCreatedAt = Long.MAX_VALUE;
        Set<Integer> tables = new LinkedHashSet<>();
        List<Row> rows = new ArrayList<>();
    }

    private static final Font BASE = Theme.font(Font.PLAIN, 17);
    private static final Font BOLD = BASE.deriveFont(Font.BOLD);
    private static final Color ORANGE = Theme.WARNING_SOFT;
    private static final Color ORANGE_DARK = Theme.WARNING;
    private static final Color RED_DARK = Theme.DANGER;
    private static final Color BLUE = new Color(232, 240, 254);
    private static final Color GREEN = Theme.SUCCESS_SOFT;
    private static final Color YELLOW = Theme.YELLOW_SOFT;
    private static final Color RED = new Color(254, 202, 202);
    private static final Color OK_TEXT = new Color(21, 128, 61);
    private static final SimpleDateFormat HM = new SimpleDateFormat("HH:mm");

    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final boolean master;
    private final ServerConnection conn;
    private List<MenuItem> menu = new ArrayList<>();
    private List<Row> orderRows = new ArrayList<>();
    private List<Group> groups = new ArrayList<>();
    private List<Row> queue = new ArrayList<>();
    private String queueSignature = "";
    private boolean groupedView;
    private long messageUntil;

    // ------------------------------------------------------------------ giao diện

    private final JLabel statusLabel = new JLabel("Đang kết nối...");
    private final JLabel clockLabel = new JLabel();
    private final JLabel messageLabel = new JLabel(" ");
    private final JLabel queueTitle = new JLabel();
    private final JPanel queueList = new JPanel();
    private final JButton nextButton = new JButton();
    private final OrderModel orderModel = new OrderModel();
    private final GroupModel groupModel = new GroupModel();
    private final JTable orderTable = new JTable(orderModel);
    private final JTable groupTable = new JTable(groupModel);
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final List<JButton> actionButtons = new ArrayList<>();
    /** Tên người làm (F15), nhớ giữa các lần mở bằng Preferences của Java. */
    private final JComboBox<String> chefBox = new JComboBox<>();
    private final Preferences prefs = Preferences.userNodeForPackage(MasterchefApp.class);

    private static final List<MasterchefApp> OPEN = new ArrayList<>();
    private final Timer refreshTimer = new Timer(500, e -> refresh());
    private final AuthApi.Session session;
    private final java.util.function.Consumer<String> onAuthFailed;

    /**
     * @param session      phiên đăng nhập; vai trò quyết định chế độ (đủ quyền / chỉ xem)
     * @param onAuthFailed gọi khi máy chủ từ chối phiên (bị khóa, hết hạn) để giao diện hệ thống đăng xuất
     */
    public MasterchefApp(String host, AuthApi.Session session, java.util.function.Consumer<String> onAuthFailed) {
        super("Màn hình bếp - " + session.user().fullName + " (" + session.user().role.label() + ")"
                + (Protocol.MODE_MASTERCHEF.equals(session.user().role.kitchenMode()) ? "" : " - chỉ xem"));
        String mode = session.user().role.kitchenMode();
        this.session = session;
        this.onAuthFailed = onAuthFailed;
        this.master = Protocol.MODE_MASTERCHEF.equals(mode);
        String name = master ? "MC" : "VIEW-" + (System.currentTimeMillis() % 1000);
        this.conn = new ServerConnection(host, name, mode, session.token(), this);
        buildUi();
        // người đăng nhập là người làm mặc định
        String me = session.user().fullName;
        boolean known = false;
        for (int i = 0; i < chefBox.getItemCount(); i++) {
            known |= chefBox.getItemAt(i).equalsIgnoreCase(me);
        }
        if (!known) {
            chefBox.insertItemAt(me, 0);
        }
        chefBox.setSelectedItem(me);
        refreshTimer.start();
        conn.start();
    }

    /** Mở màn hình bếp cho phiên này; đang mở rồi thì đưa lên trước. */
    public static void openFor(String host, AuthApi.Session session, HomeWindow home) {
        for (MasterchefApp w : OPEN) {
            if (w.session.token().equals(session.token())) {
                w.toFront();
                return;
            }
        }
        MasterchefApp w = new MasterchefApp(host, session, home::forceLogout);
        OPEN.add(w);
        w.setVisible(true);
    }

    /** Đóng mọi màn hình bếp (khi đăng xuất). */
    public static void closeAll() {
        for (MasterchefApp w : new ArrayList<>(OPEN)) {
            w.close();
        }
    }

    private void close() {
        conn.stop();
        refreshTimer.stop();
        OPEN.remove(this);
        dispose();
    }

    @Override
    public void onAuthFailed(String message) {
        SwingUtilities.invokeLater(() -> {
            if (!isDisplayable()) {
                return;
            }
            close();
            JOptionPane.showMessageDialog(null, message, "Màn hình bếp bị ngắt", JOptionPane.WARNING_MESSAGE);
            onAuthFailed.accept(message);
        });
    }

    private void buildUi() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                orderTable.requestFocusInWindow();
            }

            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });

        // Thanh trên: trạng thái kết nối, chế độ xem, đồng hồ
        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setBackground(Theme.SURFACE);
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));
        statusLabel.setFont(BOLD);
        clockLabel.setFont(BOLD.deriveFont(22f));
        JToggleButton byOrder = toggle("Theo đơn");
        JToggleButton byDish = toggle("Gộp theo món");
        ButtonGroup bg = new ButtonGroup();
        bg.add(byOrder);
        bg.add(byDish);
        byOrder.setSelected(true);
        byOrder.addActionListener(e -> switchView(false));
        byDish.addActionListener(e -> switchView(true));
        JPanel views = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        views.setOpaque(false);
        views.add(byOrder);
        views.add(byDish);
        JLabel chefLabel = new JLabel("Người làm:");
        chefLabel.setFont(BOLD);
        chefBox.setEditable(true);
        chefBox.setFont(BOLD);
        chefBox.setPreferredSize(new Dimension(230, 36));
        chefBox.setBorder(new Theme.RoundBorder(Theme.BORDER, 10));
        chefBox.setBackground(Theme.SURFACE);
        ((JComponent) chefBox.getEditor().getEditorComponent()).setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 4));
        for (String n : prefs.get("chefs", "").split("\\|")) {
            if (!n.isBlank()) {
                chefBox.addItem(n);
            }
        }
        chefBox.setSelectedItem(prefs.get("chef", ""));
        chefBox.setEnabled(master);
        // chọn / gõ xong tên (Enter) thì trả focus về bảng để phím Space dùng được ngay
        chefBox.addActionListener(e -> {
            if ("comboBoxEdited".equals(e.getActionCommand()) || !chefBox.isPopupVisible()) {
                currentChef();
                SwingUtilities.invokeLater(() -> (groupedView ? groupTable : orderTable).requestFocusInWindow());
            }
        });
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(chefLabel);
        right.add(chefBox);
        right.add(Box.createHorizontalStrut(12));
        right.add(clockLabel);
        JLabel brand = Theme.title("Bếp", 22);
        brand.setForeground(Theme.PRIMARY);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        left.setOpaque(false);
        left.add(brand);
        left.add(statusLabel);
        top.add(left, BorderLayout.WEST);
        top.add(views, BorderLayout.CENTER);
        top.add(right, BorderLayout.EAST);

        // Dải ưu tiên
        JPanel strip = new JPanel(new BorderLayout(0, 6));
        strip.setBackground(new Color(255, 247, 237));
        strip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 6, 1, 0, ORANGE_DARK),
                BorderFactory.createEmptyBorder(10, 14, 10, 16)));
        queueTitle.setFont(BOLD.deriveFont(19f));
        queueTitle.setOpaque(true);
        queueTitle.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
        queueList.setLayout(new BoxLayout(queueList, BoxLayout.Y_AXIS));
        queueList.setOpaque(false);
        JScrollPane queueScroll = new JScrollPane(queueList);
        queueScroll.setOpaque(false);
        queueScroll.getViewport().setOpaque(false);
        queueScroll.setBorder(null);
        queueScroll.getVerticalScrollBar().setUnitIncrement(21);
        queueScroll.setPreferredSize(new Dimension(100, 150));
        nextButton.setFont(BOLD.deriveFont(18f));
        nextButton.setFocusable(false);
        nextButton.setBackground(ORANGE_DARK);
        nextButton.setForeground(Color.WHITE);
        nextButton.addActionListener(e -> startNextPriority());
        JPanel stripHead = new JPanel(new BorderLayout(10, 0));
        stripHead.setOpaque(false);
        stripHead.add(queueTitle, BorderLayout.WEST);
        stripHead.add(nextButton, BorderLayout.EAST);
        strip.add(stripHead, BorderLayout.NORTH);
        strip.add(queueScroll, BorderLayout.CENTER);

        // Bảng chính
        setupTable(orderTable);
        setupTable(groupTable);
        int[] w = {85, 50, 205, 40, 110, 140, 120, 105, 320};
        for (int i = 0; i < w.length; i++) {
            orderTable.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        }
        int[] gw = {300, 100, 100, 260, 120};
        for (int i = 0; i < gw.length; i++) {
            groupTable.getColumnModel().getColumn(i).setPreferredWidth(gw[i]);
        }
        orderTable.getColumnModel().getColumn(8).setCellRenderer(new BarRenderer());
        orderTable.getColumnModel().getColumn(5).setCellRenderer(new StatusRenderer());
        center.add(new JScrollPane(orderTable), "order");
        center.add(new JScrollPane(groupTable), "group");
        center.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));

        // Thanh nút dưới
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        actions.setOpaque(false);
        actions.add(action("Nhận làm", Theme.SUCCESS, this::startSelected));
        actions.add(Box.createHorizontalStrut(10));
        actions.add(action("+1 phút", null, () -> extendSelected(1)));
        actions.add(action("+3 phút", null, () -> extendSelected(3)));
        actions.add(action("+5 phút", null, () -> extendSelected(5)));
        actions.add(Box.createHorizontalStrut(10));
        actions.add(action("Xong sớm", Theme.PRIMARY, this::finishSelected));
        actions.add(action("Chưa xong", ORANGE_DARK, this::notDoneSelected));
        actions.add(Box.createHorizontalStrut(10));
        actions.add(action("Báo hết món", RED_DARK, this::soldOut));
        messageLabel.setFont(BOLD);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(Theme.SURFACE);
        bottom.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(4, 8, 0, 8)));
        bottom.add(actions, BorderLayout.NORTH);
        bottom.add(messageLabel, BorderLayout.SOUTH);
        messageLabel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 12));
        if (!master) {
            for (JButton b : actionButtons) {
                b.setEnabled(false);
            }
            nextButton.setEnabled(false);
        }

        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.NORTH);
        north.add(strip, BorderLayout.SOUTH);
        getContentPane().add(north, BorderLayout.NORTH);
        getContentPane().add(center, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);

        // Phím Space: nhận làm món ưu tiên tiếp theo
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("SPACE"), "next-priority");
        getRootPane().getActionMap().put("next-priority", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // đang gõ tên người làm thì Space là dấu cách, không phải lệnh
                if (getFocusOwner() instanceof javax.swing.text.JTextComponent) {
                    return;
                }
                startNextPriority();
            }
        });
        // JTable dùng Space để chọn dòng; bỏ đi để Space luôn là "ưu tiên tiếp theo"
        for (JTable t : List.of(orderTable, groupTable)) {
            t.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("SPACE"), "none");
            t.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("SPACE"), "none");
        }

        setSize(1200, 800);
        setLocationRelativeTo(null);
    }

    private JToggleButton toggle(String text) {
        JToggleButton b = new JToggleButton(text);
        b.setFont(BOLD);
        b.setFocusable(false);
        return b;
    }

    private JButton action(String text, Color color, Runnable r) {
        JButton b = new JButton(text);
        b.setFont(BOLD.deriveFont(18f));
        b.setFocusable(false);
        b.setPreferredSize(new Dimension(Math.max(130, text.length() * 16), 50));
        if (color != null) {
            b.setBackground(color);
            b.setForeground(Color.WHITE);
        }
        b.addActionListener(e -> r.run());
        actionButtons.add(b);
        return b;
    }

    private void setupTable(JTable t) {
        t.setFont(BASE);
        Theme.table(t, 48);
        t.getTableHeader().setFont(BOLD);
        t.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        t.setFillsViewportHeight(true);
        t.setDefaultRenderer(Object.class, new ColorRenderer());
    }

    private void switchView(boolean grouped) {
        groupedView = grouped;
        cards.show(center, grouped ? "group" : "order");
    }

    // ------------------------------------------------------------------ sự kiện mạng (luồng mạng)

    @Override
    public void onMessage(Message m) {
        SwingUtilities.invokeLater(() -> handle(m));
    }

    @Override
    public void onStatus(boolean connected, String text) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText((connected ? "● " : "○ ") + text + (master ? "" : "  (chỉ xem)"));
            statusLabel.setForeground(connected ? OK_TEXT : RED_DARK);
        });
    }

    // ------------------------------------------------------------------ xử lý thông điệp (EDT)

    private void handle(Message m) {
        switch (m.command) {
            case Protocol.WELCOME, Protocol.MENU_UPDATE -> {
                if (m.menu != null) {
                    menu = m.menu;
                }
            }
            case Protocol.SNAPSHOT -> {
                rows.clear();
                if (m.orders != null) {
                    for (Order o : m.orders) {
                        addOrder(o);
                    }
                }
            }
            case Protocol.ORDER_NEW -> {
                if (m.order != null) {
                    boolean hasPriority = addOrder(m.order);
                    if (hasPriority) {
                        Beeper.priority();
                        showMessage("Đơn mới bàn " + m.order.table + " có món ƯU TIÊN", ORANGE_DARK);
                    } else {
                        Beeper.newOrder();
                        showMessage("Đơn mới: bàn " + m.order.table + " (" + m.order.items.size() + " món)",
                                OK_TEXT);
                    }
                }
            }
            case Protocol.ITEM_UPDATE -> updateItem(m);
            case Protocol.ERROR -> {
                Beeper.warning();
                showMessage(m.message, RED_DARK);
            }
            default -> {
                // PONG và các lệnh khác: bỏ qua
            }
        }
        refresh();
    }

    /** Thêm các món của một đơn. Trả về true nếu đơn có món ưu tiên. */
    private boolean addOrder(Order o) {
        boolean hasPriority = false;
        for (OrderItem it : o.items) {
            if (it.status == null || it.status.isFinished()) {
                continue;
            }
            Row r = new Row();
            r.orderId = o.orderId;
            r.itemNo = it.itemNo;
            r.table = o.table;
            r.menuId = it.menuId;
            r.name = it.name;
            r.qty = it.qty == null ? 1 : it.qty;
            r.note = it.note == null ? "" : it.note;
            r.status = it.status;
            r.createdAt = o.createdAt == null ? System.currentTimeMillis() : o.createdAt;
            r.deadline = it.deadline;
            r.extended = it.extendedMinutes == null ? 0 : it.extendedMinutes;
            r.priority = it.isPriority();
            r.priorityAt = it.priorityAt == null ? 0 : it.priorityAt;
            r.prep = it.prepMinutes == null ? 0 : it.prepMinutes;
            r.chef = it.chef;
            r.startedAt = it.startedAt;
            r.finishedAt = it.finishedAt;
            rows.put(r.key(), r);
            hasPriority |= r.inPriorityQueue();
        }
        return hasPriority;
    }

    private void updateItem(Message m) {
        Row r = rows.get(m.orderId + ":" + m.itemNo);
        if (r == null) {
            return;
        }
        boolean wasQueued = r.inPriorityQueue();
        r.status = m.status;
        r.deadline = m.deadline;
        r.extended = m.extendedMinutes == null ? r.extended : m.extendedMinutes;
        r.priority = Boolean.TRUE.equals(m.priority);
        r.priorityAt = m.priorityAt == null ? r.priorityAt : m.priorityAt;
        r.startedAt = m.startedAt;
        r.finishedAt = m.finishedAt;
        r.chef = m.chef;
        if (r.status == null || r.status.isFinished()) {
            rows.remove(r.key());
            if (r.status == ItemStatus.CANCELLED) {
                showMessage("Đã hủy: " + r.name + " bàn " + r.table
                        + (m.reason == null ? "" : " (" + m.reason + ")"), RED_DARK);
            }
            return;
        }
        if (!wasQueued && r.inPriorityQueue()) {
            Beeper.priority();
            showMessage("ƯU TIÊN: bàn " + r.table + " xin làm trước " + r.name + " x" + r.qty, ORANGE_DARK);
        }
        if (r.status == ItemStatus.COOKING && m.reason != null && m.reason.startsWith("Chưa xong")) {
            showMessage("Chưa xong: " + r.name + " bàn " + r.table + " - đã báo phục vụ chờ thêm", ORANGE_DARK);
        }
        if (r.status == ItemStatus.READY && "Hết giờ".equals(m.by)) {
            showMessage("Hết giờ: " + r.name + " bàn " + r.table + " - đã báo phục vụ", OK_TEXT);
        }
    }

    // ------------------------------------------------------------------ làm mới màn hình (mỗi 0,5 giây)

    private void refresh() {
        long now = conn.serverNow();
        clockLabel.setText(new SimpleDateFormat("HH:mm:ss").format(new Date(now)));
        if (System.currentTimeMillis() > messageUntil) {
            messageLabel.setText(" ");
        }

        // Hàng đợi ưu tiên
        queue = new ArrayList<>();
        for (Row r : rows.values()) {
            if (r.inPriorityQueue()) {
                queue.add(r);
            }
        }
        queue.sort(Comparator.comparingLong(r -> r.priorityAt));
        rebuildQueue(now);

        // Theo đơn: ưu tiên trước, rồi món chờ (cũ trước), đang làm (sắp hết giờ trước), chờ lấy
        Set<String> selected = selectedKeys();
        orderRows = new ArrayList<>(rows.values());
        orderRows.sort(Comparator.comparingInt(this::sortGroup)
                .thenComparingLong(r -> r.inPriorityQueue() ? r.priorityAt
                        : r.status == ItemStatus.COOKING && r.deadline != null ? r.deadline : r.createdAt));
        orderModel.fireTableDataChanged();
        restoreSelection(selected);

        // Gộp theo món
        String selectedGroup = selectedGroupId();
        Map<String, Group> map = new LinkedHashMap<>();
        for (Row r : rows.values()) {
            if (r.status != ItemStatus.PENDING && r.status != ItemStatus.COOKING) {
                continue;
            }
            Group g = map.computeIfAbsent(r.menuId, k -> new Group());
            g.menuId = r.menuId;
            g.name = r.name;
            g.rows.add(r);
            g.tables.add(r.table);
            if (r.status == ItemStatus.PENDING) {
                g.pendingQty += r.qty;
                g.firstCreatedAt = Math.min(g.firstCreatedAt, r.createdAt);
            } else {
                g.cookingQty += r.qty;
            }
            if (r.inPriorityQueue()) {
                g.priorityCount++;
                g.firstPriorityAt = Math.min(g.firstPriorityAt, r.priorityAt);
            }
        }
        groups = new ArrayList<>(map.values());
        groups.sort(Comparator.comparingLong((Group g) -> g.firstPriorityAt).thenComparingLong(g -> g.firstCreatedAt));
        groupModel.fireTableDataChanged();
        if (selectedGroup != null) {
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).menuId.equals(selectedGroup)) {
                    groupTable.getSelectionModel().setSelectionInterval(i, i);
                }
            }
        }
    }

    private int sortGroup(Row r) {
        if (r.inPriorityQueue()) {
            return 0;
        }
        return switch (r.status) {
            case PENDING -> 1;
            case COOKING -> 2;
            default -> 3;
        };
    }

    private void rebuildQueue(long now) {
        boolean alert = queue.size() >= Protocol.PRIORITY_ALERT_COUNT;
        queueTitle.setText(queue.isEmpty() ? "ƯU TIÊN: không có" : "ƯU TIÊN (" + queue.size() + ")"
                + (alert ? " - quá nhiều, kiểm tra lại!" : ""));
        queueTitle.setBackground(alert ? RED_DARK : ORANGE_DARK);
        queueTitle.setForeground(Color.WHITE);
        nextButton.setText(queue.isEmpty() ? "Nhận làm món ưu tiên tiếp theo (Space)"
                : "Nhận làm #1: " + queue.get(0).name + " - bàn " + queue.get(0).table + "  (Space)");
        nextButton.setEnabled(master && !queue.isEmpty());

        StringBuilder sig = new StringBuilder();
        for (Row r : queue) {
            sig.append(r.key()).append(',');
        }
        sig.append(now / 60_000); // cập nhật "x phút trước" mỗi phút
        if (sig.toString().equals(queueSignature)) {
            return;
        }
        queueSignature = sig.toString();
        queueList.removeAll();
        for (int i = 0; i < queue.size(); i++) {
            Row r = queue.get(i);
            long ago = Math.max(0, (now - r.priorityAt) / 60_000);
            JLabel l = new JLabel(String.format("#%d   Bàn %d   %s x%d%s   - xin lúc %s (%d phút trước)",
                    i + 1, r.table, r.name, r.qty, r.note.isEmpty() ? "" : "  [" + r.note + "]",
                    HM.format(new Date(r.priorityAt)), ago));
            l.setFont(i == 0 ? BOLD.deriveFont(18f) : BASE);
            JButton b = new JButton("Nhận làm");
            b.setFont(BOLD);
            b.setFocusable(false);
            b.setBackground(ORANGE_DARK);
            b.setForeground(Color.WHITE);
            b.setEnabled(master);
            b.addActionListener(e -> sendStart(List.of(r.ref())));
            JPanel line = new JPanel(new BorderLayout(10, 0));
            line.setBackground(i == 0 ? Theme.SURFACE : new Color(255, 251, 245));
            line.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(254, 215, 170)),
                    BorderFactory.createEmptyBorder(3, 10, 3, 6)));
            line.add(l, BorderLayout.CENTER);
            line.add(b, BorderLayout.EAST);
            line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
            line.setPreferredSize(new Dimension(100, 42));
            queueList.add(line);
        }
        queueList.revalidate();
        queueList.repaint();
    }

    // ------------------------------------------------------------------ thao tác của masterchef

    private void startNextPriority() {
        if (master && !queue.isEmpty()) {
            sendStart(List.of(queue.get(0).ref()));
        }
    }

    /** Gửi START_ITEMS kèm tên người làm. Chưa nhập tên thì nhắc, không gửi. */
    private void sendStart(List<OrderItem> refs) {
        String chef = currentChef();
        if (chef == null) {
            Beeper.warning();
            showMessage("Nhập tên người làm (góc trên bên phải) trước khi nhận món", RED_DARK);
            chefBox.requestFocusInWindow();
            return;
        }
        Message m = new Message(Protocol.START_ITEMS);
        m.reqId = conn.nextReqId();
        m.items = refs;
        m.chef = chef;
        if (!conn.send(m)) {
            showMessage("Chưa kết nối máy chủ, thử lại sau", RED_DARK);
        }
    }

    /** Tên đang chọn; tên mới được thêm vào danh sách và nhớ lại. */
    private String currentChef() {
        Object o = chefBox.isEditable() ? chefBox.getEditor().getItem() : chefBox.getSelectedItem();
        String chef = o == null ? "" : o.toString().trim().replace("|", "");
        if (chef.isEmpty()) {
            return null;
        }
        boolean known = false;
        for (int i = 0; i < chefBox.getItemCount(); i++) {
            known |= chefBox.getItemAt(i).equalsIgnoreCase(chef);
        }
        if (!known) {
            chefBox.addItem(chef);
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < chefBox.getItemCount(); i++) {
                all.append(i > 0 ? "|" : "").append(chefBox.getItemAt(i));
            }
            prefs.put("chefs", all.toString());
        }
        chefBox.setSelectedItem(chef);
        prefs.put("chef", chef);
        return chef;
    }

    /** Hỏi lý do gia hạn (F15). Trả về null nếu bấm hủy. */
    private String askReason(String title) {
        String[] options = Protocol.EXTEND_REASONS;
        int c = JOptionPane.showOptionDialog(this, "Chọn lý do:", title, JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (c < 0) {
            return null;
        }
        if (c == options.length - 1) {
            String other = JOptionPane.showInputDialog(this, "Lý do khác:", title, JOptionPane.QUESTION_MESSAGE);
            if (other == null) {
                return null;
            }
            return other.isBlank() ? "Khác" : other.trim();
        }
        return options[c];
    }

    private void startSelected() {
        List<OrderItem> refs = selectedRefs(ItemStatus.PENDING);
        if (refs.isEmpty()) {
            showMessage("Chọn món đang chờ làm trước khi bấm Nhận làm", RED_DARK);
            return;
        }
        sendStart(refs);
    }

    private void extendSelected(int minutes) {
        List<OrderItem> refs = selectedRefs(ItemStatus.COOKING);
        if (refs.isEmpty()) {
            showMessage("Chọn món đang làm để gia hạn", RED_DARK);
            return;
        }
        String reason = askReason("Gia hạn +" + minutes + " phút cho " + refs.size() + " món");
        if (reason == null) {
            return;
        }
        Message m = new Message(Protocol.EXTEND_ITEMS);
        m.reqId = conn.nextReqId();
        m.items = refs;
        m.minutes = minutes;
        m.reason = reason;
        if (!conn.send(m)) {
            showMessage("Chưa kết nối máy chủ, thử lại sau", RED_DARK);
        }
    }

    /**
     * Chưa xong (F15): món đã hết giờ (đang chờ lấy) nhưng thực ra chưa làm xong.
     * Đưa món về Đang làm, thêm phút, thu hồi lệnh rung; thời gian thực tế tiếp tục tính.
     */
    private void notDoneSelected() {
        List<OrderItem> refs = selectedRefs(ItemStatus.READY);
        if (refs.isEmpty()) {
            showMessage("Chọn món đang \"Xong - chờ lấy\" để báo Chưa xong", RED_DARK);
            return;
        }
        Object[] mins = {"+1 phút", "+3 phút", "+5 phút"};
        int c = JOptionPane.showOptionDialog(this, "Món chưa xong, cần thêm bao lâu?", "Chưa xong",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, mins, mins[1]);
        if (c < 0) {
            return;
        }
        int minutes = new int[]{1, 3, 5}[c];
        String reason = askReason("Chưa xong, thêm " + minutes + " phút");
        if (reason == null) {
            return;
        }
        Message m = new Message(Protocol.NOT_DONE);
        m.reqId = conn.nextReqId();
        m.items = refs;
        m.minutes = minutes;
        m.reason = reason;
        if (!conn.send(m)) {
            showMessage("Chưa kết nối máy chủ, thử lại sau", RED_DARK);
        }
    }

    private void finishSelected() {
        List<OrderItem> refs = selectedRefs(ItemStatus.COOKING);
        if (refs.isEmpty()) {
            showMessage("Chọn món đang làm để báo xong sớm", RED_DARK);
            return;
        }
        send(Protocol.FINISH_ITEMS, refs, null);
    }

    private void soldOut() {
        String menuId = null;
        if (groupedView && groupTable.getSelectedRow() >= 0) {
            menuId = groups.get(groupTable.getSelectedRow()).menuId;
        } else if (!groupedView && orderTable.getSelectedRow() >= 0) {
            menuId = orderRows.get(orderTable.getSelectedRow()).menuId;
        }
        if (menuId == null) {
            JComboBox<String> box = new JComboBox<>();
            for (MenuItem m : menu) {
                if (!m.isSoldOut()) {
                    box.addItem(m.menuId + " - " + m.name);
                }
            }
            if (JOptionPane.showConfirmDialog(this, box, "Chọn món đã hết", JOptionPane.OK_CANCEL_OPTION)
                    != JOptionPane.OK_OPTION || box.getSelectedItem() == null) {
                return;
            }
            menuId = box.getSelectedItem().toString().split(" - ")[0];
        }
        String name = menuId;
        for (MenuItem m : menu) {
            if (m.menuId.equals(menuId)) {
                name = m.name;
            }
        }
        String reason = JOptionPane.showInputDialog(this,
                "Báo hết \"" + name + "\". Các phần chưa làm sẽ bị hủy.\nLý do:", "Hết món");
        if (reason == null) {
            return;
        }
        Message m = new Message(Protocol.SOLD_OUT);
        m.reqId = conn.nextReqId();
        m.menuId = menuId;
        m.reason = reason.isBlank() ? "Hết món" : reason.trim();
        if (!conn.send(m)) {
            showMessage("Chưa kết nối máy chủ", RED_DARK);
        }
    }

    private void send(String command, List<OrderItem> refs, Integer minutes) {
        Message m = new Message(command);
        m.reqId = conn.nextReqId();
        m.items = refs;
        m.minutes = minutes;
        if (!conn.send(m)) {
            showMessage("Chưa kết nối máy chủ, thử lại sau", RED_DARK);
        }
    }

    /** Các món đang chọn có trạng thái cần thiết. Chế độ gộp: mọi món của các dòng gộp đang chọn. */
    private List<OrderItem> selectedRefs(ItemStatus wanted) {
        List<OrderItem> refs = new ArrayList<>();
        if (groupedView) {
            for (int i : groupTable.getSelectedRows()) {
                List<Row> rs = new ArrayList<>(groups.get(i).rows);
                rs.sort(Comparator.comparingInt((Row r) -> r.inPriorityQueue() ? 0 : 1)
                        .thenComparingLong(r -> r.createdAt));
                for (Row r : rs) {
                    if (r.status == wanted) {
                        refs.add(r.ref());
                    }
                }
            }
        } else {
            for (int i : orderTable.getSelectedRows()) {
                Row r = orderRows.get(i);
                if (r.status == wanted) {
                    refs.add(r.ref());
                }
            }
        }
        return refs;
    }

    private Set<String> selectedKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (int i : orderTable.getSelectedRows()) {
            if (i < orderRows.size()) {
                keys.add(orderRows.get(i).key());
            }
        }
        return keys;
    }

    private void restoreSelection(Set<String> keys) {
        for (int i = 0; i < orderRows.size(); i++) {
            if (keys.contains(orderRows.get(i).key())) {
                orderTable.getSelectionModel().addSelectionInterval(i, i);
            }
        }
    }

    private String selectedGroupId() {
        int i = groupTable.getSelectedRow();
        return i >= 0 && i < groups.size() ? groups.get(i).menuId : null;
    }

    private void showMessage(String text, Color color) {
        messageLabel.setText(text);
        messageLabel.setForeground(color);
        messageUntil = System.currentTimeMillis() + 6_000;
    }

    // ------------------------------------------------------------------ bảng

    private int priorityRank(Row r) {
        return r.inPriorityQueue() ? queue.indexOf(r) + 1 : 0;
    }

    private String timeText(Row r, long now) {
        return switch (r.status) {
            case PENDING -> "chờ " + mmss(now - r.createdAt);
            case COOKING -> r.deadline == null ? "" : "còn " + mmss(r.deadline - now)
                    + (r.extended > 0 ? "  (+" + r.extended + "')" : "");
            case READY -> "chờ lấy";
            default -> "";
        };
    }

    private static String mmss(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private static String statusText(ItemStatus s) {
        return switch (s) {
            case PENDING -> "Chờ làm";
            case COOKING -> "Đang làm";
            case READY -> "Xong - chờ lấy";
            case SERVED -> "Đã lấy";
            case CANCELLED -> "Đã hủy";
        };
    }

    private final class OrderModel extends AbstractTableModel {
        private final String[] cols = {"Ưu tiên", "Bàn", "Món", "SL", "Ghi chú", "Trạng thái", "Thời gian",
                "Người làm", "Tiến độ (chuẩn / thực tế)"};

        @Override
        public int getRowCount() {
            return orderRows.size();
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
        public Object getValueAt(int row, int col) {
            Row r = orderRows.get(row);
            return switch (col) {
                case 0 -> r.inPriorityQueue() ? "#" + priorityRank(r) : "";
                case 1 -> r.table;
                case 2 -> r.name;
                case 3 -> r.qty;
                case 4 -> r.note;
                case 5 -> statusText(r.status);
                case 6 -> timeText(r, conn.serverNow());
                case 7 -> r.chef == null ? "" : r.chef;
                default -> r;
            };
        }
    }

    private final class GroupModel extends AbstractTableModel {
        private final String[] cols = {"Món", "Chờ làm", "Đang làm", "Các bàn", "Ưu tiên"};

        @Override
        public int getRowCount() {
            return groups.size();
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
        public Object getValueAt(int row, int col) {
            Group g = groups.get(row);
            return switch (col) {
                case 0 -> g.name;
                case 1 -> g.pendingQty == 0 ? "" : g.pendingQty + " phần";
                case 2 -> g.cookingQty == 0 ? "" : g.cookingQty + " phần";
                case 3 -> g.tables.toString().replaceAll("[\\[\\]]", "");
                default -> g.priorityCount == 0 ? "" : g.priorityCount + " ưu tiên";
            };
        }
    }

    /** Tô màu theo trạng thái: cam = ưu tiên, xanh dương = đang làm, đỏ nhấp nháy = sắp hết giờ, xanh lá = chờ lấy. */
    private final class ColorRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, value, selected, focus, row, col);
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 6));
            setFont(table == orderTable && col == 2 || table == groupTable && col == 0 ? BOLD : BASE);
            if (selected) {
                return c;
            }
            long now = conn.serverNow();
            Color bg = Color.WHITE;
            if (table == orderTable && row < orderRows.size()) {
                Row r = orderRows.get(row);
                bg = colorOf(r, now);
            } else if (table == groupTable && row < groups.size()) {
                Group g = groups.get(row);
                bg = g.priorityCount > 0 ? ORANGE : g.pendingQty == 0 ? BLUE : Color.WHITE;
            }
            c.setBackground(bg);
            c.setForeground(Theme.TEXT);
            return c;
        }

        private Color colorOf(Row r, long now) {
            if (r.inPriorityQueue()) {
                return ORANGE;
            }
            switch (r.status) {
                case COOKING -> {
                    boolean soon = r.deadline != null && r.deadline - now <= Protocol.WARN_BEFORE_DEADLINE_MS;
                    boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
                    return soon ? (blink ? RED : Color.WHITE) : BLUE;
                }
                case READY -> {
                    return GREEN;
                }
                case PENDING -> {
                    long wait = now - r.createdAt;
                    return wait >= Protocol.PENDING_LATE_MS ? RED : wait >= Protocol.PENDING_WARN_MS ? YELLOW : Color.WHITE;
                }
                default -> {
                    return Color.WHITE;
                }
            }
        }
    }

    /** Cột Trạng thái: viên màu theo trạng thái, nền dòng như các ô khác. */
    private final class StatusRenderer extends Theme.PillRenderer {
        private final ColorRenderer rowColors = new ColorRenderer();

        @Override
        protected Color[] colors(Object value, int row) {
            Row r = row < orderRows.size() ? orderRows.get(row) : null;
            if (r == null) {
                return new Color[]{Theme.BORDER, Theme.TEXT};
            }
            return switch (r.status) {
                case PENDING -> new Color[]{new Color(226, 232, 240), new Color(51, 65, 85)};
                case COOKING -> new Color[]{Theme.PRIMARY, Color.WHITE};
                case READY -> new Color[]{Theme.SUCCESS, Color.WHITE};
                default -> new Color[]{Theme.BORDER, Theme.MUTED};
            };
        }

        @Override
        protected Color rowBackground(JTable t, int row, boolean selected) {
            if (selected || row >= orderRows.size()) {
                return selected ? t.getSelectionBackground() : Theme.SURFACE;
            }
            return rowColors.colorOf(orderRows.get(row), conn.serverNow());
        }
    }

    /**
     * Cột Tiến độ (F15). Thanh trên: thời gian chuẩn của món (cố định). Thanh dưới: thời gian thực tế.
     * Đang làm: khung = tổng thời gian dự kiến (chuẩn + gia hạn), phần tô = đã trôi qua.
     * Đã xong: phần tô = thời gian làm thật. Phần vượt chuẩn tô đỏ.
     */
    private final class BarRenderer extends JComponent implements TableCellRenderer {
        private static final Color STD = new Color(148, 163, 184);
        private static final Color ACT = Theme.PRIMARY;
        private static final Color OK = Theme.SUCCESS;
        private static final Color OVER = Theme.DANGER;
        private Row row;
        private Color bg = Color.WHITE;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focus, int r, int col) {
            row = value instanceof Row x ? x : null;
            bg = selected ? table.getSelectionBackground()
                    : row == null ? Theme.SURFACE : new ColorRenderer().colorOf(row, conn.serverNow());
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(bg);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (row == null || row.prep <= 0) {
                return;
            }
            long now = conn.serverNow();
            long std = row.prep * 60_000L;
            long planned = std;
            long done = 0;
            boolean finished = false;
            if (row.startedAt != null) {
                if (row.status == ItemStatus.READY && row.finishedAt != null) {
                    done = row.finishedAt - row.startedAt;
                    planned = done;
                    finished = true;
                } else if (row.status == ItemStatus.COOKING) {
                    done = Math.max(0, now - row.startedAt);
                    planned = row.deadline == null ? std : Math.max(row.deadline - row.startedAt, done);
                }
            }
            long scale = Math.max(1, Math.max(std, Math.max(planned, done)));
            g.setFont(BASE.deriveFont(12f));
            FontMetrics fm = g.getFontMetrics();
            int textW = 92;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int x0 = 8;
            int w = Math.max(20, getWidth() - x0 - textW - 6);
            int h = 12;
            int y1 = (getHeight() - 2 * h - 6) / 2;
            int y2 = y1 + h + 6;

            // thanh chuẩn
            int ws = (int) (w * std / scale);
            g.setColor(STD);
            g.fillRoundRect(x0, y1, ws, h, h, h);
            g.setColor(Theme.MUTED);
            g.drawString("chuẩn " + mm(std), x0 + w + 6, y1 + fm.getAscent() - 1);

            // thanh thực tế
            if (row.startedAt != null) {
                int wp = (int) (w * planned / scale);
                int wd = (int) (w * done / scale);
                java.awt.Shape track = new java.awt.geom.RoundRectangle2D.Float(x0, y2, Math.max(h, wp), h, h, h);
                g.setColor(new Color(241, 245, 249));
                g.fill(track);
                java.awt.Shape oldClip = g.getClip();
                g.clip(track);
                int wsInDone = Math.min(wd, ws);
                g.setColor(finished ? OK : ACT);
                g.fillRect(x0, y2, wsInDone, h);
                if (wd > ws) {
                    g.setColor(OVER);
                    g.fillRect(x0 + ws, y2, wd - ws, h);
                }
                g.setClip(oldClip);
                g.setColor(new Color(203, 213, 225));
                g.draw(track);
                // vạch chuẩn trên thanh thực tế
                g.setColor(Theme.TEXT);
                g.drawLine(x0 + ws, y2 - 2, x0 + ws, y2 + h + 1);
                long shown = finished ? done : planned;
                g.setColor(shown > std ? OVER : Theme.MUTED);
                g.drawString((finished ? "thực " : "dự kiến ") + mm(shown), x0 + w + 6, y2 + fm.getAscent() - 1);
            } else {
                g.setColor(Theme.MUTED);
                g.drawString("chưa nhận làm", x0, y2 + fm.getAscent() - 1);
            }
        }

        private String mm(long ms) {
            long s = Math.max(0, ms / 1000);
            return s % 60 == 0 ? s / 60 + "'" : s / 60 + "'" + String.format("%02d", s % 60);
        }
    }

    // ------------------------------------------------------------------ khởi động

    public static void main(String[] args) {
        Theme.install();
        // Chữ to trên mọi hộp thoại (chọn lý do, chưa xong...) để bấm nhanh trong bếp
        javax.swing.UIManager.put("OptionPane.buttonFont", BOLD);
        javax.swing.UIManager.put("OptionPane.messageFont", BOLD);
        SwingUtilities.invokeLater(() -> {
            String host = args.length > 0 ? args[0] : null;
            if (host == null) {
                host = JOptionPane.showInputDialog(null, "Địa chỉ IP của máy chủ:", "localhost");
                if (host == null) {
                    System.exit(0);
                }
                host = host.trim().isEmpty() ? "localhost" : host.trim();
            }
            String server = host;
            HomeWindow[] home = new HomeWindow[1];
            home[0] = new HomeWindow("Kitchen Order Suite", new RemoteAuth(server), new HomeWindow.Host() {
                @Override
                public List<HomeWindow.Tile> tiles(AuthApi.Session s) {
                    return List.of(
                            Tiles.kitchen(s, () -> openFor(server, s, home[0])),
                            Tiles.order(s, server, home[0]),
                            Tiles.pager(s, server, home[0]),
                            Tiles.managerRemote(s, server));
                }

                @Override
                public void onLogout(AuthApi.Session s) {
                    closeAll();
                }

                @Override
                public void onClose(AuthApi.Session s) {
                    closeAll();
                    if (s != null) {
                        new RemoteAuth(server).logout(s.token());
                    }
                    System.exit(0);
                }
            });
            home[0].setVisible(true);
        });
    }
}
