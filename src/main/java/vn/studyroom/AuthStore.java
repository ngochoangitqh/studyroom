package vn.studyroom;

import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** Local-first auth backed by H2 with PBKDF2 password hashes. */
public final class AuthStore {
    private final Database database;

    public AuthStore(Database database) { this.database = database; }

    public synchronized User register(String name, String username, String password) {
        String key = username != null ? username.trim().toLowerCase() : "";
        String displayName = name != null ? name.trim() : "";
        if (displayName.isBlank() || key.isBlank() || password == null || password.length() < 6) {
            throw new IllegalArgumentException("Vui lòng điền đủ thông tin; mật khẩu phải có ít nhất 6 ký tự.");
        }
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement("INSERT INTO app_user(username, display_name, password_hash, salt) VALUES (?, ?, ?, ?)") ) {
            q.setString(1, key); q.setString(2, displayName); q.setString(3, hash(password, salt)); q.setString(4, Base64.getEncoder().encodeToString(salt)); q.executeUpdate();
            return new User(key, displayName);
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) throw new IllegalArgumentException("Tên đăng nhập này đã tồn tại.");
            throw new IllegalStateException("Lỗi kết nối cơ sở dữ liệu. Vui lòng kiểm tra PostgreSQL.", e);
        }
    }

    public synchronized User login(String username, String password) {
        String key = username != null ? username.trim().toLowerCase() : "";
        if (key.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("Vui lòng nhập tên đăng nhập và mật khẩu.");
        }
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement("SELECT display_name, password_hash, salt FROM app_user WHERE username = ?")) {
            q.setString(1, key); ResultSet result = q.executeQuery();
            if (!result.next() || !hash(password, Base64.getDecoder().decode(result.getString("salt"))).equals(result.getString("password_hash"))) {
                throw new IllegalArgumentException("Tên đăng nhập hoặc mật khẩu chưa đúng. (Nếu chưa có tài khoản, hãy nhấn \"Đăng ký\" ở bên dưới).");
            }
            return new User(key, result.getString("display_name"));
        } catch (SQLException e) { throw new IllegalStateException("Lỗi kết nối cơ sở dữ liệu. Vui lòng kiểm tra PostgreSQL.", e); }
    }

    public List<User> otherUsers(String username) {
        List<User> users = new ArrayList<>();
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement("SELECT username, display_name FROM app_user WHERE username <> ? ORDER BY display_name")) {
            q.setString(1, username); ResultSet result = q.executeQuery();
            while (result.next()) users.add(new User(result.getString(1), result.getString(2)));
            return users;
        } catch (SQLException e) { throw new IllegalStateException("Không thể tải người dùng.", e); }
    }

    private final java.util.Map<String, javafx.scene.image.Image> userAvatarCache = new java.util.concurrent.ConcurrentHashMap<>();

    public void saveUserAvatar(String username, byte[] avatarData) {
        if (username == null || avatarData == null) return;
        String rawKey = username.trim();
        String lowerKey = rawKey.toLowerCase();
        try (Connection c = database.connect();
             PreparedStatement q = c.prepareStatement("UPDATE app_user SET avatar = ? WHERE username = ? OR LOWER(username) = ?")) {
            q.setBytes(1, avatarData);
            q.setString(2, rawKey);
            q.setString(3, lowerKey);
            q.executeUpdate();
            javafx.scene.image.Image img = new javafx.scene.image.Image(new java.io.ByteArrayInputStream(avatarData));
            userAvatarCache.put(rawKey, img);
            userAvatarCache.put(lowerKey, img);
        } catch (SQLException e) {
            throw new IllegalStateException("Không thể lưu ảnh đại diện.", e);
        }
    }

    public javafx.scene.image.Image getUserAvatarImage(String username) {
        if (username == null || username.isBlank()) return null;
        String rawKey = username.trim();
        String lowerKey = rawKey.toLowerCase();
        if (userAvatarCache.containsKey(rawKey)) return userAvatarCache.get(rawKey);
        if (userAvatarCache.containsKey(lowerKey)) return userAvatarCache.get(lowerKey);

        try (Connection c = database.connect();
             PreparedStatement q = c.prepareStatement("SELECT avatar FROM app_user WHERE username = ? OR LOWER(username) = ?")) {
            q.setString(1, rawKey);
            q.setString(2, lowerKey);
            ResultSet rs = q.executeQuery();
            if (rs.next()) {
                byte[] data = rs.getBytes(1);
                if (data != null && data.length > 0) {
                    javafx.scene.image.Image img = new javafx.scene.image.Image(new java.io.ByteArrayInputStream(data));
                    userAvatarCache.put(rawKey, img);
                    userAvatarCache.put(lowerKey, img);
                    return img;
                }
            }
        } catch (SQLException ignored) { }
        return null;
    }

    private static String hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 210_000, 256);
            return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (InvalidKeySpecException | java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

