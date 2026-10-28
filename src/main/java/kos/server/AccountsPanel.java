package kos.server;

import kos.common.Role;
import kos.common.Theme;
import kos.common.UserInfo;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

/**
 * Thẻ Tài khoản của quầy quản lý (F16): duyệt tài khoản mới, đổi vai trò, khóa / mở khóa,
 * đặt lại mật khẩu, xóa. Mọi thay đổi có hiệu lực ngay: người bị khóa hoặc đổi vai trò bị đăng xuất.
 */
public class AccountsPanel extends JPanel {

    private static final Font BASE = Theme.font(Font.PLAIN, 15);
    private static final Font BOLD = BASE.deriveFont(Font.BOLD);
    private static final SimpleDateFormat TIME = new SimpleDateFormat("dd/MM HH:mm");

    private final AccountStore store;
    private final Supplier<String> currentUser;
    private List<UserInfo> users = new ArrayList<>();
    private int version = -1;
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel info = new JLabel(" ");

    /** @param currentUser tên đăng nhập của quản lý đang dùng (để ghi log và không tự khóa mình) */
    public AccountsPanel(AccountStore store, Supplier<String> currentUser) {
        super(new BorderLayout(0, 8));
        this.store = store;
        this.currentUser = currentUser;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        table.setFont(BASE);
        Theme.table(table, 36);
        table.getTableHeader().setFont(BOLD);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, f, r, c);
                setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 6));
                if (!sel && r < users.size()) {
                    comp.setBackground(UserInfo.PENDING.equals(users.get(r).status) ? Theme.YELLOW_SOFT : Color.WHITE);
                    comp.setForeground(UserInfo.LOCKED.equals(users.get(r).status) ? Theme.MUTED : Theme.TEXT);
                }
                return comp;
            }
        });
        table.getColumnModel().getColumn(3).setCellRenderer(new Theme.PillRenderer() {
            @Override
            protected Color[] colors(Object value, int row) {
                String s = row < users.size() ? users.get(row).status : "";
                if (UserInfo.PENDING.equals(s)) {
                    return new Color[]{Theme.WARNING, Color.WHITE};
                }
                if (UserInfo.LOCKED.equals(s)) {
                    return new Color[]{new Color(226, 232, 240), Theme.MUTED};
                }
                return new Color[]{Theme.SUCCESS_SOFT, new Color(21, 128, 61)};
            }

            @Override
            protected Color rowBackground(JTable t, int row, boolean selected) {
                return selected ? t.getSelectionBackground()
                        : row < users.size() && UserInfo.PENDING.equals(users.get(row).status) ? Theme.YELLOW_SOFT
                        : Color.WHITE;
            }
        });
        int[] w = {150, 200, 120, 130, 130, 160};
        for (int i = 0; i < w.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        }

        JButton approve = Theme.button("Duyệt", Theme.SUCCESS);
        approve.addActionListener(e -> approve());
        JButton role = Theme.button("Đổi vai trò", Theme.PRIMARY);
        role.addActionListener(e -> changeRole());
        JButton lock = Theme.button("Khóa / Mở khóa", Theme.WARNING);
        lock.addActionListener(e -> toggleLock());
        JButton reset = Theme.button("Đặt lại mật khẩu", null);
        reset.addActionListener(e -> resetPassword());
        JButton delete = Theme.button("Xóa / Từ chối", Theme.DANGER);
        delete.addActionListener(e -> delete());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        for (JButton b : List.of(approve, role, lock, reset, delete)) {
            b.setFont(BOLD);
            bar.add(b);
        }
        info.setFont(BASE.deriveFont(13f));
        info.setForeground(Theme.MUTED);
        info.setText("Dòng vàng: tài khoản tự đăng ký đang chờ duyệt. Khóa, đổi vai trò hoặc đặt lại mật khẩu "
                + "thì người đó bị đăng xuất ngay.");
        JPanel south = new JPanel(new BorderLayout());
        south.add(bar, BorderLayout.NORTH);
        south.add(info, BorderLayout.SOUTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        refresh();
    }

    /** Vẽ lại khi danh sách tài khoản thay đổi (gọi mỗi giây từ ManagerWindow). */
    public void refresh() {
        if (store.version() == version) {
            return;
        }
        version = store.version();
        String selected = selectedUsername();
        users = store.list();
        model.fireTableDataChanged();
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i).username.equals(selected)) {
                table.getSelectionModel().setSelectionInterval(i, i);
            }
        }
    }

    private String selectedUsername() {
        int i = table.getSelectedRow();
        return i >= 0 && i < users.size() ? users.get(i).username : null;
    }

    private UserInfo selected() {
        int i = table.getSelectedRow();
        if (i < 0 || i >= users.size()) {
            JOptionPane.showMessageDialog(this, "Chọn một tài khoản trong bảng trước");
            return null;
        }
        return users.get(i);
    }

    private interface Action {
        void run() throws AccountStore.AuthError;
    }

    private void act(Action a) {
        try {
            a.run();
        } catch (AccountStore.AuthError e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Không thực hiện được", JOptionPane.WARNING_MESSAGE);
        }
        refresh();
    }

    private Role askRole(String title, Role current) {
        JComboBox<Role> box = new JComboBox<>(Role.values());
        box.setSelectedItem(current);
        box.setFont(BASE);
        JLabel desc = new JLabel(current.description());
        box.addActionListener(e -> desc.setText(((Role) box.getSelectedItem()).description()));
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(box, BorderLayout.NORTH);
        p.add(desc, BorderLayout.SOUTH);
        return JOptionPane.showConfirmDialog(this, p, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                == JOptionPane.OK_OPTION ? (Role) box.getSelectedItem() : null;
    }

    private void approve() {
        UserInfo u = selected();
        if (u == null) {
            return;
        }
        if (!UserInfo.PENDING.equals(u.status)) {
            JOptionPane.showMessageDialog(this, u.username + " không ở trạng thái chờ duyệt");
            return;
        }
        Role r = askRole("Duyệt " + u.fullName + " với vai trò", u.role);
        if (r != null) {
            act(() -> store.approve(u.username, r, currentUser.get()));
        }
    }

    private void changeRole() {
        UserInfo u = selected();
        if (u == null) {
            return;
        }
        Role r = askRole("Vai trò mới cho " + u.fullName, u.role);
        if (r != null && r != u.role) {
            act(() -> store.setRole(u.username, r, currentUser.get()));
        }
    }

    private void toggleLock() {
        UserInfo u = selected();
        if (u == null) {
            return;
        }
        boolean lock = !UserInfo.LOCKED.equals(u.status);
        if (JOptionPane.showConfirmDialog(this, (lock ? "Khóa" : "Mở khóa") + " tài khoản " + u.username + "?",
                "Xác nhận", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            act(() -> store.setLocked(u.username, lock, currentUser.get()));
        }
    }

    private void resetPassword() {
        UserInfo u = selected();
        if (u == null) {
            return;
        }
        JPasswordField f = new JPasswordField();
        if (JOptionPane.showConfirmDialog(this, new Object[]{"Mật khẩu mới cho " + u.username + " (ít nhất 6 ký tự):", f},
                "Đặt lại mật khẩu", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            act(() -> store.resetPassword(u.username, new String(f.getPassword()), currentUser.get()));
        }
    }

    private void delete() {
        UserInfo u = selected();
        if (u == null) {
            return;
        }
        String what = UserInfo.PENDING.equals(u.status) ? "Từ chối đăng ký của " : "Xóa tài khoản ";
        if (JOptionPane.showConfirmDialog(this, what + u.username + " (" + u.fullName + ")?", "Xác nhận",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
            act(() -> store.delete(u.username, currentUser.get()));
        }
    }

    private final class Model extends AbstractTableModel {
        private final String[] cols = {"Tên đăng nhập", "Họ tên", "Vai trò", "Trạng thái", "Tạo lúc", "Đăng nhập gần nhất"};

        @Override
        public int getRowCount() {
            return users.size();
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
            UserInfo u = users.get(row);
            return switch (col) {
                case 0 -> u.username;
                case 1 -> u.fullName;
                case 2 -> u.role.label();
                case 3 -> UserInfo.statusLabel(u.status);
                case 4 -> u.createdAt == null ? "" : TIME.format(new Date(u.createdAt));
                default -> u.lastLogin == null ? "chưa" : TIME.format(new Date(u.lastLogin));
            };
        }
    }
}
