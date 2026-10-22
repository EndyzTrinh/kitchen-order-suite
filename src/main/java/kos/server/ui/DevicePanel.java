package kos.server.ui;

import kos.server.ClientHandler;
import kos.server.DeviceRegistry;
import kos.server.ServerContext;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Thẻ Thiết bị (F11): màn hình bếp kết nối TCP (ClientRegistry) và các trang web
 * - trang gọi món, thiết bị rung - theo dõi bằng heartbeat (DeviceRegistry). Làm mới mỗi giây.
 */
class DevicePanel extends JPanel {

    private static final String ONLINE = "Đang kết nối";
    private static final String OFFLINE = "Mất kết nối";

    private final ServerContext ctx;
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Loại", "Tên", "IP", "Lần cuối nhận tin", "Trạng thái"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JLabel summary = new JLabel(" ");
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss");

    DevicePanel(ServerContext ctx) {
        super(new BorderLayout(8, 8));
        this.ctx = ctx;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JTable table = new JTable(model);
        Ui.style(table);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, f, r, c);
                if (!sel) {
                    boolean on = ONLINE.equals(model.getValueAt(r, 4));
                    comp.setForeground(c == 4 ? (on ? Ui.GREEN_TEXT : Ui.RED_TEXT) : java.awt.Color.BLACK);
                    comp.setBackground(on ? Ui.WHITE : Ui.RED);
                }
                return comp;
            }
        });
        summary.setFont(summary.getFont().deriveFont(Font.BOLD, 14f));
        add(summary, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(new JLabel("Trang web quá 15 giây không gửi heartbeat thì bị coi là mất kết nối; "
                + "màn hình bếp hiện ở đây khi đang giữ kết nối TCP cổng 6000."), BorderLayout.SOUTH);

        new Timer(1000, e -> reload()).start();
        reload();
    }

    private void reload() {
        model.setRowCount(0);
        int online = 0;
        int kitchens = 0;
        for (ClientHandler c : ctx.clients().getAll()) {
            if (!c.isRegistered()) {
                continue; // đã kết nối TCP nhưng chưa HELLO
            }
            kitchens++;
            online++;
            model.addRow(new Object[]{"Màn hình bếp (" + c.getMode() + ")", c.getName(), c.getRemoteIp(),
                    "giữ kết nối TCP", ONLINE});
        }
        int web = 0;
        for (DeviceRegistry.Device d : ctx.devices().getAll()) {
            web++;
            if (d.online()) {
                online++;
            }
            String name = DeviceRegistry.TYPE_PAGER.equals(d.type()) ? "Số " + d.name() : d.name();
            model.addRow(new Object[]{d.type(), name, d.ip(), time.format(new Date(d.lastSeen())),
                    d.online() ? ONLINE : OFFLINE});
        }
        summary.setText((kitchens + web) + " thiết bị   ·   " + online + " đang kết nối   ·   "
                + (kitchens + web - online) + " mất kết nối");
    }
}
