package vn.studyroom;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Pomodoro Focus Timer, Study Session Tracker, and Study Leaderboard View.
 */
public final class PomodoroStudyView {
    private static Timeline activeTimeline;
    private static int totalSeconds = 25 * 60;
    private static int remainingSeconds = 25 * 60;
    private static boolean isRunning = false;
    private static String currentModeName = "🍅 Tập trung tiêu chuẩn (25 phút)";
    private static String currentAccentHex = "#4f46e5";
    private static boolean isBreakMode = false;
    private static int unsavedMinutesCounter = 0;

    public record LeaderboardEntry(String username, String displayName, int totalMinutes, int pomodoroCount) {}

    private final Database database;
    private final User user;
    private final MusicPlayerService musicPlayer;
    private final Consumer<String> toastCallback;
    private final BiFunction<String, String, StackPane> avatarProvider;

    private Label timeDisplayLbl;
    private Label modeBadgeLbl;
    private Label stateSubLbl;
    private Button startPauseBtn;
    private Canvas ringCanvas;
    private VBox leaderboardList;
    private Label myMinutesLbl;
    private Label myPomodorosLbl;
    private Label myBadgeTitleLbl;

    public PomodoroStudyView(Database database, User user, MusicPlayerService musicPlayer,
                             Consumer<String> toastCallback, BiFunction<String, String, StackPane> avatarProvider) {
        this.database = database;
        this.user = user;
        this.musicPlayer = musicPlayer;
        this.toastCallback = toastCallback;
        this.avatarProvider = avatarProvider;
        ensureUserInStats();
    }

    private void toast(String msg) {
        if (toastCallback != null) toastCallback.accept(msg);
    }

    private void ensureUserInStats() {
        if (database == null || user == null) return;
        String sql = """
            INSERT INTO study_stats (username, display_name, total_minutes, pomodoro_count, updated_at)
            VALUES (?, ?, 0, 0, CURRENT_TIMESTAMP)
            ON CONFLICT (username) DO UPDATE SET display_name = EXCLUDED.display_name
        """;
        try (Connection c = database.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.username());
            ps.setString(2, user.displayName());
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    public void addStudyProgress(int addMinutes, int addPomodoros) {
        if (database == null || user == null) return;
        String sql = """
            INSERT INTO study_stats (username, display_name, total_minutes, pomodoro_count, updated_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (username) DO UPDATE SET
                display_name = EXCLUDED.display_name,
                total_minutes = study_stats.total_minutes + EXCLUDED.total_minutes,
                pomodoro_count = study_stats.pomodoro_count + EXCLUDED.pomodoro_count,
                updated_at = CURRENT_TIMESTAMP
        """;
        try (Connection c = database.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.username());
            ps.setString(2, user.displayName());
            ps.setInt(3, Math.max(0, addMinutes));
            ps.setInt(4, Math.max(0, addPomodoros));
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    public void render(VBox content) {
        content.getChildren().clear();
        content.getStyleClass().setAll("workspace");
        content.setPadding(new Insets(18, 26, 18, 26));
        content.setSpacing(14);

        // Header
        HBox topBar = new HBox(12);
        topBar.setAlignment(Pos.CENTER_LEFT);

        VBox titleCol = new VBox(2);
        Label title = new Label("🍅 Đồng hồ Pomodoro & Bảng xếp hạng Học tập");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: 800; -fx-text-fill: -ink;");
        Label sub = new Label("Phương pháp quản lý thời gian tập trung sâu kết hợp sóng não Alpha & thi đua học tập cùng bạn bè");
        sub.getStyleClass().add("muted");
        sub.setStyle("-fx-font-size: 13px;");
        titleCol.getChildren().addAll(title, sub);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button openNotesBtn = new Button("📝 Mở Sổ tay ghi chú nổi");
        openNotesBtn.setStyle("-fx-background-color: #eef2ff; -fx-text-fill: #4338ca; -fx-border-color: #c7d2fe; -fx-border-radius: 12; -fx-background-radius: 12; -fx-font-weight: 700; -fx-font-size: 12.5px; -fx-padding: 8 16; -fx-cursor: hand;");
        openNotesBtn.setOnAction(e -> StudyNotesWindow.getInstance().show(database, user, toastCallback));

        Button focusMusicBtn = new Button("🎧 Bật nhạc sóng não 432Hz");
        focusMusicBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-background-radius: 12; -fx-font-weight: 700; -fx-font-size: 12.5px; -fx-padding: 8 16; -fx-cursor: hand;");
        focusMusicBtn.setOnAction(e -> {
            if (musicPlayer != null) {
                musicPlayer.switchRoom("room_sp_focus");
                if (!musicPlayer.isPlaying()) {
                    musicPlayer.play();
                }
                toast("Đã bật nhạc sóng não tập trung (Focus Beats) 🎧");
            }
        });

        topBar.getChildren().addAll(titleCol, spacer, openNotesBtn, focusMusicBtn);

        // Main Split: Left = Pomodoro Timer Card, Right = Leaderboard & Personal Stats
        HBox mainBody = new HBox(20);
        VBox.setVgrow(mainBody, Priority.ALWAYS);

        VBox timerCard = buildTimerCard();
        HBox.setHgrow(timerCard, Priority.ALWAYS);

        VBox rightCol = buildLeaderboardColumn();
        rightCol.setPrefWidth(410);
        rightCol.setMinWidth(380);

        mainBody.getChildren().addAll(timerCard, rightCol);
        content.getChildren().addAll(topBar, mainBody);

        updateTimerUI();
        refreshLeaderboardUI();
        bindGlobalTimer();
    }

    private VBox buildTimerCard() {
        VBox card = new VBox(16);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(24));
        card.setStyle("-fx-background-color: white; -fx-background-radius: 20; -fx-border-color: #e2e8f0; -fx-border-radius: 20; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.04), 12, 0, 0, 3);");

        // Mode selector pills
        HBox modeBar = new HBox(10);
        modeBar.setAlignment(Pos.CENTER);

        Button m25 = createModePill("🍅 Tập trung 25p", 25 * 60, "🍅 Tập trung tiêu chuẩn (25 phút)", "#4f46e5", false);
        Button m50 = createModePill("⚡ Học sâu 50p", 50 * 60, "⚡ Học sâu cao độ (50 phút)", "#e11d48", false);
        Button m5  = createModePill("☕ Nghỉ ngắn 5p", 5 * 60, "☕ Nghỉ giải lao ngắn (5 phút)", "#10b981", true);
        Button m15 = createModePill("🌴 Nghỉ dài 15p", 15 * 60, "🌴 Nghỉ phục hồi năng lượng (15 phút)", "#0284c7", true);

        modeBar.getChildren().addAll(m25, m50, m5, m15);

        modeBadgeLbl = new Label(currentModeName);
        modeBadgeLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: 800; -fx-text-fill: #4338ca; -fx-background-color: #eef2ff; -fx-padding: 5 14; -fx-background-radius: 20;");

        // Circular Progress Ring + Time Label inside StackPane
        StackPane ringStack = new StackPane();
        ringCanvas = new Canvas(240, 240);

        VBox centerLabels = new VBox(4);
        centerLabels.setAlignment(Pos.CENTER);

        timeDisplayLbl = new Label("25:00");
        timeDisplayLbl.setStyle("-fx-font-size: 48px; -fx-font-weight: 900; -fx-text-fill: #0f172a; -fx-font-family: 'Segoe UI', Arial, sans-serif;");

        stateSubLbl = new Label(isRunning ? "ĐANG TẬP TRUNG..." : "SẴN SÀNG BẮT ĐẦU");
        stateSubLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: 800; -fx-text-fill: #64748b;");

        centerLabels.getChildren().addAll(timeDisplayLbl, stateSubLbl);
        ringStack.getChildren().addAll(ringCanvas, centerLabels);

        // Action Buttons
        HBox controls = new HBox(12);
        controls.setAlignment(Pos.CENTER);

        startPauseBtn = new Button(isRunning ? "⏸ Tạm dừng" : "▶ Bắt đầu ngay");
        startPauseBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 14px; -fx-background-radius: 24; -fx-padding: 10 28; -fx-cursor: hand;");
        startPauseBtn.setOnAction(e -> toggleStartPause());

        Button resetBtn = new Button("🔄 Đặt lại");
        resetBtn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #334155; -fx-font-weight: 700; -fx-font-size: 13px; -fx-background-radius: 24; -fx-padding: 10 20; -fx-cursor: hand;");
        resetBtn.setOnAction(e -> resetTimer());

        Button completeNowBtn = new Button("✅ Hoàn thành hiệp");
        completeNowBtn.setTooltip(new Tooltip("Kết thúc hiệp học hiện tại và cộng thời gian đã học vào Bảng xếp hạng"));
        completeNowBtn.setStyle("-fx-background-color: #ecfdf5; -fx-text-fill: #059669; -fx-border-color: #a7f3d0; -fx-border-radius: 24; -fx-font-weight: 700; -fx-font-size: 13px; -fx-background-radius: 24; -fx-padding: 10 18; -fx-cursor: hand;");
        completeNowBtn.setOnAction(e -> completeSessionManually());

        controls.getChildren().addAll(startPauseBtn, resetBtn, completeNowBtn);

        // Study Tip Box
        HBox tipBox = new HBox(10);
        tipBox.setAlignment(Pos.CENTER_LEFT);
        tipBox.setPadding(new Insets(10, 14, 10, 14));
        tipBox.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 12; -fx-border-color: #e2e8f0; -fx-border-radius: 12;");
        Label tipLbl = new Label("💡 Mỗi phút bạn bật đồng hồ tập trung sẽ được tự động ghi nhận vào Bảng xếp hạng chăm chỉ bên phải!");
        tipLbl.setWrapText(true);
        tipLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #475569;");
        tipBox.getChildren().add(tipLbl);

        card.getChildren().addAll(modeBar, modeBadgeLbl, ringStack, controls, tipBox);
        return card;
    }

    private Button createModePill(String label, int seconds, String fullTitle, String accentHex, boolean breakMode) {
        Button btn = new Button(label);
        btn.setMinSize(Region.USE_PREF_SIZE, 34);
        btn.setMaxHeight(34);
        btn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #334155; -fx-border-color: #e2e8f0; -fx-border-radius: 999; -fx-font-weight: 700; -fx-font-size: 12px; -fx-background-radius: 999; -fx-padding: 0 14; -fx-cursor: hand;");
        btn.setOnAction(e -> {
            isRunning = false;
            totalSeconds = seconds;
            remainingSeconds = seconds;
            currentModeName = fullTitle;
            currentAccentHex = accentHex;
            isBreakMode = breakMode;
            unsavedMinutesCounter = 0;
            updateTimerUI();
        });
        return btn;
    }

    private VBox buildLeaderboardColumn() {
        VBox col = new VBox(14);

        // 1. Personal stats summary card
        VBox myCard = new VBox(10);
        myCard.setPadding(new Insets(16, 18, 16, 18));
        myCard.setStyle("-fx-background-color: linear-gradient(to bottom right, #4f46e5, #7c3aed); -fx-background-radius: 18; -fx-effect: dropshadow(gaussian, rgba(79,70,229,0.22), 10, 0, 0, 3);");

        HBox myHeader = new HBox(10);
        myHeader.setAlignment(Pos.CENTER_LEFT);
        Label myTitle = new Label("🌟 Thành tích học tập của bạn");
        myTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: 800; -fx-text-fill: white;");
        Region g = new Region();
        HBox.setHgrow(g, Priority.ALWAYS);
        myBadgeTitleLbl = new Label("Tân binh Chăm chỉ");
        myBadgeTitleLbl.setStyle("-fx-background-color: rgba(255,255,255,0.22); -fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 11px; -fx-padding: 3 10; -fx-background-radius: 12;");
        myHeader.getChildren().addAll(myTitle, g, myBadgeTitleLbl);

        HBox statsRow = new HBox(24);
        statsRow.setAlignment(Pos.CENTER_LEFT);

        VBox minBox = new VBox(2);
        myMinutesLbl = new Label("0 phút");
        myMinutesLbl.setStyle("-fx-font-size: 22px; -fx-font-weight: 900; -fx-text-fill: white;");
        Label minSub = new Label("Tổng thời gian tập trung");
        minSub.setStyle("-fx-font-size: 11px; -fx-text-fill: #e0e7ff;");
        minBox.getChildren().addAll(myMinutesLbl, minSub);

        VBox pomBox = new VBox(2);
        myPomodorosLbl = new Label("0 hiệp");
        myPomodorosLbl.setStyle("-fx-font-size: 22px; -fx-font-weight: 900; -fx-text-fill: white;");
        Label pomSub = new Label("Hiệp Pomodoro hoàn thành");
        pomSub.setStyle("-fx-font-size: 11px; -fx-text-fill: #e0e7ff;");
        pomBox.getChildren().addAll(myPomodorosLbl, pomSub);

        statsRow.getChildren().addAll(minBox, pomBox);
        myCard.getChildren().addAll(myHeader, statsRow);

        // 2. Leaderboard card
        VBox boardCard = new VBox(10);
        boardCard.setPadding(new Insets(16, 18, 16, 18));
        boardCard.setStyle("-fx-background-color: white; -fx-background-radius: 18; -fx-border-color: #e2e8f0; -fx-border-radius: 18;");
        VBox.setVgrow(boardCard, Priority.ALWAYS);

        HBox boardHeader = new HBox(8);
        boardHeader.setAlignment(Pos.CENTER_LEFT);
        Label boardTitle = new Label("🏆 Bảng xếp hạng Chăm chỉ");
        boardTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");
        Region bg = new Region();
        HBox.setHgrow(bg, Priority.ALWAYS);
        Button refreshBtn = new Button("↻ Làm mới");
        refreshBtn.setMinSize(Region.USE_PREF_SIZE, 28);
        refreshBtn.setMaxHeight(28);
        refreshBtn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-border-color: #e2e8f0; -fx-border-radius: 999; -fx-font-size: 11px; -fx-font-weight: 700; -fx-background-radius: 999; -fx-padding: 0 11; -fx-cursor: hand;");
        refreshBtn.setOnAction(e -> refreshLeaderboardUI());
        boardHeader.getChildren().addAll(boardTitle, bg, refreshBtn);

        leaderboardList = new VBox(8);
        ScrollPane scroll = new ScrollPane(leaderboardList);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().addAll("thread-scroll", "soft-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        boardCard.getChildren().addAll(boardHeader, scroll);
        col.getChildren().addAll(myCard, boardCard);
        return col;
    }

    private void bindGlobalTimer() {
        if (activeTimeline != null) {
            activeTimeline.stop();
        }
        activeTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (!isRunning) return;
            if (remainingSeconds > 0) {
                remainingSeconds--;
                if (!isBreakMode) {
                    unsavedMinutesCounter++;
                    if (unsavedMinutesCounter >= 60) {
                        unsavedMinutesCounter = 0;
                        addStudyProgress(1, 0);
                        refreshLeaderboardUI();
                    }
                }
                updateTimerUI();
            } else {
                isRunning = false;
                playCompletionChime();
                if (!isBreakMode) {
                    addStudyProgress(1, 1);
                    toast("🎉 Chúc mừng! Bạn đã hoàn thành 1 hiệp Pomodoro tập trung!");
                } else {
                    toast("⏰ Đã hết giờ nghỉ giải lao! Sẵn sàng quay lại học tập nhé.");
                }
                remainingSeconds = totalSeconds;
                updateTimerUI();
                refreshLeaderboardUI();
            }
        }));
        activeTimeline.setCycleCount(Animation.INDEFINITE);
        activeTimeline.play();
    }

    private void toggleStartPause() {
        isRunning = !isRunning;
        updateTimerUI();
    }

    private void resetTimer() {
        isRunning = false;
        remainingSeconds = totalSeconds;
        unsavedMinutesCounter = 0;
        updateTimerUI();
    }

    private void completeSessionManually() {
        int elapsedSeconds = totalSeconds - remainingSeconds;
        int elapsedMinutes = Math.max(1, elapsedSeconds / 60);
        isRunning = false;
        remainingSeconds = totalSeconds;
        unsavedMinutesCounter = 0;
        playCompletionChime();
        if (!isBreakMode) {
            addStudyProgress(elapsedMinutes, 1);
            toast("🎉 Đã hoàn thành hiệp học! Cộng +" + elapsedMinutes + " phút & +1 Pomodoro vào Bảng xếp hạng!");
        } else {
            toast("Đã kết thúc phiên nghỉ giải lao.");
        }
        updateTimerUI();
        refreshLeaderboardUI();
    }

    private void updateTimerUI() {
        if (timeDisplayLbl == null) return;
        int m = remainingSeconds / 60;
        int s = remainingSeconds % 60;
        timeDisplayLbl.setText(String.format("%02d:%02d", m, s));
        modeBadgeLbl.setText(currentModeName);

        if (isRunning) {
            stateSubLbl.setText(isBreakMode ? "☕ ĐANG NGHỈ GIẢI LAO..." : "🔥 ĐANG TẬP TRUNG CAO ĐỘ...");
            stateSubLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: 800; -fx-text-fill: " + currentAccentHex + ";");
            startPauseBtn.setText("⏸ Tạm dừng");
            startPauseBtn.setStyle("-fx-background-color: #f59e0b; -fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 14px; -fx-background-radius: 24; -fx-padding: 10 28; -fx-cursor: hand;");
        } else {
            stateSubLbl.setText("SẴN SÀNG BẮT ĐẦU");
            stateSubLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: 800; -fx-text-fill: #64748b;");
            startPauseBtn.setText("▶ Bắt đầu ngay");
            startPauseBtn.setStyle("-fx-background-color: " + currentAccentHex + "; -fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 14px; -fx-background-radius: 24; -fx-padding: 10 28; -fx-cursor: hand;");
        }

        drawProgressRing();
    }

    private void drawProgressRing() {
        if (ringCanvas == null) return;
        GraphicsContext gc = ringCanvas.getGraphicsContext2D();
        double w = ringCanvas.getWidth();
        double h = ringCanvas.getHeight();
        gc.clearRect(0, 0, w, h);

        double pad = 16;
        double size = w - pad * 2;

        // Track background circle
        gc.setStroke(Color.web("#f1f5f9"));
        gc.setLineWidth(14);
        gc.strokeOval(pad, pad, size, size);

        // Progress arc
        double progress = totalSeconds > 0 ? (double) remainingSeconds / totalSeconds : 1.0;
        double angle = progress * 360.0;

        gc.setStroke(Color.web(currentAccentHex));
        gc.setLineWidth(14);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.strokeArc(pad, pad, size, size, 90, -angle, ArcType.OPEN);
    }

    private void refreshLeaderboardUI() {
        if (leaderboardList == null || database == null) return;
        List<LeaderboardEntry> entries = new ArrayList<>();
        String sql = "SELECT username, display_name, total_minutes, pomodoro_count FROM study_stats ORDER BY total_minutes DESC, pomodoro_count DESC LIMIT 20";
        try (Connection c = database.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                entries.add(new LeaderboardEntry(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getInt(3),
                    rs.getInt(4)
                ));
            }
        } catch (Exception ignored) {}

        leaderboardList.getChildren().clear();
        for (int i = 0; i < entries.size(); i++) {
            LeaderboardEntry e = entries.get(i);
            boolean isMe = user != null && user.username().equalsIgnoreCase(e.username());

            if (isMe) {
                myMinutesLbl.setText(e.totalMinutes() + " phút");
                myPomodorosLbl.setText(e.pomodoroCount() + " hiệp");
                myBadgeTitleLbl.setText(getRankTitle(e.totalMinutes()));
            }

            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(10, 12, 10, 12));
            row.setStyle(isMe
                ? "-fx-background-color: #eef2ff; -fx-border-color: #6366f1; -fx-border-radius: 12; -fx-background-radius: 12;"
                : "-fx-background-color: #f8fafc; -fx-border-color: #f1f5f9; -fx-border-radius: 12; -fx-background-radius: 12;");

            String medal = switch (i) {
                case 0 -> "🥇";
                case 1 -> "🥈";
                case 2 -> "🥉";
                default -> "#" + (i + 1);
            };
            Label rankLbl = new Label(medal);
            rankLbl.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-min-width: 28;");

            StackPane avt = avatarProvider != null ? avatarProvider.apply(e.username(), e.displayName()) : new StackPane();

            VBox meta = new VBox(2);
            HBox.setHgrow(meta, Priority.ALWAYS);
            Label nameLbl = new Label(e.displayName() + (isMe ? " (Bạn)" : ""));
            nameLbl.setStyle("-fx-font-weight: 800; -fx-font-size: 13px; -fx-text-fill: #0f172a;");
            Label subLbl = new Label(getRankTitle(e.totalMinutes()) + " · 🍅 " + e.pomodoroCount() + " hiệp");
            subLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");
            meta.getChildren().addAll(nameLbl, subLbl);

            Label minBadge = new Label(e.totalMinutes() + " phút");
            minBadge.setStyle("-fx-background-color: #ecfdf5; -fx-text-fill: #059669; -fx-font-weight: 800; -fx-font-size: 12px; -fx-padding: 4 10; -fx-background-radius: 10;");

            row.getChildren().addAll(rankLbl, avt, meta, minBadge);
            leaderboardList.getChildren().add(row);
        }
    }

    private String getRankTitle(int minutes) {
        if (minutes >= 300) return "👑 Học bá Toàn năng";
        if (minutes >= 120) return "🔥 Chiến thần Tập trung";
        if (minutes >= 50)  return "⭐ Học viên Chăm chỉ";
        return "🌱 Tân binh Khởi đầu";
    }

    public static void playCompletionChime() {
        Thread.ofVirtual().start(() -> {
            try {
                float sampleRate = 44100f;
                AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
                try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                    line.open(format);
                    line.start();
                    double[] freqs = { 880.0, 1108.73, 1318.51 }; // A5 major chord chime
                    for (double freq : freqs) {
                        int durationMs = 240;
                        int samples = (int) (sampleRate * durationMs / 1000);
                        byte[] buf = new byte[samples * 2];
                        for (int i = 0; i < samples; i++) {
                            double t = i / sampleRate;
                            double env = Math.exp(-4.5 * t);
                            short val = (short) (Math.sin(2.0 * Math.PI * freq * t) * 14000 * env);
                            buf[i * 2] = (byte) (val & 0xff);
                            buf[i * 2 + 1] = (byte) ((val >> 8) & 0xff);
                        }
                        line.write(buf, 0, buf.length);
                    }
                    line.drain();
                }
            } catch (Exception ignored) {}
        });
    }
}
