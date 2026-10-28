package kos.ui;

import kos.common.Role;
import kos.common.Theme;

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
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

/**
 * Giao diện hệ thống chung (F16), dùng trên mọi máy:
 * <ol>
 *   <li>Màn hình đăng nhập / đăng ký (lần đầu trên máy chủ: tạo tài khoản Quản lý).</li>
 *   <li>Trang chủ: các ô chức năng. Ô nào vai trò không được dùng thì bị khóa và ghi rõ lý do.</li>
 * </ol>
 * Các ô do nơi chạy (máy chủ hay máy bếp) cung cấp qua {@link Host}.
 * Lệnh mạng (đăng nhập qua TCP) chạy trên luồng nền để giao diện không bị treo.
 */
public class HomeWindow extends JFrame {

    /** Một ô chức năng trên trang chủ. */
    public record Tile(String title, String description, String letter, Color color, boolean allowed,
                       String lockedText, Runnable action) { }

    /** Nơi chạy cung cấp các ô và xử lý đăng xuất / đóng cửa sổ. */
    public interface Host {
        List<Tile> tiles(AuthApi.Session session);

        /** Đóng các cửa sổ con của người vừa đăng xuất. */
        void onLogout(AuthApi.Session session);

        /** Bấm nút đóng cửa sổ; session null nếu chưa đăng nhập. */
        void onClose(AuthApi.Session session);
    }

    private static final Font BASE = Theme.font(Font.PLAIN, 15);
    private static final Font BOLD = BASE.deriveFont(Font.BOLD);

    private final AuthApi auth;
    private final Host host;
    private final CardLayout pages = new CardLayout();
    private final JPanel root = new JPanel(pages);
    private AuthApi.Session session;

    // đăng nhập / đăng ký
    private final CardLayout forms = new CardLayout();
    private final JPanel formCards = new JPanel(forms);
    private final JPanel toggles = new JPanel(new GridLayout(1, 2, 6, 0));
    private final JLabel formTitle = new JLabel();
    private final JTextField loginUser = new JTextField();
    private final JPasswordField loginPass = new JPasswordField();
    private final JLabel loginMsg = message();
    private final JButton loginButton = Theme.button("Đăng nhập", Theme.PRIMARY);
    private final JTextField regName = new JTextField();
    private final JTextField regUser = new JTextField();
    private final JPasswordField regPass = new JPasswordField();
    private final JPasswordField regPass2 = new JPasswordField();
    private final JComboBox<Role> regRole = new JComboBox<>(new Role[]{Role.BEP_TRUONG, Role.BEP_PHU, Role.PHUC_VU});
    private final JLabel regRoleDesc = new JLabel();
    private final JLabel regMsg = message();
    private final JButton regButton = Theme.button("Gửi đăng ký", Theme.PRIMARY);
    private final JToggleButton tabLogin = new JToggleButton("Đăng nhập");
    private final JToggleButton tabRegister = new JToggleButton("Đăng ký");
    private boolean firstManagerMode;

    // trang chủ
    private final JLabel userName = new JLabel();
    private final JLabel userRole = new JLabel();
    private final Avatar avatar = new Avatar();
    private final JLabel greeting = Theme.title("", 26);
    private final JLabel roleDesc = new JLabel();
    private final JPanel grid = new JPanel(new GridLayout(0, 4, 16, 16));
    private String tileSignature = "";

    public HomeWindow(String title, AuthApi auth, Host host) {
        super(title);
        this.auth = auth;
        this.host = host;
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                host.onClose(session);
            }
        });
        root.add(buildLoginPage(), "login");
        root.add(buildHomePage(), "home");
        setContentPane(root);
        setSize(1200, 760);
        setMinimumSize(new Dimension(900, 640));
        setLocationRelativeTo(null);
        showLogin(null);
        // Mỗi 3 giây: phiên còn hiệu lực không (bị khóa / đổi vai trò thì tự đăng xuất), cập nhật các ô.
        new Timer(3000, e -> tick()).start();
    }

    public AuthApi.Session session() {
        return session;
    }

    // ------------------------------------------------------------------ trang đăng nhập

    private static JLabel message() {
        JLabel l = new JLabel(" ");
        l.setFont(BOLD.deriveFont(14f));
        return l;
    }

    private JPanel buildLoginPage() {
        JPanel page = new JPanel(new BorderLayout());

        // cột trái: giới thiệu hệ thống và các vai trò
        JPanel brand = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setPaint(new GradientPaint(0, 0, new Color(29, 78, 216), getWidth(), getHeight(), new Color(15, 23, 42)));
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        brand.setPreferredSize(new Dimension(400, 10));
        brand.setBorder(BorderFactory.createEmptyBorder(48, 40, 32, 36));
        JLabel name = white("Kitchen Order Suite", 30, Font.BOLD);
        JLabel tag = white("Hệ thống gọi món & điều phối bếp", 16, Font.PLAIN);
        brand.add(name);
        brand.add(Box.createVerticalStrut(6));
        brand.add(tag);
        brand.add(Box.createVerticalStrut(40));
        brand.add(white("Vai trò trong hệ thống", 14, Font.BOLD));
        brand.add(Box.createVerticalStrut(10));
        for (Role r : Role.values()) {
            JLabel l = white("<html><b>" + r.label() + "</b><br><span style='color:#cbd5e1'>" + r.description()
                    + "</span></html>", 14, Font.PLAIN);
            l.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
            brand.add(l);
        }
        brand.add(Box.createVerticalGlue());
        brand.add(white(auth.serverLabel(), 13, Font.PLAIN));

        // cột phải: thẻ đăng nhập / đăng ký
        JPanel card = new RoundPanel(Theme.SURFACE, 20);
        card.setLayout(new BorderLayout(0, 14));
        card.setBorder(BorderFactory.createEmptyBorder(24, 32, 22, 32));
        card.setPreferredSize(new Dimension(440, 680));
        formTitle.setFont(BOLD.deriveFont(24f));
        formTitle.setForeground(Theme.TEXT);
        ButtonGroup bg = new ButtonGroup();
        bg.add(tabLogin);
        bg.add(tabRegister);
        for (JToggleButton b : List.of(tabLogin, tabRegister)) {
            b.setFocusable(false);
            b.setFont(BOLD);
            toggles.add(b);
        }
        toggles.setOpaque(false);
        tabLogin.addActionListener(e -> showForm(false));
        tabRegister.addActionListener(e -> showForm(true));
        JPanel head = new JPanel(new BorderLayout(0, 14));
        head.setOpaque(false);
        head.add(formTitle, BorderLayout.NORTH);
        head.add(toggles, BorderLayout.SOUTH);
        formCards.setOpaque(false);
        formCards.add(buildLoginForm(), "login");
        formCards.add(buildRegisterForm(), "register");
        card.add(head, BorderLayout.NORTH);
        card.add(formCards, BorderLayout.CENTER);

        JPanel right = new JPanel(new GridBagLayout());
        right.add(card);
        page.add(brand, BorderLayout.WEST);
        page.add(right, BorderLayout.CENTER);
        return page;
    }

    private static JLabel white(String text, int size, int style) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(style, size));
        l.setForeground(Color.WHITE);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** Một cột nhãn + ô nhập, xếp dọc. */
    private static JPanel column(Object... parts) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTH;
        int y = 0;
        for (Object o : parts) {
            c.gridy = y++;
            if (o instanceof String s) {
                JLabel l = new JLabel(s);
                l.setFont(BOLD.deriveFont(13f));
                l.setForeground(Theme.MUTED);
                c.insets = new Insets(y == 1 ? 0 : 8, 0, 4, 0);
                p.add(l, c);
            } else {
                JComponent comp = (JComponent) o;
                if (comp instanceof JTextField || comp instanceof JComboBox) {
                    comp.setFont(BASE.deriveFont(16f));
                    comp.setPreferredSize(new Dimension(10, 40));
                    comp.setMinimumSize(new Dimension(10, 40));
                }
                if (comp instanceof JButton) {
                    comp.setFont(BOLD.deriveFont(17f));
                    comp.setPreferredSize(new Dimension(10, 48));
                    c.insets = new Insets(14, 0, 0, 0);
                } else {
                    c.insets = new Insets(0, 0, 0, 0);
                }
                p.add(comp, c);
            }
        }
        c.gridy = y;
        c.weighty = 1;
        p.add(Box.createVerticalGlue(), c);
        return p;
    }

    private JPanel buildLoginForm() {
        loginButton.addActionListener(e -> doLogin());
        loginPass.addActionListener(e -> doLogin());
        loginUser.addActionListener(e -> loginPass.requestFocusInWindow());
        JLabel hint = new JLabel("<html>Chưa có tài khoản? Chọn <b>Đăng ký</b>, quản lý duyệt xong là đăng nhập được.</html>");
        hint.setFont(BASE.deriveFont(13f));
        hint.setForeground(Theme.MUTED);
        return column("Tên đăng nhập", loginUser, "Mật khẩu", loginPass, loginButton, loginMsg, hint);
    }

    private JPanel buildRegisterForm() {
        regButton.addActionListener(e -> doRegister());
        regPass2.addActionListener(e -> doRegister());
        regRoleDesc.setFont(BASE.deriveFont(13f));
        regRoleDesc.setForeground(Theme.MUTED);
        regRole.addActionListener(e -> regRoleDesc.setText("<html>" + ((Role) regRole.getSelectedItem()).description()
                + "</html>"));
        regRoleDesc.setText("<html>" + Role.BEP_TRUONG.description() + "</html>");
        return column("Họ tên", regName, "Tên đăng nhập (không dấu, ví dụ: nam.th)", regUser,
                "Mật khẩu (ít nhất 6 ký tự)", regPass, "Nhập lại mật khẩu", regPass2,
                "Vai trò xin cấp", regRole, regRoleDesc, regButton, regMsg);
    }

    private void showForm(boolean register) {
        tabLogin.setSelected(!register);
        tabRegister.setSelected(register);
        forms.show(formCards, register ? "register" : "login");
        if (!firstManagerMode) {
            formTitle.setText(register ? "Đăng ký tài khoản" : "Đăng nhập");
        }
        SwingUtilities.invokeLater(() -> (register ? regName : loginUser.getText().isEmpty() ? loginUser : loginPass)
                .requestFocusInWindow());
    }

    /** Về màn hình đăng nhập; message hiện dưới nút (null = không có). */
    public void showLogin(String message) {
        firstManagerMode = auth.needsFirstManager();
        toggles.setVisible(!firstManagerMode);
        regRole.setVisible(!firstManagerMode);
        regRoleDesc.setVisible(!firstManagerMode);
        // nhãn "Vai trò xin cấp" là thành phần đứng ngay trước ô vai trò
        Component[] cs = regRole.getParent().getComponents();
        for (int i = 1; i < cs.length; i++) {
            if (cs[i] == regRole) {
                cs[i - 1].setVisible(!firstManagerMode);
            }
        }
        if (firstManagerMode) {
            formTitle.setText("<html>Thiết lập lần đầu<br><span style='font-size:12px;color:#64748b'>"
                    + "Tạo tài khoản Quản lý cho hệ thống</span></html>");
            regButton.setText("Tạo tài khoản Quản lý");
            showForm(true);
        } else {
            regButton.setText("Gửi đăng ký");
            showForm(false);
        }
        loginPass.setText("");
        loginMsg.setText(message == null ? " " : "<html>" + message + "</html>");
        loginMsg.setForeground(Theme.DANGER);
        getRootPane().setDefaultButton(null);
        pages.show(root, "login");
    }

    /** Chạy lệnh mạng trên luồng nền, xong thì quay về luồng giao diện. */
    private void background(JButton button, String busyText, Task task) {
        String old = button.getText();
        button.setEnabled(false);
        button.setText(busyText);
        Thread t = new Thread(() -> {
            Runnable done;
            try {
                done = task.run();
            } catch (AuthApi.AuthException e) {
                done = () -> {
                    JLabel target = button == loginButton ? loginMsg : regMsg;
                    target.setForeground(Theme.DANGER);
                    target.setText("<html>" + e.getMessage() + "</html>");
                };
            }
            Runnable finish = done;
            SwingUtilities.invokeLater(() -> {
                button.setEnabled(true);
                button.setText(old);
                finish.run();
            });
        }, "auth");
        t.setDaemon(true);
        t.start();
    }

    private interface Task {
        Runnable run() throws AuthApi.AuthException;
    }

    private void doLogin() {
        String u = loginUser.getText().trim();
        String p = new String(loginPass.getPassword());
        if (u.isEmpty() || p.isEmpty()) {
            loginMsg.setForeground(Theme.DANGER);
            loginMsg.setText("Nhập tên đăng nhập và mật khẩu");
            return;
        }
        loginMsg.setText(" ");
        background(loginButton, "Đang đăng nhập...", () -> {
            AuthApi.Session s = auth.login(u, p);
            return () -> showHome(s);
        });
    }

    private void doRegister() {
        String name = regName.getText().trim();
        String u = regUser.getText().trim();
        String p = new String(regPass.getPassword());
        String p2 = new String(regPass2.getPassword());
        regMsg.setForeground(Theme.DANGER);
        if (!p.equals(p2)) {
            regMsg.setText("Hai lần nhập mật khẩu không giống nhau");
            return;
        }
        Role role = firstManagerMode ? Role.QUAN_LY : (Role) regRole.getSelectedItem();
        regMsg.setText(" ");
        background(regButton, "Đang gửi...", () -> {
            String text = auth.register(u, name, p, role);
            return () -> {
                for (JTextField f : List.of(regName, regUser, regPass, regPass2)) {
                    f.setText("");
                }
                showLogin(null);
                loginUser.setText(u.toLowerCase());
                loginMsg.setForeground(new Color(21, 128, 61));
                loginMsg.setText("<html>" + text + "</html>");
                showForm(false);
            };
        });
    }

    // ------------------------------------------------------------------ trang chủ

    private JPanel buildHomePage() {
        JPanel page = new JPanel(new BorderLayout());

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.SURFACE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(12, 24, 12, 24)));
        JLabel brand = Theme.title("Kitchen Order Suite", 20);
        brand.setForeground(Theme.PRIMARY);
        JLabel sub = new JLabel("Trang chủ hệ thống");
        sub.setFont(BOLD);
        JPanel brandBox = new JPanel(new BorderLayout());
        brandBox.setOpaque(false);
        brandBox.add(brand, BorderLayout.NORTH);
        brandBox.add(sub, BorderLayout.SOUTH);

        userName.setFont(BOLD);
        userRole.setFont(BASE.deriveFont(13f));
        userRole.setForeground(Theme.MUTED);
        JPanel who = new JPanel(new BorderLayout());
        who.setOpaque(false);
        who.add(userName, BorderLayout.NORTH);
        who.add(userRole, BorderLayout.SOUTH);
        JButton change = Theme.button("Đổi mật khẩu", null);
        change.addActionListener(e -> changePassword());
        JButton logout = Theme.button("Đăng xuất", Theme.DANGER);
        logout.addActionListener(e -> logout(null));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        right.add(avatar);
        right.add(who);
        right.add(Box.createHorizontalStrut(8));
        right.add(change);
        right.add(logout);
        header.add(brandBox, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);

        JPanel body = new JPanel(new BorderLayout(0, 18));
        body.setBorder(BorderFactory.createEmptyBorder(26, 28, 20, 28));
        roleDesc.setFont(BASE.deriveFont(16f));
        roleDesc.setForeground(Theme.MUTED);
        JPanel hello = new JPanel(new BorderLayout(0, 4));
        hello.setOpaque(false);
        hello.add(greeting, BorderLayout.NORTH);
        hello.add(roleDesc, BorderLayout.SOUTH);
        grid.setOpaque(false);
        JPanel gridWrap = new JPanel(new BorderLayout());
        gridWrap.setOpaque(false);
        gridWrap.add(grid, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(gridWrap);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        body.add(hello, BorderLayout.NORTH);
        body.add(scroll, BorderLayout.CENTER);
        JLabel foot = new JLabel(auth.serverLabel());
        foot.setFont(BASE.deriveFont(13f));
        foot.setForeground(Theme.MUTED);
        body.add(foot, BorderLayout.SOUTH);

        page.add(header, BorderLayout.NORTH);
        page.add(body, BorderLayout.CENTER);
        return page;
    }

    private void showHome(AuthApi.Session s) {
        session = s;
        userName.setText(s.user().fullName);
        userRole.setText(s.user().role.label() + "  ·  " + s.user().username);
        // chữ cái đầu của tên (từ cuối trong họ tên tiếng Việt)
        String[] words = s.user().fullName.trim().split("\\s+");
        avatar.text = words[words.length - 1].isEmpty() ? "?" : words[words.length - 1].substring(0, 1).toUpperCase();
        greeting.setText("Xin chào, " + s.user().fullName);
        roleDesc.setText("Vai trò " + s.user().role.label() + ": " + s.user().role.description());
        tileSignature = "";
        rebuildTiles();
        pages.show(root, "home");
    }

    private void rebuildTiles() {
        if (session == null) {
            return;
        }
        List<Tile> tiles = host.tiles(session);
        StringBuilder sig = new StringBuilder();
        for (Tile t : tiles) {
            sig.append(t.title()).append(t.description()).append(t.allowed()).append('|');
        }
        if (sig.toString().equals(tileSignature)) {
            return;
        }
        tileSignature = sig.toString();
        grid.removeAll();
        for (Tile t : tiles) {
            grid.add(tileCard(t));
        }
        grid.revalidate();
        grid.repaint();
    }

    private JComponent tileCard(Tile t) {
        RoundPanel card = new RoundPanel(t.allowed() ? Theme.SURFACE : new Color(248, 250, 252), 18);
        card.setLayout(new BorderLayout(0, 12));
        card.setBorder(BorderFactory.createEmptyBorder(18, 18, 16, 18));
        card.setPreferredSize(new Dimension(240, 205));
        Badge icon = new Badge(t.letter(), t.allowed() ? t.color() : new Color(203, 213, 225));
        JLabel title = new JLabel(t.title());
        title.setFont(BOLD.deriveFont(18f));
        title.setForeground(t.allowed() ? Theme.TEXT : Theme.MUTED);
        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setOpaque(false);
        top.add(icon, BorderLayout.WEST);
        top.add(title, BorderLayout.CENTER);
        JLabel desc = new JLabel("<html>" + t.description() + "</html>");
        desc.setFont(BASE.deriveFont(14f));
        desc.setForeground(Theme.MUTED);
        desc.setVerticalAlignment(JLabel.TOP);
        card.add(top, BorderLayout.NORTH);
        card.add(desc, BorderLayout.CENTER);
        if (t.allowed()) {
            JButton open = Theme.button("Mở", t.color());
            open.addActionListener(e -> t.action().run());
            JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            b.setOpaque(false);
            b.add(open);
            card.add(b, BorderLayout.SOUTH);
            card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            card.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    t.action().run();
                }
            });
        } else {
            JLabel lock = new JLabel("<html>Không có quyền<br><span style='font-weight:normal'>" + t.lockedText()
                    + "</span></html>");
            lock.setFont(BOLD.deriveFont(13f));
            lock.setForeground(new Color(148, 163, 184));
            card.add(lock, BorderLayout.SOUTH);
        }
        return card;
    }

    private void tick() {
        if (session == null) {
            return;
        }
        String token = session.token();
        Thread t = new Thread(() -> {
            boolean ok = auth.isValid(token);
            SwingUtilities.invokeLater(() -> {
                if (!ok && session != null && session.token().equals(token)) {
                    forceLogout("Phiên đăng nhập đã kết thúc (tài khoản bị khóa, đổi vai trò hoặc đặt lại mật khẩu).");
                } else {
                    rebuildTiles();
                }
            });
        }, "session-check");
        t.setDaemon(true);
        t.start();
    }

    /** Đăng xuất do máy chủ (bị khóa, phiên hết hạn...). Gọi được từ luồng bất kỳ. */
    public void forceLogout(String message) {
        SwingUtilities.invokeLater(() -> {
            if (session != null) {
                logout(message);
            }
        });
    }

    private void logout(String message) {
        AuthApi.Session s = session;
        session = null;
        if (s != null) {
            host.onLogout(s);
            Thread t = new Thread(() -> auth.logout(s.token()), "logout");
            t.setDaemon(true);
            t.start();
        }
        showLogin(message);
        toFront();
    }

    private void changePassword() {
        JPasswordField old = new JPasswordField();
        JPasswordField p1 = new JPasswordField();
        JPasswordField p2 = new JPasswordField();
        JPanel p = column("Mật khẩu hiện tại", old, "Mật khẩu mới (ít nhất 6 ký tự)", p1, "Nhập lại mật khẩu mới", p2);
        p.setPreferredSize(new Dimension(340, 230));
        if (JOptionPane.showConfirmDialog(this, p, "Đổi mật khẩu", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        String n1 = new String(p1.getPassword());
        if (!n1.equals(new String(p2.getPassword()))) {
            JOptionPane.showMessageDialog(this, "Hai lần nhập mật khẩu mới không giống nhau");
            return;
        }
        try {
            auth.changePassword(session.token(), new String(old.getPassword()), n1);
            JOptionPane.showMessageDialog(this, "Đã đổi mật khẩu");
        } catch (AuthApi.AuthException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Không đổi được", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ------------------------------------------------------------------ hình vẽ nhỏ

    /** Khung nền trắng bo góc. */
    static class RoundPanel extends JPanel {
        private final Color fill;
        private final int arc;

        RoundPanel(Color fill, int arc) {
            this.fill = fill;
            this.arc = arc;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(15, 23, 42, 14));
            g2.fillRoundRect(0, 3, getWidth(), getHeight() - 3, arc, arc);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight() - 3, arc, arc);
            g2.setColor(Theme.BORDER);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 4, arc, arc);
            g2.dispose();
        }
    }

    /** Ô vuông màu có chữ cái đầu. */
    static class Badge extends JComponent {
        private final String text;
        private final Color color;

        Badge(String text, Color color) {
            this.text = text;
            this.color = color;
            setPreferredSize(new Dimension(46, 46));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillRoundRect(0, 0, 46, 46, 14, 14);
            g2.setColor(Color.WHITE);
            g2.setFont(BOLD.deriveFont(20f));
            int w = g2.getFontMetrics().stringWidth(text);
            g2.drawString(text, (46 - w) / 2, 30);
            g2.dispose();
        }
    }

    /** Hình tròn chữ cái đầu của tên người đăng nhập. */
    static class Avatar extends JComponent {
        String text = "?";

        Avatar() {
            setPreferredSize(new Dimension(40, 40));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(Theme.PRIMARY_SOFT);
            g2.fillOval(0, 0, 40, 40);
            g2.setColor(Theme.PRIMARY);
            g2.setFont(BOLD.deriveFont(17f));
            int w = g2.getFontMetrics().stringWidth(text);
            g2.drawString(text, (40 - w) / 2, 26);
            g2.dispose();
        }
    }
}
