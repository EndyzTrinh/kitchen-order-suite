package kos.server.ui;

import kos.common.ItemStatus;
import kos.common.Order;
import kos.common.OrderItem;
import kos.common.Protocol;
import kos.server.PagerDispatcher.OpenCall;
import kos.server.ServerContext;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Thẻ Đơn hàng (F10 phần hủy món, F09 phần quầy):
 * bảng mọi món của các đơn chưa hoàn tất, bảng lệnh rung đang mở,
 * nút hủy món (chỉ món bếp chưa nhận làm) và nút xác nhận đã lấy món thủ công.
 * Giao diện không tự đổi trạng thái: mọi thao tác gọi KitchenController / PagerDispatcher của máy chủ,
 * bảng chỉ vẽ lại theo dữ liệu đọc từ OrderStore mỗi giây.
 */
class OrderPanel extends JPanel {

    /** Một dòng của bảng món. */
    private record Row(Order order, OrderItem item, OpenCall call) {
        String key() {
            return order.orderId + "-" + item.itemNo;
        }
    }

    private static final String[] ITEM_COLS = {"Đơn", "Bàn", "Món", "SL", "Ghi chú", "Trạng thái", "Thời gian",
            "TB rung", "Người gửi"};
    private static final String[] CALL_COLS = {"Mã lệnh", "Bàn", "Đơn", "Thiết bị", "Món", "Tình trạng", "Đã chờ"};
    private static final String[] REASONS = {"Khách hủy", "Gọi nhầm món", "Khác"};

    private final ServerContext ctx;
    private List<Row> rows = new ArrayList<>();
    private List<OpenCall> calls = new ArrayList<>();

    private final AbstractTableModel itemModel = new AbstractTableModel() {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return ITEM_COLS.length;
        }

        @Override
        public String getColumnName(int c) {
            return ITEM_COLS[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            Row x = rows.get(r);
            OrderItem it = x.item();
            return switch (c) {
                case 0 -> x.order().orderId;
                case 1 -> x.order().table;
                case 2 -> it.name;
                case 3 -> it.qty;
                case 4 -> it.note == null ? "" : it.note;
                case 5 -> Ui.label(it.status) + (it.status == ItemStatus.CANCELLED && it.reason != null
                        ? " (" + it.reason + ")" : "");
                case 6 -> timeText(x);
                case 7 -> x.order().pagerId;
                default -> x.order().waiter == null ? "" : x.order().waiter;
            };
        }
    };

    private final AbstractTableModel callModel = new AbstractTableModel() {
        @Override
        public int getRowCount() {
            return calls.size();
        }

        @Override
        public int getColumnCount() {
            return CALL_COLS.length;
        }

        @Override
        public String getColumnName(int c) {
            return CALL_COLS[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            OpenCall k = calls.get(r);
            return switch (c) {
                case 0 -> k.callId();
                case 1 -> k.table();
                case 2 -> k.orderId();
                case 3 -> k.pagerId();
                case 4 -> k.itemsText();
                case 5 -> k.forwarded() ? "Thiết bị " + k.pagerId() + " không phản hồi, đã chuyển cho mọi thiết bị"
                        : k.received() ? "Thiết bị đã nhận, chờ phục vụ lấy" : "Chưa xác nhận";
                default -> Ui.mmss(System.currentTimeMillis() - k.createdAt());
            };
        }
    };

    private final JTable itemTable = new JTable(itemModel);
    private final JTable callTable = new JTable(callModel);
    private final JButton cancelBtn = new JButton("Hủy món...");
    private final JButton confirmBtn = new JButton("Xác nhận đã lấy món");
    private final JLabel summary = new JLabel(" ");

    OrderPanel(ServerContext ctx) {
        super(new BorderLayout(8, 8));
        this.ctx = ctx;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        Ui.style(itemTable);
        Ui.style(callTable);
        int[] widths = {50, 50, 200, 40, 160, 150, 110, 60, 90};
        for (int i = 0; i < widths.length; i++) {
            itemTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        callTable.getColumnModel().getColumn(4).setPreferredWidth(220);
        callTable.getColumnModel().getColumn(5).setPreferredWidth(320);
        itemTable.setDefaultRenderer(Object.class, new ItemRenderer());
        callTable.setDefaultRenderer(Object.class, new CallRenderer());
        itemTable.getSelectionModel().addListSelectionListener(e -> updateButtons());
        callTable.getSelectionModel().addListSelectionListener(e -> updateButtons());

        summary.setFont(summary.getFont().deriveFont(Font.BOLD, 14f));
        add(summary, BorderLayout.NORTH);

        JPanel itemBox = new JPanel(new BorderLayout(4, 4));
        itemBox.add(new JScrollPane(itemTable), BorderLayout.CENTER);
        JPanel itemButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        itemButtons.add(cancelBtn);
        itemButtons.add(new JLabel("Chỉ hủy được món bếp chưa nhận làm."));
        itemBox.add(itemButtons, BorderLayout.SOUTH);

        JPanel callBox = new JPanel(new BorderLayout(4, 4));
        JLabel callTitle = new JLabel("Lệnh rung đang mở (món đã xong, chờ phục vụ lấy)");
        callTitle.setFont(callTitle.getFont().deriveFont(Font.BOLD));
        callBox.add(callTitle, BorderLayout.NORTH);
        callBox.add(new JScrollPane(callTable), BorderLayout.CENTER);
        JPanel callButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        callButtons.add(confirmBtn);
        callButtons.add(new JLabel("Dùng khi không có thiết bị rung nào phản hồi."));
        callBox.add(callButtons, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, itemBox, callBox);
        split.setResizeWeight(0.68);
        add(split, BorderLayout.CENTER);

        cancelBtn.addActionListener(e -> cancelSelected());
        confirmBtn.addActionListener(e -> confirmSelected());
        updateButtons();

        new Timer(1000, e -> reload()).start();
        reload();
    }

    /** Đọc lại dữ liệu từ máy chủ, giữ nguyên dòng đang chọn. */
    private void reload() {
        String selItem = selectedRow() == null ? null : selectedRow().key();
        String selCall = selectedCall() == null ? null : selectedCall().callId();

        calls = ctx.pagers().getOpenCalls();
        List<Row> list = new ArrayList<>();
        int pending = 0;
        int cooking = 0;
        int ready = 0;
        List<Order> orders = Ui.copyOpenOrders(ctx);
        for (Order o : orders) {
            for (OrderItem it : o.items) {
                list.add(new Row(o, it, findCall(o.orderId, it.itemNo)));
                if (it.status == ItemStatus.PENDING) {
                    pending++;
                } else if (it.status == ItemStatus.COOKING) {
                    cooking++;
                } else if (it.status == ItemStatus.READY) {
                    ready++;
                }
            }
        }
        rows = list;
        itemModel.fireTableDataChanged();
        callModel.fireTableDataChanged();
        reselect(selItem, selCall);
        summary.setText(orders.size() + " đơn đang mở   ·   " + pending + " chờ nhận làm   ·   " + cooking
                + " đang làm   ·   " + ready + " chờ lấy   ·   bếp: " + ctx.clients().kitchenCount()
                + " màn hình   ·   thiết bị rung: " + ctx.pagers().connectedCount());
        updateButtons();
    }

    private OpenCall findCall(int orderId, int itemNo) {
        for (OpenCall k : calls) {
            if (k.orderId() == orderId && k.itemNos().contains(itemNo)) {
                return k;
            }
        }
        return null;
    }

    private void reselect(String itemKey, String callId) {
        for (int i = 0; itemKey != null && i < rows.size(); i++) {
            if (rows.get(i).key().equals(itemKey)) {
                itemTable.setRowSelectionInterval(i, i);
                break;
            }
        }
        for (int i = 0; callId != null && i < calls.size(); i++) {
            if (calls.get(i).callId().equals(callId)) {
                callTable.setRowSelectionInterval(i, i);
                break;
            }
        }
    }

    private void cancelSelected() {
        Row x = selectedRow();
        if (x == null) {
            return;
        }
        Object reason = JOptionPane.showInputDialog(this,
                "Hủy " + x.item().name + " x" + x.item().qty + " (đơn " + x.order().orderId + ", bàn "
                        + x.order().table + ")?\nLý do:",
                "Hủy món", JOptionPane.QUESTION_MESSAGE, null, REASONS, REASONS[0]);
        if (reason == null) {
            return;
        }
        // Máy chủ kiểm tra lại trạng thái: bếp có thể vừa nhận làm đúng lúc quản lý bấm hủy.
        String err = ctx.kitchen().cancelItem(x.order().orderId, x.item().itemNo, reason.toString(), "Quản lý");
        if (err != null) {
            Ui.error(this, err);
        }
        reload();
    }

    private void confirmSelected() {
        OpenCall k = selectedCall();
        if (k == null) {
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this,
                "Xác nhận phục vụ đã lấy món bàn " + k.table() + ":\n" + k.itemsText() + "?",
                "Xác nhận đã lấy món", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) {
            return;
        }
        if (!ctx.pagers().confirmManually(k.callId())) {
            Ui.error(this, "Lệnh " + k.callId() + " đã được thiết bị rung xử lý trước đó.");
        }
        reload();
    }

    private void updateButtons() {
        Row x = selectedRow();
        cancelBtn.setEnabled(x != null && x.item().status == ItemStatus.PENDING);
        confirmBtn.setEnabled(selectedCall() != null);
    }

    private Row selectedRow() {
        int r = itemTable.getSelectedRow();
        return r < 0 || r >= rows.size() ? null : rows.get(r);
    }

    private OpenCall selectedCall() {
        int r = callTable.getSelectedRow();
        return r < 0 || r >= calls.size() ? null : calls.get(r);
    }

    /** Món chờ lấy quá 60 giây hoặc thiết bị rung không phản hồi thì tô đỏ (F09). */
    private static boolean overdue(OpenCall k) {
        return k != null && (k.forwarded()
                || System.currentTimeMillis() - k.createdAt() >= Protocol.PICKUP_REMIND_MS);
    }

    private static String timeText(Row x) {
        OrderItem it = x.item();
        long now = System.currentTimeMillis();
        if (it.status == ItemStatus.PENDING && x.order().createdAt != null) {
            return "chờ " + Ui.mmss(now - x.order().createdAt);
        }
        if (it.status == ItemStatus.COOKING && it.deadline != null) {
            return "còn " + Ui.mmss(it.deadline - now)
                    + (it.extendedMinutes != null && it.extendedMinutes > 0 ? " (+" + it.extendedMinutes + "')" : "");
        }
        if (it.status == ItemStatus.READY && x.call() != null) {
            return "chờ lấy " + Ui.mmss(now - x.call().createdAt());
        }
        return "";
    }

    private class ItemRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            Component comp = super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            if (!sel && r < rows.size()) {
                Row x = rows.get(r);
                ItemStatus s = x.item().status;
                if (s == ItemStatus.READY) {
                    comp.setBackground(overdue(x.call()) ? Ui.RED : Ui.YELLOW);
                } else if (s == ItemStatus.COOKING) {
                    comp.setBackground(Ui.BLUE);
                } else if (s != null && s.isFinished()) {
                    comp.setBackground(Ui.GREY);
                } else {
                    comp.setBackground(Ui.WHITE);
                }
            }
            return comp;
        }
    }

    private class CallRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            Component comp = super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            if (!sel && r < calls.size()) {
                comp.setBackground(overdue(calls.get(r)) ? Ui.RED : Ui.YELLOW);
            }
            return comp;
        }
    }
}
