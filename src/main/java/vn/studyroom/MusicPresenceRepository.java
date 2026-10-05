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

    public record RoomSyncState(
        String roomId,
        String roomName,
        String currentTrackId,
        String currentTrackTitle,
        String currentTrackArtist,
        int currentTrackDuration,
        String currentTrackThumb,
        String currentTrackAudio,
        String currentTrackQuery,
        boolean isPlaying,
        double positionSeconds,
        String lastUpdatedBy,
        long version,
        long updatedAtEpoch
    ) {}

    public record RoomPlaylistItem(
        long id,
        String roomId,
        String trackId,
        String title,
        String artist,
        int durationSeconds,
        String thumbnailUrl,
        String audioPath,
        String youtubeQuery,
        String addedBy,
        int sortOrder
    ) {}

    public MusicPresenceRepository(Database database) {
        this.database = database;
    }

    public RoomSyncState getRoomSyncState(String roomId) {
        if (roomId == null || roomId.isBlank()) return null;
        String sql = """
            SELECT room_id, room_name, current_track_id, current_track_title, current_track_artist,
                   current_track_duration, current_track_thumb, current_track_audio, current_track_query,
                   is_playing, position_seconds, last_updated_by, version,
                   EXTRACT(EPOCH FROM updated_at)::BIGINT
            FROM music_room_state
            WHERE room_id = ?
        """;
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, roomId);
            ResultSet rs = q.executeQuery();
            if (rs.next()) {
                return new RoomSyncState(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getInt(6),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getString(9),
                    rs.getBoolean(10),
                    rs.getDouble(11),
                    rs.getString(12),
                    rs.getLong(13),
                    rs.getLong(14)
                );
            }
        } catch (SQLException ignored) { }
        return null;
    }

    public void updateRoomSyncState(String roomId, String roomName, MusicTrack track, boolean isPlaying, double positionSeconds, String updatedBy, String youtubeQuery) {
        if (roomId == null || roomId.isBlank()) return;
        String sql = """
            INSERT INTO music_room_state (
                room_id, room_name, current_track_id, current_track_title, current_track_artist,
                current_track_duration, current_track_thumb, current_track_audio, current_track_query,
                is_playing, position_seconds, last_updated_by, version, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP)
            ON CONFLICT (room_id) DO UPDATE SET
                room_name = EXCLUDED.room_name,
                current_track_id = EXCLUDED.current_track_id,
                current_track_title = EXCLUDED.current_track_title,
                current_track_artist = EXCLUDED.current_track_artist,
                current_track_duration = EXCLUDED.current_track_duration,
                current_track_thumb = EXCLUDED.current_track_thumb,
                current_track_audio = EXCLUDED.current_track_audio,
                current_track_query = EXCLUDED.current_track_query,
                is_playing = EXCLUDED.is_playing,
                position_seconds = EXCLUDED.position_seconds,
                last_updated_by = EXCLUDED.last_updated_by,
                version = music_room_state.version + 1,
                updated_at = CURRENT_TIMESTAMP
        """;
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, roomId);
            q.setString(2, roomName != null ? roomName : "Phòng học");
            q.setString(3, track != null ? track.id() : "");
            q.setString(4, track != null ? track.title() : "");
            q.setString(5, track != null ? track.artist() : "");
            q.setInt(6, track != null ? track.durationSeconds() : 0);
            q.setString(7, track != null ? track.thumbnailUrl() : "");
            q.setString(8, track != null ? track.widgetSrc() : "");
            q.setString(9, youtubeQuery != null ? youtubeQuery : "");
            q.setBoolean(10, isPlaying);
            q.setDouble(11, positionSeconds);
            q.setString(12, updatedBy != null ? updatedBy : "anonymous");
            q.executeUpdate();
        } catch (SQLException ignored) { }
    }

    public void addTrackToRoomPlaylist(String roomId, MusicTrack track, String addedBy, String youtubeQuery) {
        if (roomId == null || track == null) return;
        String sql = """
            INSERT INTO music_room_playlist (
                room_id, track_id, title, artist, duration_seconds,
                thumbnail_url, audio_path, youtube_query, added_by, sort_order, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM music_room_playlist WHERE room_id = ?), CURRENT_TIMESTAMP)
        """;
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, roomId);
            q.setString(2, track.id());
            q.setString(3, track.title());
            q.setString(4, track.artist());
            q.setInt(5, track.durationSeconds());
            q.setString(6, track.thumbnailUrl() != null ? track.thumbnailUrl() : "");
            q.setString(7, track.widgetSrc() != null ? track.widgetSrc() : "");
            q.setString(8, youtubeQuery != null ? youtubeQuery : "");
            q.setString(9, addedBy != null ? addedBy : "anonymous");
            q.setString(10, roomId);
            q.executeUpdate();
        } catch (SQLException ignored) { }
    }

    public List<RoomPlaylistItem> getRoomPlaylist(String roomId) {
        List<RoomPlaylistItem> list = new ArrayList<>();
        if (roomId == null) return list;
        String sql = """
            SELECT id, room_id, track_id, title, artist, duration_seconds,
                   thumbnail_url, audio_path, youtube_query, added_by, sort_order
            FROM music_room_playlist
            WHERE room_id = ?
            ORDER BY sort_order ASC, id ASC
        """;
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, roomId);
            ResultSet rs = q.executeQuery();
            while (rs.next()) {
                list.add(new RoomPlaylistItem(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getInt(6),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getString(9),
                    rs.getString(10),
                    rs.getInt(11)
                ));
            }
        } catch (SQLException ignored) { }
        return list;
    }

    public void removeTrackFromRoomPlaylist(String roomId, String trackId) {
        if (roomId == null || trackId == null) return;
        String sql = "DELETE FROM music_room_playlist WHERE room_id = ? AND track_id = ?";
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, roomId);
            q.setString(2, trackId);
            q.executeUpdate();
        } catch (SQLException ignored) { }
    }

    public void clearRoomPlaylist(String roomId) {
        if (roomId == null) return;
        String sql = "DELETE FROM music_room_playlist WHERE room_id = ?";
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, roomId);
            q.executeUpdate();
        } catch (SQLException ignored) { }
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
