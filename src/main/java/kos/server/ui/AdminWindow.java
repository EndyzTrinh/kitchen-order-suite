package kos.server.ui;

import kos.common.Protocol;
import kos.server.ServerContext;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

/**
 * Cửa sổ quầy quản lý của máy chủ: thẻ Đơn hàng, Thực đơn, Thiết bị, Log.
 * Dòng log có chữ "CẢNH BÁO" (thiết bị rung không phản hồi, không có thiết bị rung nào, mất kết nối...)
 * hiện thành băng đỏ ở đầu cửa sổ kèm tiếng bíp; bấm vào băng để tắt.
 */
public class AdminWindow extends JFrame {

    private static final int TAB_ORDERS = 0;

    private final JTabbedPane tabs = new JTabbedPane();
    private final LogPanel logPanel = new LogPanel();
    private final JLabel banner = new JLabel(" ", SwingConstants.CENTER);

    /** Mở cửa sổ quầy quản lý; gọi được từ mọi luồng. */
    public static void open(ServerContext ctx, List<String> addresses, Runnable onExit) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // giữ giao diện mặc định
            }
            new AdminWindow(ctx, addresses, onExit).setVisible(true);
        });
    }

    /**
     * @param addresses địa chỉ IP của máy để hiện ở thanh trạng thái
     * @param onExit    việc cần làm khi quản lý đóng cửa sổ (lưu dữ liệu, đóng cổng, thoát)
     */
    private AdminWindow(ServerContext ctx, List<String> addresses, Runnable onExit) {
        super("Kitchen Order Suite - Quầy quản lý");
        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 14f));
        tabs.addTab("Đơn hàng", new OrderPanel(ctx));
        tabs.addTab("Thực đơn", new MenuPanel(ctx));
        tabs.addTab("Thiết bị", new DevicePanel(ctx));
        tabs.addTab("Log", logPanel);
        add(tabs, BorderLayout.CENTER);

        banner.setOpaque(true);
        banner.setFont(banner.getFont().deriveFont(Font.BOLD, 15f));
        banner.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        banner.setVisible(false);
        banner.setToolTipText("Bấm để tắt cảnh báo");
        banner.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                banner.setVisible(false);
            }
        });
        add(banner, BorderLayout.NORTH);

        String ip = addresses.isEmpty() ? "<IP máy chủ>" : addresses.get(0);
        JLabel status = new JLabel("  TCP " + Protocol.TCP_PORT + " / HTTP " + Protocol.HTTP_PORT
                + "   ·   Gọi món: http://" + ip + ":" + Protocol.HTTP_PORT + "/order"
                + "   ·   Thiết bị rung: http://" + ip + ":" + Protocol.HTTP_PORT + "/pager?id=1"
                + (addresses.size() > 1 ? "   ·   IP khác: " + String.join(", ", addresses.subList(1, addresses.size())) : ""));
        status.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        add(status, BorderLayout.SOUTH);

        // Log: hiện lại các dòng lúc khởi động, rồi nhận dòng mới từ mọi luồng.
        for (String line : ctx.recentLog()) {
            logPanel.append(line);
        }
        ctx.addLogListener(line -> SwingUtilities.invokeLater(() -> onLog(line)));

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                int ok = JOptionPane.showConfirmDialog(AdminWindow.this,
                        "Tắt máy chủ? iPad, màn hình bếp và thiết bị rung sẽ mất kết nối.",
                        "Tắt máy chủ", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (ok == JOptionPane.YES_OPTION) {
                    setTitle("Đang tắt máy chủ...");
                    new Thread(onExit, "shutdown").start();
                }
            }
        });
        setSize(1200, 720);
        setLocationRelativeTo(null);
    }

    private void onLog(String line) {
        logPanel.append(line);
        if (LogPanel.isWarning(line)) {
            alert(line.replaceFirst("^\\[[0-9:]+\\] ", ""));
        }
    }

    /** Băng đỏ + tiếng bíp, chuyển sang thẻ Đơn hàng. */
    private void alert(String text) {
        banner.setText(text);
        banner.setBackground(new Color(0xC0392B));
        banner.setForeground(Color.WHITE);
        banner.setVisible(true);
        if (text.contains("rung") || text.contains("lấy món")) {
            tabs.setSelectedIndex(TAB_ORDERS); // cảnh báo về món chờ lấy: đưa quản lý tới bảng lệnh rung
        }
        Toolkit.getDefaultToolkit().beep();
    }
}
