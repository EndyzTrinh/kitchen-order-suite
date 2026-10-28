package kos.common;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.UIResource;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.plaf.basic.BasicToggleButtonUI;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Giao diện phẳng, hiện đại cho các cửa sổ Swing (màn hình bếp, quầy quản lý), tự vẽ, không cần thư viện ngoài.
 * Gọi {@link #install()} một lần ở đầu main, trước khi tạo cửa sổ.
 * <ul>
 *   <li>Nút bo góc, đổi sắc khi rê chuột / bấm. Nút có màu nền riêng (setBackground) giữ màu đó.</li>
 *   <li>Thẻ (tab) dạng gạch chân, thanh cuộn mảnh, bảng không kẻ dọc, chữ to dễ đọc.</li>
 * </ul>
 */
public final class Theme {

    // Bảng màu
    public static final Color BG = new Color(243, 245, 249);
    public static final Color SURFACE = Color.WHITE;
    public static final Color TEXT = new Color(30, 41, 59);
    public static final Color MUTED = new Color(100, 116, 139);
    public static final Color BORDER = new Color(226, 232, 240);
    public static final Color PRIMARY = new Color(37, 99, 235);
    public static final Color PRIMARY_SOFT = new Color(219, 234, 254);
    public static final Color SUCCESS = new Color(22, 163, 74);
    public static final Color SUCCESS_SOFT = new Color(220, 252, 231);
    public static final Color WARNING = new Color(234, 88, 12);
    public static final Color WARNING_SOFT = new Color(255, 237, 213);
    public static final Color DANGER = new Color(220, 38, 38);
    public static final Color DANGER_SOFT = new Color(254, 226, 226);
    public static final Color YELLOW_SOFT = new Color(254, 249, 195);
    public static final Color DARK = new Color(15, 23, 42);

    /** Họ phông dùng chung: ưu tiên phông có đủ dấu tiếng Việt. */
    public static final String FAMILY = pickFamily();

    private static boolean installed;

    private Theme() {
    }

    private static String pickFamily() {
        try {
            List<String> have = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames());
            for (String f : List.of("Segoe UI", "Inter", "Roboto", "Noto Sans", "Open Sans", "DejaVu Sans")) {
                if (have.contains(f)) {
                    return f;
                }
            }
        } catch (Exception | Error ignored) {
            // không có màn hình
        }
        return Font.SANS_SERIF;
    }

    public static Font font(int style, float size) {
        return new Font(FAMILY, style, Math.round(size)).deriveFont(size);
    }

    /** Cài giao diện cho toàn chương trình. */
    public static synchronized void install() {
        if (installed || GraphicsEnvironment.isHeadless()) {
            return;
        }
        installed = true;
        try {
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (Exception ignored) {
            // giữ giao diện mặc định
        }
        System.setProperty("awt.useSystemAAFontSettings", "on");

        FontUIResource base = new FontUIResource(font(Font.PLAIN, 15));
        for (Object key : Collections.list(UIManager.getDefaults().keys())) {
            if (key instanceof String k && k.endsWith(".font")) {
                UIManager.put(k, base);
            }
        }
        FontUIResource bold = new FontUIResource(font(Font.BOLD, 15));
        UIManager.put("Button.font", bold);
        UIManager.put("ToggleButton.font", bold);
        UIManager.put("TabbedPane.font", bold);
        UIManager.put("TableHeader.font", bold);
        UIManager.put("OptionPane.buttonFont", new FontUIResource(font(Font.BOLD, 16)));
        UIManager.put("OptionPane.messageFont", new FontUIResource(font(Font.PLAIN, 16)));

        ColorUIResource bg = new ColorUIResource(BG);
        ColorUIResource text = new ColorUIResource(TEXT);
        for (String k : List.of("Panel.background", "OptionPane.background", "RootPane.background",
                "Viewport.background", "TabbedPane.background", "CheckBox.background", "SplitPane.background",
                "ToolBar.background", "MenuBar.background")) {
            UIManager.put(k, bg);
        }
        for (String k : List.of("Label.foreground", "Button.foreground", "ToggleButton.foreground",
                "CheckBox.foreground", "TabbedPane.foreground", "Table.foreground", "TableHeader.foreground",
                "TextField.foreground", "List.foreground", "ComboBox.foreground", "OptionPane.messageForeground",
                "TextPane.foreground")) {
            UIManager.put(k, text);
        }

        // Ô nhập
        Border field = BorderFactory.createCompoundBorder(new RoundBorder(BORDER, 10),
                BorderFactory.createEmptyBorder(5, 9, 5, 9));
        UIManager.put("TextField.border", field);
        UIManager.put("FormattedTextField.border", field);
        UIManager.put("PasswordField.border", field);
        UIManager.put("PasswordField.background", new ColorUIResource(SURFACE));
        UIManager.put("PasswordField.selectionBackground", new ColorUIResource(PRIMARY_SOFT));
        UIManager.put("TextField.background", new ColorUIResource(SURFACE));
        UIManager.put("TextField.selectionBackground", new ColorUIResource(PRIMARY_SOFT));
        UIManager.put("ComboBox.background", new ColorUIResource(SURFACE));
        UIManager.put("ComboBox.selectionBackground", new ColorUIResource(PRIMARY_SOFT));
        UIManager.put("ComboBox.selectionForeground", text);
        UIManager.put("ComboBox.buttonBackground", new ColorUIResource(SURFACE));
        UIManager.put("CheckBox.focus", bg);

        // Bảng, danh sách
        UIManager.put("Table.background", new ColorUIResource(SURFACE));
        UIManager.put("Table.gridColor", new ColorUIResource(BORDER));
        UIManager.put("Table.selectionBackground", new ColorUIResource(PRIMARY_SOFT));
        UIManager.put("Table.selectionForeground", text);
        UIManager.put("Table.focusCellHighlightBorder", BorderFactory.createEmptyBorder(1, 1, 1, 1));
        UIManager.put("TableHeader.background", new ColorUIResource(new Color(248, 250, 252)));
        UIManager.put("TableHeader.cellBorder", BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, BORDER), BorderFactory.createEmptyBorder(8, 8, 6, 8)));
        UIManager.put("List.selectionBackground", new ColorUIResource(PRIMARY_SOFT));
        UIManager.put("List.selectionForeground", text);
        UIManager.put("ScrollPane.border", new RoundBorder(BORDER, 12));
        UIManager.put("ScrollPane.background", new ColorUIResource(SURFACE));
        UIManager.put("SplitPane.border", BorderFactory.createEmptyBorder());
        UIManager.put("SplitPaneDivider.border", BorderFactory.createEmptyBorder());
        UIManager.put("ToolTip.background", new ColorUIResource(DARK));
        UIManager.put("ToolTip.foreground", new ColorUIResource(Color.WHITE));

        // Thẻ
        UIManager.put("TabbedPane.contentBorderInsets", new Insets(8, 0, 0, 0));
        UIManager.put("TabbedPane.tabInsets", new Insets(8, 16, 8, 16));
        UIManager.put("TabbedPane.selected", bg);
        UIManager.put("TabbedPane.contentAreaColor", bg);

        // Lớp vẽ tự viết
        UIManager.put("ButtonUI", FlatButtonUI.class.getName());
        UIManager.put(FlatButtonUI.class.getName(), FlatButtonUI.class);
        UIManager.put("ToggleButtonUI", FlatToggleUI.class.getName());
        UIManager.put(FlatToggleUI.class.getName(), FlatToggleUI.class);
        UIManager.put("ScrollBarUI", ThinScrollBarUI.class.getName());
        UIManager.put(ThinScrollBarUI.class.getName(), ThinScrollBarUI.class);
        UIManager.put("TabbedPaneUI", UnderlineTabsUI.class.getName());
        UIManager.put(UnderlineTabsUI.class.getName(), UnderlineTabsUI.class);
    }

    // ------------------------------------------------------------------ tiện ích dùng chung

    /** Nút có màu (chữ trắng). */
    public static JButton button(String text, Color color) {
        JButton b = new JButton(text);
        if (color != null) {
            b.setBackground(color);
            b.setForeground(Color.WHITE);
        }
        b.setFocusable(false);
        return b;
    }

    /** Bảng gọn gàng: dòng cao, không kẻ dọc, đầu bảng phẳng. */
    public static void table(JTable t, int rowHeight) {
        t.setRowHeight(rowHeight);
        t.setShowVerticalLines(false);
        t.setShowHorizontalLines(true);
        t.setGridColor(BORDER);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setFillsViewportHeight(true);
        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setPreferredSize(new Dimension(10, 38));
    }

    /** Nhãn tiêu đề lớn. */
    public static JLabel title(String text, float size) {
        JLabel l = new JLabel(text);
        l.setFont(font(Font.BOLD, size));
        l.setForeground(TEXT);
        return l;
    }

    static Color shade(Color c, double f) {
        if (f < 1) {
            return new Color((int) (c.getRed() * f), (int) (c.getGreen() * f), (int) (c.getBlue() * f), c.getAlpha());
        }
        double t = f - 1;
        return new Color((int) (c.getRed() + (255 - c.getRed()) * t), (int) (c.getGreen() + (255 - c.getGreen()) * t),
                (int) (c.getBlue() + (255 - c.getBlue()) * t), c.getAlpha());
    }

    private static Graphics2D smooth(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g2;
    }

    /** Viền bo góc một nét. */
    public static final class RoundBorder implements Border, UIResource {
        private final Color color;
        private final int arc;

        public RoundBorder(Color color, int arc) {
            this.color = color;
            this.arc = arc;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = smooth(g);
            g2.setColor(color);
            g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(2, 2, 2, 2);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }

    /** Ô bảng hiển thị trạng thái dạng "viên thuốc" màu. */
    public static class PillRenderer extends DefaultTableCellRenderer {
        private Color pill = BORDER;
        private Color ink = TEXT;
        private Color rowBg = SURFACE;
        private String text = "";

        /** Lớp con trả màu nền / màu chữ của viên theo giá trị. */
        protected Color[] colors(Object value, int row) {
            return new Color[]{BORDER, TEXT};
        }

        /** Màu nền của dòng. */
        protected Color rowBackground(JTable t, int row, boolean selected) {
            return selected ? t.getSelectionBackground() : SURFACE;
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            text = value == null ? "" : value.toString();
            Color[] c = colors(value, row);
            pill = c[0];
            ink = c[1];
            rowBg = rowBackground(t, row, sel);
            setFont(t.getFont().deriveFont(Font.BOLD, t.getFont().getSize2D() - 2));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = smooth(g);
            g2.setColor(rowBg);
            g2.fillRect(0, 0, getWidth(), getHeight());
            if (!text.isEmpty()) {
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int w = Math.min(getWidth() - 8, fm.stringWidth(text) + 20);
                int h = Math.min(getHeight() - 8, fm.getHeight() + 6);
                int y = (getHeight() - h) / 2;
                g2.setColor(pill);
                g2.fillRoundRect(4, y, w, h, h, h);
                g2.setColor(ink);
                g2.drawString(text, 14, y + (h + fm.getAscent() - fm.getDescent()) / 2);
            }
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ lớp vẽ

    /** Nút phẳng bo góc. */
    public static class FlatButtonUI extends BasicButtonUI {
        public static ComponentUI createUI(JComponent c) {
            return new FlatButtonUI();
        }

        @Override
        public void installDefaults(AbstractButton b) {
            super.installDefaults(b);
            b.setOpaque(false);
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setRolloverEnabled(true);
            b.setBorder(BorderFactory.createEmptyBorder(9, 18, 9, 18));
            if (b.getBackground() == null || b.getBackground() instanceof UIResource) {
                b.setBackground(new ColorUIResource(SURFACE));
            }
        }

        protected boolean selected(AbstractButton b) {
            return false;
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            ButtonModel m = b.getModel();
            Graphics2D g2 = smooth(g);
            boolean plain = b.getBackground() instanceof UIResource;
            Color fill = selected(b) ? PRIMARY : b.getBackground();
            if (!b.isEnabled()) {
                fill = plain ? new Color(241, 245, 249) : shade(fill, 1.55);
            } else if (m.isPressed()) {
                fill = plain && !selected(b) ? new Color(226, 232, 240) : shade(fill, 0.82);
            } else if (m.isRollover()) {
                fill = plain && !selected(b) ? new Color(241, 245, 249) : shade(fill, 1.12);
            }
            int arc = Math.min(14, c.getHeight());
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, c.getWidth(), c.getHeight(), arc, arc);
            if (plain && !selected(b)) {
                g2.setColor(BORDER);
                g2.drawRoundRect(0, 0, c.getWidth() - 1, c.getHeight() - 1, arc, arc);
            }
            g2.dispose();
            super.paint(g, c);
        }

        @Override
        protected void paintText(Graphics g, AbstractButton b, Rectangle r, String text) {
            Graphics2D g2 = smooth(g);
            FontMetrics fm = g2.getFontMetrics(b.getFont());
            Color fg = selected(b) ? Color.WHITE : b.getForeground();
            g2.setColor(b.isEnabled() ? fg : b.getBackground() instanceof UIResource ? MUTED : Color.WHITE);
            g2.setFont(b.getFont());
            g2.drawString(text, r.x, r.y + fm.getAscent());
            g2.dispose();
        }

        @Override
        protected void paintButtonPressed(Graphics g, AbstractButton b) {
            // đã vẽ trong paint()
        }

        @Override
        protected void paintFocus(Graphics g, AbstractButton b, Rectangle vr, Rectangle tr, Rectangle ir) {
            // không vẽ khung focus
        }
    }

    /** Nút bật/tắt (ví dụ "Theo đơn" / "Gộp theo món"): đang chọn tô màu chính. */
    public static class FlatToggleUI extends BasicToggleButtonUI {
        private final FlatButtonUI delegate = new FlatButtonUI() {
            @Override
            protected boolean selected(AbstractButton b) {
                return b.isSelected();
            }
        };

        public static ComponentUI createUI(JComponent c) {
            return new FlatToggleUI();
        }

        @Override
        protected void installDefaults(AbstractButton b) {
            super.installDefaults(b);
            delegate.installDefaults(b);
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            delegate.paint(g, c);
        }
    }

    /** Thanh cuộn mảnh, không mũi tên. */
    public static class ThinScrollBarUI extends BasicScrollBarUI {
        public static ComponentUI createUI(JComponent c) {
            return new ThinScrollBarUI();
        }

        @Override
        protected void configureScrollBarColors() {
            thumbColor = new Color(203, 213, 225);
            trackColor = SURFACE;
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            return new Dimension(11, 11);
        }

        private JButton zero() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }

        @Override
        protected JButton createDecreaseButton(int o) {
            return zero();
        }

        @Override
        protected JButton createIncreaseButton(int o) {
            return zero();
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(trackColor);
            g.fillRect(r.x, r.y, r.width, r.height);
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty()) {
                return;
            }
            Graphics2D g2 = smooth(g);
            g2.setColor(isThumbRollover() ? new Color(148, 163, 184) : thumbColor);
            g2.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 8, 8);
            g2.dispose();
        }
    }

    /** Thẻ dạng chữ + gạch chân màu chính. */
    public static class UnderlineTabsUI extends BasicTabbedPaneUI {
        public static ComponentUI createUI(JComponent c) {
            return new UnderlineTabsUI();
        }

        @Override
        protected void installDefaults() {
            super.installDefaults();
            tabAreaInsets = new Insets(4, 8, 0, 8);
            contentBorderInsets = new Insets(1, 0, 0, 0);
            selectedTabPadInsets = new Insets(0, 0, 0, 0);
        }

        @Override
        protected int calculateTabHeight(int placement, int index, int fontHeight) {
            return fontHeight + 20;
        }

        @Override
        protected int calculateTabWidth(int placement, int index, FontMetrics fm) {
            return super.calculateTabWidth(placement, index, fm) + 12;
        }

        @Override
        protected void paintTabBackground(Graphics g, int p, int i, int x, int y, int w, int h, boolean sel) {
            if (sel) {
                Graphics2D g2 = smooth(g);
                g2.setColor(SURFACE);
                g2.fillRoundRect(x + 2, y + 4, w - 4, h - 4, 12, 12);
                g2.fillRect(x + 2, y + h - 8, w - 4, 8);
                g2.dispose();
            }
        }

        @Override
        protected void paintTabBorder(Graphics g, int p, int i, int x, int y, int w, int h, boolean sel) {
            if (sel) {
                g.setColor(PRIMARY);
                g.fillRect(x + 10, y + h - 3, w - 20, 3);
            }
        }

        @Override
        protected void paintText(Graphics g, int p, Font font, FontMetrics fm, int i, String title,
                                 Rectangle r, boolean sel) {
            Graphics2D g2 = smooth(g);
            g2.setFont(font);
            Color fg = tabPane.getForegroundAt(i);
            boolean custom = fg != null && !(fg instanceof UIResource) && !fg.equals(tabPane.getForeground());
            g2.setColor(custom ? fg : sel ? TEXT : MUTED);
            g2.drawString(title, r.x, r.y + fm.getAscent());
            g2.dispose();
        }

        @Override
        protected void paintFocusIndicator(Graphics g, int p, Rectangle[] rects, int i, Rectangle ir, Rectangle tr,
                                           boolean sel) {
            // không vẽ
        }

        @Override
        protected void paintContentBorder(Graphics g, int p, int sel) {
            Rectangle area = new Rectangle(0, 0, tabPane.getWidth(), tabPane.getHeight());
            int y = calculateTabAreaHeight(p, runCount, maxTabHeight);
            g.setColor(BORDER);
            g.fillRect(area.x, y, area.width, 1);
        }
    }

    /** Căn giữa chữ trong nhãn. */
    public static JLabel center(JLabel l) {
        l.setHorizontalAlignment(SwingConstants.CENTER);
        return l;
    }
}
