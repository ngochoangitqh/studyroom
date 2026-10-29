package vn.studyroom;

import java.sql.*;
import java.util.*;

public final class QuizRepository {
    private final Database database;

    public record Quiz(
        String id,
        String courseId,
        String title,
        String description,
        int durationMinutes,
        String createdBy,
        Timestamp createdAt,
        int questionCount,
        Double userScore
    ) {}

    public record Question(
        String id,
        String quizId,
        String questionText,
        String optionA,
        String optionB,
        String optionC,
        String optionD,
        String correctOption,
        int orderNum
    ) {}

    public record QuizSubmission(
        String id,
        String quizId,
        String username,
        String displayName,
        double score,
        int totalQuestions,
        int correctCount,
        String answersJson,
        Timestamp submittedAt
    ) {}

    public record QuestionInput(
        String questionText,
        String optionA,
        String optionB,
        String optionC,
        String optionD,
        String correctOption
    ) {}

    public QuizRepository(Database database) {
        this.database = database;
    }

    public List<Quiz> getQuizzesForCourse(String courseId, String currentUsername) {
        List<Quiz> list = new ArrayList<>();
        if (courseId == null || courseId.isBlank()) return list;

        String sql = """
            SELECT q.quiz_id, q.course_id, q.title, q.description, q.duration_minutes, q.created_by, q.created_at,
                   (SELECT COUNT(*) FROM quiz_question qq WHERE qq.quiz_id = q.quiz_id) AS question_count,
                   (SELECT qs.score FROM quiz_submission qs WHERE qs.quiz_id = q.quiz_id AND qs.username = ?) AS user_score
            FROM course_quiz q
            WHERE q.course_id = ?
            ORDER BY q.created_at DESC
        """;

        try (Connection c = database.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, currentUsername != null ? currentUsername : "");
            ps.setString(2, courseId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Double userScore = null;
                double sc = rs.getDouble(9);
                if (!rs.wasNull()) {
                    userScore = sc;
                }
                list.add(new Quiz(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getInt(5),
                    rs.getString(6),
                    rs.getTimestamp(7),
                    rs.getInt(8),
                    userScore
                ));
            }
        } catch (SQLException ignored) { }
        return list;
    }

    public Quiz getQuiz(String quizId, String currentUsername) {
        if (quizId == null || quizId.isBlank()) return null;
        String sql = """
            SELECT q.quiz_id, q.course_id, q.title, q.description, q.duration_minutes, q.created_by, q.created_at,
                   (SELECT COUNT(*) FROM quiz_question qq WHERE qq.quiz_id = q.quiz_id) AS question_count,
                   (SELECT qs.score FROM quiz_submission qs WHERE qs.quiz_id = q.quiz_id AND qs.username = ?) AS user_score
            FROM course_quiz q
            WHERE q.quiz_id = ?
        """;
        try (Connection c = database.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, currentUsername != null ? currentUsername : "");
            ps.setString(2, quizId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Double userScore = null;
                double sc = rs.getDouble(9);
                if (!rs.wasNull()) {
                    userScore = sc;
                }
                return new Quiz(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getInt(5),
                    rs.getString(6),
                    rs.getTimestamp(7),
                    rs.getInt(8),
                    userScore
                );
            }
        } catch (SQLException ignored) { }
        return null;
    }

    public String createQuiz(String courseId, String title, String description, int durationMinutes, String createdBy, List<QuestionInput> questions) {
        if (courseId == null || title == null || title.isBlank()) {
            throw new IllegalArgumentException("Tiêu đề bài kiểm tra không được để trống.");
        }
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("Bài kiểm tra phải có ít nhất 1 câu hỏi.");
        }

        String quizId = UUID.randomUUID().toString();
        String sqlQuiz = """
            INSERT INTO course_quiz(quiz_id, course_id, title, description, duration_minutes, created_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
        String sqlQuestion = """
            INSERT INTO quiz_question(question_id, quiz_id, question_text, option_a, option_b, option_c, option_d, correct_option, order_num)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (Connection c = database.connect()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement psQ = c.prepareStatement(sqlQuiz)) {
                    psQ.setString(1, quizId);
                    psQ.setString(2, courseId);
                    psQ.setString(3, title.trim());
                    psQ.setString(4, description != null ? description.trim() : "");
                    psQ.setInt(5, Math.max(1, durationMinutes));
                    psQ.setString(6, createdBy);
                    psQ.executeUpdate();
                }

                try (PreparedStatement psQuest = c.prepareStatement(sqlQuestion)) {
                    for (int i = 0; i < questions.size(); i++) {
                        QuestionInput qi = questions.get(i);
                        psQuest.setString(1, UUID.randomUUID().toString());
                        psQuest.setString(2, quizId);
                        psQuest.setString(3, qi.questionText().trim());
                        psQuest.setString(4, qi.optionA().trim());
                        psQuest.setString(5, qi.optionB().trim());
                        psQuest.setString(6, qi.optionC().trim());
                        psQuest.setString(7, qi.optionD().trim());
                        psQuest.setString(8, qi.correctOption() != null ? qi.correctOption().toUpperCase().trim() : "A");
                        psQuest.setInt(9, i + 1);
                        psQuest.addBatch();
                    }
                    psQuest.executeBatch();
                }
                c.commit();
                return quizId;
            } catch (SQLException e) {
                c.rollback();
                throw new IllegalStateException("Không thể tạo bài kiểm tra: " + e.getMessage(), e);
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lỗi kết nối cơ sở dữ liệu: " + e.getMessage(), e);
        }
    }

    public void deleteQuiz(String quizId) {
        if (quizId == null || quizId.isBlank()) return;
        try (Connection c = database.connect();
             PreparedStatement ps = c.prepareStatement("DELETE FROM course_quiz WHERE quiz_id = ?")) {
            ps.setString(1, quizId);
            ps.executeUpdate();
        } catch (SQLException ignored) { }
    }

    public List<Question> getQuestionsForQuiz(String quizId) {
        List<Question> list = new ArrayList<>();
        if (quizId == null || quizId.isBlank()) return list;

        String sql = """
            SELECT question_id, quiz_id, question_text, option_a, option_b, option_c, option_d, correct_option, order_num
            FROM quiz_question
            WHERE quiz_id = ?
            ORDER BY order_num ASC
        """;

        try (Connection c = database.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, quizId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new Question(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getInt(9)
                ));
            }
        } catch (SQLException ignored) { }
        return list;
    }

    public QuizSubmission submitQuiz(String quizId, String username, Map<String, String> userAnswers) {
        List<Question> questions = getQuestionsForQuiz(quizId);
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("Bài kiểm tra không có câu hỏi nào.");
        }

        int correctCount = 0;
        StringBuilder sb = new StringBuilder();

        for (Question q : questions) {
            String ans = userAnswers.getOrDefault(q.id(), "");
            if (ans.equalsIgnoreCase(q.correctOption())) {
                correctCount++;
            }
            if (!sb.isEmpty()) sb.append(";");
            sb.append(q.id()).append("=").append(ans.toUpperCase());
        }

        int total = questions.size();
        double score = Math.round(((double) correctCount / total) * 10.0 * 10.0) / 10.0; // scale 10.0 with 1 decimal

        String subId = UUID.randomUUID().toString();
        String sql = """
            INSERT INTO quiz_submission(submission_id, quiz_id, username, score, total_questions, correct_count, answers_json, submitted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (quiz_id, username)
            DO UPDATE SET score = EXCLUDED.score,
                          total_questions = EXCLUDED.total_questions,
                          correct_count = EXCLUDED.correct_count,
                          answers_json = EXCLUDED.answers_json,
                          submitted_at = CURRENT_TIMESTAMP
        """;

        try (Connection c = database.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, subId);
            ps.setString(2, quizId);
            ps.setString(3, username);
            ps.setDouble(4, score);
            ps.setInt(5, total);
            ps.setInt(6, correctCount);
            ps.setString(7, sb.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Không thể lưu kết quả bài thi: " + e.getMessage(), e);
        }

        return getUserSubmission(quizId, username);
    }

    public List<QuizSubmission> getSubmissionsForQuiz(String quizId) {
        List<QuizSubmission> list = new ArrayList<>();
        if (quizId == null || quizId.isBlank()) return list;

        String sql = """
            SELECT s.submission_id, s.quiz_id, s.username, COALESCE(u.display_name, s.username),
                   s.score, s.total_questions, s.correct_count, s.answers_json, s.submitted_at
            FROM quiz_submission s
            LEFT JOIN app_user u ON s.username = u.username
            WHERE s.quiz_id = ?
            ORDER BY s.score DESC, s.submitted_at ASC
        """;

        try (Connection c = database.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, quizId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new QuizSubmission(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getDouble(5),
                    rs.getInt(6),
                    rs.getInt(7),
                    rs.getString(8),
                    rs.getTimestamp(9)
                ));
            }
        } catch (SQLException ignored) { }
        return list;
    }

    public QuizSubmission getUserSubmission(String quizId, String username) {
        if (quizId == null || username == null) return null;

        String sql = """
            SELECT s.submission_id, s.quiz_id, s.username, COALESCE(u.display_name, s.username),
                   s.score, s.total_questions, s.correct_count, s.answers_json, s.submitted_at
            FROM quiz_submission s
            LEFT JOIN app_user u ON s.username = u.username
            WHERE s.quiz_id = ? AND s.username = ?
        """;

        try (Connection c = database.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, quizId);
            ps.setString(2, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new QuizSubmission(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getDouble(5),
                    rs.getInt(6),
                    rs.getInt(7),
                    rs.getString(8),
                    rs.getTimestamp(9)
                );
            }
        } catch (SQLException ignored) { }
        return null;
    }

    public void seedDefaultQuizIfEmpty(String courseId, String username) {
        try (Connection c = database.connect()) {
            try (PreparedStatement check = c.prepareStatement("SELECT COUNT(*) FROM course_quiz WHERE course_id = ?")) {
                check.setString(1, courseId);
                ResultSet rs = check.executeQuery();
                if (rs.next() && rs.getInt(1) > 0) {
                    return; // Already has quizzes
                }
            }

            List<QuestionInput> defaultQuestions = List.of(
                new QuestionInput(
                    "Este có công thức phân tử C2H4O2 có tên gọi là:",
                    "Etyl fomat",
                    "Metyl fomat",
                    "Metyl axetat",
                    "Axit axetic",
                    "B"
                ),
                new QuestionInput(
                    "Chất béo (trigixerit) là trieste của axit béo với chất nào sau đây?",
                    "Etylen glicol",
                    "Metanol",
                    "Glixerol",
                    "Etanol",
                    "C"
                ),
                new QuestionInput(
                    "Phản ứng thủy phân este trong môi trường kiềm (đun nóng) còn được gọi là phản ứng:",
                    "Xà phòng hóa",
                    "Trùng hợp",
                    "Trùng ngưng",
                    "Este hóa",
                    "A"
                ),
                new QuestionInput(
                    "Mùi thơm đặc trưng của quả chuối chín là do este nào sau đây tạo nên?",
                    "Isoamyl axetat",
                    "Benzyl axetat",
                    "Geranyl axetat",
                    "Etyl butirat",
                    "A"
                ),
                new QuestionInput(
                    "Đun nóng este CH3COOC2H5 với dung dịch NaOH vừa đủ thu được muối có công thức là:",
                    "HCOONa",
                    "CH3COONa",
                    "C2H5COONa",
                    "CH3COOCH3",
                    "B"
                )
            );

            createQuiz(
                courseId,
                "Đề kiểm tra 15 phút: Este & Hóa học hữu cơ 12",
                "Đề ôn tập củng cố kiến thức trọng tâm về danh pháp, tính chất vật lý và phản ứng thủy phân este trong đề thi THPT Quốc Gia.",
                15,
                username,
                defaultQuestions
            );
        } catch (Exception ignored) { }
    }
}
