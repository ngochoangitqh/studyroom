package vn.studyroom;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class FriendRepository {
    private final Database database;
    public FriendRepository(Database database) { this.database = database; }

    public void sendRequest(String from, String to) {
        if (from.equals(to)) throw new IllegalArgumentException("Không thể kết bạn với chính mình.");
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(
                "INSERT INTO friendship(sender, receiver, status) VALUES (?, ?, 'pending') ON CONFLICT DO NOTHING")) {
            q.setString(1, from); q.setString(2, to); q.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Không thể gửi lời mời.", e); }
    }

    public void acceptRequest(String sender, String receiver) {
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(
                "UPDATE friendship SET status = 'accepted' WHERE sender = ? AND receiver = ? AND status = 'pending'")) {
            q.setString(1, sender); q.setString(2, receiver); q.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Không thể chấp nhận lời mời.", e); }
    }

    public void rejectRequest(String sender, String receiver) {
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(
                "DELETE FROM friendship WHERE sender = ? AND receiver = ? AND status = 'pending'")) {
            q.setString(1, sender); q.setString(2, receiver); q.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Không thể từ chối lời mời.", e); }
    }

    public void unfriend(String user1, String user2) {
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(
                "DELETE FROM friendship WHERE (sender = ? AND receiver = ?) OR (sender = ? AND receiver = ?)")) {
            q.setString(1, user1); q.setString(2, user2); q.setString(3, user2); q.setString(4, user1); q.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Không thể huỷ kết bạn.", e); }
    }

    public List<User> friends(String username) {
        List<User> list = new ArrayList<>();
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(
                "SELECT u.username, u.display_name FROM app_user u WHERE u.username IN (" +
                "  SELECT CASE WHEN sender = ? THEN receiver ELSE sender END FROM friendship " +
                "  WHERE (sender = ? OR receiver = ?) AND status = 'accepted'" +
                ") ORDER BY u.display_name")) {
            q.setString(1, username); q.setString(2, username); q.setString(3, username);
            ResultSet result = q.executeQuery();
            while (result.next()) list.add(new User(result.getString(1), result.getString(2)));
            return list;
        } catch (SQLException e) { throw new IllegalStateException("Không thể tải danh sách bạn bè.", e); }
    }

    public List<User> pendingReceived(String username) {
        List<User> list = new ArrayList<>();
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(
                "SELECT u.username, u.display_name FROM friendship f JOIN app_user u ON f.sender = u.username WHERE f.receiver = ? AND f.status = 'pending' ORDER BY f.created_at DESC")) {
            q.setString(1, username); ResultSet result = q.executeQuery();
            while (result.next()) list.add(new User(result.getString(1), result.getString(2)));
            return list;
        } catch (SQLException e) { throw new IllegalStateException("Không thể tải lời mời kết bạn.", e); }
    }

    public List<User> strangersFor(String username) {
        List<User> list = new ArrayList<>();
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(
                "SELECT username, display_name FROM app_user WHERE username <> ? " +
                "AND username NOT IN (SELECT CASE WHEN sender = ? THEN receiver ELSE sender END FROM friendship WHERE sender = ? OR receiver = ?) " +
                "ORDER BY display_name")) {
            q.setString(1, username); q.setString(2, username); q.setString(3, username); q.setString(4, username);
            ResultSet result = q.executeQuery();
            while (result.next()) list.add(new User(result.getString(1), result.getString(2)));
            return list;
        } catch (SQLException e) { throw new IllegalStateException("Không thể tải người dùng.", e); }
    }
    public List<UserSearchResult> searchUsers(String currentUsername, String query) {
        List<UserSearchResult> results = new ArrayList<>();
        if (query == null || query.isBlank()) return results;
        String searchTerm = "%" + query.trim().toLowerCase() + "%";
        String sql = """
            SELECT u.username, u.display_name,
                   f1.status AS sent_status,
                   f2.status AS recv_status
            FROM app_user u
            LEFT JOIN friendship f1 ON f1.sender = ? AND f1.receiver = u.username
            LEFT JOIN friendship f2 ON f2.sender = u.username AND f2.receiver = ?
            WHERE u.username <> ?
              AND (LOWER(u.username) LIKE ? OR LOWER(u.display_name) LIKE ?)
            ORDER BY u.display_name
            LIMIT 20
        """;
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, currentUsername);
            q.setString(2, currentUsername);
            q.setString(3, currentUsername);
            q.setString(4, searchTerm);
            q.setString(5, searchTerm);
            ResultSet rs = q.executeQuery();
            while (rs.next()) {
                String un = rs.getString("username");
                String dn = rs.getString("display_name");
                String sent = rs.getString("sent_status");
                String recv = rs.getString("recv_status");
                String relStatus = "none";
                if ("accepted".equals(sent) || "accepted".equals(recv)) {
                    relStatus = "friend";
                } else if ("pending".equals(sent)) {
                    relStatus = "pending_sent";
                } else if ("pending".equals(recv)) {
                    relStatus = "pending_received";
                }
                results.add(new UserSearchResult(new User(un, dn), relStatus));
            }
            return results;
        } catch (SQLException e) {
            throw new IllegalStateException("Không thể tìm kiếm người dùng.", e);
        }
    }
    public record UserSearchResult(User user, String status) { }
}
