package kos.server.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Thẻ Log: mọi dòng ctx.log() của máy chủ (kết nối, đơn mới, gia hạn, hủy món, lệnh rung, lỗi).
 * Chỉ được gọi trên luồng giao diện (AdminWindow chuyển sang bằng invokeLater).
 */
class LogPanel extends JPanel {

    private static final int MAX_LINES = 2000;

    private final JTextArea area = new JTextArea();
    private final JCheckBox warningsOnly = new JCheckBox("Chỉ hiện cảnh báo");
    private final List<String> lines = new ArrayList<>();

    LogPanel() {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        add(new JScrollPane(area), BorderLayout.CENTER);

        JButton clear = new JButton("Xóa màn hình");
        clear.addActionListener(e -> {
            lines.clear();
            area.setText("");
        });
        warningsOnly.addActionListener(e -> redraw());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(warningsOnly);
        bar.add(clear);
        add(bar, BorderLayout.SOUTH);
    }

    void append(String line) {
        lines.add(line);
        if (lines.size() > MAX_LINES) {
            lines.subList(0, lines.size() - MAX_LINES).clear();
            redraw();
            return;
        }
        if (show(line)) {
            area.append(line + "\n");
            area.setCaretPosition(area.getDocument().getLength());
        }
    }

    static boolean isWarning(String line) {
        return line.contains("CẢNH BÁO") || line.contains("LỖI");
    }

    private boolean show(String line) {
        return !warningsOnly.isSelected() || isWarning(line);
    }

    private void redraw() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (show(l)) {
                sb.append(l).append('\n');
            }
        }
        area.setText(sb.toString());
        area.setCaretPosition(area.getDocument().getLength());
    }
}
