package kos.server;

import kos.common.Order;
import kos.common.OrderItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lịch sử thời gian làm món (F15). Mỗi món được phục vụ lấy xong thì ghi một dòng vào data/lich-su.csv
 * (mở được bằng Excel). Thẻ Thống kê của quầy quản lý đọc dữ liệu ở đây.
 * <p>
 * Thời gian thực tế = thời điểm xong (READY lần cuối) - thời điểm nhận làm.
 * "Trễ" luôn so với thời gian chuẩn trong thực đơn, không so với hạn đã gia hạn,
 * để việc gia hạn không che được việc làm chậm.
 */
public class HistoryStore {

    public static final Path FILE = DataFile.DIR.resolve("lich-su.csv");
    private static final String HEADER = "ngay,gio_nhan_lam,don,ban,ma_mon,mon,so_luong,lam_gop,chef,uu_tien,"
            + "chuan_giay,thuc_te_giay,chenh_lech_giay,so_lan_gia_han,gia_han_phut,so_lan_chua_xong,ly_do,"
            + "ket_thuc,cho_lay_giay";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** Một dòng lịch sử. */
    public record Record(LocalDateTime startedAt, int orderId, int table, String menuId, String name, int qty,
                         int batchQty, String chef, boolean priority, long standardSec, long actualSec,
                         int extendCount, int extendedMinutes, int notDoneCount, String reasons,
                         String finishMode, long waitSec) {

        public long diffSec() {
            return actualSec - standardSec;
        }

        public boolean late() {
            return actualSec > standardSec;
        }
    }

    /** Một dòng tổng hợp trên thẻ Thống kê. */
    public record Summary(String group, int count, double avgStandardMin, double avgActualMin, double avgDiffMin,
                          double latePercent, int extendCount, String topReason, double avgWaitSec,
                          int notDoneCount) { }

    private final ServerContext ctx;
    private final List<Record> records = new ArrayList<>();

    public HistoryStore(ServerContext ctx) {
        this.ctx = ctx;
    }

    // ------------------------------------------------------------------ ghi

    /** Ghi một món vừa được lấy. Chỉ ghi món đã được nhận làm và đã xong. */
    public synchronized void record(Order o, OrderItem it) {
        if (it.startedAt == null || it.finishedAt == null) {
            return;
        }
        long standard = (it.prepMinutes == null ? 0 : it.prepMinutes) * 60L;
        long actual = Math.max(0, (it.finishedAt - it.startedAt) / 1000);
        long wait = it.servedAt == null ? 0 : Math.max(0, (it.servedAt - it.finishedAt) / 1000);
        Record r = new Record(LocalDateTime.ofInstant(Instant.ofEpochMilli(it.startedAt), ZONE),
                o.orderId, o.table, it.menuId, it.name, it.qty == null ? 1 : it.qty,
                it.batchQty == null ? (it.qty == null ? 1 : it.qty) : it.batchQty,
                it.chef == null ? "Không rõ" : it.chef, it.isPriority(), standard, actual,
                nz(it.extendCount), nz(it.extendedMinutes), nz(it.notDoneCount),
                it.extendReasons == null ? "" : it.extendReasons,
                it.finishMode == null ? "" : it.finishMode, wait);
        records.add(r);
        try {
            Files.createDirectories(FILE.getParent());
            boolean fresh = !Files.exists(FILE);
            // BOM UTF-8 để Excel hiển thị đúng tiếng Việt
            String text = (fresh ? "﻿" + HEADER + "\r\n" : "") + toCsv(r) + "\r\n";
            Files.writeString(FILE, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            ctx.log("CẢNH BÁO: không ghi được lich-su.csv: " + e.getMessage());
        }
        if (r.late()) {
            ctx.log("Ghi lịch sử: " + r.name() + " bàn " + r.table() + " - " + r.chef() + " - thực tế "
                    + mmss(r.actualSec()) + ", chậm " + mmss(r.diffSec()) + " so với chuẩn");
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String toCsv(Record r) {
        return String.join(",",
                r.startedAt().format(DAY), r.startedAt().format(HOUR), String.valueOf(r.orderId()),
                String.valueOf(r.table()), q(r.menuId()), q(r.name()), String.valueOf(r.qty()),
                String.valueOf(r.batchQty()), q(r.chef()), r.priority() ? "1" : "0",
                String.valueOf(r.standardSec()), String.valueOf(r.actualSec()), String.valueOf(r.diffSec()),
                String.valueOf(r.extendCount()), String.valueOf(r.extendedMinutes()),
                String.valueOf(r.notDoneCount()), q(r.reasons()), q(r.finishMode()), String.valueOf(r.waitSec()));
    }

    private static String q(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }

    // ------------------------------------------------------------------ đọc

    /** Nạp lại lịch sử từ file khi khởi động. Dòng lỗi bị bỏ qua. */
    public synchronized void load() {
        records.clear();
        if (!Files.exists(FILE)) {
            return;
        }
        int bad = 0;
        try {
            List<String> lines = Files.readAllLines(FILE, StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                try {
                    List<String> f = parseCsv(lines.get(i));
                    if (f.size() < 19) {
                        continue;
                    }
                    records.add(new Record(
                            LocalDateTime.of(LocalDate.parse(f.get(0), DAY),
                                    java.time.LocalTime.parse(f.get(1), HOUR)),
                            Integer.parseInt(f.get(2)), Integer.parseInt(f.get(3)), f.get(4), f.get(5),
                            Integer.parseInt(f.get(6)), Integer.parseInt(f.get(7)), f.get(8), "1".equals(f.get(9)),
                            Long.parseLong(f.get(10)), Long.parseLong(f.get(11)), Integer.parseInt(f.get(13)),
                            Integer.parseInt(f.get(14)), Integer.parseInt(f.get(15)), f.get(16), f.get(17),
                            Long.parseLong(f.get(18))));
                } catch (RuntimeException e) {
                    bad++;
                }
            }
        } catch (IOException e) {
            ctx.log("CẢNH BÁO: không đọc được lich-su.csv: " + e.getMessage());
            return;
        }
        ctx.log("Đã nạp " + records.size() + " dòng lịch sử" + (bad > 0 ? " (bỏ qua " + bad + " dòng lỗi)" : ""));
    }

    static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '﻿') {
                continue;
            }
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** Các dòng trong khoảng ngày [from, to]. */
    public synchronized List<Record> between(LocalDate from, LocalDate to) {
        List<Record> list = new ArrayList<>();
        for (Record r : records) {
            LocalDate d = r.startedAt().toLocalDate();
            if (!d.isBefore(from) && !d.isAfter(to)) {
                list.add(r);
            }
        }
        return list;
    }

    /** Ghi các dòng đã lọc ra một file CSV khác (nút "Xuất CSV" của thẻ Thống kê). */
    public static void export(List<Record> list, Path target) throws IOException {
        StringBuilder sb = new StringBuilder("﻿").append(HEADER).append("\r\n");
        for (Record r : list) {
            sb.append(toCsv(r)).append("\r\n");
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ tổng hợp

    /** Cách nhóm trên thẻ Thống kê. */
    public enum GroupBy { MON, KHUNG_GIO, CHEF }

    public static List<Summary> summarize(List<Record> list, GroupBy by) {
        Map<String, List<Record>> groups = new LinkedHashMap<>();
        for (Record r : list) {
            String key = switch (by) {
                case MON -> r.name();
                case KHUNG_GIO -> String.format("%02d:00 - %02d:59", r.startedAt().getHour(), r.startedAt().getHour());
                case CHEF -> r.chef();
            };
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        List<Summary> out = new ArrayList<>();
        for (Map.Entry<String, List<Record>> e : groups.entrySet()) {
            List<Record> g = e.getValue();
            double std = 0;
            double act = 0;
            double wait = 0;
            int late = 0;
            int ext = 0;
            int notDone = 0;
            Map<String, Integer> reasons = new HashMap<>();
            for (Record r : g) {
                std += r.standardSec();
                act += r.actualSec();
                wait += r.waitSec();
                late += r.late() ? 1 : 0;
                ext += r.extendCount();
                notDone += r.notDoneCount();
                for (String part : r.reasons().split(";")) {
                    String reason = part.replaceAll("\\(\\+\\d+\\)", "").trim();
                    if (!reason.isEmpty()) {
                        reasons.merge(reason, 1, Integer::sum);
                    }
                }
            }
            int n = g.size();
            String top = reasons.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(x -> x.getKey() + " (" + x.getValue() + ")").orElse("");
            out.add(new Summary(e.getKey(), n, std / n / 60, act / n / 60, (act - std) / n / 60,
                    100.0 * late / n, ext, top, wait / n, notDone));
        }
        if (by == GroupBy.KHUNG_GIO) {
            out.sort(Comparator.comparing(Summary::group));
        } else {
            out.sort(Comparator.comparingDouble(Summary::avgDiffMin).reversed());
        }
        return out;
    }

    static String mmss(long sec) {
        long s = Math.abs(sec);
        return (sec < 0 ? "-" : "") + s / 60 + ":" + String.format("%02d", s % 60);
    }
}
