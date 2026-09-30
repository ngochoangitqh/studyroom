package vn.studyroom;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ChatRepository {
    private final Database database;
    public ChatRepository(Database database) { this.database = database; }
    public void save(String roomId, String sender, String body) {
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement("INSERT INTO chat_message(room_id, sender, body) VALUES (?, ?, ?)")) {
            q.setString(1, roomId); q.setString(2, sender); q.setString(3, body); q.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Không thể lưu tin nhắn.", e); }
    }
    public List<Message> recent(String roomId, int limit) {
        List<Message> messages = new ArrayList<>();
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement("SELECT id, sender, body FROM chat_message WHERE room_id = ? ORDER BY id DESC LIMIT ?")) {
            q.setString(1, roomId); q.setInt(2, limit); ResultSet result = q.executeQuery();
            while (result.next()) messages.add(0, new Message(result.getLong(1), result.getString(2), result.getString(3)));
            return messages;
        } catch (SQLException e) { throw new IllegalStateException("Không thể tải lịch sử chat.", e); }
    }
    public List<Message> messagesSince(String roomId, long lastId) {
        List<Message> list = new ArrayList<>();
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(
                "SELECT id, sender, body FROM chat_message WHERE room_id = ? AND id > ? ORDER BY id ASC")) {
            q.setString(1, roomId); q.setLong(2, lastId); ResultSet result = q.executeQuery();
            while (result.next()) list.add(new Message(result.getLong(1), result.getString(2), result.getString(3)));
            return list;
        } catch (SQLException e) { return list; }
    }
    public Room createRoom(String owner, String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Tên nhóm không được để trống.");
        String id = UUID.randomUUID().toString();
        try (Connection c = database.connect()) {
            try (PreparedStatement q = c.prepareStatement("INSERT INTO study_group(room_id, room_name, owner_username) VALUES (?, ?, ?)")) {
                q.setString(1, id); q.setString(2, name.trim()); q.setString(3, owner); q.executeUpdate();
            }
            try (PreparedStatement q = c.prepareStatement("INSERT INTO group_member(room_id, username) VALUES (?, ?)")) {
                q.setString(1, id); q.setString(2, owner); q.executeUpdate();
            }
            return new Room(id, name.trim());
        } catch (SQLException e) { throw new IllegalStateException("Không thể tạo nhóm.", e); }
    }
    public List<Room> roomsFor(String username) {
        List<Room> rooms = new ArrayList<>();
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(
                "SELECT g.room_id, g.room_name FROM study_group g LEFT JOIN group_member m ON g.room_id = m.room_id WHERE g.owner_username = ? OR m.username = ? GROUP BY g.room_id, g.room_name, g.created_at ORDER BY g.created_at DESC")) {
            q.setString(1, username); q.setString(2, username); ResultSet result = q.executeQuery();
            while (result.next()) rooms.add(new Room(result.getString(1), result.getString(2)));
            return rooms;
        } catch (SQLException e) { throw new IllegalStateException("Không thể tải danh sách nhóm.", e); }
    }
    public void addMember(String roomId, String username) {
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement("INSERT INTO group_member(room_id, username) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            q.setString(1, roomId); q.setString(2, username); q.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Không thể thêm thành viên.", e); }
    }
    public void removeMember(String roomId, String username) {
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement("DELETE FROM group_member WHERE room_id = ? AND username = ?")) {
            q.setString(1, roomId); q.setString(2, username); q.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Không thể xoá thành viên.", e); }
    }
    public List<String> membersOf(String roomId) {
        List<String> members = new ArrayList<>();
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(
                "SELECT u.display_name FROM group_member m JOIN app_user u ON m.username = u.username WHERE m.room_id = ? ORDER BY m.joined_at")) {
            q.setString(1, roomId); ResultSet result = q.executeQuery();
            while (result.next()) members.add(result.getString(1));
            return members;
        } catch (SQLException e) { throw new IllegalStateException("Không thể tải thành viên.", e); }
    }
    public List<User> nonMembers(String roomId) {
        List<User> users = new ArrayList<>();
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(
                "SELECT username, display_name FROM app_user WHERE username NOT IN (SELECT username FROM group_member WHERE room_id = ?) ORDER BY display_name")) {
            q.setString(1, roomId); ResultSet result = q.executeQuery();
            while (result.next()) users.add(new User(result.getString(1), result.getString(2)));
            return users;
        } catch (SQLException e) { throw new IllegalStateException("Không thể tải danh sách người dùng.", e); }
    }
    public record Message(long id, String sender, String body) { }
    public record Room(String id, String name) { }
}
