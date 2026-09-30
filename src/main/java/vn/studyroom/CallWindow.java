package vn.studyroom;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Modern floating Call Window for 1-1 and Group voice calls.
 * Displays participants, timer, mute/deafen controls, and end call button.
 */
public final class CallWindow {
    private final Stage stage = new Stage();
    private final CallRepository.CallSession session;
    private final User currentUser;
    private final CallRepository callRepo;
    private final VoiceEngine voiceEngine = new VoiceEngine();

    private final FlowPane participantGrid = new FlowPane(16, 16);
    private final Label statusLabel = new Label("Đang kết nối...");
    private final Label timerLabel = new Label("00:00");
    private final Button micBtn = new Button("🎤");
    private final Button speakerBtn = new Button("🔊");

    private Timer pollTimer;
    private final AtomicInteger secondsElapsed = new AtomicInteger(0);

    public CallWindow(CallRepository.CallSession session, User currentUser, CallRepository callRepo) {
        this.session = session;
        this.currentUser = currentUser;
        this.callRepo = callRepo;

        buildUI();
    }

    private void buildUI() {
        stage.setTitle("Cuộc gọi · " + session.roomName());
        stage.setMinWidth(460);
        stage.setMinHeight(520);

        VBox root = new VBox(18);
        root.setStyle("-fx-background-color: #1a1a24; -fx-padding: 24;");
        root.setAlignment(Pos.TOP_CENTER);

        // Header
        Label title = new Label(session.roomName());
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: 800; -fx-text-fill: white;");

        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #9d9db5;");
        timerLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #7c5cff;");

        VBox header = new VBox(4, title, statusLabel, timerLabel);
        header.setAlignment(Pos.CENTER);

        // Participant Grid
        participantGrid.setAlignment(Pos.CENTER);
        participantGrid.setPadding(new Insets(20, 10, 20, 10));
        VBox.setVgrow(participantGrid, Priority.ALWAYS);

        // Bottom Controls
        HBox controls = new HBox(16);
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(14, 0, 8, 0));

        micBtn.setStyle(controlBtnStyle("#2c2c3d", "white"));
        micBtn.setTooltip(new Tooltip("Bật / Tắt Mic"));
        micBtn.setOnAction(e -> {
            boolean isMuted = !voiceEngine.isMuted();
            voiceEngine.setMuted(isMuted);
            micBtn.setText(isMuted ? "🔇" : "🎤");
            micBtn.setStyle(controlBtnStyle(isMuted ? "#ff4757" : "#2c2c3d", "white"));
        });

        speakerBtn.setStyle(controlBtnStyle("#2c2c3d", "white"));
        speakerBtn.setTooltip(new Tooltip("Bật / Tắt Loa"));
        speakerBtn.setOnAction(e -> {
            boolean isDeaf = !voiceEngine.isDeafened();
            voiceEngine.setDeafened(isDeaf);
            speakerBtn.setText(isDeaf ? "🔈" : "🔊");
            speakerBtn.setStyle(controlBtnStyle(isDeaf ? "#ff4757" : "#2c2c3d", "white"));
        });

        Button endBtn = new Button("📞");
        endBtn.setStyle(controlBtnStyle("#ff4757", "white"));
        endBtn.setTooltip(new Tooltip("Rời cuộc gọi"));
        endBtn.setOnAction(e -> closeCall());

        controls.getChildren().addAll(micBtn, speakerBtn, endBtn);

        root.getChildren().addAll(header, participantGrid, controls);

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> closeCall());
    }

    private static String controlBtnStyle(String bg, String fg) {
        return String.format(
            "-fx-background-color: %s; -fx-text-fill: %s; -fx-font-size: 20px; " +
            "-fx-min-width: 56px; -fx-min-height: 56px; -fx-max-width: 56px; -fx-max-height: 56px; " +
            "-fx-background-radius: 28px; -fx-cursor: hand;", bg, fg
        );
    }

    public void start() {
        int localPort = voiceEngine.start(5100);
        String myIp = VoiceEngine.getLocalIp();
        callRepo.joinCall(session.callId(), currentUser.username(), currentUser.displayName(), localPort, myIp);

        stage.show();

        // Timer and participant sync
        pollTimer = new Timer(true);
        pollTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> syncState());
            }
        }, 500, 1000);
    }

    private void syncState() {
        // Update timer
        int sec = secondsElapsed.incrementAndGet();
        int mins = sec / 60;
        int secs = sec % 60;
        timerLabel.setText(String.format("%02d:%02d", mins, secs));

        // Sync participants from database
        List<CallRepository.Participant> list = callRepo.getParticipants(session.callId());
        if (list.isEmpty()) {
            closeCall();
            return;
        }

        statusLabel.setText(list.size() > 1 ? "Đang đàm thoại (" + list.size() + " người)" : "Đang chờ người khác tham gia...");

        participantGrid.getChildren().clear();
        for (CallRepository.Participant p : list) {
            VBox tile = new VBox(8);
            tile.setAlignment(Pos.CENTER);
            tile.setPadding(new Insets(10));
            tile.setStyle("-fx-background-color: #242436; -fx-background-radius: 16px; -fx-min-width: 110px; -fx-min-height: 110px;");

            StackPane avatar = new StackPane();
            String initials = initials(p.displayName());
            Label mark = new Label(initials);
            mark.setStyle("-fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 16px;");
            avatar.getChildren().add(mark);
            avatar.setMinSize(52, 52);
            avatar.setMaxSize(52, 52);
            avatar.setStyle("-fx-background-color: #7c5cff; -fx-background-radius: 26px; -fx-alignment: center;");

            Label name = new Label(p.displayName() + (p.username().equals(currentUser.username()) ? " (Bạn)" : ""));
            name.setStyle("-fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: 600;");

            tile.getChildren().addAll(avatar, name);
            participantGrid.getChildren().add(tile);

            // Connect UDP peer if not self
            if (!p.username().equals(currentUser.username()) && p.udpPort() > 0) {
                String targetIp = p.ipAddress();
                if (targetIp == null || targetIp.isBlank()) {
                    targetIp = "127.0.0.1";
                }
                voiceEngine.addPeer(targetIp, p.udpPort());
            }
        }
    }

    private static String initials(String name) {
        String[] parts = name.trim().split("\\s+");
        return parts.length == 1 ? parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase()
                                 : ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    public void closeCall() {
        if (pollTimer != null) {
            pollTimer.cancel();
            pollTimer = null;
        }
        voiceEngine.stop();
        callRepo.leaveCall(session.callId(), currentUser.username());
        stage.close();
    }
}
