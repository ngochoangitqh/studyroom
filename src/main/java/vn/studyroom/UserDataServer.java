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
 * Manages user accounts, credentials, registration & login logs in "data server" directory.
 */
public final class UserDataServer {
    private static final File DATA_SERVER_DIR = new File(System.getProperty("user.dir"), "data server");
    private static final File USERS_DIR = new File(DATA_SERVER_DIR, "users");
    private static final File USERS_JSON_FILE = new File(DATA_SERVER_DIR, "users.json");
    private static final File ACTIVITY_LOG_FILE = new File(DATA_SERVER_DIR, "auth_activity.log");
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
                        saveIndividualUserFile(p);
                        changed = true;
                    }
                }
            }
            if (changed || !USERS_JSON_FILE.exists()) {
                saveAll();
            }
        } catch (Exception e) {
            System.err.println("UserDataServer syncFromDatabase error: " + e.getMessage());
        }
    }

    private synchronized void saveAll() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            int idx = 0;
            int total = userCache.size();
            for (UserProfile p : userCache.values()) {
                sb.append("  {\n");
                sb.append("    \"username\": \"").append(escapeJson(p.username())).append("\",\n");
                sb.append("    \"displayName\": \"").append(escapeJson(p.displayName())).append("\",\n");
                sb.append("    \"password\": \"").append(escapeJson(p.password())).append("\",\n");
                sb.append("    \"registeredAt\": \"").append(escapeJson(p.registeredAt())).append("\",\n");
                sb.append("    \"lastLoginAt\": \"").append(escapeJson(p.lastLoginAt())).append("\",\n");
                sb.append("    \"loginCount\": ").append(p.loginCount()).append(",\n");
                sb.append("    \"status\": \"").append(escapeJson(p.status())).append("\"\n");
                sb.append("  }").append(idx < total - 1 ? "," : "").append("\n");
                idx++;
            }
            sb.append("]\n");

            Files.writeString(USERS_JSON_FILE.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("UserDataServer saveAll error: " + e.getMessage());
        }
    }

    private void saveIndividualUserFile(UserProfile p) {
        try {
            File userFile = new File(USERS_DIR, p.username() + ".json");
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"username\": \"").append(escapeJson(p.username())).append("\",\n");
            sb.append("  \"displayName\": \"").append(escapeJson(p.displayName())).append("\",\n");
            sb.append("  \"password\": \"").append(escapeJson(p.password())).append("\",\n");
            sb.append("  \"registeredAt\": \"").append(escapeJson(p.registeredAt())).append("\",\n");
            sb.append("  \"lastLoginAt\": \"").append(escapeJson(p.lastLoginAt())).append("\",\n");
            sb.append("  \"loginCount\": ").append(p.loginCount()).append(",\n");
            sb.append("  \"status\": \"").append(escapeJson(p.status())).append("\"\n");
            sb.append("}\n");

            Files.writeString(userFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("UserDataServer saveIndividualUserFile error: " + e.getMessage());
        }
    }

    private void appendLog(String logLine) {
        try {
            String entry = "[" + now() + "] " + logLine + System.lineSeparator();
            Files.writeString(ACTIVITY_LOG_FILE.toPath(), entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
