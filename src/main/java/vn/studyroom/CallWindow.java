package vn.studyroom;

import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Modern floating Call Window for 1-1 and Group voice/video calls.
 * - Shows "Đang chờ..." until a second participant joins.
 * - Timer starts only after someone answers.
 * - Supports Camera/Webcam toggle and video tiles for all participants.
 * - Fires onCallEnded(durationString) callback when call ends.
 */
public final class CallWindow {
    private final Stage stage = new Stage();
    private final CallRepository.CallSession session;
    private final User currentUser;
    private final CallRepository callRepo;
    private final VoiceEngine voiceEngine = new VoiceEngine();
    private final WebcamStreamEngine webcamStream = WebcamStreamEngine.getInstance();

    // Optional callback: called with formatted duration when call ends
    private Consumer<String> onCallEnded;

    private final FlowPane participantGrid = new FlowPane(16, 16);
    private final Label statusLabel = new Label("Đang kết nối...");
    private final Label timerLabel  = new Label("");
    private final Button micBtn     = new Button("🎤");
    private final Button camBtn     = new Button("📹");
    private final Button speakerBtn = new Button("🔊");

    private Timer pollTimer;
    private volatile long callStartTimeMs = 0;
    private final AtomicBoolean callAnswered   = new AtomicBoolean(false); // true once ≥2 people in call

    private boolean isCameraOn = false;
    private boolean autoStartCam = false;
    private volatile Image localVideoFrame = null;
    private final Map<String, Image> remoteVideoFrames = new ConcurrentHashMap<>();

    public CallWindow(CallRepository.CallSession session, User currentUser, CallRepository callRepo) {
        this.session     = session;
        this.currentUser = currentUser;
        this.callRepo    = callRepo;
        buildUI();
    }

    /** Set whether camera should automatically turn on when call window opens. */
    public void setAutoStartCamera(boolean autoStart) {
        this.autoStartCam = autoStart;
    }

    /** Set a callback that fires when this call ends. Receives a formatted duration string like "2:35". */
    public void setOnCallEnded(Consumer<String> cb) { this.onCallEnded = cb; }

    // ── UI ──────────────────────────────────────────────────────────────────────
    private void buildUI() {
        stage.setTitle("Cuộc gọi · " + session.roomName());
        stage.setMinWidth(480);
        stage.setMinHeight(540);

        VBox root = new VBox(18);
        root.setStyle("-fx-background-color: #1a1a24; -fx-padding: 24;");
        root.setAlignment(Pos.TOP_CENTER);

        // Header
        Label title = new Label(session.roomName());
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: 800; -fx-text-fill: white;");

        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #9d9db5;");
        timerLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #7c5cff;");
        timerLabel.setVisible(false); // hidden until call is answered

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

        camBtn.setStyle(controlBtnStyle("#2c2c3d", "white"));
        camBtn.setTooltip(new Tooltip("Bật / Tắt Camera"));
        camBtn.setOnAction(e -> toggleCamera());

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

        controls.getChildren().addAll(micBtn, camBtn, speakerBtn, endBtn);
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

    public void toggleCamera() {
        isCameraOn = !isCameraOn;
        camBtn.setText(isCameraOn ? "📷" : "📹");
        camBtn.setStyle(controlBtnStyle(isCameraOn ? "#6366f1" : "#2c2c3d", "white"));
        if (isCameraOn) {
            CameraEngine.getInstance().start(img -> {
                localVideoFrame = img;
                Platform.runLater(this::syncState);
            });
            CameraEngine.getInstance().setRawFrameCallback(rawBytes -> {
                if (isCameraOn) {
                    webcamStream.broadcastFrame(currentUser.username(), rawBytes);
                }
            });
        } else {
            CameraEngine.getInstance().stop();
            CameraEngine.getInstance().setRawFrameCallback(null);
            localVideoFrame = null;
            Platform.runLater(this::syncState);
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────
    public void start() {
        int voicePort = voiceEngine.start(5100);
        int camPort   = webcamStream.start(5300);
        String myIp   = VoiceEngine.getLocalIp();
        callRepo.joinCall(session.callId(), currentUser.username(), currentUser.displayName(), voicePort, myIp);

        // Initial waiting state
        statusLabel.setText("Đang chờ người khác tham gia...");
        timerLabel.setVisible(false);

        stage.show();

        if (autoStartCam && !isCameraOn) {
            toggleCamera();
        }

        pollTimer = new Timer(true);
        pollTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                Platform.runLater(() -> syncState());
            }
        }, 500, 1000);
    }

    // ── Sync ─────────────────────────────────────────────────────────────────────
    private void syncState() {
        List<CallRepository.Participant> list = callRepo.getParticipants(session.callId());

        // Call was ended by the other side
        if (list.isEmpty()) {
            closeCall();
            return;
        }

        boolean hasOthers = list.size() > 1;

        if (hasOthers && !callAnswered.get()) {
            // Someone just picked up → start timer
            callAnswered.set(true);
            timerLabel.setVisible(true);
            callStartTimeMs = System.currentTimeMillis();
        }

        if (callAnswered.get()) {
            // Calculate accurate elapsed seconds from start timestamp
            long elapsedSec = (System.currentTimeMillis() - callStartTimeMs) / 1000;
            long mins = elapsedSec / 60;
            long secs = elapsedSec % 60;
            timerLabel.setText(String.format("%02d:%02d", mins, secs));
            statusLabel.setText("Đang đàm thoại (" + list.size() + " người)");
        } else {
            // Still waiting
            statusLabel.setText("Đang chờ người khác tham gia...");
            timerLabel.setVisible(false);
        }

        // Render participants
        participantGrid.getChildren().clear();
        for (CallRepository.Participant p : list) {
            boolean isSelf = p.username().equals(currentUser.username());

            // Check for UDP peer camera callbacks
            if (!isSelf) {
                webcamStream.registerMemberCallback(p.username(), img -> {
                    remoteVideoFrames.put(p.username(), img);
                    Platform.runLater(this::syncState);
                });
                if (p.udpPort() > 0) {
                    String ip = (p.ipAddress() == null || p.ipAddress().isBlank()) ? "127.0.0.1" : p.ipAddress();
                    voiceEngine.addPeer(ip, p.udpPort());
                    webcamStream.addPeer(ip, p.udpPort() + 200);
                }
            }

            Image frame = isSelf ? (isCameraOn ? localVideoFrame : null) : remoteVideoFrames.get(p.username());
            boolean hasVideo = frame != null && (isSelf ? isCameraOn : webcamStream.hasRecentVideo(p.username()));

            if (hasVideo) {
                // Video Frame Tile
                ImageView iv = new ImageView(frame);
                iv.setFitWidth(180);
                iv.setFitHeight(135);
                iv.setPreserveRatio(false);
                iv.setSmooth(true);

                javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(180, 135);
                clip.setArcWidth(16);
                clip.setArcHeight(16);
                iv.setClip(clip);

                Label nameOverlay = new Label(p.displayName() + (isSelf ? " (Bạn)" : ""));
                nameOverlay.setStyle("-fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: 600; " +
                                     "-fx-background-color: rgba(0,0,0,0.6); -fx-background-radius: 8; -fx-padding: 3 8;");

                StackPane videoTile = new StackPane(iv, nameOverlay);
                StackPane.setAlignment(nameOverlay, Pos.BOTTOM_LEFT);
                StackPane.setMargin(nameOverlay, new Insets(6));
                videoTile.setStyle("-fx-background-color: #000; -fx-background-radius: 16px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 8, 0, 0, 2);");

                participantGrid.getChildren().add(videoTile);
            } else {
                // Avatar Tile
                VBox tile = new VBox(8);
                tile.setAlignment(Pos.CENTER);
                tile.setPadding(new Insets(10));
                tile.setStyle("-fx-background-color: #242436; -fx-background-radius: 16px; -fx-min-width: 120px; -fx-min-height: 120px;");

                StackPane avatar = new StackPane();
                Label mark = new Label(initials(p.displayName()));
                mark.setStyle("-fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 16px;");
                avatar.getChildren().add(mark);
                avatar.setMinSize(52, 52);
                avatar.setMaxSize(52, 52);
                avatar.setStyle("-fx-background-color: #7c5cff; -fx-background-radius: 26px; -fx-alignment: center;");

                Label name = new Label(p.displayName() + (isSelf ? " (Bạn)" : ""));
                name.setStyle("-fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: 600;");

                tile.getChildren().addAll(avatar, name);
                participantGrid.getChildren().add(tile);
            }
        }
    }

    // ── Close ─────────────────────────────────────────────────────────────────────
    public void closeCall() {
        if (pollTimer != null) { pollTimer.cancel(); pollTimer = null; }
        if (isCameraOn) {
            CameraEngine.getInstance().stop();
            CameraEngine.getInstance().setRawFrameCallback(null);
            isCameraOn = false;
        }
        webcamStream.stop();
        voiceEngine.stop();
        callRepo.leaveCall(session.callId(), currentUser.username());

        remoteVideoFrames.clear();
        localVideoFrame = null;

        // Build duration string
        long elapsedSec = callAnswered.get() ? (System.currentTimeMillis() - callStartTimeMs) / 1000 : 0;
        String duration = formatDuration((int) elapsedSec);
        boolean wasConnected = callAnswered.get();

        stage.close();

        // Fire callback so the chat can show the call summary
        if (onCallEnded != null) {
            onCallEnded.accept(wasConnected ? duration : null); // null = call was not answered
        }
    }

    private static String formatDuration(int totalSeconds) {
        if (totalSeconds < 60) return totalSeconds + " giây";
        int mins = totalSeconds / 60;
        int secs = totalSeconds % 60;
        return String.format("%d:%02d", mins, secs);
    }

    private static String initials(String name) {
        String[] parts = name.trim().split("\\s+");
        return parts.length == 1 ? parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase()
                                 : ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
    }
}
