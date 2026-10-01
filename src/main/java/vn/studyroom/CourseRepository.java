package vn.studyroom;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class CourseRepository {
    private final Database database;

    public CourseRepository(Database database) {
        this.database = database;
    }

    public Course createCourse(String title, String code, String password, String description, String ownerUsername) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("Tên khóa học không được để trống.");
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Mã phòng học không được để trống.");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("Mật khẩu phòng học không được để trống.");

        String courseId = UUID.randomUUID().toString();
        String normalizedCode = code.trim().toUpperCase();

        try (Connection c = database.connect()) {
            try (PreparedStatement q = c.prepareStatement(
                    "INSERT INTO study_course(course_id, course_code, course_password, title, description, owner_username, current_slide, is_presenting) VALUES (?, ?, ?, ?, ?, ?, 4, FALSE)")) {
                q.setString(1, courseId);
                q.setString(2, normalizedCode);
                q.setString(3, password.trim());
                q.setString(4, title.trim());
                q.setString(5, description != null ? description.trim() : "");
                q.setString(6, ownerUsername);
                q.executeUpdate();
            }

            try (PreparedStatement q = c.prepareStatement(
                    "INSERT INTO course_member(course_id, username, role) VALUES (?, ?, 'TEACHER')")) {
                q.setString(1, courseId);
                q.setString(2, ownerUsername);
                q.executeUpdate();
            }

            // Seed default materials and schedule
            seedDefaults(c, courseId, ownerUsername);

            return new Course(courseId, normalizedCode, title.trim(), description, ownerUsername, password.trim(), 4, 1, false, "127.0.0.1", 5200);
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalArgumentException("Mã phòng học \"" + normalizedCode + "\" đã tồn tại. Vui lòng chọn mã khác.");
            }
            throw new IllegalStateException("Không thể tạo khóa học.", e);
        }
    }

    public Course joinCourse(String code, String password, String username) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Vui lòng nhập mã phòng học.");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("Vui lòng nhập mật khẩu.");

        String normalizedCode = code.trim().toUpperCase();

        try (Connection c = database.connect()) {
            String courseId;
            String title;
            String desc;
            String owner;
            String storedPassword;
            int currentSlide;
            boolean isPresenting;
            String hostIp;
            int screenPort;

            try (PreparedStatement q = c.prepareStatement(
                    "SELECT course_id, title, description, owner_username, course_password, current_slide, is_presenting, host_ip, screen_port FROM study_course WHERE UPPER(course_code) = ?")) {
                q.setString(1, normalizedCode);
                ResultSet rs = q.executeQuery();
                if (!rs.next()) {
                    throw new IllegalArgumentException("Không tìm thấy phòng học với mã: " + normalizedCode);
                }
                courseId = rs.getString(1);
                title = rs.getString(2);
                desc = rs.getString(3);
                owner = rs.getString(4);
                storedPassword = rs.getString(5);
                currentSlide = rs.getInt(6);
                isPresenting = rs.getBoolean(7);
                hostIp = rs.getString(8) != null ? rs.getString(8) : "127.0.0.1";
                screenPort = rs.getInt(9) > 0 ? rs.getInt(9) : 5200;
            }

            if (!storedPassword.equals(password.trim())) {
                throw new IllegalArgumentException("Mật khẩu phòng học không chính xác.");
            }

            // Add member if not already joined
            try (PreparedStatement q = c.prepareStatement(
                    "INSERT INTO course_member(course_id, username, role) VALUES (?, ?, 'STUDENT') ON CONFLICT DO NOTHING")) {
                q.setString(1, courseId);
                q.setString(2, username);
                q.executeUpdate();
            }

            int memberCount = countMembers(c, courseId);
            return new Course(courseId, normalizedCode, title, desc, owner, storedPassword, currentSlide, memberCount, isPresenting, hostIp, screenPort);
        } catch (SQLException e) {
            throw new IllegalStateException("Không thể tham gia phòng học.", e);
        }
    }

    public List<Course> coursesFor(String username) {
        List<Course> list = new ArrayList<>();
        String sql = """
            SELECT c.course_id, c.course_code, c.title, c.description, c.owner_username, c.course_password, c.current_slide,
                   (SELECT COUNT(*) FROM course_member m2 WHERE m2.course_id = c.course_id) AS member_count,
                   c.is_presenting, c.host_ip, c.screen_port
            FROM study_course c
            JOIN course_member m ON c.course_id = m.course_id
            WHERE m.username = ?
            ORDER BY c.created_at DESC
        """;
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, username);
            ResultSet rs = q.executeQuery();
            while (rs.next()) {
                list.add(new Course(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getBoolean(9),
                    rs.getString(10) != null ? rs.getString(10) : "127.0.0.1",
                    rs.getInt(11) > 0 ? rs.getInt(11) : 5200
                ));
            }
            return list;
        } catch (SQLException e) {
            return list;
        }
    }

    public Course getCourse(String courseId) {
        String sql = """
            SELECT c.course_id, c.course_code, c.title, c.description, c.owner_username, c.course_password, c.current_slide,
                   (SELECT COUNT(*) FROM course_member m WHERE m.course_id = c.course_id) AS member_count,
                   c.is_presenting, c.host_ip, c.screen_port
            FROM study_course c
            WHERE c.course_id = ?
        """;
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, courseId);
            ResultSet rs = q.executeQuery();
            if (rs.next()) {
                return new Course(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getBoolean(9),
                    rs.getString(10) != null ? rs.getString(10) : "127.0.0.1",
                    rs.getInt(11) > 0 ? rs.getInt(11) : 5200
                );
            }
            return null;
        } catch (SQLException e) {
            return null;
        }
    }

    public void setPresenting(String courseId, boolean isPresenting) {
        try (Connection c = database.connect();
             PreparedStatement q = c.prepareStatement("UPDATE study_course SET is_presenting = ? WHERE course_id = ?")) {
            q.setBoolean(1, isPresenting);
            q.setString(2, courseId);
            q.executeUpdate();
        } catch (SQLException ignored) { }
    }

    public void startScreenShare(String courseId, String hostIp, int port) {
        try (Connection c = database.connect();
             PreparedStatement q = c.prepareStatement("UPDATE study_course SET is_presenting = TRUE, host_ip = ?, screen_port = ? WHERE course_id = ?")) {
            q.setString(1, hostIp);
            q.setInt(2, port);
            q.setString(3, courseId);
            q.executeUpdate();
        } catch (SQLException ignored) { }
    }

    public void stopScreenShare(String courseId) {
        try (Connection c = database.connect();
             PreparedStatement q = c.prepareStatement("UPDATE study_course SET is_presenting = FALSE WHERE course_id = ?")) {
            q.setString(1, courseId);
            q.executeUpdate();
        } catch (SQLException ignored) { }
    }

    public void updateSlide(String courseId, int slideNumber) {
        try (Connection c = database.connect();
             PreparedStatement q = c.prepareStatement("UPDATE study_course SET current_slide = ? WHERE course_id = ?")) {
            q.setInt(1, slideNumber);
            q.setString(2, courseId);
            q.executeUpdate();
        } catch (SQLException ignored) { }
    }

    public List<Material> materialsOf(String courseId) {
        List<Material> list = new ArrayList<>();
        try (Connection c = database.connect();
             PreparedStatement q = c.prepareStatement("SELECT material_id, course_id, title, file_type, file_size, uploaded_by FROM course_material WHERE course_id = ? ORDER BY created_at DESC")) {
            q.setString(1, courseId);
            ResultSet rs = q.executeQuery();
            while (rs.next()) {
                list.add(new Material(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)));
            }
            return list;
        } catch (SQLException e) {
            return list;
        }
    }

    public void addMaterial(String courseId, String title, String fileType, String fileSize, String uploader) {
        try (Connection c = database.connect();
             PreparedStatement q = c.prepareStatement("INSERT INTO course_material(material_id, course_id, title, file_type, file_size, uploaded_by) VALUES (?, ?, ?, ?, ?, ?)")) {
            q.setString(1, UUID.randomUUID().toString());
            q.setString(2, courseId);
            q.setString(3, title);
            q.setString(4, fileType);
            q.setString(5, fileSize);
            q.setString(6, uploader);
            q.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Không thể thêm tài liệu.", e);
        }
    }

    public List<Schedule> schedulesOf(String courseId) {
        List<Schedule> list = new ArrayList<>();
        try (Connection c = database.connect();
             PreparedStatement q = c.prepareStatement("SELECT schedule_id, course_id, session_title, session_time, description FROM course_schedule WHERE course_id = ? ORDER BY created_at ASC")) {
            q.setString(1, courseId);
            ResultSet rs = q.executeQuery();
            while (rs.next()) {
                list.add(new Schedule(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
            }
            return list;
        } catch (SQLException e) {
            return list;
        }
    }

    public void addSchedule(String courseId, String title, String time, String description) {
        try (Connection c = database.connect();
             PreparedStatement q = c.prepareStatement("INSERT INTO course_schedule(schedule_id, course_id, session_title, session_time, description) VALUES (?, ?, ?, ?, ?)")) {
            q.setString(1, UUID.randomUUID().toString());
            q.setString(2, courseId);
            q.setString(3, title);
            q.setString(4, time);
            q.setString(5, description);
            q.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Không thể thêm lịch học.", e);
        }
    }

    public List<User> membersOf(String courseId) {
        List<User> list = new ArrayList<>();
        try (Connection c = database.connect();
             PreparedStatement q = c.prepareStatement("SELECT u.username, u.display_name FROM course_member m JOIN app_user u ON m.username = u.username WHERE m.course_id = ? ORDER BY m.joined_at")) {
            q.setString(1, courseId);
            ResultSet rs = q.executeQuery();
            while (rs.next()) {
                list.add(new User(rs.getString(1), rs.getString(2)));
            }
            return list;
        } catch (SQLException e) {
            return list;
        }
    }

    public void heartbeatPresence(String courseId, String username, String displayName, boolean cameraOn, boolean micOn) {
        heartbeatPresence(courseId, username, displayName, cameraOn, micOn, "", 0, false, 0);
    }

    public void heartbeatPresence(String courseId, String username, String displayName, boolean cameraOn, boolean micOn, String ip, int voicePort, boolean speaking) {
        heartbeatPresence(courseId, username, displayName, cameraOn, micOn, ip, voicePort, speaking, 0);
    }

    public void heartbeatPresence(String courseId, String username, String displayName, boolean cameraOn, boolean micOn, String ip, int voicePort, boolean speaking, int camPort) {
        String upsert = """
            INSERT INTO course_online_presence(course_id, username, display_name, camera_on, mic_on, ip, voice_port, speaking, cam_port, last_seen)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (course_id, username)
            DO UPDATE SET display_name = EXCLUDED.display_name,
                          camera_on = EXCLUDED.camera_on,
                          mic_on = EXCLUDED.mic_on,
                          ip = CASE WHEN EXCLUDED.ip <> '' THEN EXCLUDED.ip ELSE course_online_presence.ip END,
                          voice_port = CASE WHEN EXCLUDED.voice_port > 0 THEN EXCLUDED.voice_port ELSE course_online_presence.voice_port END,
                          speaking = EXCLUDED.speaking,
                          cam_port = CASE WHEN EXCLUDED.cam_port > 0 THEN EXCLUDED.cam_port ELSE course_online_presence.cam_port END,
                          last_seen = CURRENT_TIMESTAMP
        """;
        try (Connection c = database.connect()) {
            try (PreparedStatement q = c.prepareStatement(upsert)) {
                q.setString(1, courseId);
                q.setString(2, username);
                q.setString(3, displayName);
                q.setBoolean(4, cameraOn);
                q.setBoolean(5, micOn);
                q.setString(6, ip != null ? ip : "");
                q.setInt(7, voicePort);
                q.setBoolean(8, speaking);
                q.setInt(9, camPort);
                q.executeUpdate();
            }
            try (PreparedStatement q = c.prepareStatement("DELETE FROM course_online_presence WHERE last_seen < CURRENT_TIMESTAMP - INTERVAL '15' SECOND")) {
                q.executeUpdate();
            }
        } catch (SQLException ignored) { }
    }

    public void leavePresence(String courseId, String username) {
        try (Connection c = database.connect();
             PreparedStatement q = c.prepareStatement("DELETE FROM course_online_presence WHERE course_id = ? AND username = ?")) {
            q.setString(1, courseId);
            q.setString(2, username);
            q.executeUpdate();
        } catch (SQLException ignored) { }
    }

    public List<OnlineMember> getOnlineMembers(String courseId) {
        List<OnlineMember> list = new ArrayList<>();
        String sql = """
            SELECT username, display_name, camera_on, mic_on, COALESCE(ip, ''), COALESCE(voice_port, 0), COALESCE(speaking, false), COALESCE(cam_port, 0)
            FROM course_online_presence
            WHERE course_id = ? AND last_seen >= CURRENT_TIMESTAMP - INTERVAL '15' SECOND
            ORDER BY last_seen ASC
        """;
        try (Connection c = database.connect(); PreparedStatement q = c.prepareStatement(sql)) {
            q.setString(1, courseId);
            ResultSet rs = q.executeQuery();
            while (rs.next()) {
                list.add(new OnlineMember(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getBoolean(3),
                    rs.getBoolean(4),
                    rs.getString(5),
                    rs.getInt(6),
                    rs.getBoolean(7),
                    rs.getInt(8)
                ));
            }
            return list;
        } catch (SQLException e) {
            return list;
        }
    }

    public void seedInitialDemoIfEmpty(String username) {
        try (Connection c = database.connect()) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM study_course")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    createCourse("Ôn thi Hóa Hữu Cơ", "HOA12", "123", "Khóa ôn luyện trọng tâm môn Hóa Hữu cơ 12 chuẩn bị cho kỳ thi THPT Quốc Gia.", username);
                }
            }
        } catch (Exception ignored) { }
    }

    private int countMembers(Connection c, String courseId) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("SELECT COUNT(*) FROM course_member WHERE course_id = ?")) {
            q.setString(1, courseId);
            ResultSet rs = q.executeQuery();
            return rs.next() ? rs.getInt(1) : 1;
        }
    }

    private void seedDefaults(Connection c, String courseId, String owner) throws SQLException {
        // Default Slides & Materials
        try (PreparedStatement q = c.prepareStatement("INSERT INTO course_material(material_id, course_id, title, file_type, file_size, uploaded_by) VALUES (?, ?, ?, ?, ?, ?)")) {
            q.setString(1, UUID.randomUUID().toString());
            q.setString(2, courseId);
            q.setString(3, "Slide Bài giảng: Phản ứng Este hóa & Ứng dụng");
            q.setString(4, "SLIDE");
            q.setString(5, "12 Slides · 4.8 MB");
            q.setString(6, owner);
            q.executeUpdate();

            q.setString(1, UUID.randomUUID().toString());
            q.setString(2, courseId);
            q.setString(3, "Đề cương Tóm tắt Hóa Hữu cơ 12 - Chương Este");
            q.setString(4, "PDF");
            q.setString(5, "Tài liệu PDF · 1.2 MB");
            q.setString(6, owner);
            q.executeUpdate();

            q.setString(1, UUID.randomUUID().toString());
            q.setString(2, courseId);
            q.setString(3, "50 Câu Trắc nghiệm Este - Lipit có lời giải chi tiết");
            q.setString(4, "EXAM");
            q.setString(5, "Đề ôn luyện · 2.5 MB");
            q.setString(6, owner);
            q.executeUpdate();
        }

        // Default Schedules
        try (PreparedStatement q = c.prepareStatement("INSERT INTO course_schedule(schedule_id, course_id, session_title, session_time, description) VALUES (?, ?, ?, ?, ?)")) {
            q.setString(1, UUID.randomUUID().toString());
            q.setString(2, courseId);
            q.setString(3, "Buổi 1: Tổng ôn lý thuyết Este - Lipit & Danh pháp");
            q.setString(4, "Thứ Ba · 19:30 - 21:00");
            q.setString(5, "Nắm chắc tính chất vật lý, hóa học và phản ứng thủy phân este.");
            q.executeUpdate();

            q.setString(1, UUID.randomUUID().toString());
            q.setString(2, courseId);
            q.setString(3, "Buổi 2: Phương pháp giải bài toán Este nâng cao (Vận dụng cao)");
            q.setString(4, "Thứ Năm · 19:30 - 21:00");
            q.setString(5, "Kỹ thuật đồng đẳng hóa và bảo toàn khối lượng trong bài toán este đa chức.");
            q.executeUpdate();

            q.setString(1, UUID.randomUUID().toString());
            q.setString(2, courseId);
            q.setString(3, "Buổi 3: Luyện đề thi thử trực tuyến & Giải đáp thắc mắc");
            q.setString(4, "Thứ Bảy · 20:00 - 21:30");
            q.setString(5, "Thi thử 50 câu trực tiếp và sửa đề chi tiết cùng giáo viên.");
            q.executeUpdate();
        }
    }

    public record Course(String id, String code, String title, String description, String ownerUsername, String password, int currentSlide, int memberCount, boolean isPresenting, String hostIp, int screenPort) { }
    public record Material(String id, String courseId, String title, String fileType, String fileSize, String uploadedBy) { }
    public record Schedule(String id, String courseId, String sessionTitle, String sessionTime, String description) { }
    public record OnlineMember(String username, String displayName, boolean cameraOn, boolean micOn, String ip, int voicePort, boolean speaking, int camPort) { }
}
