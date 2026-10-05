package vn.studyroom;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class CallRepository {
    private final Database database;

    public CallRepository(Database database) {
        this.database = database;
    }

    public CallSession startCall(String roomId, String roomName, String hostUsername, String hostDisplayName, String callType, int udpPort, String ipAddress) {
        return startCall(roomId, roomName, hostUsername, hostDisplayName, null, callType, udpPort, ipAddress);
    }

    public CallSession startCall(String roomId, String roomName, String hostUsername, String hostDisplayName, String receiverUsername, String callType, int udpPort, String ipAddress) {
        // End any previous active call in this room first
        endCallsInRoom(roomId);

        String callId = UUID.randomUUID().toString();
        String initialStatus = "DIRECT".equalsIgnoreCase(callType) ? "RINGING" : "ACTIVE";

        try (Connection c = database.connect()) {
            try (PreparedStatement q = c.prepareStatement(
                    "INSERT INTO call_session(call_id, room_id, room_name, host_username, receiver_username, call_type, status) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                q.setString(1, callId);
                q.setString(2, roomId);
                q.setString(3, roomName);
                q.setString(4, hostUsername);
                q.setString(5, receiverUsername);
                q.setString(6, callType.toUpperCase());
                q.setString(7, initialStatus);
                q.executeUpdate();
            }

            try (PreparedStatement q = c.prepareStatement(
                    "INSERT INTO call_participant(call_id, username, display_name, status, udp_port, ip_address) VALUES (?, ?, ?, 'CONNECTED', ?, ?)")) {
                q.setString(1, callId);
                q.setString(2, hostUsername);
                q.setString(3, hostDisplayName);
                q.setInt(4, udpPort);
                q.setString(5, ipAddress != null ? ipAddress : "127.0.0.1");
                q.executeUpdate();
            }

            return new CallSession(callId, roomId, roomName, hostUsername, callType.toUpperCase(), initialStatus);
        } catch (SQLException e) {
            throw new IllegalStateException("Không thể bắt đầu cuộc gọi.", e);
        }
    }

    public CallSession getActiveCall(String roomId) {
        try (Connection c = database.connect();
             PreparedStatement q = c.prepareStatement(
                     "SELECT call_id, room_id, room_name, host_username, call_type, status FROM call_session WHERE room_id = ? AND status IN ('RINGING', 'ACTIVE') ORDER BY created_at DESC LIMIT 1")) {
            q.setString(1, roomId);
            ResultSet rs = q.executeQuery();
            if (rs.next()) {
                return new CallSession(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6));
            }
            return null;
        } catch (SQLException e) {
            return null;
        }
    }

    public CallSession getIncomingDirectCall(String username) {
        String sql = """
            SELECT c.call_id, c.room_id, COALESCE(u.display_name, c.room_name), c.host_username, c.call_type, c.status
            FROM call_session c
            LEFT JOIN app_user u ON c.host_username = u.username
            WHERE (
                c.status = 'RINGING'
                OR (
                    c.call_type = 'DIRECT'
                    AND c.status = 'ACTIVE'
                    AND NOT EXISTS (
                        SELECT 1 FROM call_participant cp 
                        WHERE cp.call_id = c.call_id AND cp.username = ? AND cp.status = 'CONNECTED'
                    )
                )
            )
              AND c.host_username <> ?
              AND (
                  c.receiver_username = ?
                  OR (
                      c.call_type = 'DIRECT'
                      AND c.room_id LIKE 'direct:%'
                      AND (
                          c.receiver_username IS NULL
                          OR EXISTS (
                              SELECT 1 FROM friendship f
                              WHERE (f.sender = ? AND f.receiver = c.host_username)
                                 OR (f.receiver = ? AND f.sender = c.host_username)
                          )
                      )
                  )
              )
              AND c.created_at >= CURRENT_TIMESTAMP - INTERVAL '60 seconds'
            ORDER BY c.created_at DESC LIMIT 1
        """;
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, username);
            q.setString(2, username);
            q.setString(3, username);
            q.setString(4, username);
            q.setString(5, username);
            ResultSet rs = q.executeQuery();
            if (rs.next()) {
                return new CallSession(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6));
            }
            return null;
        } catch (SQLException e) {
            System.err.println("[CallRepository] Error querying incoming call: " + e.getMessage());
            return null;
        }
    }

    public void joinCall(String callId, String username, String displayName, int udpPort, String ipAddress) {
        try (Connection c = database.connect()) {
            try (PreparedStatement q = c.prepareStatement(
                    "INSERT INTO call_participant(call_id, username, display_name, status, udp_port, ip_address) VALUES (?, ?, ?, 'CONNECTED', ?, ?) " +
                    "ON CONFLICT (call_id, username) DO UPDATE SET status = 'CONNECTED', udp_port = ?, ip_address = ?")) {
                q.setString(1, callId);
                q.setString(2, username);
                q.setString(3, displayName);
                q.setInt(4, udpPort);
                q.setString(5, ipAddress != null ? ipAddress : "127.0.0.1");
                q.setInt(6, udpPort);
                q.setString(7, ipAddress != null ? ipAddress : "127.0.0.1");
                q.executeUpdate();
            }

            try (PreparedStatement q = c.prepareStatement(
                    "UPDATE call_session SET status = 'ACTIVE' WHERE call_id = ? AND status = 'RINGING' AND host_username <> ?")) {
                q.setString(1, callId);
                q.setString(2, username);
                q.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Không thể tham gia cuộc gọi.", e);
        }
    }

    public void leaveCall(String callId, String username) {
        try (Connection c = database.connect()) {
            try (PreparedStatement q = c.prepareStatement(
                    "UPDATE call_participant SET status = 'LEFT' WHERE call_id = ? AND username = ?")) {
                q.setString(1, callId);
                q.setString(2, username);
                q.executeUpdate();
            }

            // If no connected participants remain, mark call as ended
            try (PreparedStatement q = c.prepareStatement(
                    "SELECT COUNT(*) FROM call_participant WHERE call_id = ? AND status = 'CONNECTED'")) {
                q.setString(1, callId);
                ResultSet rs = q.executeQuery();
                if (rs.next() && rs.getInt(1) == 0) {
                    endCall(callId);
                }
            }
        } catch (SQLException ignored) { }
    }

    public void endCall(String callId) {
        try (Connection c = database.connect()) {
            try (PreparedStatement q = c.prepareStatement(
                    "UPDATE call_session SET status = 'ENDED' WHERE call_id = ?")) {
                q.setString(1, callId);
                q.executeUpdate();
            }
            try (PreparedStatement q = c.prepareStatement(
                    "UPDATE call_participant SET status = 'LEFT' WHERE call_id = ? AND status <> 'LEFT'")) {
                q.setString(1, callId);
                q.executeUpdate();
            }
        } catch (SQLException ignored) { }
    }

    public void endCallsInRoom(String roomId) {
        try (Connection c = database.connect();
             PreparedStatement q = c.prepareStatement(
                     "UPDATE call_session SET status = 'ENDED' WHERE room_id = ? AND status IN ('RINGING', 'ACTIVE')")) {
            q.setString(1, roomId);
            q.executeUpdate();
        } catch (SQLException ignored) { }
    }

    public List<Participant> getParticipants(String callId) {
        List<Participant> list = new ArrayList<>();
        try (Connection c = database.connect();
             PreparedStatement q = c.prepareStatement(
                     "SELECT username, display_name, status, udp_port, COALESCE(ip_address, '127.0.0.1') FROM call_participant WHERE call_id = ? AND status = 'CONNECTED' ORDER BY joined_at")) {
            q.setString(1, callId);
            ResultSet rs = q.executeQuery();
            while (rs.next()) {
                list.add(new Participant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)));
            }
            return list;
        } catch (SQLException e) {
            return list;
        }
    }

    public record CallSession(String callId, String roomId, String roomName, String hostUsername, String callType, String status) { }
    public record Participant(String username, String displayName, String status, int udpPort, String ipAddress) { }
}
