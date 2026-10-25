package kos.common;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.awt.Toolkit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phát tiếng bíp tạo bằng mã (không cần file âm thanh), chạy trên luồng riêng để không làm đứng giao diện.
 * Máy không có loa / thiết bị âm thanh thì dùng tiếng bíp mặc định của hệ điều hành.
 */
public final class Beeper {

    private static final ExecutorService PLAYER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "beeper");
        t.setDaemon(true);
        return t;
    });

    private Beeper() {
    }

    /** Đơn mới: một tiếng trầm. */
    public static void newOrder() {
        play(660, 180, 1);
    }

    /** Món ưu tiên: hai tiếng cao, ngắn. */
    public static void priority() {
        play(1175, 120, 2);
    }

    /** Cảnh báo: ba tiếng. */
    public static void warning() {
        play(880, 150, 3);
    }

    public static void play(int hz, int ms, int times) {
        PLAYER.execute(() -> {
            try {
                AudioFormat fmt = new AudioFormat(44_100, 8, 1, true, false);
                try (SourceDataLine line = AudioSystem.getSourceDataLine(fmt)) {
                    line.open(fmt);
                    line.start();
                    byte[] tone = new byte[44_100 * ms / 1000];
                    for (int i = 0; i < tone.length; i++) {
                        tone[i] = (byte) (Math.sin(2 * Math.PI * hz * i / 44_100.0) * 90);
                    }
                    byte[] gap = new byte[44_100 * 90 / 1000];
                    for (int k = 0; k < times; k++) {
                        line.write(tone, 0, tone.length);
                        line.write(gap, 0, gap.length);
                    }
                    line.drain();
                }
            } catch (Exception | LinkageError e) {
                try {
                    Toolkit.getDefaultToolkit().beep();
                } catch (Exception | LinkageError ignored) {
                    // không có âm thanh, bỏ qua
                }
            }
        });
    }
}
