package kos.common;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * Chuyển đổi giữa đối tượng Java và JSON.
 * Trên kênh TCP, mỗi thông điệp nằm trên đúng một dòng: Gson in JSON gọn trên một dòng
 * và mã hóa ký tự xuống dòng trong chuỗi thành \n, nên dòng không bao giờ bị vỡ.
 */
public final class Json {

    private Json() {
    }

    /** Gson dùng chung (an toàn khi dùng từ nhiều luồng). */
    public static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .disableHtmlEscaping()
            .create();

    /** Đối tượng -> một dòng JSON (chưa có ký tự xuống dòng ở cuối). */
    public static String toLine(Object obj) {
        return GSON.toJson(obj);
    }

    /**
     * Một dòng JSON -> Message.
     *
     * @throws JsonParseException nếu không phải JSON hợp lệ hoặc thiếu trường command
     */
    public static Message parseMessage(String line) {
        Message m = GSON.fromJson(line, Message.class);
        if (m == null || m.command == null || m.command.isBlank()) {
            throw new JsonParseException("Thiếu trường command");
        }
        return m;
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }
}
