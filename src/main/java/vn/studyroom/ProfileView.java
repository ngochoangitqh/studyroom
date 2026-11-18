package vn.studyroom;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Comprehensive, data-driven Personal Profile & Study Analytics Dashboard.
 * Aggregates real user activity across Pomodoro, Quizzes, Courses, Study Notes, Chat, Friends, Music, and AI.
 */
public final class ProfileView {

    public record QuizHistoryItem(String quizTitle, String courseTitle, double score, int correctCount, int totalQuestions, String submittedAt) {}
    public record CourseSummaryItem(String courseTitle, String courseCode, boolean isOwner, int memberCount) {}

    public static final class UserUsageStats {
        public int totalFocusMinutes = 0;
        public int pomodoroSessions = 0;
        public int quizzesTaken = 0;
        public double avgQuizScore = 0.0;
        public double maxQuizScore = 0.0;
        public int coursesJoined = 0;
        public int coursesOwned = 0;
        public int materialsUploaded = 0;
        public int notesCreated = 0;
        public int todosCompleted = 0;
        public int todosTotal = 0;
        public int friendsCount = 0;
        public int messagesSent = 0;
        public int aiQueriesCount = 0;
        public int musicTracksAdded = 0;
        public final List<QuizHistoryItem> recentQuizzes = new ArrayList<>();
        public final List<CourseSummaryItem> myCourses = new ArrayList<>();
    }

    private final Database database;
    private final User user;
    private final StackPane avatarNode;
    private final Runnable onChangeAvatar;
    private final Runnable onSwitchAccount;
    private final Runnable onLogout;
    private final Runnable onGoPomodoro;
    private final Runnable onGoNotes;
    private final Runnable onGoCourses;
    private final Runnable onGoAi;
    private final Consumer<String> onDisplayNameUpdated;
    private final Consumer<String> toastCallback;

    public ProfileView(
        Database database,
        User user,
        StackPane avatarNode,
        Runnable onChangeAvatar,
        Runnable onSwitchAccount,
        Runnable onLogout,
        Runnable onGoPomodoro,
        Runnable onGoNotes,
        Runnable onGoCourses,
        Runnable onGoAi,
        Consumer<String> onDisplayNameUpdated,
        Consumer<String> toastCallback
    ) {
        this.database = database;
        this.user = user;
        this.avatarNode = avatarNode;
        this.onChangeAvatar = onChangeAvatar;
        this.onSwitchAccount = onSwitchAccount;
        this.onLogout = onLogout;
        this.onGoPomodoro = onGoPomodoro;
        this.onGoNotes = onGoNotes;
        this.onGoCourses = onGoCourses;
        this.onGoAi = onGoAi;
        this.onDisplayNameUpdated = onDisplayNameUpdated;
        this.toastCallback = toastCallback;
    }

    private void toast(String msg) {
        if (toastCallback != null) toastCallback.accept(msg);
    }

    public void render(VBox content) {
        content.getChildren().clear();
        content.getStyleClass().setAll("workspace");
        content.setPadding(new Insets(18, 26, 20, 26));
        content.setSpacing(14);

        UserUsageStats stats = loadUserStats();

        VBox scrollContent = new VBox(18);
        scrollContent.setPadding(new Insets(2, 4, 16, 2));

        // 1. Top Page Header
        HBox pageHeader = new HBox(12);
        pageHeader.setAlignment(Pos.CENTER_LEFT);
        VBox titleBox = new VBox(2);
        Label pageTitle = new Label("👤 Hồ sơ Cá nhân & Thống kê Học tập");
        pageTitle.setStyle("-fx-font-size: 21px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");
        Label pageSub = new Label("Tổng hợp toàn bộ dữ liệu hoạt động, tiến độ tích lũy, điểm số và thành tựu của bạn trên Studyroom");
        pageSub.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");
        titleBox.getChildren().addAll(pageTitle, pageSub);
        pageHeader.getChildren().add(titleBox);

        // 2. Hero Profile Banner Card
        VBox heroCard = buildHeroProfileCard(stats);

        // 3. 6-Card Usage Metrics Grid
        GridPane metricsGrid = buildMetricsGrid(stats);

        // 4. Bottom 2-Column Insights (Quiz/Course History + Achievements)
        HBox bottomSplit = new HBox(18);
        VBox historyCard = buildHistoryCard(stats);
        HBox.setHgrow(historyCard, Priority.ALWAYS);

        VBox achievementsCard = buildAchievementsCard(stats);
        achievementsCard.setPrefWidth(420);
        achievementsCard.setMinWidth(380);

        bottomSplit.getChildren().addAll(historyCard, achievementsCard);

        scrollContent.getChildren().addAll(heroCard, metricsGrid, bottomSplit);

        ScrollPane mainScroll = new ScrollPane(scrollContent);
        mainScroll.setFitToWidth(true);
        mainScroll.getStyleClass().addAll("thread-scroll", "soft-scroll");
        VBox.setVgrow(mainScroll, Priority.ALWAYS);

        content.getChildren().addAll(pageHeader, mainScroll);
    }

    private VBox buildHeroProfileCard(UserUsageStats stats) {
        VBox outer = new VBox(0);
        outer.setStyle(
            "-fx-background-color: white;" +
            "-fx-background-radius: 22;" +
            "-fx-border-color: #eef2f6;" +
            "-fx-border-radius: 22;" +
            "-fx-effect: dropshadow(gaussian, rgba(15, 23, 42, 0.05), 18, 0, 0, 4);"
        );

        // Soft decorative gradient banner header
        HBox topAccent = new HBox();
        topAccent.setPrefHeight(10);
        topAccent.setMinHeight(10);
        topAccent.setMaxHeight(10);
        topAccent.setStyle("-fx-background-color: linear-gradient(to right, #6366f1, #8b5cf6, #ec4899); -fx-background-radius: 22 22 0 0;");

        HBox body = new HBox(22);
        body.setAlignment(Pos.CENTER_LEFT);
        body.setPadding(new Insets(20, 26, 22, 26));

        // Left: Avatar with glow ring
        StackPane avatarWrapper = new StackPane();
        avatarWrapper.setPadding(new Insets(4));
        avatarWrapper.setStyle("-fx-background-color: linear-gradient(to bottom right, #6366f1, #a855f7); -fx-background-radius: 999; -fx-cursor: hand;");
        if (avatarNode != null) {
            avatarNode.setCursor(Cursor.HAND);
            Tooltip.install(avatarNode, new Tooltip("Nhấp để thay đổi ảnh đại diện"));
            avatarNode.setOnMouseClicked(e -> {
                if (onChangeAvatar != null) onChangeAvatar.run();
            });
            avatarWrapper.getChildren().add(avatarNode);
        }

        // Center: Identity + Level & XP Progress
        VBox identityCol = new VBox(8);
        HBox.setHgrow(identityCol, Priority.ALWAYS);

        HBox nameRow = new HBox(10);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        Label nameLbl = new Label(user.displayName());
        nameLbl.setStyle("-fx-font-size: 22px; -fx-font-weight: 900; -fx-text-fill: #0f172a;");

        Label handleLbl = new Label("@" + user.username());
        handleLbl.setStyle("-fx-font-size: 13.5px; -fx-font-weight: 600; -fx-text-fill: #64748b; -fx-background-color: #f1f5f9; -fx-padding: 3 10; -fx-background-radius: 999;");

        Label onlinePill = new Label("● Đang hoạt động");
        onlinePill.setStyle("-fx-font-size: 11.5px; -fx-font-weight: 700; -fx-text-fill: #059669; -fx-background-color: #ecfdf5; -fx-border-color: #a7f3d0; -fx-border-radius: 999; -fx-padding: 3 10; -fx-background-radius: 999;");

        int totalXp = calculateXp(stats);
        int level = Math.max(1, (totalXp / 200) + 1);
        int xpInCurrentLevel = totalXp % 200;
        double levelProgress = xpInCurrentLevel / 200.0;

        Label rankPill = new Label(getRankTitle(level));
        rankPill.setStyle("-fx-font-size: 11.5px; -fx-font-weight: 800; -fx-text-fill: #4f46e5; -fx-background-color: #eef2ff; -fx-border-color: #c7d2fe; -fx-border-radius: 999; -fx-padding: 3 12; -fx-background-radius: 999;");

        nameRow.getChildren().addAll(nameLbl, handleLbl, onlinePill, rankPill);

        // XP Progress Box
        VBox xpBox = new VBox(5);
        xpBox.setMaxWidth(540);
        HBox xpHeader = new HBox(8);
        xpHeader.setAlignment(Pos.CENTER_LEFT);
        Label lvlLbl = new Label("⚡ Cấp độ " + level + "  ·  " + totalXp + " XP tích lũy");
        lvlLbl.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 800; -fx-text-fill: #334155;");
        Region xpGap = new Region();
        HBox.setHgrow(xpGap, Priority.ALWAYS);
        Label nextLvlLbl = new Label("Còn " + (200 - xpInCurrentLevel) + " XP lên Cấp " + (level + 1));
        nextLvlLbl.setStyle("-fx-font-size: 11.5px; -fx-font-weight: 600; -fx-text-fill: #6366f1;");
        xpHeader.getChildren().addAll(lvlLbl, xpGap, nextLvlLbl);

        ProgressBar xpBar = new ProgressBar(Math.max(0.06, levelProgress));
        xpBar.setMaxWidth(Double.MAX_VALUE);
        xpBar.getStyleClass().add("soft-progress");

        xpBox.getChildren().addAll(xpHeader, xpBar);
        identityCol.getChildren().addAll(nameRow, xpBox);

        // Right: Action Buttons Column
        VBox actionsBox = new VBox(8);
        actionsBox.setAlignment(Pos.CENTER_RIGHT);

        HBox topActionRow = new HBox(8);
        topActionRow.setAlignment(Pos.CENTER_RIGHT);

        Button changeAvtBtn = createActionPill("📷 Đổi ảnh đại diện", "#eef2ff", "#4f46e5", "#c7d2fe", "#e0e7ff");
        changeAvtBtn.setOnAction(e -> {
            if (onChangeAvatar != null) onChangeAvatar.run();
        });

        Button editNameBtn = createActionPill("✏ Đổi tên hiển thị", "#f8fafc", "#334155", "#cbd5e1", "#f1f5f9");
        editNameBtn.setOnAction(e -> showEditDisplayNameDialog());

        topActionRow.getChildren().addAll(changeAvtBtn, editNameBtn);

        HBox bottomActionRow = new HBox(8);
        bottomActionRow.setAlignment(Pos.CENTER_RIGHT);

        Button switchAccBtn = createActionPill("🔄 Đổi tài khoản", "#f5f3ff", "#6d28d9", "#ddd6fe", "#ede9fe");
        switchAccBtn.setOnAction(e -> {
            if (onSwitchAccount != null) onSwitchAccount.run();
        });

        Button logoutBtn = createActionPill("🚪 Đăng xuất", "#fff1f2", "#e11d48", "#fecdd3", "#ffe4e6");
        logoutBtn.setOnAction(e -> {
            if (onLogout != null) onLogout.run();
        });

        bottomActionRow.getChildren().addAll(switchAccBtn, logoutBtn);
        actionsBox.getChildren().addAll(topActionRow, bottomActionRow);

        body.getChildren().addAll(avatarWrapper, identityCol, actionsBox);
        outer.getChildren().addAll(topAccent, body);
        return outer;
    }

    private GridPane buildMetricsGrid(UserUsageStats s) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(16);

        for (int c = 0; c < 3; c++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(33.33);
            cc.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(cc);
        }

        // Card 1: Pomodoro & Focus Time
        VBox c1 = createMetricCard(
            "🍅", "Thời gian Tập trung",
            s.totalFocusMinutes + " phút",
            "Đã hoàn thành " + s.pomodoroSessions + " hiệp Pomodoro",
            "#eef2ff", "#4f46e5",
            "Vào Pomodoro →", onGoPomodoro
        );

        // Card 2: Quizzes & Scores
        String avgStr = s.quizzesTaken > 0 ? String.format("%.1f / 10 điểm", s.avgQuizScore) : "Chưa làm bài";
        String maxStr = s.quizzesTaken > 0 ? String.format("Điểm cao nhất: %.1f · Đã nộp %d đề", s.maxQuizScore, s.quizzesTaken) : "Tham gia lớp học để làm đề trắc nghiệm";
        VBox c2 = createMetricCard(
            "📝", "Kết quả Kiểm tra",
            avgStr,
            maxStr,
            "#ecfdf5", "#059669",
            "Xem bài thi →", onGoCourses
        );

        // Card 3: Courses & Materials
        VBox c3 = createMetricCard(
            "🎓", "Lớp học & Tài liệu",
            s.coursesJoined + " lớp học",
            "Chủ phòng " + s.coursesOwned + " lớp · Đóng góp " + s.materialsUploaded + " tài liệu",
            "#fef3c7", "#d97706",
            "Vào phòng chung →", onGoCourses
        );

        // Card 4: Study Notes & Goals
        VBox c4 = createMetricCard(
            "📚", "Sổ tay & Mục tiêu",
            s.notesCreated + " trang ghi chú",
            "Đã hoàn thành " + s.todosCompleted + "/" + s.todosTotal + " mục tiêu đặt ra",
            "#f5f3ff", "#7c3aed",
            "Mở Sổ tay →", onGoNotes
        );

        // Card 5: Friends & Chat Messages
        VBox c5 = createMetricCard(
            "💬", "Tương tác & Bạn bè",
            s.friendsCount + " người bạn",
            "Đã trao đổi " + s.messagesSent + " tin nhắn học tập",
            "#eff6ff", "#2563eb",
            null, null
        );

        // Card 6: AI StudyBot & Music
        VBox c6 = createMetricCard(
            "🤖", "Trợ lý AI & Âm nhạc",
            s.aiQueriesCount + " câu hỏi AI",
            "Đã thêm " + s.musicTracksAdded + " bài nhạc vào phòng nghe",
            "#fdf2f8", "#db2777",
            "Hỏi StudyBot →", onGoAi
        );

        grid.add(c1, 0, 0);
        grid.add(c2, 1, 0);
        grid.add(c3, 2, 0);
        grid.add(c4, 0, 1);
        grid.add(c5, 1, 1);
        grid.add(c6, 2, 1);

        return grid;
    }

    private VBox createMetricCard(String icon, String category, String mainValue, String subText,
                                  String iconBgHex, String accentHex, String linkText, Runnable onLink) {
        VBox card = new VBox(10);
        card.setPadding(new Insets(16, 18, 16, 18));
        card.setStyle(
            "-fx-background-color: white;" +
            "-fx-background-radius: 18;" +
            "-fx-border-color: #eef2f6;" +
            "-fx-border-radius: 18;" +
            "-fx-effect: dropshadow(gaussian, rgba(15, 23, 42, 0.035), 14, 0, 0, 3);"
        );

        HBox top = new HBox(10);
        top.setAlignment(Pos.CENTER_LEFT);

        Label iconBadge = new Label(icon);
        iconBadge.setMinSize(38, 38);
        iconBadge.setPrefSize(38, 38);
        iconBadge.setAlignment(Pos.CENTER);
        iconBadge.setStyle("-fx-background-color: " + iconBgHex + "; -fx-background-radius: 12; -fx-font-size: 17px;");

        VBox catBox = new VBox(2);
        HBox.setHgrow(catBox, Priority.ALWAYS);
        Label catLbl = new Label(category);
        catLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #64748b;");
        Label valLbl = new Label(mainValue);
        valLbl.setStyle("-fx-font-size: 19px; -fx-font-weight: 900; -fx-text-fill: #0f172a;");
        catBox.getChildren().addAll(catLbl, valLbl);

        top.getChildren().addAll(iconBadge, catBox);

        if (linkText != null && onLink != null) {
            Button linkBtn = new Button(linkText);
            linkBtn.setMinSize(Region.USE_PREF_SIZE, 26);
            linkBtn.setMaxHeight(26);
            linkBtn.setStyle("-fx-background-color: " + iconBgHex + "; -fx-text-fill: " + accentHex + "; -fx-font-size: 11px; -fx-font-weight: 700; -fx-background-radius: 999; -fx-border-color: transparent; -fx-padding: 0 10; -fx-cursor: hand;");
            linkBtn.setOnAction(e -> onLink.run());
            top.getChildren().add(linkBtn);
        }

        Label subLbl = new Label(subText);
        subLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #475569; -fx-font-weight: 500;");
        subLbl.setWrapText(true);

        card.getChildren().addAll(top, subLbl);
        return card;
    }

    private VBox buildHistoryCard(UserUsageStats s) {
        VBox card = new VBox(12);
        card.setPadding(new Insets(18, 20, 18, 20));
        card.setStyle(
            "-fx-background-color: white;" +
            "-fx-background-radius: 20;" +
            "-fx-border-color: #eef2f6;" +
            "-fx-border-radius: 20;" +
            "-fx-effect: dropshadow(gaussian, rgba(15, 23, 42, 0.04), 16, 0, 0, 4);"
        );

        Label secTitle = new Label("📊 Hoạt động Lớp học & Bài kiểm tra của bạn");
        secTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");
        card.getChildren().add(secTitle);

        // Sub-section 1: Recent Quizzes
        Label qHeader = new Label("Bài kiểm tra đã hoàn thành gần đây:");
        qHeader.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 700; -fx-text-fill: #475569;");
        card.getChildren().add(qHeader);

        if (s.recentQuizzes.isEmpty()) {
            HBox emptyQ = new HBox(10);
            emptyQ.setAlignment(Pos.CENTER_LEFT);
            emptyQ.setPadding(new Insets(10, 14, 10, 14));
            emptyQ.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 12; -fx-border-color: #e2e8f0; -fx-border-radius: 12;");
            Label eqLbl = new Label("Bạn chưa nộp bài kiểm tra nào. Hãy vào mục Phòng chung → Bài kiểm tra để thử sức nhé!");
            eqLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");
            eqLbl.setWrapText(true);
            emptyQ.getChildren().add(eqLbl);
            card.getChildren().add(emptyQ);
        } else {
            VBox qList = new VBox(8);
            for (QuizHistoryItem q : s.recentQuizzes) {
                HBox row = new HBox(10);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(10, 14, 10, 14));
                row.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 12; -fx-border-color: #eef2f6; -fx-border-radius: 12;");

                VBox meta = new VBox(2);
                HBox.setHgrow(meta, Priority.ALWAYS);
                Label qt = new Label(q.quizTitle());
                qt.setStyle("-fx-font-weight: 800; -fx-font-size: 13px; -fx-text-fill: #1e293b;");
                Label qc = new Label("📚 " + q.courseTitle() + "  ·  Đúng " + q.correctCount() + "/" + q.totalQuestions() + " câu  ·  🕒 " + q.submittedAt());
                qc.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #64748b;");
                meta.getChildren().addAll(qt, qc);

                String scoreStyle = q.score() >= 8.0
                    ? "-fx-background-color: #ecfdf5; -fx-text-fill: #059669; -fx-border-color: #a7f3d0;"
                    : (q.score() >= 5.0 ? "-fx-background-color: #eff6ff; -fx-text-fill: #2563eb; -fx-border-color: #bfdbfe;"
                                        : "-fx-background-color: #fff1f2; -fx-text-fill: #e11d48; -fx-border-color: #fecdd3;");
                Label scorePill = new Label(String.format("%.1f / 10", q.score()));
                scorePill.setStyle(scoreStyle + " -fx-font-weight: 800; -fx-font-size: 12.5px; -fx-padding: 4 12; -fx-background-radius: 999; -fx-border-radius: 999;");

                row.getChildren().addAll(meta, scorePill);
                qList.getChildren().add(row);
            }
            card.getChildren().add(qList);
        }

        // Sub-section 2: My Joined Courses
        Label cHeader = new Label("Các lớp học đang tham gia (" + s.myCourses.size() + "):");
        cHeader.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 700; -fx-text-fill: #475569; -fx-padding: 4 0 0 0;");
        card.getChildren().add(cHeader);

        if (s.myCourses.isEmpty()) {
            Label noC = new Label("Chưa tham gia lớp học nào.");
            noC.setStyle("-fx-font-size: 12px; -fx-text-fill: #94a3b8;");
            card.getChildren().add(noC);
        } else {
            FlowPane courseChips = new FlowPane(8, 8);
            for (CourseSummaryItem c : s.myCourses) {
                HBox chip = new HBox(6);
                chip.setAlignment(Pos.CENTER_LEFT);
                chip.setPadding(new Insets(6, 12, 6, 12));
                chip.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 12; -fx-background-radius: 12;");

                Label roleTag = new Label(c.isOwner() ? "👑 Chủ phòng" : "🎓 Học viên");
                roleTag.setStyle("-fx-font-size: 10.5px; -fx-font-weight: 800; -fx-text-fill: " + (c.isOwner() ? "#d97706;" : "#4f46e5;"));

                Label cName = new Label(c.courseTitle() + " (" + c.courseCode() + ")");
                cName.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #1e293b;");

                chip.getChildren().addAll(roleTag, cName);
                courseChips.getChildren().add(chip);
            }
            card.getChildren().add(courseChips);
        }

        return card;
    }

    private VBox buildAchievementsCard(UserUsageStats s) {
        VBox card = new VBox(10);
        card.setPadding(new Insets(18, 20, 18, 20));
        card.setStyle(
            "-fx-background-color: white;" +
            "-fx-background-radius: 20;" +
            "-fx-border-color: #eef2f6;" +
            "-fx-border-radius: 20;" +
            "-fx-effect: dropshadow(gaussian, rgba(15, 23, 42, 0.04), 16, 0, 0, 4);"
        );

        Label title = new Label("🏅 Bộ sưu tập Thành tựu Học tập");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");
        card.getChildren().add(title);

        card.getChildren().addAll(
            createAchievementRow("🎯", "Khởi đầu Vững chắc", "Tham gia hoặc tạo lớp học trực tuyến đầu tiên", s.coursesJoined >= 1),
            createAchievementRow("🍅", "Chiến binh Tập trung", "Hoàn thành phiên học tập với đồng hồ Pomodoro", s.totalFocusMinutes >= 1 || s.pomodoroSessions >= 1),
            createAchievementRow("🏆", "Cao thủ Trắc nghiệm", "Đạt điểm số từ 8.0/10 trở lên trong bài kiểm tra", s.maxQuizScore >= 8.0),
            createAchievementRow("📝", "Cây bút Chăm chỉ", "Tạo sổ tay ghi chú bài giảng & checklist mục tiêu", s.notesCreated >= 1),
            createAchievementRow("🤝", "Kết nối Bốn phương", "Kết bạn và trao đổi tích cực trong phòng trò chuyện", s.friendsCount >= 1 || s.messagesSent >= 5),
            createAchievementRow("🤖", "Nhà Thám hiểm AI", "Đặt câu hỏi và giải bài tập cùng Trợ lý StudyBot", s.aiQueriesCount >= 1)
        );

        return card;
    }

    private HBox createAchievementRow(String icon, String title, String desc, boolean unlocked) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 12, 8, 12));
        row.setStyle(unlocked
            ? "-fx-background-color: linear-gradient(to right, #f5f3ff, #fdf4ff); -fx-border-color: #d8b4fe; -fx-border-radius: 13; -fx-background-radius: 13;"
            : "-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 13; -fx-background-radius: 13; -fx-opacity: 0.68;");

        Label ic = new Label(icon);
        ic.setMinSize(34, 34);
        ic.setPrefSize(34, 34);
        ic.setAlignment(Pos.CENTER);
        ic.setStyle("-fx-background-color: " + (unlocked ? "white;" : "#e2e8f0;") + " -fx-background-radius: 10; -fx-font-size: 16px;");

        VBox textCol = new VBox(2);
        HBox.setHgrow(textCol, Priority.ALWAYS);
        Label tLbl = new Label(title);
        tLbl.setStyle("-fx-font-size: 12.5px; -fx-font-weight: 800; -fx-text-fill: " + (unlocked ? "#3b0764;" : "#475569;"));
        Label dLbl = new Label(desc);
        dLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");
        dLbl.setWrapText(true);
        textCol.getChildren().addAll(tLbl, dLbl);

        Label badge = new Label(unlocked ? "✔ Đã đạt" : "🔒 Chưa mở");
        badge.setMinSize(Region.USE_PREF_SIZE, 22);
        badge.setStyle(unlocked
            ? "-fx-background-color: #ecfdf5; -fx-text-fill: #059669; -fx-font-size: 10.5px; -fx-font-weight: 800; -fx-padding: 2 8; -fx-background-radius: 999;"
            : "-fx-background-color: #e2e8f0; -fx-text-fill: #64748b; -fx-font-size: 10.5px; -fx-font-weight: 700; -fx-padding: 2 8; -fx-background-radius: 999;");

        row.getChildren().addAll(ic, textCol, badge);
        return row;
    }

    private Button createActionPill(String text, String bgHex, String textHex, String borderHex, String hoverBgHex) {
        Button btn = new Button(text);
        btn.setMinSize(Region.USE_PREF_SIZE, 34);
        btn.setMaxHeight(34);
        String normal = String.format(
            "-fx-background-color: %s; -fx-text-fill: %s; -fx-border-color: %s;" +
            "-fx-border-radius: 12; -fx-background-radius: 12; -fx-font-weight: 700;" +
            "-fx-font-size: 12px; -fx-padding: 0 14; -fx-cursor: hand;",
            bgHex, textHex, borderHex
        );
        String hover = String.format(
            "-fx-background-color: %s; -fx-text-fill: %s; -fx-border-color: %s;" +
            "-fx-border-radius: 12; -fx-background-radius: 12; -fx-font-weight: 700;" +
            "-fx-font-size: 12px; -fx-padding: 0 14; -fx-cursor: hand;",
            hoverBgHex, textHex, borderHex
        );
        btn.setStyle(normal);
        btn.setOnMouseEntered(e -> btn.setStyle(hover));
        btn.setOnMouseExited(e -> btn.setStyle(normal));
        return btn;
    }

    private void showEditDisplayNameDialog() {
        TextInputDialog dialog = new TextInputDialog(user.displayName());
        dialog.setTitle("Đổi tên hiển thị");
        dialog.setHeaderText("Cập nhật tên hiển thị của bạn (@" + user.username() + ")");
        dialog.setContentText("Tên hiển thị mới:");
        dialog.showAndWait().ifPresent(newName -> {
            String trimmed = newName.trim();
            if (trimmed.isBlank()) {
                toast("Tên hiển thị không được để trống.");
                return;
            }
            try (Connection c = database.connect()) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE app_user SET display_name = ? WHERE username = ?")) {
                    ps.setString(1, trimmed);
                    ps.setString(2, user.username());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE study_stats SET display_name = ? WHERE username = ?")) {
                    ps.setString(1, trimmed);
                    ps.setString(2, user.username());
                    ps.executeUpdate();
                }
                toast("✅ Đã cập nhật tên hiển thị thành: " + trimmed);
                if (onDisplayNameUpdated != null) {
                    onDisplayNameUpdated.accept(trimmed);
                }
            } catch (Exception ex) {
                toast("Không thể đổi tên: " + ex.getMessage());
            }
        });
    }

    private int calculateXp(UserUsageStats s) {
        return (s.totalFocusMinutes * 8)
             + (s.pomodoroSessions * 40)
             + (s.quizzesTaken * 80)
             + (s.coursesJoined * 50)
             + (s.notesCreated * 35)
             + (s.todosCompleted * 20)
             + (s.friendsCount * 30)
             + (s.messagesSent * 3)
             + (s.aiQueriesCount * 15);
    }

    private String getRankTitle(int level) {
        if (level >= 10) return "👑 Học bá Toàn năng";
        if (level >= 6)  return "🔥 Chiến thần Tập trung";
        if (level >= 3)  return "⭐ Học viên Chăm chỉ";
        return "🌱 Tân binh Khởi đầu";
    }

    private UserUsageStats loadUserStats() {
        UserUsageStats s = new UserUsageStats();
        if (database == null || user == null) return s;
        String u = user.username();
        String dName = user.displayName();

        try (Connection c = database.connect()) {
            // 1. Pomodoro & Study Stats
            try (PreparedStatement ps = c.prepareStatement("SELECT total_minutes, pomodoro_count FROM study_stats WHERE username = ?")) {
                ps.setString(1, u);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    s.totalFocusMinutes = rs.getInt(1);
                    s.pomodoroSessions = rs.getInt(2);
                }
            } catch (Exception ignored) {}

            // 2. Quizzes summary & recent submissions
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), COALESCE(AVG(score), 0), COALESCE(MAX(score), 0) FROM quiz_submission WHERE username = ?")) {
                ps.setString(1, u);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    s.quizzesTaken = rs.getInt(1);
                    s.avgQuizScore = rs.getDouble(2);
                    s.maxQuizScore = rs.getDouble(3);
                }
            } catch (Exception ignored) {}

            String recQuizSql = """
                SELECT q.title, sc.title, qs.score, qs.correct_count, qs.total_questions, TO_CHAR(qs.submitted_at, 'DD/MM HH24:MI')
                FROM quiz_submission qs
                JOIN course_quiz q ON q.quiz_id = qs.quiz_id
                JOIN study_course sc ON sc.course_id = q.course_id
                WHERE qs.username = ?
                ORDER BY qs.submitted_at DESC
                LIMIT 5
            """;
            try (PreparedStatement ps = c.prepareStatement(recQuizSql)) {
                ps.setString(1, u);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    s.recentQuizzes.add(new QuizHistoryItem(
                        rs.getString(1), rs.getString(2), rs.getDouble(3),
                        rs.getInt(4), rs.getInt(5), rs.getString(6)
                    ));
                }
            } catch (Exception ignored) {}

            // 3. Courses joined, owned, and materials uploaded
            String myCoursesSql = """
                SELECT sc.title, sc.course_code, sc.owner_username,
                       (SELECT COUNT(*) FROM course_member cm2 WHERE cm2.course_id = sc.course_id)
                FROM study_course sc
                WHERE sc.owner_username = ?
                   OR EXISTS (SELECT 1 FROM course_member cm WHERE cm.course_id = sc.course_id AND cm.username = ?)
                ORDER BY sc.created_at DESC
            """;
            try (PreparedStatement ps = c.prepareStatement(myCoursesSql)) {
                ps.setString(1, u);
                ps.setString(2, u);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    boolean isOwner = u.equalsIgnoreCase(rs.getString(3));
                    s.coursesJoined++;
                    if (isOwner) s.coursesOwned++;
                    s.myCourses.add(new CourseSummaryItem(rs.getString(1), rs.getString(2), isOwner, rs.getInt(4)));
                }
            } catch (Exception ignored) {}

            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM course_material WHERE uploaded_by = ? OR uploaded_by = ?")) {
                ps.setString(1, u);
                ps.setString(2, dName);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) s.materialsUploaded = rs.getInt(1);
            } catch (Exception ignored) {}

            // 4. Study Notes & Checklist Todos
            try (PreparedStatement ps = c.prepareStatement("SELECT todo_text FROM study_note WHERE username = ?")) {
                ps.setString(1, u);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    s.notesCreated++;
                    String todoRaw = rs.getString(1);
                    if (todoRaw != null && !todoRaw.isBlank()) {
                        for (String line : todoRaw.split("\n")) {
                            String t = line.trim();
                            if (t.startsWith("[x] ")) {
                                s.todosTotal++;
                                s.todosCompleted++;
                            } else if (t.startsWith("[ ] ") || !t.isEmpty()) {
                                s.todosTotal++;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            // 5. Friends & Chat Messages
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM friendship WHERE (sender = ? OR receiver = ?) AND LOWER(status) = 'accepted'")) {
                ps.setString(1, u);
                ps.setString(2, u);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) s.friendsCount = rs.getInt(1);
            } catch (Exception ignored) {}

            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM chat_message WHERE sender = ? OR sender = ?")) {
                ps.setString(1, u);
                ps.setString(2, dName);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) s.messagesSent = rs.getInt(1);
            } catch (Exception ignored) {}

            // 6. AI Queries & Music Tracks
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM ai_chat_message WHERE username = ? AND LOWER(role) = 'user'")) {
                ps.setString(1, u);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) s.aiQueriesCount = rs.getInt(1);
            } catch (Exception ignored) {}

            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM music_room_playlist WHERE added_by = ? OR added_by = ?")) {
                ps.setString(1, u);
                ps.setString(2, dName);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) s.musicTracksAdded = rs.getInt(1);
            } catch (Exception ignored) {}

        } catch (Exception ignored) {}

        return s;
    }
}
