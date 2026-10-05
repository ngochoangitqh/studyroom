package vn.studyroom;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Spotify-inspired persistent bottom player bar docked to the bottom of the app window.
 * Keeps music playing in the background while allowing full control and quick return to the room.
 */
public class BottomMusicBar extends HBox {
    private final MusicPlayerService player;
    private final Runnable onOpenMusicRoom;

    // Left
    private StackPane miniArt;
    private Label trackTitleLbl;
    private Label trackArtistLbl;
    private Label roomBadgeLbl;
    private Button heartBtn;

    // Center
    private Button playPauseBtn;
    private Button shuffleBtn;
    private Button repeatBtn;
    private Slider timeSlider;
    private Label curTimeLbl;
    private Label totalTimeLbl;

    // Right
    private HBox miniEqualizer;
    private final Region[] eqBars = new Region[5];
    private Label listenersLbl;
    private Slider volSlider;
    private Button muteBtn;

    public BottomMusicBar(MusicPlayerService player, Runnable onOpenMusicRoom) {
        this.player = player;
        this.onOpenMusicRoom = onOpenMusicRoom;

        setPrefHeight(74);
        setMinHeight(74);
        setMaxHeight(74);
        setAlignment(Pos.CENTER);
        setPadding(new Insets(0, 20, 0, 20));
        setStyle("-fx-background-color: #18181b; -fx-border-color: #27272a transparent transparent transparent; -fx-border-width: 1.5;");

        buildLeftSection();
        buildCenterSection();
        buildRightSection();

        // Keep Spotify WebView permanently in active scene graph for background streaming
        getChildren().add(player.getSpotifyBridge().getWebView());

        setupSubscriptions();
        refreshTrack();
        refreshState();
    }

    private void buildLeftSection() {
        HBox left = new HBox(12);
        left.setAlignment(Pos.CENTER_LEFT);
        left.setPrefWidth(260);
        left.setMinWidth(220);

        miniArt = new StackPane();
        miniArt.setMinSize(46, 46);
        miniArt.setMaxSize(46, 46);
        miniArt.setStyle("-fx-background-color: linear-gradient(to bottom right, #6366f1, #a855f7); -fx-background-radius: 8; -fx-cursor: hand;");
        Label miniIcon = new Label("🎵");
        miniIcon.setStyle("-fx-font-size: 20px; -fx-text-fill: white;");
        miniArt.getChildren().add(miniIcon);
        miniArt.setOnMouseClicked(e -> {
            if (onOpenMusicRoom != null) onOpenMusicRoom.run();
        });

        VBox meta = new VBox(2);
        meta.setAlignment(Pos.CENTER_LEFT);
        trackTitleLbl = new Label("Đang tải...");
        trackTitleLbl.setStyle("-fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 13px; -fx-cursor: hand;");
        trackTitleLbl.setOnMouseClicked(e -> {
            if (onOpenMusicRoom != null) onOpenMusicRoom.run();
        });

        trackArtistLbl = new Label("Nghệ sĩ");
        trackArtistLbl.setStyle("-fx-text-fill: #a1a1aa; -fx-font-size: 11px;");

        roomBadgeLbl = new Label("🟢 Lofi Chill");
        roomBadgeLbl.setStyle("-fx-text-fill: #10b981; -fx-font-size: 10px; -fx-font-weight: 600;");

        meta.getChildren().addAll(trackTitleLbl, trackArtistLbl, roomBadgeLbl);

        heartBtn = new Button("♡");
        heartBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #a1a1aa; -fx-font-size: 16px; -fx-cursor: hand;");
        heartBtn.setOnAction(e -> {
            boolean liked = "♥".equals(heartBtn.getText());
            heartBtn.setText(liked ? "♡" : "♥");
            heartBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: " + (liked ? "#a1a1aa" : "#ef4444") + "; -fx-font-size: 16px; -fx-cursor: hand;");
        });

        left.getChildren().addAll(miniArt, meta, heartBtn);
        getChildren().add(left);
    }

    private void buildCenterSection() {
        VBox center = new VBox(4);
        center.setAlignment(Pos.CENTER);
        HBox.setHgrow(center, Priority.ALWAYS);

        // Control buttons
        HBox btns = new HBox(16);
        btns.setAlignment(Pos.CENTER);

        shuffleBtn = new Button("🔀");
        shuffleBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #71717a; -fx-font-size: 14px; -fx-cursor: hand;");
        shuffleBtn.setOnAction(e -> player.toggleShuffle());

        Button prevBtn = new Button("⏮");
        prevBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #e4e4e7; -fx-font-size: 16px; -fx-cursor: hand;");
        prevBtn.setOnAction(e -> player.prev());

        playPauseBtn = new Button("▶");
        playPauseBtn.setMinSize(36, 36);
        playPauseBtn.setMaxSize(36, 36);
        playPauseBtn.setStyle("-fx-background-color: #1db954; -fx-text-fill: black; -fx-font-size: 15px; -fx-font-weight: 800; -fx-background-radius: 999; -fx-cursor: hand;");
        playPauseBtn.setOnAction(e -> player.togglePlayPause());

        Button nextBtn = new Button("⏭");
        nextBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #e4e4e7; -fx-font-size: 16px; -fx-cursor: hand;");
        nextBtn.setOnAction(e -> player.next());

        repeatBtn = new Button("🔁");
        repeatBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #71717a; -fx-font-size: 14px; -fx-cursor: hand;");
        repeatBtn.setOnAction(e -> player.toggleRepeat());

        btns.getChildren().addAll(shuffleBtn, prevBtn, playPauseBtn, nextBtn, repeatBtn);

        // Timeline slider
        HBox timeRow = new HBox(8);
        timeRow.setAlignment(Pos.CENTER);
        timeRow.setMaxWidth(520);

        curTimeLbl = new Label("00:00");
        curTimeLbl.setStyle("-fx-text-fill: #a1a1aa; -fx-font-size: 11px; -fx-font-family: monospace;");
        totalTimeLbl = new Label("03:35");
        totalTimeLbl.setStyle("-fx-text-fill: #a1a1aa; -fx-font-size: 11px; -fx-font-family: monospace;");

        timeSlider = new Slider(0, 100, 0);
        timeSlider.setStyle("-fx-accent: #1db954;");
        HBox.setHgrow(timeSlider, Priority.ALWAYS);
        timeSlider.setOnMouseReleased(e -> player.seek(timeSlider.getValue() / 100.0));

        timeRow.getChildren().addAll(curTimeLbl, timeSlider, totalTimeLbl);

        center.getChildren().addAll(btns, timeRow);
        getChildren().add(center);
    }

    private void buildRightSection() {
        HBox right = new HBox(12);
        right.setAlignment(Pos.CENTER_RIGHT);
        right.setPrefWidth(260);
        right.setMinWidth(220);

        // Mini animated equalizer
        miniEqualizer = new HBox(2);
        miniEqualizer.setAlignment(Pos.BOTTOM_CENTER);
        miniEqualizer.setPrefHeight(16);
        for (int i = 0; i < 5; i++) {
            Region r = new Region();
            r.setMinWidth(3);
            r.setMaxWidth(3);
            r.setPrefHeight(6 + i * 2);
            r.setStyle("-fx-background-color: #10b981; -fx-background-radius: 2;");
            eqBars[i] = r;
            miniEqualizer.getChildren().add(r);
        }

        listenersLbl = new Label("👥 4");
        listenersLbl.setStyle("-fx-text-fill: #a1a1aa; -fx-font-size: 11px;");
        Tooltip.install(listenersLbl, new Tooltip("Số người đang cùng nghe trong phòng"));

        muteBtn = new Button("🔊");
        muteBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #a1a1aa; -fx-font-size: 14px; -fx-cursor: hand;");
        muteBtn.setOnAction(e -> {
            player.toggleMute();
            muteBtn.setText(player.isMuted() ? "🔇" : "🔊");
        });

        volSlider = new Slider(0, 100, 70);
        volSlider.setPrefWidth(80);
        volSlider.setStyle("-fx-accent: #6366f1;");
        volSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            player.setVolume(newVal.doubleValue() / 100.0);
            muteBtn.setText(player.getVolume() == 0 ? "🔇" : "🔊");
        });

        Button openRoomBtn = new Button("⛶");
        openRoomBtn.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-text-fill: #e4e4e7; -fx-font-size: 14px; -fx-background-radius: 8; -fx-cursor: hand; -fx-padding: 4 8;");
        Tooltip.install(openRoomBtn, new Tooltip("Mở toàn màn hình phòng nghe nhạc"));
        openRoomBtn.setOnAction(e -> {
            if (onOpenMusicRoom != null) onOpenMusicRoom.run();
        });

        right.getChildren().addAll(miniEqualizer, listenersLbl, muteBtn, volSlider, openRoomBtn);
        getChildren().add(right);
    }

    private void setupSubscriptions() {
        player.addTrackChangeListener(t -> Platform.runLater(this::refreshTrack));
        player.addPlayStateListener(playing -> Platform.runLater(this::refreshState));
        player.addTimeUpdateListener(sec -> Platform.runLater(() -> updateTime(sec)));
        player.addRoomChangeListener(r -> Platform.runLater(this::refreshRoom));
        player.addRoomUpdateListener(() -> Platform.runLater(() -> {
            refreshRoom();
            refreshShuffleRepeat();
        }));
        player.addVisualizerListener(this::animateMiniEqualizer);
    }

    private void animateMiniEqualizer(float[] bands) {
        if (!player.isPlaying()) {
            for (Region r : eqBars) r.setPrefHeight(4);
            return;
        }
        for (int i = 0; i < eqBars.length; i++) {
            float v = bands[i * 4 % bands.length];
            eqBars[i].setPrefHeight(Math.max(4, Math.min(18, v * 20)));
        }
    }

    private void refreshTrack() {
        if (player.getCurrentYoutubeTrack() != null) {
            YoutubePlayerBridge.YoutubeTrackInfo yt = player.getCurrentYoutubeTrack();
            trackTitleLbl.setText(yt.title());
            trackArtistLbl.setText("YouTube Stream");
            totalTimeLbl.setText("LIVE");
            roomBadgeLbl.setText("▶ YouTube");
            roomBadgeLbl.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 10px; -fx-font-weight: 800;");
            miniArt.setStyle("-fx-background-color: #ef4444; -fx-background-radius: 8; -fx-cursor: hand;");
            return;
        }

        MusicTrack t = player.getCurrentTrack();
        if (t == null) return;
        trackTitleLbl.setText(t.title());
        trackArtistLbl.setText(t.artist());
        totalTimeLbl.setText(t.formattedDuration());
        if (t.isSpotify()) {
            roomBadgeLbl.setText("🟢 Spotify Stream");
            roomBadgeLbl.setStyle("-fx-text-fill: #1db954; -fx-font-size: 10px; -fx-font-weight: 800;");
            miniArt.setStyle("-fx-background-color: #1db954; -fx-background-radius: 8; -fx-cursor: hand;");
        } else if (t.isSoundCloud()) {
            roomBadgeLbl.setText("☁️ SoundCloud Stream");
            roomBadgeLbl.setStyle("-fx-text-fill: #ff5500; -fx-font-size: 10px; -fx-font-weight: 800;");
            miniArt.setStyle("-fx-background-color: linear-gradient(to bottom right, #ea580c, #f97316); -fx-background-radius: 8; -fx-cursor: hand;");
        } else {
            roomBadgeLbl.setStyle("-fx-text-fill: #10b981; -fx-font-size: 10px; -fx-font-weight: 600;");
            miniArt.setStyle("-fx-background-color: linear-gradient(to bottom right, #6366f1, #a855f7); -fx-background-radius: 8; -fx-cursor: hand;");
            refreshRoom();
        }
    }

    private void refreshRoom() {
        MusicRoom r = player.getCurrentRoom();
        if (r == null) return;
        roomBadgeLbl.setText("🟢 " + r.getName());
        listenersLbl.setText("👥 " + r.getListeners().size());
    }

    private void refreshState() {
        boolean playing = player.isPlaying();
        playPauseBtn.setText(playing ? "⏸" : "▶");
    }

    private void refreshShuffleRepeat() {
        shuffleBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: " + (player.isShuffle() ? "#1db954" : "#71717a") + "; -fx-font-size: 14px; -fx-cursor: hand;");
        repeatBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: " + (player.isRepeat() ? "#1db954" : "#71717a") + "; -fx-font-size: 14px; -fx-cursor: hand;");
    }

    private void updateTime(double currentSec) {
        MusicTrack t = player.getCurrentTrack();
        if (t == null) return;
        int m = (int) currentSec / 60;
        int s = (int) currentSec % 60;
        curTimeLbl.setText(String.format("%02d:%02d", m, s));

        if (!timeSlider.isValueChanging()) {
            double percent = (currentSec / Math.max(1, t.durationSeconds())) * 100.0;
            timeSlider.setValue(percent);
        }
    }
}
