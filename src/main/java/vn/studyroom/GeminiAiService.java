package vn.studyroom;

import javafx.application.Platform;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class GeminiAiService {
    public static final String DEFAULT_API_KEY = new String(
        java.util.Base64.getDecoder().decode("QVEuQWI4Uk42SmM0YjNEY1pXaUZEd25YRHRmVTFhUmV0WnYteFpyZHZkYXRmMU5Famd1SWc="),
        StandardCharsets.UTF_8
    );
    private static final String SYSTEM_INSTRUCTION = """
        Bạn là StudyBot - Trợ lý AI học tập thông minh và tận tâm trong ứng dụng Studyroom.
        Nhiệm vụ chính của bạn:
        1. Giải đáp thắc mắc bài học và giải chi tiết các bài tập (Toán, Vật Lý, Hóa Học, Sinh Học, Ngữ Văn, Tiếng Anh, Tin học...).
        2. Trình bày lời giải rõ ràng từng bước (Bước 1, Bước 2...), nêu rõ công thức, định lý và giải thích cặn kẽ để học sinh thực sự hiểu bài.
        3. Hỗ trợ tóm tắt lý thuyết trọng tâm, tạo câu hỏi trắc nghiệm ôn tập và mẹo ghi nhớ nhanh.
        4. Giữ thái độ thân thiện, khích lệ, truyền cảm hứng học tập tích cực, trả lời bằng tiếng Việt chuẩn mực, sử dụng biểu tượng cảm xúc sinh động khi phù hợp.
        """;

    private final Database database;
    private final HttpClient httpClient;
    private static final File KEY_FILE = new File(System.getProperty("user.dir"), ".cache/gemini_key.txt");

    public record ChatMessage(
        String id,
        String username,
        String role, // "user" or "model"
        String content,
        Timestamp createdAt
    ) {}

    public GeminiAiService(Database database) {
        this.database = database;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    public String getApiKey() {
        if (KEY_FILE.exists() && KEY_FILE.isFile()) {
            try {
                String customKey = Files.readString(KEY_FILE.toPath(), StandardCharsets.UTF_8).trim();
                if (!customKey.isBlank()) {
                    return customKey;
                }
            } catch (IOException ignored) {}
        }
        return DEFAULT_API_KEY;
    }

    public void setApiKey(String key) {
        try {
            KEY_FILE.getParentFile().mkdirs();
            Files.writeString(KEY_FILE.toPath(), key != null ? key.trim() : "", StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    public List<ChatMessage> getHistory(String username) {
        List<ChatMessage> list = new ArrayList<>();
        if (username == null || username.isBlank()) return list;

        String sql = """
            SELECT message_id, username, role, content, created_at
            FROM ai_chat_message
            WHERE username = ?
            ORDER BY created_at ASC
            LIMIT 100
        """;

        try (Connection c = database.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new ChatMessage(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getTimestamp(5)
                ));
            }
        } catch (SQLException ignored) {}
        return list;
    }

    public void saveMessage(String username, String role, String content) {
        if (username == null || content == null || content.isBlank()) return;
        String sql = """
            INSERT INTO ai_chat_message(message_id, username, role, content, created_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
        try (Connection c = database.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, username);
            ps.setString(3, role);
            ps.setString(4, content);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public void clearHistory(String username) {
        if (username == null || username.isBlank()) return;
        try (Connection c = database.connect();
             PreparedStatement ps = c.prepareStatement("DELETE FROM ai_chat_message WHERE username = ?")) {
            ps.setString(1, username);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public void askGeminiAsync(String username, String userMessage, Consumer<String> onSuccess, Consumer<String> onError) {
        if (userMessage == null || userMessage.isBlank()) return;

        saveMessage(username, "user", userMessage);

        Thread.ofVirtual().start(() -> {
            try {
                List<ChatMessage> history = getHistory(username);
                // Take up to the last 8 messages for context
                int start = Math.max(0, history.size() - 8);
                List<ChatMessage> context = history.subList(start, history.size());

                String response = requestGemini("gemini-3.5-flash", context);
                if (response == null || response.isBlank()) {
                    response = requestGemini("gemini-3.5-flash-lite", context);
                }

                if (response != null && !response.isBlank()) {
                    saveMessage(username, "model", response);
                    final String finalResponse = response;
                    Platform.runLater(() -> onSuccess.accept(finalResponse));
                } else {
                    Platform.runLater(() -> onError.accept("Không nhận được phản hồi từ AI. Vui lòng thử lại."));
                }
            } catch (Exception ex) {
                Platform.runLater(() -> onError.accept("Lỗi kết nối Gemini AI: " + ex.getMessage()));
            }
        });
    }

    private String requestGemini(String model, List<ChatMessage> messages) {
        String apiKey = getApiKey();
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"system_instruction\": {\n");
        json.append("    \"parts\": [{\"text\": \"").append(escapeJson(SYSTEM_INSTRUCTION)).append("\"}]\n");
        json.append("  },\n");
        json.append("  \"contents\": [\n");

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            String role = "user".equalsIgnoreCase(m.role()) ? "user" : "model";
            json.append("    {\n");
            json.append("      \"role\": \"").append(role).append("\",\n");
            json.append("      \"parts\": [{\"text\": \"").append(escapeJson(m.content())).append("\"}]\n");
            json.append("    }").append(i < messages.size() - 1 ? "," : "").append("\n");
        }

        json.append("  ]\n");
        json.append("}\n");

        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(25))
                .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                return extractTextFromResponse(resp.body());
            } else {
                System.err.println("Gemini " + model + " error HTTP " + resp.statusCode() + ": " + resp.body());
            }
        } catch (Exception ex) {
            System.err.println("Gemini request error: " + ex.getMessage());
        }
        return null;
    }

    private String extractTextFromResponse(String responseBody) {
        if (responseBody == null) return null;

        int candidatesIdx = responseBody.indexOf("\"candidates\"");
        if (candidatesIdx == -1) return null;

        int textKeyIdx = responseBody.indexOf("\"text\": \"", candidatesIdx);
        if (textKeyIdx == -1) {
            textKeyIdx = responseBody.indexOf("\"text\":\"", candidatesIdx);
            if (textKeyIdx == -1) return null;
            textKeyIdx += 8;
        } else {
            textKeyIdx += 9;
        }

        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = textKeyIdx; i < responseBody.length(); i++) {
            char c = responseBody.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'u' -> {
                        if (i + 4 < responseBody.length()) {
                            try {
                                String hex = responseBody.substring(i + 1, i + 5);
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (Exception ignored) {
                                sb.append('u');
                            }
                        } else {
                            sb.append('u');
                        }
                    }
                    default -> sb.append(c);
                }
                escaped = false;
            } else {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < ' ') {
                        String hex = Integer.toHexString(ch);
                        sb.append("\\u");
                        sb.append("0".repeat(4 - hex.length())).append(hex);
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }
}
