package kos.server;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Thẻ Thống kê của quầy quản lý (F15): đọc lịch sử thời gian làm món, lọc theo ngày,
 * tổng hợp theo món / khung giờ / người làm, vẽ biểu đồ chuẩn - thực tế và xuất CSV.
 * Dùng để tìm món hay chậm, giờ cao điểm và làm căn cứ thưởng phạt.
 */
public class StatsPanel extends JPanel {

    private static final Font BASE = kos.common.Theme.font(Font.PLAIN, 15);
    private static final Font BOLD = BASE.deriveFont(Font.BOLD);
    private static final DateTimeFormatter VN = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Color STD = new Color(148, 163, 184);
    private static final Color ACT = kos.common.Theme.PRIMARY;
    private static final Color OVER = kos.common.Theme.DANGER;

    private final ServerContext ctx;
    private final JTextField from = new JTextField(9);
    private final JTextField to = new JTextField(9);
    private final JComboBox<String> groupBy = new JComboBox<>(new String[]{"Theo món", "Theo khung giờ", "Theo người làm"});
    private final JLabel summaryLabel = new JLabel(" ");
    private List<HistoryStore.Record> records = new ArrayList<>();
    private List<HistoryStore.Summary> summaries = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final Chart chart = new Chart();

    public StatsPanel(ServerContext ctx) {
        super(new BorderLayout(0, 6));
        this.ctx = ctx;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        String today = LocalDate.now().format(VN);
        from.setText(today);
        to.setText(today);
        from.setFont(BASE);
        to.setFont(BASE);
        groupBy.setFont(BASE);

        JPanel filter = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        filter.add(label("Từ ngày"));
        filter.add(from);
        filter.add(label("đến"));
        filter.add(to);
        filter.add(quick("Hôm nay", 0));
        filter.add(quick("7 ngày", 6));
        filter.add(quick("30 ngày", 29));
        JPanel filter2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        filter2.add(label("Nhóm"));
        filter2.add(groupBy);
        JButton view = button("Xem");
        view.addActionListener(e -> reload());
        filter2.add(view);
        JButton export = button("Xuất CSV...");
        export.setBackground(kos.common.Theme.PRIMARY);
        export.setForeground(Color.WHITE);
        export.addActionListener(e -> exportCsv());
        filter2.add(export);
        JButton open = button("Mở file lịch sử");
        open.addActionListener(e -> openHistory());
        filter2.add(open);
        groupBy.addActionListener(e -> reload());

        summaryLabel.setFont(BOLD);
        summaryLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        JPanel north = new JPanel(new BorderLayout());
        JPanel filters = new JPanel(new java.awt.GridLayout(2, 1));
        filters.add(filter);
        filters.add(filter2);
        north.add(filters, BorderLayout.NORTH);
        north.add(summaryLabel, BorderLayout.SOUTH);

        table.setFont(BASE);
        kos.common.Theme.table(table, 32);
        table.getTableHeader().setFont(BOLD);
        table.setAutoCreateRowSorter(true);
        table.setDefaultRenderer(Object.class, new LateRenderer());
        table.setDefaultRenderer(Number.class, new LateRenderer());
        int[] w = {170, 70, 90, 100, 90, 70, 75, 90, 220, 100};
        for (int i = 0; i < w.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        }

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(chart));
        split.setResizeWeight(0.5);
        split.setDividerLocation(260);

        add(north, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        JLabel note = new JLabel("<html>Thời gian tính bằng phút (Chờ lấy tính bằng giây). "
                + "Thực tế = từ lúc Nhận làm tới lúc món xong (hết giờ hoặc Xong sớm). "
                + "Trễ = thực tế vượt thời gian chuẩn trong thực đơn, kể cả khi đã gia hạn. "
                + "Chờ lấy = từ lúc món xong tới lúc phục vụ bấm Đã lấy.</html>");
        note.setForeground(kos.common.Theme.MUTED);
        note.setFont(BASE.deriveFont(13f));
        add(note, BorderLayout.SOUTH);
        reload();
    }

    private static JLabel label(String t) {
        JLabel l = new JLabel(t);
        l.setFont(BASE);
        return l;
    }

    private static JButton button(String t) {
        JButton b = new JButton(t);
        b.setFont(BOLD);
        b.setFocusable(false);
        return b;
    }

    private JButton quick(String text, int daysBack) {
        JButton b = new JButton(text);
        b.setFont(BASE);
        b.setFocusable(false);
        b.addActionListener(e -> {
            LocalDate t = LocalDate.now();
            from.setText(t.minusDays(daysBack).format(VN));
            to.setText(t.format(VN));
            reload();
        });
        return b;
    }

    /** Đọc lại dữ liệu theo bộ lọc. Được gọi khi bấm Xem, đổi cách nhóm, hoặc mở thẻ. */
    public void reload() {
        LocalDate f;
        LocalDate t;
        try {
            f = LocalDate.parse(from.getText().trim(), VN);
            t = LocalDate.parse(to.getText().trim(), VN);
        } catch (DateTimeParseException e) {
            JOptionPane.showMessageDialog(this, "Ngày phải có dạng dd/MM/yyyy, ví dụ 29/10/2026", "Sai ngày",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (t.isBefore(f)) {
            LocalDate x = f;
            f = t;
            t = x;
        }
        records = ctx.history().between(f, t);
        HistoryStore.GroupBy by = switch (groupBy.getSelectedIndex()) {
            case 1 -> HistoryStore.GroupBy.KHUNG_GIO;
            case 2 -> HistoryStore.GroupBy.CHEF;
            default -> HistoryStore.GroupBy.MON;
        };
        summaries = HistoryStore.summarize(records, by);
        model.fireTableDataChanged();
        chart.repaint();
        chart.revalidate();

        int late = 0;
        long diff = 0;
        for (HistoryStore.Record r : records) {
            late += r.late() ? 1 : 0;
            diff += r.diffSec();
        }
        summaryLabel.setText(records.isEmpty() ? "Chưa có món nào trong khoảng ngày này."
                : String.format("%d món  |  trễ %d món (%.0f%%)  |  chênh lệch trung bình %+.1f phút/món",
                records.size(), late, 100.0 * late / records.size(), diff / 60.0 / records.size()));
        summaryLabel.setForeground(late > 0 ? OVER.darker() : new Color(30, 132, 73));
    }

    private void exportCsv() {
        if (records.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Không có dữ liệu để xuất.");
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("thong-ke-" + from.getText().replace("/", "-") + "_"
                + to.getText().replace("/", "-") + ".csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            HistoryStore.export(records, fc.getSelectedFile().toPath());
            ctx.log("Đã xuất " + records.size() + " dòng thống kê ra " + fc.getSelectedFile());
            JOptionPane.showMessageDialog(this, "Đã xuất " + records.size() + " dòng.\nMở bằng Excel để xem.");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Không ghi được file: " + e.getMessage(), "Lỗi",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openHistory() {
        File f = HistoryStore.FILE.toAbsolutePath().toFile();
        if (!f.exists()) {
            JOptionPane.showMessageDialog(this, "Chưa có file lịch sử (chưa có món nào được lấy).");
            return;
        }
        try {
            Desktop.getDesktop().open(f);
        } catch (IOException | UnsupportedOperationException e) {
            JOptionPane.showMessageDialog(this, "File nằm ở: " + f);
        }
    }

    // ------------------------------------------------------------------ bảng

    private final class Model extends AbstractTableModel {
        private final String[] cols = {"Nhóm", "Số món", "Chuẩn TB", "Thực tế TB",
                "Chênh TB", "% trễ", "Gia hạn", "Chưa xong", "Lý do hay gặp", "Chờ lấy TB"};

        @Override
        public int getRowCount() {
            return summaries.size();
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
        public Class<?> getColumnClass(int c) {
            return c == 0 || c == 8 ? String.class : Number.class;
        }

        @Override
        public Object getValueAt(int row, int col) {
            HistoryStore.Summary s = summaries.get(row);
            return switch (col) {
                case 0 -> s.group();
                case 1 -> s.count();
                case 2 -> round(s.avgStandardMin());
                case 3 -> round(s.avgActualMin());
                case 4 -> round(s.avgDiffMin());
                case 5 -> Math.round(s.latePercent());
                case 6 -> s.extendCount();
                case 7 -> s.notDoneCount();
                case 8 -> s.topReason();
                default -> Math.round(s.avgWaitSec());
            };
        }

        private double round(double v) {
            return Math.round(v * 10) / 10.0;
        }
    }

    /** Dòng có chênh lệch dương (chậm hơn chuẩn) tô đỏ nhạt. */
    private final class LateRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            setHorizontalAlignment(v instanceof Number ? RIGHT : LEFT);
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            if (!sel) {
                HistoryStore.Summary s = summaries.get(t.convertRowIndexToModel(row));
                c.setBackground(s.avgDiffMin() > 0.05 ? kos.common.Theme.DANGER_SOFT : Color.WHITE);
            }
            return c;
        }
    }

    // ------------------------------------------------------------------ biểu đồ

    /** Biểu đồ thanh ngang: mỗi nhóm hai thanh, xám = chuẩn trung bình, xanh/đỏ = thực tế trung bình. */
    private final class Chart extends JComponent {
        private static final int ROW = 44;
        private static final int LEFT = 190;

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(600, Math.max(120, 50 + summaries.size() * ROW));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(BASE.deriveFont(13f));
            FontMetrics fm = g.getFontMetrics();
            if (summaries.isEmpty()) {
                g.setColor(Color.GRAY);
                g.drawString("Chưa có dữ liệu", 20, 40);
                return;
            }
            double max = 0.1;
            for (HistoryStore.Summary s : summaries) {
                max = Math.max(max, Math.max(s.avgStandardMin(), s.avgActualMin()));
            }
            int width = Math.max(100, getWidth() - LEFT - 90);

            // chú thích
            g.setColor(STD);
            g.fillRect(LEFT, 8, 14, 10);
            g.setColor(Color.DARK_GRAY);
            g.drawString("Chuẩn trung bình", LEFT + 20, 18);
            g.setColor(ACT);
            g.fillRect(LEFT + 150, 8, 14, 10);
            g.setColor(Color.DARK_GRAY);
            g.drawString("Thực tế trung bình", LEFT + 170, 18);
            g.setColor(OVER);
            g.fillRect(LEFT + 310, 8, 14, 10);
            g.setColor(Color.DARK_GRAY);
            g.drawString("Phần vượt chuẩn", LEFT + 330, 18);

            int y = 34;
            for (HistoryStore.Summary s : summaries) {
                String name = s.group();
                while (fm.stringWidth(name) > LEFT - 14 && name.length() > 3) {
                    name = name.substring(0, name.length() - 2);
                }
                g.setColor(Color.BLACK);
                g.drawString(name, 8, y + 18);
                int ws = (int) (width * s.avgStandardMin() / max);
                int wa = (int) (width * s.avgActualMin() / max);
                g.setColor(STD);
                g.fillRoundRect(LEFT, y + 2, Math.max(ws, 4), 14, 6, 6);
                g.setColor(ACT);
                g.fillRect(LEFT, y + 20, Math.min(wa, ws), 14);
                if (wa > ws) {
                    g.setColor(OVER);
                    g.fillRect(LEFT + ws, y + 20, wa - ws, 14);
                }
                g.setColor(Color.DARK_GRAY);
                g.drawString(String.format("%.1f'", s.avgStandardMin()), LEFT + ws + 6, y + 14);
                g.setColor(wa > ws ? OVER.darker() : Color.DARK_GRAY);
                g.drawString(String.format("%.1f'", s.avgActualMin()), LEFT + Math.max(wa, ws) + 6, y + 32);
                y += ROW;
            }
        }
    }
}
