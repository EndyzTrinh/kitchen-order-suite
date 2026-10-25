package kos.kitchen;

import kos.common.Beeper;
import kos.common.ItemStatus;
import kos.common.MenuItem;
import kos.common.Message;
import kos.common.Order;
import kos.common.OrderItem;
import kos.common.Protocol;

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
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
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

/**
 * Masterchef Display (F04 - F07, F14): màn hình bếp kết nối TCP tới máy chủ.
 * <ul>
 *   <li>Dải ưu tiên màu cam trên cùng: món khách xin làm trước, xếp theo thứ tự xin (#1 làm trước).
 *       Nút lớn hoặc phím Space: nhận làm món ưu tiên tiếp theo.</li>
 *   <li>Hai chế độ xem: theo đơn, và gộp theo món (cộng số phần của cùng một món ở các bàn).</li>
 *   <li>Nhận làm, gia hạn +1 / +3 / +5 phút, xong sớm, báo hết món (chỉ chế độ MASTERCHEF).</li>
 *   <li>Đồng hồ đếm ngược theo giờ máy chủ; còn 30 giây thì nhấp nháy đỏ.</li>
 * </ul>
 * Cách chạy: Run File. Tham số (không bắt buộc): IP máy chủ, MASTERCHEF hoặc VIEW.
 * Không có tham số thì cửa sổ hỏi địa chỉ máy chủ khi mở.
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

    private static final Font BASE = new Font(Font.SANS_SERIF, Font.PLAIN, 17);
    private static final Font BOLD = BASE.deriveFont(Font.BOLD);
    private static final Color ORANGE = new Color(255, 224, 178);
    private static final Color ORANGE_DARK = new Color(230, 126, 34);
    private static final Color RED_DARK = new Color(192, 57, 43);
    private static final Color BLUE = new Color(214, 234, 248);
    private static final Color GREEN = new Color(212, 239, 223);
    private static final Color YELLOW = new Color(252, 243, 207);
    private static final Color RED = new Color(245, 183, 177);
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

    public MasterchefApp(String host, String mode) {
        super("Masterchef Display - " + mode);
        this.master = Protocol.MODE_MASTERCHEF.equals(mode);
        String name = master ? "MC" : "VIEW-" + (System.currentTimeMillis() % 1000);
        this.conn = new ServerConnection(host, name, mode, this);
        buildUi();
        new Timer(500, e -> refresh()).start();
        conn.start();
    }

    private void buildUi() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                conn.stop();
                dispose();
                System.exit(0);
            }
        });

        // Thanh trên: trạng thái kết nối, chế độ xem, đồng hồ
        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
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
        views.add(byOrder);
        views.add(byDish);
        top.add(statusLabel, BorderLayout.WEST);
        top.add(views, BorderLayout.CENTER);
        top.add(clockLabel, BorderLayout.EAST);

        // Dải ưu tiên
        JPanel strip = new JPanel(new BorderLayout(0, 6));
        strip.setBackground(ORANGE);
        strip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(3, 0, 3, 0, ORANGE_DARK),
                BorderFactory.createEmptyBorder(6, 12, 8, 12)));
        queueTitle.setFont(BOLD.deriveFont(19f));
        queueTitle.setOpaque(true);
        queueTitle.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        queueList.setLayout(new BoxLayout(queueList, BoxLayout.Y_AXIS));
        queueList.setOpaque(false);
        JScrollPane queueScroll = new JScrollPane(queueList);
        queueScroll.setOpaque(false);
        queueScroll.getViewport().setOpaque(false);
        queueScroll.setBorder(null);
        queueScroll.getVerticalScrollBar().setUnitIncrement(21);
        queueScroll.setPreferredSize(new Dimension(100, 176));
        nextButton.setFont(BOLD.deriveFont(18f));
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
        int[] w = {90, 70, 260, 50, 220, 130, 140};
        for (int i = 0; i < w.length; i++) {
            orderTable.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        }
        int[] gw = {300, 100, 100, 260, 120};
        for (int i = 0; i < gw.length; i++) {
            groupTable.getColumnModel().getColumn(i).setPreferredWidth(gw[i]);
        }
        center.add(new JScrollPane(orderTable), "order");
        center.add(new JScrollPane(groupTable), "group");

        // Thanh nút dưới
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        actions.add(action("Nhận làm", new Color(39, 174, 96), this::startSelected));
        actions.add(Box.createHorizontalStrut(10));
        actions.add(action("+1 phút", null, () -> extendSelected(1)));
        actions.add(action("+3 phút", null, () -> extendSelected(3)));
        actions.add(action("+5 phút", null, () -> extendSelected(5)));
        actions.add(Box.createHorizontalStrut(10));
        actions.add(action("Xong sớm", new Color(41, 128, 185), this::finishSelected));
        actions.add(Box.createHorizontalStrut(10));
        actions.add(action("Báo hết món", RED_DARK, this::soldOut));
        messageLabel.setFont(BOLD);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(actions, BorderLayout.NORTH);
        bottom.add(messageLabel, BorderLayout.SOUTH);
        messageLabel.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
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
        b.setPreferredSize(new Dimension(Math.max(130, text.length() * 16), 46));
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
        t.setRowHeight(34);
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
            statusLabel.setForeground(connected ? new Color(30, 132, 73) : RED_DARK);
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
                                new Color(30, 132, 73));
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
        if (r.status == ItemStatus.READY && "Hết giờ".equals(m.by)) {
            showMessage("Hết giờ: " + r.name + " bàn " + r.table + " - đã báo phục vụ", new Color(30, 132, 73));
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
            b.setEnabled(master);
            b.addActionListener(e -> send(Protocol.START_ITEMS, List.of(r.ref()), null));
            JPanel line = new JPanel(new BorderLayout(10, 0));
            line.setOpaque(false);
            line.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
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
            send(Protocol.START_ITEMS, List.of(queue.get(0).ref()), null);
        }
    }

    private void startSelected() {
        List<OrderItem> refs = selectedRefs(ItemStatus.PENDING);
        if (refs.isEmpty()) {
            showMessage("Chọn món đang chờ làm trước khi bấm Nhận làm", RED_DARK);
            return;
        }
        send(Protocol.START_ITEMS, refs, null);
    }

    private void extendSelected(int minutes) {
        List<OrderItem> refs = selectedRefs(ItemStatus.COOKING);
        if (refs.isEmpty()) {
            showMessage("Chọn món đang làm để gia hạn", RED_DARK);
            return;
        }
        send(Protocol.EXTEND_ITEMS, refs, minutes);
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
        private final String[] cols = {"Ưu tiên", "Bàn", "Món", "SL", "Ghi chú", "Trạng thái", "Thời gian"};

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
                default -> timeText(r, conn.serverNow());
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
            c.setForeground(Color.BLACK);
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

    // ------------------------------------------------------------------ khởi động

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            String host = args.length > 0 ? args[0] : null;
            String mode = args.length > 1 ? args[1].toUpperCase() : null;
            if (host == null) {
                host = JOptionPane.showInputDialog(null, "Địa chỉ IP của máy chủ:", "localhost");
                if (host == null) {
                    System.exit(0);
                }
                host = host.trim().isEmpty() ? "localhost" : host.trim();
            }
            if (mode == null) {
                Object[] options = {"Masterchef (đủ quyền)", "Chỉ xem (màn hình phụ)"};
                int c = JOptionPane.showOptionDialog(null, "Chọn chế độ cho màn hình này:", "Masterchef Display",
                        JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
                if (c < 0) {
                    System.exit(0);
                }
                mode = c == 0 ? Protocol.MODE_MASTERCHEF : Protocol.MODE_VIEW;
            }
            new MasterchefApp(host, mode).setVisible(true);
        });
    }
}
