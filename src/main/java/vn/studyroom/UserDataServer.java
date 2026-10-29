package vn.studyroom;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Manages user accounts, credentials, registration & login logs in "data server" directory as .txt files.
 */
public final class UserDataServer {
    private static final File DATA_SERVER_DIR = new File(System.getProperty("user.dir"), "data server");
    private static final File USERS_DIR = new File(DATA_SERVER_DIR, "users");
    private static final File USERS_TXT_FILE = new File(DATA_SERVER_DIR, "danh_sach_tai_khoan.txt");
    private static final File USERS_TXT_ALIAS = new File(DATA_SERVER_DIR, "users.txt");
    private static final File USERS_JSON_FILE = new File(DATA_SERVER_DIR, "users.json");
    private static final File ACTIVITY_TXT_FILE = new File(DATA_SERVER_DIR, "nhat_ky_dang_nhap.txt");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final UserDataServer INSTANCE = new UserDataServer();

    public record UserProfile(
        String username,
        String displayName,
        String password,
        String registeredAt,
        String lastLoginAt,
        int loginCount,
        String status
    ) {}

    private final Map<String, UserProfile> userCache = new LinkedHashMap<>();

    public static UserDataServer getInstance() {
        return INSTANCE;
    }

    private UserDataServer() {
        ensureDirectories();
        loadExistingUsers();
    }

    private synchronized void ensureDirectories() {
        if (!DATA_SERVER_DIR.exists()) {
            DATA_SERVER_DIR.mkdirs();
        }
        if (!USERS_DIR.exists()) {
            USERS_DIR.mkdirs();
        }
    }

    private synchronized void loadExistingUsers() {
        if (!USERS_JSON_FILE.exists() || !USERS_JSON_FILE.isFile()) return;
        try {
            String json = Files.readString(USERS_JSON_FILE.toPath(), StandardCharsets.UTF_8).trim();
            if (json.startsWith("[") && json.endsWith("]")) {
                List<Map<String, String>> list = parseJsonObjects(json);
                for (Map<String, String> m : list) {
                    String u = m.getOrDefault("username", "");
                    if (!u.isBlank()) {
                        int count = 0;
                        try { count = Integer.parseInt(m.getOrDefault("loginCount", "0")); } catch (Exception ignored) {}
                        UserProfile p = new UserProfile(
                            u,
                            m.getOrDefault("displayName", u),
                            m.getOrDefault("password", "******"),
                            m.getOrDefault("registeredAt", now()),
                            m.getOrDefault("lastLoginAt", ""),
                            count,
                            m.getOrDefault("status", "ACTIVE")
                        );
                        userCache.put(u.toLowerCase(), p);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("UserDataServer load error: " + e.getMessage());
        }
    }

    public synchronized void recordRegistration(String username, String displayName, String password) {
        ensureDirectories();
        String key = username != null ? username.trim().toLowerCase() : "";
        if (key.isBlank()) return;

        String currentTime = now();
        UserProfile profile = new UserProfile(
            key,
            displayName != null ? displayName.trim() : key,
            password != null ? password : "",
            currentTime,
            "",
            0,
            "ACTIVE"
        );
        userCache.put(key, profile);
        saveAll();
        saveIndividualUserFile(profile);

        appendLog("[ĐĂNG KÝ MỚI] Tài khoản: @" + key + " (" + profile.displayName() + ") | Mật khẩu: " + profile.password() + " | Thời gian: " + currentTime);
    }

    public synchronized void recordLoginSuccess(String username, String displayName, String password) {
        ensureDirectories();
        String key = username != null ? username.trim().toLowerCase() : "";
        if (key.isBlank()) return;

        String currentTime = now();
        UserProfile existing = userCache.get(key);
        int newCount = (existing != null ? existing.loginCount() : 0) + 1;
        String regTime = existing != null && existing.registeredAt() != null && !existing.registeredAt().isBlank() ? existing.registeredAt() : currentTime;
        String pass = (password != null && !password.isBlank()) ? password : (existing != null ? existing.password() : "******");
        String dName = (displayName != null && !displayName.isBlank()) ? displayName : (existing != null ? existing.displayName() : key);

        UserProfile updated = new UserProfile(
            key,
            dName,
            pass,
            regTime,
            currentTime,
            newCount,
            "ACTIVE"
        );
        userCache.put(key, updated);
        saveAll();
        saveIndividualUserFile(updated);

        appendLog("[ĐĂNG NHẬP THÀNH CÔNG] Tài khoản: @" + key + " (" + dName + ") | Lần đăng nhập thứ: " + newCount + " | Thời gian: " + currentTime);
    }

    public synchronized void recordLoginFailed(String username, String reason) {
        ensureDirectories();
        String key = username != null ? username.trim().toLowerCase() : "unknown";
        String currentTime = now();
        appendLog("[ĐĂNG NHẬP THẤT BẠI] Tài khoản: @" + key + " | Lý do: " + (reason != null ? reason : "Mật khẩu không khớp") + " | Thời gian: " + currentTime);
    }

    public synchronized void recordLogout(String username) {
        ensureDirectories();
        String key = username != null ? username.trim().toLowerCase() : "unknown";
        String currentTime = now();
        appendLog("[ĐĂNG XUẤT] Tài khoản: @" + key + " | Thời gian: " + currentTime);
    }

    public synchronized List<UserProfile> getAllUsers() {
        return new ArrayList<>(userCache.values());
    }

    public synchronized void syncFromDatabase(Database database) {
        if (database == null) return;
        ensureDirectories();
        try (Connection c = database.connect();
             PreparedStatement ps = c.prepareStatement("SELECT username, display_name, created_at FROM app_user ORDER BY created_at ASC");
             ResultSet rs = ps.executeQuery()) {

            boolean changed = false;
            while (rs.next()) {
                String u = rs.getString(1);
                String d = rs.getString(2);
                Timestamp t = rs.getTimestamp(3);
                if (u != null && !u.isBlank()) {
                    String key = u.trim().toLowerCase();
                    if (!userCache.containsKey(key)) {
                        String regTime = t != null ? t.toLocalDateTime().format(TIME_FMT) : now();
                        UserProfile p = new UserProfile(
                            key,
                            d != null ? d.trim() : key,
                            "******",
                            regTime,
                            "",
                            0,
                            "ACTIVE"
                        );
                        userCache.put(key, p);
                        changed = true;
                    }
                }
            }
            saveAll();
            for (UserProfile p : userCache.values()) {
                saveIndividualUserFile(p);
            }
        } catch (Exception e) {
            System.err.println("UserDataServer syncFromDatabase error: " + e.getMessage());
        }
    }

    private synchronized void saveAll() {
        ensureDirectories();
        try {
            // 1. Build beautiful human-readable TXT file
            StringBuilder txt = new StringBuilder();
            txt.append("========================================================================================================\n");
            txt.append("                                DANH SÁCH TÀI KHOẢN NGƯỜI DÙNG - STUDYROOM\n");
            txt.append("========================================================================================================\n");
            txt.append(String.format("%-4s | %-18s | %-20s | %-16s | %-20s | %-20s | %-8s\n",
                "STT", "TÊN ĐĂNG NHẬP", "TÊN HIỂN THỊ", "MẬT KHẨU", "NGÀY ĐĂNG KÝ", "ĐĂNG NHẬP CUỐI", "SỐ LẦN"));
            txt.append("--------------------------------------------------------------------------------------------------------\n");

            int stt = 1;
            for (UserProfile p : userCache.values()) {
                String lastLogin = p.lastLoginAt().isBlank() ? "(Chưa đăng nhập)" : p.lastLoginAt();
                txt.append(String.format("%-4d | @%-17s | %-20s | %-16s | %-20s | %-20s | %-8d\n",
                    stt++, p.username(), p.displayName(), p.password(), p.registeredAt(), lastLogin, p.loginCount()));
            }

            txt.append("========================================================================================================\n");
            txt.append("Tổng số tài khoản: ").append(userCache.size()).append(" | Cập nhật lúc: ").append(now()).append("\n\n");

            txt.append("--- CHI TIẾT TỪNG TÀI KHOẢN ---\n");
            int detailIdx = 1;
            for (UserProfile p : userCache.values()) {
                txt.append("\n[").append(detailIdx++).append("] Tài khoản: @").append(p.username()).append("\n");
                txt.append("    • Tên hiển thị:       ").append(p.displayName()).append("\n");
                txt.append("    • Mật khẩu:           ").append(p.password()).append("\n");
                txt.append("    • Ngày đăng ký:       ").append(p.registeredAt()).append("\n");
                txt.append("    • Đăng nhập gần nhất: ").append(p.lastLoginAt().isBlank() ? "(Chưa đăng nhập)" : p.lastLoginAt()).append("\n");
                txt.append("    • Tổng số lần vào:    ").append(p.loginCount()).append(" lần\n");
                txt.append("    • Trạng thái:         ").append(p.status()).append("\n");
            }

            String txtContent = txt.toString();
            Files.writeString(USERS_TXT_FILE.toPath(), txtContent, StandardCharsets.UTF_8);
            Files.writeString(USERS_TXT_ALIAS.toPath(), txtContent, StandardCharsets.UTF_8);

            // 2. Also save JSON as backup machine format
            StringBuilder json = new StringBuilder();
            json.append("[\n");
            int idx = 0;
            int total = userCache.size();
            for (UserProfile p : userCache.values()) {
                json.append("  {\n");
                json.append("    \"username\": \"").append(escapeJson(p.username())).append("\",\n");
                json.append("    \"displayName\": \"").append(escapeJson(p.displayName())).append("\",\n");
                json.append("    \"password\": \"").append(escapeJson(p.password())).append("\",\n");
                json.append("    \"registeredAt\": \"").append(escapeJson(p.registeredAt())).append("\",\n");
                json.append("    \"lastLoginAt\": \"").append(escapeJson(p.lastLoginAt())).append("\",\n");
                json.append("    \"loginCount\": ").append(p.loginCount()).append(",\n");
                json.append("    \"status\": \"").append(escapeJson(p.status())).append("\"\n");
                json.append("  }").append(idx < total - 1 ? "," : "").append("\n");
                idx++;
            }
            json.append("]\n");
            Files.writeString(USERS_JSON_FILE.toPath(), json.toString(), StandardCharsets.UTF_8);

        } catch (IOException e) {
            System.err.println("UserDataServer saveAll error: " + e.getMessage());
        }
    }

    private void saveIndividualUserFile(UserProfile p) {
        try {
            // Save as readable TXT file
            File userTxtFile = new File(USERS_DIR, p.username() + ".txt");
            StringBuilder sb = new StringBuilder();
            sb.append("=====================================================\n");
            sb.append("        THÔNG TIN TÀI KHOẢN NGƯỜI DÙNG: @").append(p.username()).append("\n");
            sb.append("=====================================================\n");
            sb.append("Tên đăng nhập:       @").append(p.username()).append("\n");
            sb.append("Tên hiển thị:        ").append(p.displayName()).append("\n");
            sb.append("Mật khẩu:            ").append(p.password()).append("\n");
            sb.append("Thời gian đăng ký:   ").append(p.registeredAt()).append("\n");
            sb.append("Đăng nhập gần nhất:  ").append(p.lastLoginAt().isBlank() ? "(Chưa đăng nhập)" : p.lastLoginAt()).append("\n");
            sb.append("Số lần đăng nhập:    ").append(p.loginCount()).append(" lần\n");
            sb.append("Trạng thái:          ").append(p.status()).append("\n");
            sb.append("=====================================================\n");
            sb.append("File tạo tự động bởi Studyroom Data Server\n");

            Files.writeString(userTxtFile.toPath(), sb.toString(), StandardCharsets.UTF_8);

            // Clean up old json individual file if exists
            File oldJson = new File(USERS_DIR, p.username() + ".json");
            if (oldJson.exists()) {
                oldJson.delete();
            }
        } catch (IOException e) {
            System.err.println("UserDataServer saveIndividualUserFile error: " + e.getMessage());
        }
    }

    private void appendLog(String logLine) {
        try {
            String entry = "[" + now() + "] " + logLine + System.lineSeparator();
            Files.writeString(ACTIVITY_TXT_FILE.toPath(), entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FMT);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static List<Map<String, String>> parseJsonObjects(String json) {
        List<Map<String, String>> list = new ArrayList<>();
        int i = 0;
        while (i < json.length()) {
            int startObj = json.indexOf('{', i);
            if (startObj == -1) break;
            int endObj = json.indexOf('}', startObj);
            if (endObj == -1) break;

            String objStr = json.substring(startObj + 1, endObj);
            Map<String, String> map = new LinkedHashMap<>();
            String[] lines = objStr.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            for (String pair : lines) {
                int colon = pair.indexOf(':');
                if (colon != -1) {
                    String k = pair.substring(0, colon).trim().replace("\"", "");
                    String v = pair.substring(colon + 1).trim();
                    if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                        v = v.substring(1, v.length() - 1);
                    }
                    map.put(k, v);
                }
            }
            list.add(map);
            i = endObj + 1;
        }
        return list;
    }
}
