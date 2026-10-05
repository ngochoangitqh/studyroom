package vn.studyroom;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class MusicPresenceRepository {
    private final Database database;

    public record FriendMusicPresence(String username, String displayName, String roomId, String roomName, String currentSong) {}
    public record MusicInvitation(long id, String senderUsername, String senderDisplayName, String receiverUsername, String roomId, String roomName) {}

    public MusicPresenceRepository(Database database) {
        this.database = database;
    }

    public void updatePresence(String username, String displayName, String roomId, String roomName, String currentSong) {
        String sql = """
            INSERT INTO music_presence (username, display_name, room_id, room_name, current_song, updated_at)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (username) DO UPDATE
            SET display_name = EXCLUDED.display_name,
                room_id = EXCLUDED.room_id,
                room_name = EXCLUDED.room_name,
                current_song = EXCLUDED.current_song,
                updated_at = CURRENT_TIMESTAMP
        """;
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, username);
            q.setString(2, displayName);
            q.setString(3, roomId);
            q.setString(4, roomName);
            q.setString(5, currentSong != null ? currentSong : "");
            q.executeUpdate();
        } catch (SQLException ignored) { }
    }

    public void clearPresence(String username) {
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement("DELETE FROM music_presence WHERE username = ?")) {
            q.setString(1, username);
            q.executeUpdate();
        } catch (SQLException ignored) { }
    }

    public FriendMusicPresence getFriendPresence(String friendUsername) {
        if (friendUsername == null) return null;
        String sql = "SELECT username, display_name, room_id, room_name, current_song FROM music_presence WHERE username = ? AND updated_at >= CURRENT_TIMESTAMP - INTERVAL '3 minutes'";
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, friendUsername);
            ResultSet rs = q.executeQuery();
            if (rs.next()) {
                return new FriendMusicPresence(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5));
            }
        } catch (SQLException ignored) { }
        return null;
    }

    public List<FriendMusicPresence> getFriendsListening(String currentUsername) {
        List<FriendMusicPresence> list = new ArrayList<>();
        String sql = """
            SELECT mp.username, mp.display_name, mp.room_id, mp.room_name, mp.current_song
            FROM music_presence mp
            JOIN friendship f ON ((f.sender = ? AND f.receiver = mp.username) OR (f.receiver = ? AND f.sender = mp.username))
            WHERE mp.username <> ?
              AND mp.updated_at >= CURRENT_TIMESTAMP - INTERVAL '3 minutes'
            ORDER BY mp.updated_at DESC
        """;
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, currentUsername);
            q.setString(2, currentUsername);
            q.setString(3, currentUsername);
            ResultSet rs = q.executeQuery();
            while (rs.next()) {
                list.add(new FriendMusicPresence(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
            }
        } catch (SQLException ignored) { }
        return list;
    }

    public void sendInvite(String senderUser, String senderName, String receiverUser, String roomId, String roomName) {
        String sql = "INSERT INTO music_invite (sender_username, sender_display_name, receiver_username, room_id, room_name, status) VALUES (?, ?, ?, ?, ?, 'PENDING')";
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, senderUser);
            q.setString(2, senderName);
            q.setString(3, receiverUser);
            q.setString(4, roomId);
            q.setString(5, roomName);
            q.executeUpdate();
        } catch (SQLException ignored) { }
    }

    public MusicInvitation getLatestPendingInvite(String receiverUser) {
        String sql = "SELECT id, sender_username, sender_display_name, receiver_username, room_id, room_name FROM music_invite WHERE receiver_username = ? AND status = 'PENDING' AND created_at >= CURRENT_TIMESTAMP - INTERVAL '2 minutes' ORDER BY created_at DESC LIMIT 1";
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, receiverUser);
            ResultSet rs = q.executeQuery();
            if (rs.next()) {
                return new MusicInvitation(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6));
            }
        } catch (SQLException ignored) { }
        return null;
    }

    public void dismissInvite(long inviteId) {
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement("UPDATE music_invite SET status = 'DISMISSED' WHERE id = ?")) {
            q.setLong(1, inviteId);
            q.executeUpdate();
        } catch (SQLException ignored) { }
    }

    public void acceptInvite(long inviteId) {
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement("UPDATE music_invite SET status = 'ACCEPTED' WHERE id = ?")) {
            q.setLong(1, inviteId);
            q.executeUpdate();
        } catch (SQLException ignored) { }
    }
}
