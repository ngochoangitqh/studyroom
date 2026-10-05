package vn.studyroom;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * StudyTogether shared music room view matching Image 2 perfectly.
 * Features:
 *  - Clean, soft modern light aesthetic (#fafafa)
 *  - Header with room switcher dropdown, listener count badge, leave room button
 *  - Center stage: Large aesthetic album cover / YouTube thumbnail with shadow & rounded corners
 *  - Song title and artist
 *  - Interactive soundwave waveform progress bar with pink/rose played bars and gray unplayed bars
 *  - Player controls (Shuffle, Previous, Big Purple Play/Pause, Next, Repeat)
 *  - "Đang nghe cùng" active listeners with animated soundwaves
 *  - Right sidebar: "Danh sách phát tiếp theo" (+ Thêm bài / YouTube search) & "Gợi ý cho phòng" (+ add)
 */
public class MusicRoomView extends VBox {

    private final MusicPlayerService player;
    private final User currentUser;
    private final Runnable onInviteFriends;
    private final Runnable onLeaveRoom;
    private final Consumer<String> toastCallback;

    // Header controls
    private Label roomTitleLbl;
    private Label roomDescLbl;
    private Label listenerBadgeLbl;

    // Center Stage controls
    private ImageView ambientGlowView;
    private ImageView coverImageView;
    private Label songTitleLbl;
    private Label songArtistLbl;
    private WaveformVisualizer waveformBar;
    private Label curTimeLbl;
    private Label totalTimeLbl;
    private Button playPauseBtn;
    private Button shuffleBtn;
    private Button repeatBtn;
    private HBox listenersBox;

    // Right Sidebar controls
    private VBox upNextListBox;
    private VBox addBox;
    private TextField searchField;
    private Button addBtn;
    private VBox suggestionsBox;

    // Realtime Database Sync
    private Timeline syncTimeline;
    private long lastSyncedVersion = -1;
    private volatile boolean isSyncing = false;
    private final java.util.Set<String> pendingRoomDownloads = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Default aesthetic cover
    private static final String DEFAULT_COVER = "https://images.unsplash.com/photo-1518495973542-4542c06a5843?w=600&q=80";

    public MusicRoomView(MusicPlayerService player, User currentUser, Runnable onInviteFriends, Consumer<String> toastCallback) {
        this(player, currentUser, onInviteFriends, null, toastCallback);
    }

    public MusicRoomView(MusicPlayerService player, User currentUser, Runnable onInviteFriends, Runnable onLeaveRoom, Consumer<String> toastCallback) {
        this.player = player;
        this.currentUser = currentUser;
        this.onInviteFriends = onInviteFriends;
        this.onLeaveRoom = onLeaveRoom;
        this.toastCallback = toastCallback;

        setStyle("-fx-background-color: #fafafa;");
        setSpacing(0);
        VBox.setVgrow(this, Priority.ALWAYS);

        buildTopHeader();
        buildMainContent();

        setupSubscriptions();
        refreshAll();
        startRoomSync();

        sceneProperty().addListener((obs, oldS, newS) -> {
            if (newS == null) {
                stopRoomSync();
            } else {
                startRoomSync();
            }
        });
    }

    // =========================================================================
    // 1. TOP HEADER: Room dropdown, listener badge, leave button
    // =========================================================================
    private void buildTopHeader() {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 28, 14, 28));
        header.setStyle("-fx-background-color: #fafafa;");

        // Left: Room Title with chevron + subtitle
        VBox titleCol = new VBox(3);
        HBox titleRow = new HBox(6);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        roomTitleLbl = new Label("Đang phát cùng phòng \"Học đêm khuya\"");
        roomTitleLbl.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        Label chevron = new Label("⌵");
        chevron.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-text-fill: #64748b; -fx-cursor: hand;");

        titleRow.getChildren().addAll(roomTitleLbl, chevron);
        titleRow.setCursor(Cursor.HAND);
        titleRow.setOnMouseClicked(e -> showRoomSelectorMenu(titleRow));

        roomDescLbl = new Label("Cùng nghe nhạc, cùng tập trung 🎧");
        roomDescLbl.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");

        titleCol.getChildren().addAll(titleRow, roomDescLbl);
        HBox.setHgrow(titleCol, Priority.ALWAYS);

        // Right: Badges & Buttons
        HBox rightActions = new HBox(10);
        rightActions.setAlignment(Pos.CENTER_RIGHT);

        // Pill badge: 5 người đang nghe (soft pink/red)
        HBox badgePill = new HBox(6);
        badgePill.setAlignment(Pos.CENTER);
        badgePill.setStyle("-fx-background-color: #fff1f2; -fx-border-color: #ffe4e6; -fx-border-radius: 20; -fx-background-radius: 20; -fx-padding: 6 14;");

        Label waveIcon = new Label("📶");
        waveIcon.setStyle("-fx-font-family: 'Segoe UI Emoji', 'Segoe UI Symbol', sans-serif; -fx-font-size: 11px; -fx-text-fill: #f43f5e;");
        listenerBadgeLbl = new Label("5 người đang nghe");
        listenerBadgeLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #f43f5e;");
        badgePill.getChildren().addAll(waveIcon, listenerBadgeLbl);

        // Invite friends button
        Label inviteIcon = new Label("➕");
        inviteIcon.setStyle("-fx-font-family: 'Segoe UI Emoji', 'Segoe UI Symbol'; -fx-font-size: 11px;");
        Button inviteBtn = new Button("Mời bạn", inviteIcon);
        inviteBtn.setGraphicTextGap(6);
        inviteBtn.setStyle("-fx-font-family: 'Segoe UI', Arial, sans-serif; -fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 20; -fx-background-radius: 20; -fx-padding: 6 14; -fx-text-fill: #1e293b; -fx-font-weight: 700; -fx-font-size: 12px; -fx-cursor: hand;");
        inviteBtn.setOnMouseEntered(e -> inviteBtn.setStyle("-fx-font-family: 'Segoe UI', Arial, sans-serif; -fx-background-color: #e2e8f0; -fx-border-color: #94a3b8; -fx-border-radius: 20; -fx-background-radius: 20; -fx-padding: 6 14; -fx-text-fill: #0f172a; -fx-font-weight: 700; -fx-font-size: 12px; -fx-cursor: hand;"));
        inviteBtn.setOnMouseExited(e -> inviteBtn.setStyle("-fx-font-family: 'Segoe UI', Arial, sans-serif; -fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 20; -fx-background-radius: 20; -fx-padding: 6 14; -fx-text-fill: #1e293b; -fx-font-weight: 700; -fx-font-size: 12px; -fx-cursor: hand;"));
        inviteBtn.setOnAction(e -> {
            if (onInviteFriends != null) onInviteFriends.run();
        });

        // Leave / Switch Room button
        Label leaveIcon = new Label("🚪");
        leaveIcon.setStyle("-fx-font-family: 'Segoe UI Emoji', 'Segoe UI Symbol'; -fx-font-size: 13px;");
        Button leaveBtn = new Button("Rời phòng", leaveIcon);
        leaveBtn.setGraphicTextGap(6);
        leaveBtn.setStyle("-fx-font-family: 'Segoe UI', Arial, sans-serif; -fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 20; -fx-background-radius: 20; -fx-padding: 6 14; -fx-text-fill: #334155; -fx-font-weight: 600; -fx-font-size: 12px; -fx-cursor: hand;");
        leaveBtn.setOnMouseEntered(e -> leaveBtn.setStyle("-fx-font-family: 'Segoe UI', Arial, sans-serif; -fx-background-color: #fee2e2; -fx-border-color: #fca5a5; -fx-border-radius: 20; -fx-background-radius: 20; -fx-padding: 6 14; -fx-text-fill: #dc2626; -fx-font-weight: 600; -fx-font-size: 12px; -fx-cursor: hand;"));
        leaveBtn.setOnMouseExited(e -> leaveBtn.setStyle("-fx-font-family: 'Segoe UI', Arial, sans-serif; -fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 20; -fx-background-radius: 20; -fx-padding: 6 14; -fx-text-fill: #334155; -fx-font-weight: 600; -fx-font-size: 12px; -fx-cursor: hand;"));
        leaveBtn.setOnAction(e -> {
            stopRoomSync();
            if (onLeaveRoom != null) {
                onLeaveRoom.run();
            } else {
                showRoomSelectorMenu(leaveBtn);
            }
        });

        // Options button: •••
        Button moreBtn = new Button("•••");
        moreBtn.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 20; -fx-background-radius: 20; -fx-padding: 6 12; -fx-text-fill: #64748b; -fx-font-weight: 800; -fx-font-size: 12px; -fx-cursor: hand;");
        moreBtn.setOnMouseEntered(e -> moreBtn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 20; -fx-background-radius: 20; -fx-padding: 6 12; -fx-text-fill: #0f172a; -fx-font-weight: 800; -fx-font-size: 12px; -fx-cursor: hand;"));
        moreBtn.setOnMouseExited(e -> moreBtn.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 20; -fx-background-radius: 20; -fx-padding: 6 12; -fx-text-fill: #64748b; -fx-font-weight: 800; -fx-font-size: 12px; -fx-cursor: hand;"));
        moreBtn.setOnAction(e -> {
            if (onInviteFriends != null) onInviteFriends.run();
        });

        rightActions.getChildren().addAll(badgePill, inviteBtn, leaveBtn, moreBtn);
        header.getChildren().addAll(titleCol, rightActions);

        getChildren().add(header);
    }

    // =========================================================================
    // 2. MAIN CONTENT (Split: Left Center Player & Right Sidebar)
    // =========================================================================
    private void buildMainContent() {
        HBox body = new HBox(28);
        body.setPadding(new Insets(6, 28, 24, 28));
        VBox.setVgrow(body, Priority.ALWAYS);

        // Center / Left Player Section
        VBox centerBox = buildCenterPlayerSection();
        HBox.setHgrow(centerBox, Priority.ALWAYS);

        // Right Sidebar Section (Up next + Suggestions)
        VBox sidebarBox = buildRightSidebarSection();
        sidebarBox.setMinWidth(360);
        sidebarBox.setPrefWidth(380);
        sidebarBox.setMaxWidth(420);

        body.getChildren().addAll(centerBox, sidebarBox);
        getChildren().add(body);
    }

    // =========================================================================
    // 3. CENTER PLAYER: Album art, Title, Waveform, Controls, Listeners
    // =========================================================================
    private VBox buildCenterPlayerSection() {
        VBox center = new VBox(14);
        center.setAlignment(Pos.TOP_CENTER);
        center.setStyle("-fx-background-color: transparent;");

        // 1. Large Rounded Album Cover with Ambient Glow & Glass Highlight
        StackPane coverContainer = new StackPane();
        coverContainer.setAlignment(Pos.CENTER);
        coverContainer.setPadding(new Insets(6, 0, 12, 0));

        // Background ambient glow radiating music colors
        ambientGlowView = new ImageView();
        ambientGlowView.setFitWidth(300);
        ambientGlowView.setFitHeight(300);
        ambientGlowView.setPreserveRatio(false);
        ambientGlowView.setSmooth(true);
        ambientGlowView.setEffect(new GaussianBlur(36));
        ambientGlowView.setOpacity(0.55);

        // Foreground Artwork Card with subtle depth & glass border
        StackPane artCard = new StackPane();
        artCard.setMaxSize(310, 310);
        artCard.setPrefSize(310, 310);

        coverImageView = new ImageView();
        coverImageView.setFitWidth(310);
        coverImageView.setFitHeight(310);
        coverImageView.setPreserveRatio(false);
        coverImageView.setSmooth(true);

        Rectangle clip = new Rectangle(310, 310);
        clip.setArcWidth(28);
        clip.setArcHeight(28);
        coverImageView.setClip(clip);

        Rectangle glassBorder = new Rectangle(310, 310);
        glassBorder.setArcWidth(28);
        glassBorder.setArcHeight(28);
        glassBorder.setFill(Color.TRANSPARENT);
        glassBorder.setStroke(Color.rgb(255, 255, 255, 0.40));
        glassBorder.setStrokeWidth(1.5);
        glassBorder.setMouseTransparent(true);

        artCard.getChildren().addAll(coverImageView, glassBorder);
        artCard.setEffect(new DropShadow(BlurType.GAUSSIAN, Color.rgb(15, 23, 42, 0.18), 26, 0.10, 0, 10));

        artCard.setOnMouseEntered(e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(180), artCard);
            st.setToX(1.02);
            st.setToY(1.02);
            st.play();
        });
        artCard.setOnMouseExited(e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(180), artCard);
            st.setToX(1.0);
            st.setToY(1.0);
            st.play();
        });

        coverContainer.getChildren().addAll(ambientGlowView, artCard);

        // 2. Song Title & Artist
        VBox songMeta = new VBox(4);
        songMeta.setAlignment(Pos.CENTER);

        songTitleLbl = new Label("Lofi Study Beats");
        songTitleLbl.setStyle("-fx-font-family: 'Segoe UI', Arial, sans-serif; -fx-font-size: 20px; -fx-font-weight: 800; -fx-text-fill: #0f172a; -fx-alignment: center; -fx-text-alignment: center;");
        songTitleLbl.setWrapText(true);
        songTitleLbl.setMaxWidth(560);

        songArtistLbl = new Label("Chill Collective");
        songArtistLbl.setStyle("-fx-font-family: 'Segoe UI', Arial, sans-serif; -fx-font-size: 14px; -fx-font-weight: 500; -fx-text-fill: #64748b; -fx-alignment: center;");

        songMeta.getChildren().addAll(songTitleLbl, songArtistLbl);

        // 3. Interactive Waveform Progress Bar
        VBox waveBox = new VBox(6);
        waveBox.setAlignment(Pos.CENTER);
        waveBox.setMaxWidth(480);

        waveformBar = new WaveformVisualizer(44, (ratio) -> {
            player.seek(ratio);
        });

        HBox timeRow = new HBox();
        timeRow.setAlignment(Pos.CENTER);
        curTimeLbl = new Label("0:00");
        curTimeLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");
        Region timeSpacer = new Region();
        HBox.setHgrow(timeSpacer, Priority.ALWAYS);
        totalTimeLbl = new Label("3:56");
        totalTimeLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");
        timeRow.getChildren().addAll(curTimeLbl, timeSpacer, totalTimeLbl);

        waveBox.getChildren().addAll(waveformBar, timeRow);

        // 4. Primary Playback Controls
        HBox controls = new HBox(22);
        controls.setAlignment(Pos.CENTER);

        shuffleBtn = createIconButton("🔀", 15, e -> player.toggleShuffle());
        Button prevBtn = createIconButton("⏮", 18, e -> player.prev());

        // Big vibrant Indigo/Purple play button
        playPauseBtn = new Button("▶");
        playPauseBtn.setPrefSize(56, 56);
        playPauseBtn.setMinSize(56, 56);
        playPauseBtn.setMaxSize(56, 56);
        playPauseBtn.setStyle("-fx-background-color: #4f46e5; -fx-background-radius: 999; -fx-text-fill: white; -fx-font-size: 20px; -fx-cursor: hand; -fx-effect: dropshadow(gaussian, rgba(79,70,229,0.35), 14, 0.1, 0, 4);");
        playPauseBtn.setOnMouseEntered(e -> playPauseBtn.setStyle("-fx-background-color: #4338ca; -fx-background-radius: 999; -fx-text-fill: white; -fx-font-size: 20px; -fx-cursor: hand; -fx-effect: dropshadow(gaussian, rgba(79,70,229,0.45), 16, 0.15, 0, 6);"));
        playPauseBtn.setOnAction(e -> {
            if (player.getCurrentRoom() == null || player.getCurrentRoom().getPlaylist().isEmpty()) {
                toggleAddSearchBox();
                if (toastCallback != null) toastCallback.accept("💡 Danh sách đang trống! Hãy thêm nhạc YouTube ở góc phải nhé.");
            } else {
                player.togglePlayPause();
            }
        });

        Button nextBtn = createIconButton("⏭", 18, e -> player.next());
        repeatBtn = createIconButton("🔁", 15, e -> player.toggleRepeat());

        controls.getChildren().addAll(shuffleBtn, prevBtn, playPauseBtn, nextBtn, repeatBtn);

        // 5. "Đang nghe cùng" (Listening Together) Section
        VBox listenersSection = new VBox(8);
        listenersSection.setAlignment(Pos.CENTER);
        listenersSection.setPadding(new Insets(8, 0, 0, 0));

        Label listenersHeader = new Label("Đang nghe cùng");
        listenersHeader.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #64748b;");

        listenersBox = new HBox(22);
        listenersBox.setAlignment(Pos.CENTER);

        listenersSection.getChildren().addAll(listenersHeader, listenersBox);

        center.getChildren().addAll(coverContainer, songMeta, waveBox, controls, listenersSection);
        return center;
    }

    private Button createIconButton(String icon, int fontSize, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button b = new Button(icon);
        b.setStyle("-fx-background-color: transparent; -fx-text-fill: #64748b; -fx-font-size: " + fontSize + "px; -fx-cursor: hand; -fx-padding: 6;");
        b.setOnMouseEntered(e -> b.setStyle("-fx-background-color: rgba(0,0,0,0.05); -fx-background-radius: 999; -fx-text-fill: #0f172a; -fx-font-size: " + fontSize + "px; -fx-cursor: hand; -fx-padding: 6;"));
        b.setOnMouseExited(e -> b.setStyle("-fx-background-color: transparent; -fx-text-fill: #64748b; -fx-font-size: " + fontSize + "px; -fx-cursor: hand; -fx-padding: 6;"));
        b.setOnAction(handler);
        return b;
    }

    // =========================================================================
    // 4. RIGHT SIDEBAR: Up Next List + Suggestions
    // =========================================================================
    private VBox buildRightSidebarSection() {
        VBox sidebar = new VBox(16);
        sidebar.setAlignment(Pos.TOP_CENTER);
        VBox.setVgrow(sidebar, Priority.ALWAYS);

        // Card 1: Danh sách phát tiếp theo
        VBox cardUpNext = new VBox(10);
        cardUpNext.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 16; -fx-padding: 16; -fx-border-color: #f1f5f9; -fx-border-radius: 16; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.03), 8, 0, 0, 2);");
        VBox.setVgrow(cardUpNext, Priority.ALWAYS);

        HBox upNextHeader = new HBox(8);
        upNextHeader.setAlignment(Pos.CENTER_LEFT);
        Label upNextTitle = new Label("Danh sách phát tiếp theo");
        upNextTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: #0f172a;");
        Region sp1 = new Region();
        HBox.setHgrow(sp1, Priority.ALWAYS);

        Button addTrackBtn = new Button("＋ Thêm bài");
        addTrackBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 12px; -fx-padding: 6 14; -fx-background-radius: 20; -fx-cursor: hand;");
        addTrackBtn.setOnMouseEntered(e -> addTrackBtn.setStyle("-fx-background-color: #4338ca; -fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 12px; -fx-padding: 6 14; -fx-background-radius: 20; -fx-cursor: hand;"));
        addTrackBtn.setOnMouseExited(e -> addTrackBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 12px; -fx-padding: 6 14; -fx-background-radius: 20; -fx-cursor: hand;"));
        addTrackBtn.setOnAction(e -> toggleAddSearchBox());

        upNextHeader.getChildren().addAll(upNextTitle, sp1, addTrackBtn);

        // Expandable Search / Add Box
        addBox = new VBox(8);
        addBox.setVisible(false);
        addBox.setManaged(false);
        addBox.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 10; -fx-padding: 10; -fx-border-color: #e2e8f0; -fx-border-radius: 10;");

        HBox inputRow = new HBox(6);
        inputRow.setAlignment(Pos.CENTER_LEFT);

        searchField = new TextField();
        searchField.setPromptText("🔍 Tên bài hát hoặc dán link YouTube...");
        searchField.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 14; -fx-background-radius: 14; -fx-padding: 6 12; -fx-font-size: 12px;");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setOnAction(e -> handleAddTrack());

        addBtn = new Button("Thêm");
        addBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 11px; -fx-padding: 6 12; -fx-background-radius: 14; -fx-cursor: hand;");
        addBtn.setOnAction(e -> handleAddTrack());

        inputRow.getChildren().addAll(searchField, addBtn);
        Label addTip = new Label("💡 Nhập tên bài hoặc dán link YouTube để phát nhạc thật và lấy ảnh thumbnail!");
        addTip.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");
        addBox.getChildren().addAll(inputRow, addTip);

        // Scrollable Track items list
        upNextListBox = new VBox(4);
        ScrollPane upNextScroll = new ScrollPane(upNextListBox);
        upNextScroll.setFitToWidth(true);
        upNextScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
        VBox.setVgrow(upNextScroll, Priority.ALWAYS);

        cardUpNext.getChildren().addAll(upNextHeader, addBox, upNextScroll);

        // Card 2: Quản lý Playlist & Cài đặt phát
        VBox cardPlaylistInfo = new VBox(10);
        cardPlaylistInfo.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 16; -fx-padding: 14 16; -fx-border-color: #f1f5f9; -fx-border-radius: 16; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.03), 8, 0, 0, 2);");

        HBox plHeader = new HBox(8);
        plHeader.setAlignment(Pos.CENTER_LEFT);
        Label plTitle = new Label("⚙ Quản lý Playlist");
        plTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #0f172a;");
        Region sp2 = new Region();
        HBox.setHgrow(sp2, Priority.ALWAYS);

        Button clearBtn = new Button("🗑 Xóa hết");
        clearBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #ef4444; -fx-font-size: 11px; -fx-font-weight: 600; -fx-cursor: hand; -fx-padding: 3 8; -fx-border-color: #fecaca; -fx-border-radius: 8; -fx-background-radius: 8;");
        clearBtn.setOnMouseEntered(e -> clearBtn.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #dc2626; -fx-font-size: 11px; -fx-font-weight: 600; -fx-cursor: hand; -fx-padding: 3 8; -fx-border-color: #fca5a5; -fx-border-radius: 8; -fx-background-radius: 8;"));
        clearBtn.setOnMouseExited(e -> clearBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #ef4444; -fx-font-size: 11px; -fx-font-weight: 600; -fx-cursor: hand; -fx-padding: 3 8; -fx-border-color: #fecaca; -fx-border-radius: 8; -fx-background-radius: 8;"));
        clearBtn.setOnAction(e -> handleClearPlaylist());

        plHeader.getChildren().addAll(plTitle, sp2, clearBtn);

        VBox infoContent = new VBox(6);
        Label feature1 = new Label("✓ Tự động chuyển tiếp bài tiếp theo khi hết bài");
        feature1.setStyle("-fx-font-size: 11px; -fx-text-fill: #10b981; -fx-font-weight: 600;");

        Label feature2 = new Label("✓ Playlist được tự động lưu trữ trên máy tính");
        feature2.setStyle("-fx-font-size: 11px; -fx-text-fill: #6366f1; -fx-font-weight: 600;");

        Label tipLbl = new Label("💡 Bạn có thể dán trực tiếp link YouTube hoặc gõ tên bài hát để tìm kiếm bài yêu thích!");
        tipLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");
        tipLbl.setWrapText(true);

        infoContent.getChildren().addAll(feature1, feature2, tipLbl);
        cardPlaylistInfo.getChildren().addAll(plHeader, infoContent);

        sidebar.getChildren().addAll(cardUpNext, cardPlaylistInfo);
        return sidebar;
    }

    private void toggleAddSearchBox() {
        boolean nextState = !addBox.isVisible();
        addBox.setVisible(nextState);
        addBox.setManaged(nextState);
        if (nextState) {
            searchField.requestFocus();
        }
    }

    private void handleAddTrack() {
        String input = searchField.getText();
        if (input == null || input.isBlank()) {
            if (toastCallback != null) toastCallback.accept("💡 Vui lòng nhập tên bài hát hoặc dán link YouTube!");
            return;
        }
        String query = input.trim();
        addBtn.setDisable(true);
        addBtn.setText("Đang tải...");
        if (toastCallback != null) toastCallback.accept("⏳ Đang tải âm thanh và ảnh thumbnail...");

        Thread.ofVirtual().start(() -> {
            YoutubePlayerBridge.YoutubeTrackInfo info = player.loadYoutubeUrl(query);
            Platform.runLater(() -> {
                addBtn.setDisable(false);
                addBtn.setText("Thêm");
                if (info != null) {
                    searchField.clear();
                    addBox.setVisible(false);
                    addBox.setManaged(false);
                    if (toastCallback != null) toastCallback.accept("🎵 Đã thêm vào playlist: " + info.title());
                } else {
                    if (toastCallback != null) toastCallback.accept("Không tìm thấy bài hát trên YouTube. Vui lòng kiểm tra lại link.");
                }
            });
        });
    }

    private void handleClearPlaylist() {
        if (player.getCurrentRoom() == null || player.getCurrentRoom().getPlaylist().isEmpty()) {
            if (toastCallback != null) toastCallback.accept("Danh sách phát hiện đang trống.");
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Xác nhận xóa playlist");
        alert.setHeaderText("Bạn có chắc chắn muốn xóa toàn bộ danh sách phát?");
        alert.setContentText("Tất cả bài hát trong playlist sẽ bị xóa khỏi danh sách.");
        alert.showAndWait().ifPresent(res -> {
            if (res == ButtonType.OK) {
                player.clearUserPlaylist();
                if (toastCallback != null) toastCallback.accept("Đã xóa sạch playlist của bạn.");
            }
        });
    }

    // =========================================================================
    // 6. EVENT SUBSCRIPTIONS & DATA BINDINGS
    // =========================================================================
    private void setupSubscriptions() {
        player.addTrackChangeListener(t -> Platform.runLater(this::refreshCurrentTrack));
        player.addPlayStateListener(p -> Platform.runLater(this::refreshPlayState));
        player.addTimeUpdateListener(s -> Platform.runLater(() -> refreshTime(s)));
        player.addVisualizerListener(bars -> Platform.runLater(() -> waveformBar.updateAudioBands(bars)));
        player.addRoomChangeListener(r -> Platform.runLater(() -> {
            lastSyncedVersion = -1;
            refreshAll();
            checkRoomSyncFromDatabase();
        }));
        player.addRoomUpdateListener(() -> Platform.runLater(this::refreshAll));
    }

    private void refreshAll() {
        refreshRoomInfo();
        refreshCurrentTrack();
        refreshPlayState();
        refreshUpNextList();
        refreshListeners();
    }

    // =========================================================================
    // 7. REALTIME DATABASE ROOM SYNCHRONIZATION
    // =========================================================================
    public void startRoomSync() {
        stopRoomSync();
        checkRoomSyncFromDatabase();
        syncTimeline = new Timeline(new KeyFrame(Duration.millis(1000), e -> checkRoomSyncFromDatabase()));
        syncTimeline.setCycleCount(Animation.INDEFINITE);
        syncTimeline.play();
    }

    public void stopRoomSync() {
        if (syncTimeline != null) {
            syncTimeline.stop();
            syncTimeline = null;
        }
    }

    private void checkRoomSyncFromDatabase() {
        if (isSyncing) return;
        MusicPresenceRepository repo = player.getMusicPresenceRepository();
        MusicRoom room = player.getCurrentRoom();
        if (repo == null || room == null) return;
        String roomId = room.getId();

        isSyncing = true;
        Thread.ofVirtual().start(() -> {
            try {
                // 1. Sync Room Playlist
                List<MusicPresenceRepository.RoomPlaylistItem> dbPlaylist = repo.getRoomPlaylist(roomId);
                if ((dbPlaylist == null || dbPlaylist.isEmpty()) && !room.getPlaylist().isEmpty()) {
                    repo.syncAllTracksToRoomPlaylist(roomId, new ArrayList<>(room.getPlaylist()), currentUser.username());
                    dbPlaylist = repo.getRoomPlaylist(roomId);
                }
                if (dbPlaylist != null && !dbPlaylist.isEmpty()) {
                    final var finalDb = dbPlaylist;
                    Platform.runLater(() -> syncLocalPlaylist(finalDb));
                }

                // 2. Sync Room Playback State (Track, Play/Pause, Seek)
                MusicPresenceRepository.RoomSyncState state = repo.getRoomSyncState(roomId);
                if (state != null) {
                    Platform.runLater(() -> applyRoomSyncState(state));
                } else if (!room.getPlaylist().isEmpty()) {
                    player.syncRoomStateToDatabase();
                }
            } catch (Exception ignored) {
            } finally {
                isSyncing = false;
            }
        });
    }

    private void syncLocalPlaylist(List<MusicPresenceRepository.RoomPlaylistItem> dbPlaylist) {
        MusicRoom room = player.getCurrentRoom();
        if (room == null) return;
        List<MusicTrack> currentList = room.getPlaylist();
        boolean changed = false;

        for (MusicPresenceRepository.RoomPlaylistItem item : dbPlaylist) {
            boolean exists = currentList.stream().anyMatch(t -> t.id().equals(item.trackId()));
            if (!exists) {
                File localAudio = null;
                if (item.audioPath() != null && !item.audioPath().isBlank()) {
                    File f = new File(item.audioPath());
                    if (f.exists() && f.isFile() && f.length() > 1024) localAudio = f;
                }
                if (localAudio == null && item.trackId() != null && item.trackId().startsWith("yt-")) {
                    String vId = item.trackId().substring(3);
                    File audioDir = new File(System.getProperty("user.dir"), ".cache/audio");
                    File[] matches = audioDir.listFiles((dir, name) -> name.startsWith(vId + ".") && name.length() > 1024);
                    if (matches != null && matches.length > 0) localAudio = matches[0];
                }
                String resolvedPath = (localAudio != null) ? localAudio.getAbsolutePath() : item.audioPath();

                MusicTrack newTrk = new MusicTrack(
                    item.trackId(),
                    item.title(),
                    item.artist(),
                    "YouTube Audio",
                    item.durationSeconds(),
                    "YOUTUBE",
                    "linear-gradient(to bottom right, #f43f5e, #fb7185)",
                    "YouTube Audio",
                    null, null, null,
                    item.thumbnailUrl(),
                    resolvedPath
                );
                currentList.add(newTrk);
                changed = true;
            }
        }

        if (changed) {
            player.saveUserPlaylist();
            refreshUpNextList();
        }
    }

    private void applyRoomSyncState(MusicPresenceRepository.RoomSyncState state) {
        if (state == null) return;
        MusicRoom room = player.getCurrentRoom();
        if (room == null || !room.getId().equals(state.roomId())) return;

        // If updated by ourselves, we already have our state
        if (state.lastUpdatedBy() != null && state.lastUpdatedBy().equalsIgnoreCase(currentUser.username())) {
            lastSyncedVersion = state.version();
            return;
        }

        // Immediately update Center Stage visual information so user never sees a blank screen
        if (state.currentTrackTitle() != null && !state.currentTrackTitle().isBlank()) {
            MusicTrack cur = player.getCurrentTrack();
            if (cur == null || !cur.id().equals(state.currentTrackId())) {
                songTitleLbl.setText(state.currentTrackTitle());
                songArtistLbl.setText(state.currentTrackArtist() != null ? state.currentTrackArtist() : "YouTube");
                if (state.currentTrackThumb() != null && !state.currentTrackThumb().isBlank()) {
                    try {
                        coverImageView.setImage(new Image(state.currentTrackThumb(), true));
                    } catch (Exception ignored) {}
                }
                int dur = state.currentTrackDuration() > 0 ? state.currentTrackDuration() : 210;
                totalTimeLbl.setText(String.format("%d:%02d", dur / 60, dur % 60));
                playPauseBtn.setText(state.isPlaying() ? "⏸" : "▶");
            }
        }

        if (state.version() <= lastSyncedVersion) {
            return;
        }

        lastSyncedVersion = state.version();
        MusicTrack cur = player.getCurrentTrack();

        // 1. Did the track change?
        if (state.currentTrackId() != null && !state.currentTrackId().isBlank()
                && (cur == null || !cur.id().equals(state.currentTrackId()))) {

            MusicTrack found = null;
            for (MusicTrack t : room.getPlaylist()) {
                if (t.id().equals(state.currentTrackId())) {
                    found = t;
                    break;
                }
            }

            if (found == null) {
                found = new MusicTrack(
                    state.currentTrackId(),
                    state.currentTrackTitle(),
                    state.currentTrackArtist() != null ? state.currentTrackArtist() : "YouTube",
                    "YouTube Audio",
                    state.currentTrackDuration() > 0 ? state.currentTrackDuration() : 210,
                    "YOUTUBE",
                    "linear-gradient(to bottom right, #f43f5e, #fb7185)",
                    "YouTube Audio",
                    null, null, null,
                    state.currentTrackThumb(),
                    null
                );
                room.getPlaylist().add(found);
                player.saveUserPlaylist();
                refreshUpNextList();
            }

            File localAudio = null;
            if (found.widgetSrc() != null && !found.widgetSrc().isBlank()) {
                File f = new File(found.widgetSrc());
                if (f.exists() && f.isFile() && f.length() > 1024) localAudio = f;
            }
            if (localAudio == null && state.currentTrackId().startsWith("yt-")) {
                String vId = state.currentTrackId().substring(3);
                File audioDir = new File(System.getProperty("user.dir"), ".cache/audio");
                File[] matches = audioDir.listFiles((dir, name) -> name.startsWith(vId + ".") && name.length() > 1024);
                if (matches != null && matches.length > 0) {
                    localAudio = matches[0];
                    found = new MusicTrack(
                        found.id(), found.title(), found.artist(), found.album(),
                        found.durationSeconds(), found.soundType(), found.coverGradient(),
                        found.genre(), null, null, null, found.thumbnailUrl(),
                        localAudio.getAbsolutePath()
                    );
                    for (int i = 0; i < room.getPlaylist().size(); i++) {
                        if (room.getPlaylist().get(i).id().equals(found.id())) {
                            room.getPlaylist().set(i, found);
                            break;
                        }
                    }
                }
            }

            double targetPos = state.positionSeconds();

            if (localAudio != null) {
                player.applyRemoteTrack(state.currentTrackId(), state.isPlaying(), targetPos);
                refreshCurrentTrack();
            } else {
                if (!pendingRoomDownloads.add(state.currentTrackId())) {
                    return; // Already downloading this track in background
                }
                String dlQuery;
                if (state.currentTrackId().startsWith("yt-")) {
                    dlQuery = "https://www.youtube.com/watch?v=" + state.currentTrackId().substring(3);
                } else if (state.currentTrackQuery() != null && !state.currentTrackQuery().isBlank()
                        && !state.currentTrackQuery().contains(":\\") && !state.currentTrackQuery().contains("/")) {
                    dlQuery = state.currentTrackQuery();
                } else {
                    dlQuery = state.currentTrackTitle() + " " + (state.currentTrackArtist() != null ? state.currentTrackArtist() : "");
                }

                final String trackToDownload = state.currentTrackId();
                Thread.ofVirtual().start(() -> {
                    try {
                        YoutubeAudioService.DownloadedTrack dt = YoutubeAudioService.downloadAudio(dlQuery);
                        if (dt != null) {
                            Platform.runLater(() -> {
                                MusicTrack downloadedTrk = new MusicTrack(
                                    "yt-" + dt.videoId(),
                                    dt.title(),
                                    dt.artist(),
                                    "YouTube Audio",
                                    dt.durationSeconds(),
                                    "YOUTUBE",
                                    "linear-gradient(to bottom right, #f43f5e, #fb7185)",
                                    "YouTube Audio",
                                    null, null, null,
                                    dt.thumbnailUrl(),
                                    dt.audioFile().getAbsolutePath()
                                );
                                boolean replaced = false;
                                for (int i = 0; i < room.getPlaylist().size(); i++) {
                                    if (room.getPlaylist().get(i).id().equals(trackToDownload)
                                            || room.getPlaylist().get(i).id().equals(downloadedTrk.id())) {
                                        room.getPlaylist().set(i, downloadedTrk);
                                        replaced = true;
                                        break;
                                    }
                                }
                                if (!replaced) {
                                    room.getPlaylist().add(downloadedTrk);
                                }
                                player.saveUserPlaylist();
                                refreshUpNextList();
                                player.applyRemoteTrack(downloadedTrk.id(), state.isPlaying(), targetPos);
                                refreshCurrentTrack();
                            });
                        }
                    } finally {
                        pendingRoomDownloads.remove(trackToDownload);
                    }
                });
            }
            return;
        }

        // 2. Did Play / Pause state change?
        if (state.isPlaying() != player.isPlaying()) {
            if (state.isPlaying()) {
                player.applyRemotePlay(state.positionSeconds());
            } else {
                player.applyRemotePause();
            }
            refreshPlayState();
            return;
        }

        // 3. What if state.isPlaying is true, but activeMediaPlayer is null?
        if (state.isPlaying() && player.getActiveMediaPlayer() == null && cur != null) {
            player.applyRemotePlay(state.positionSeconds());
            return;
        }

        // 4. Did seek position change significantly?
        if (Math.abs(state.positionSeconds() - player.getCurrentPositionSeconds()) > 4.0) {
            player.applyRemoteSeek(state.positionSeconds());
        }
    }

    private void refreshRoomInfo() {
        MusicRoom room = player.getCurrentRoom();
        if (room != null) {
            roomTitleLbl.setText("Đang phát cùng phòng \"" + room.getName() + "\"");
            roomDescLbl.setText(room.getDescription() != null ? room.getDescription() : "Cùng nghe nhạc, cùng tập trung 🎧");
            int listenerCount = room.getListeners().size();
            listenerBadgeLbl.setText(listenerCount + " người đang nghe");
        }
    }

    private void refreshCurrentTrack() {
        MusicTrack t = player.getCurrentTrack();
        if (t != null) {
            songTitleLbl.setText(t.title());
            songArtistLbl.setText(t.artist() != null ? t.artist() : "YouTube Audio");
            totalTimeLbl.setText(t.formattedDuration());

            String coverUrl = t.thumbnailUrl();
            if (coverUrl == null || coverUrl.isBlank()) {
                coverUrl = DEFAULT_COVER;
            }
            setCrispCoverImage(coverUrl);
        } else {
            songTitleLbl.setText("Chưa có bài hát nào trong playlist");
            songArtistLbl.setText("Bấm \"＋ Thêm bài\" ở bên phải để thêm nhạc YouTube");
            curTimeLbl.setText("0:00");
            totalTimeLbl.setText("0:00");
            waveformBar.setProgress(0.0);
            setCrispCoverImage(DEFAULT_COVER);
        }
        refreshUpNextList();
    }

    private void setCrispCoverImage(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            rawUrl = DEFAULT_COVER;
        }

        // Try maxresdefault.jpg first for YouTube URLs if they were pointing to hqdefault
        String primaryUrl = rawUrl;
        if (primaryUrl.contains("img.youtube.com/vi/") && primaryUrl.contains("/hqdefault.jpg")) {
            primaryUrl = primaryUrl.replace("/hqdefault.jpg", "/maxresdefault.jpg");
        }

        loadAndApplyCoverImage(primaryUrl, rawUrl);
    }

    private void loadAndApplyCoverImage(String url, String fallbackUrl) {
        try {
            Image img = new Image(url, true);
            img.errorProperty().addListener((obs, oldErr, isErr) -> {
                if (Boolean.TRUE.equals(isErr)) {
                    if (fallbackUrl != null && !fallbackUrl.equals(url)) {
                        Platform.runLater(() -> loadAndApplyCoverImage(fallbackUrl, DEFAULT_COVER));
                    } else if (!url.equals(DEFAULT_COVER)) {
                        Platform.runLater(() -> loadAndApplyCoverImage(DEFAULT_COVER, null));
                    }
                }
            });

            Runnable updateView = () -> {
                if (img.isError()) return;
                applyCrispImage(coverImageView, img, url);
                if (ambientGlowView != null) {
                    applyCrispImage(ambientGlowView, img, url);
                }
            };

            if (img.getProgress() >= 1.0 && img.getWidth() > 0) {
                updateView.run();
            } else {
                img.progressProperty().addListener((obs, oldProg, newProg) -> {
                    if (newProg.doubleValue() >= 1.0 && img.getWidth() > 0) {
                        Platform.runLater(updateView);
                    }
                });
            }

            coverImageView.setImage(img);
            if (ambientGlowView != null) {
                ambientGlowView.setImage(img);
            }
        } catch (Exception ex) {
            try {
                Image defImg = new Image(DEFAULT_COVER, true);
                coverImageView.setImage(defImg);
                if (ambientGlowView != null) ambientGlowView.setImage(defImg);
            } catch (Exception ignored) {}
        }
    }

    private void applyCrispImage(ImageView iv, Image img, String url) {
        if (iv == null || img == null) return;
        iv.setImage(img);

        double w = img.getWidth();
        double h = img.getHeight();
        if (w <= 0 || h <= 0) return;

        // Detect if this is a 4:3 YouTube thumbnail (hqdefault.jpg 480x360 has 45px black letterbox bars top & bottom)
        boolean isLetterboxedHq = url.contains("/hqdefault.jpg") || (Math.abs(w / h - 4.0 / 3.0) < 0.05 && w <= 480);
        if (isLetterboxedHq) {
            // Actual 16:9 video content occupies the middle 75% height (e.g. 270px on 360px height)
            double contentH = h * 0.75;
            double contentY = (h - contentH) / 2.0;
            double squareSize = Math.min(w, contentH);
            double cropX = (w - squareSize) / 2.0;
            iv.setViewport(new Rectangle2D(cropX, contentY, squareSize, squareSize));
        } else {
            // High-res 16:9 (like maxresdefault 1280x720) or standard aspect ratio: center square crop
            double squareSize = Math.min(w, h);
            double cropX = (w - squareSize) / 2.0;
            double cropY = (h - squareSize) / 2.0;
            iv.setViewport(new Rectangle2D(cropX, cropY, squareSize, squareSize));
        }
    }

    private void refreshPlayState() {
        boolean playing = player.isPlaying();
        playPauseBtn.setText(playing ? "⏸" : "▶");
        shuffleBtn.setStyle(player.isShuffle() ? "-fx-background-color: transparent; -fx-text-fill: #4f46e5; -fx-font-size: 15px; -fx-cursor: hand;" : "-fx-background-color: transparent; -fx-text-fill: #64748b; -fx-font-size: 15px; -fx-cursor: hand;" );
        repeatBtn.setStyle(player.isRepeat() ? "-fx-background-color: transparent; -fx-text-fill: #4f46e5; -fx-font-size: 15px; -fx-cursor: hand;" : "-fx-background-color: transparent; -fx-text-fill: #64748b; -fx-font-size: 15px; -fx-cursor: hand;" );
    }

    private void refreshTime(double curSec) {
        MusicTrack t = player.getCurrentTrack();
        if (t == null) return;
        int m = (int)curSec / 60;
        int s = (int)curSec % 60;
        curTimeLbl.setText(String.format("%d:%02d", m, s));

        double ratio = (t.durationSeconds() > 0) ? (curSec / t.durationSeconds()) : 0.0;
        waveformBar.setProgress(ratio);
    }

    private void refreshUpNextList() {
        upNextListBox.getChildren().clear();
        MusicRoom room = player.getCurrentRoom();
        if (room == null || room.getPlaylist().isEmpty()) {
            VBox emptyBox = new VBox(10);
            emptyBox.setAlignment(Pos.CENTER);
            emptyBox.setPadding(new Insets(32, 16, 32, 16));

            Label emptyIcon = new Label("🎵");
            emptyIcon.setStyle("-fx-font-size: 32px;");

            Label emptyTitle = new Label("Playlist của bạn đang trống");
            emptyTitle.setStyle("-fx-text-fill: #0f172a; -fx-font-weight: 700; -fx-font-size: 13px;");

            Label emptySub = new Label("Dán link hoặc tìm tên bài hát trên YouTube để thêm vào danh sách phát cá nhân của bạn.");
            emptySub.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px; -fx-text-alignment: center;");
            emptySub.setWrapText(true);

            Button quickAddBtn = new Button("＋ Thêm bài hát ngay");
            quickAddBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 11px; -fx-background-radius: 16; -fx-padding: 6 16; -fx-cursor: hand;");
            quickAddBtn.setOnAction(e -> {
                addBox.setVisible(true);
                addBox.setManaged(true);
                searchField.requestFocus();
            });

            emptyBox.getChildren().addAll(emptyIcon, emptyTitle, emptySub, quickAddBtn);
            upNextListBox.getChildren().add(emptyBox);
            return;
        }

        List<MusicTrack> tracks = room.getPlaylist();
        int activeIdx = player.getCurrentTrackIndex();

        for (int i = 0; i < tracks.size(); i++) {
            final int index = i;
            MusicTrack trk = tracks.get(i);
            boolean isActive = (i == activeIdx);

            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(6, 8, 6, 8));
            row.setStyle(isActive ? "-fx-background-color: #eef2ff; -fx-background-radius: 10;" : "-fx-background-color: transparent; -fx-background-radius: 10;");
            row.setCursor(Cursor.HAND);

            row.setOnMouseEntered(e -> {
                if (!isActive) row.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 10;");
            });
            row.setOnMouseExited(e -> {
                if (!isActive) row.setStyle("-fx-background-color: transparent; -fx-background-radius: 10;");
            });

            // Index or Playing indicator
            Label idxLbl = new Label(isActive ? "▶" : String.valueOf(i + 1));
            idxLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: " + (isActive ? "#4f46e5;" : "#94a3b8;") + " -fx-min-width: 16px;");

            // Thumbnail
            ImageView iv = new ImageView();
            iv.setFitWidth(38);
            iv.setFitHeight(38);
            iv.setPreserveRatio(false);
            iv.setSmooth(true);
            Rectangle clip = new Rectangle(38, 38);
            clip.setArcWidth(8);
            clip.setArcHeight(8);
            iv.setClip(clip);

            String th = (trk.thumbnailUrl() != null && !trk.thumbnailUrl().isBlank()) ? trk.thumbnailUrl() : DEFAULT_COVER;
            try {
                Image img = new Image(th, true);
                iv.setImage(img);
                Runnable cropUpNext = () -> applyCrispImage(iv, img, th);
                if (img.getProgress() >= 1.0 && img.getWidth() > 0) {
                    cropUpNext.run();
                } else {
                    img.progressProperty().addListener((obs, oldP, newP) -> {
                        if (newP.doubleValue() >= 1.0 && img.getWidth() > 0) {
                            Platform.runLater(cropUpNext);
                        }
                    });
                }
            } catch (Exception ignored) {}

            // Title & Artist
            VBox meta = new VBox(2);
            Label tLbl = new Label(trk.title());
            tLbl.setStyle("-fx-font-family: 'Segoe UI', Arial, sans-serif; -fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: " + (isActive ? "#4f46e5;" : "#0f172a;"));
            tLbl.setMaxWidth(160);
            Label aLbl = new Label(trk.artist());
            aLbl.setStyle("-fx-font-family: 'Segoe UI', Arial, sans-serif; -fx-font-size: 11px; -fx-text-fill: #64748b;");
            aLbl.setMaxWidth(160);
            meta.getChildren().addAll(tLbl, aLbl);
            HBox.setHgrow(meta, Priority.ALWAYS);

            // Duration
            Label dLbl = new Label(trk.formattedDuration());
            dLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #94a3b8;");

            // Nút Xóa bài (✕)
            Button delBtn = new Button("✕");
            delBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #94a3b8; -fx-font-size: 12px; -fx-font-weight: 700; -fx-cursor: hand; -fx-padding: 3 6; -fx-background-radius: 6;");
            delBtn.setOnMouseEntered(e -> delBtn.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #ef4444; -fx-font-size: 12px; -fx-font-weight: 700; -fx-cursor: hand; -fx-padding: 3 6; -fx-background-radius: 6;"));
            delBtn.setOnMouseExited(e -> delBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #94a3b8; -fx-font-size: 12px; -fx-font-weight: 700; -fx-cursor: hand; -fx-padding: 3 6; -fx-background-radius: 6;"));
            delBtn.setTooltip(new Tooltip("Xóa khỏi playlist"));
            delBtn.setOnAction(e -> {
                e.consume();
                player.removeTrack(index);
                if (toastCallback != null) toastCallback.accept("Đã xóa khỏi playlist: " + trk.title());
            });

            row.getChildren().addAll(idxLbl, iv, meta, dLbl, delBtn);
            row.setOnMouseClicked(e -> {
                if (e.getTarget() != delBtn) {
                    player.selectTrack(index);
                }
            });

            upNextListBox.getChildren().add(row);
        }
    }

    private void refreshListeners() {
        listenersBox.getChildren().clear();
        MusicRoom room = player.getCurrentRoom();
        if (room == null) return;

        List<String> list = new ArrayList<>(room.getListeners());
        String myName = (currentUser != null && currentUser.displayName() != null && !currentUser.displayName().isBlank())
                ? currentUser.displayName() : (currentUser != null ? currentUser.username() : "Bạn");
        if (!list.contains(myName) && currentUser != null && !list.contains(currentUser.username())) {
            list.add(0, myName);
        }

        String[] colors = {"#f43f5e", "#6366f1", "#06b6d4", "#10b981", "#8b5cf6"};
        int count = Math.min(list.size(), 3);
        for (int i = 0; i < count; i++) {
            String name = list.get(i);
            String color = colors[i % colors.length];
            listenersBox.getChildren().add(createListenerAvatar(name, color, "ııl|ıı"));
        }
        if (list.size() > 3) {
            listenersBox.getChildren().add(createOverlappingAvatar("+" + (list.size() - 3)));
        }

        // Add an invite friend avatar button right inside listenersBox
        VBox inviteBox = new VBox(4);
        inviteBox.setAlignment(Pos.CENTER);
        inviteBox.setCursor(Cursor.HAND);
        StackPane plusCircle = new StackPane();
        Circle c = new Circle(19);
        c.setFill(Color.web("#f1f5f9"));
        c.setStroke(Color.web("#cbd5e1"));
        c.setStrokeWidth(1.5);
        c.getStrokeDashArray().addAll(4.0, 4.0);
        Label plusSign = new Label("＋");
        plusSign.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-text-fill: #4f46e5;");
        plusCircle.getChildren().addAll(c, plusSign);

        Label inviteLbl = new Label("Mời bạn");
        inviteLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: 600; -fx-text-fill: #4f46e5;");

        Region sp = new Region();
        sp.setPrefHeight(12);

        inviteBox.getChildren().addAll(plusCircle, inviteLbl, sp);
        inviteBox.setOnMouseClicked(e -> {
            if (onInviteFriends != null) onInviteFriends.run();
        });
        listenersBox.getChildren().add(inviteBox);
    }

    private VBox createListenerAvatar(String name, String waveColor, String waveText) {
        VBox box = new VBox(4);
        box.setAlignment(Pos.CENTER);

        StackPane avatarCircle = new StackPane();
        Circle c = new Circle(19);
        c.setFill(Color.web("#e0e7ff"));
        c.setStroke(Color.web("#c7d2fe"));
        c.setStrokeWidth(1.5);

        Label initial = new Label(name.substring(0, 1).toUpperCase());
        initial.setStyle("-fx-font-size: 14px; -fx-font-weight: 800; -fx-text-fill: #4338ca;");
        avatarCircle.getChildren().addAll(c, initial);

        Label nameLbl = new Label(name);
        nameLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: #0f172a;");

        Label waveLbl = new Label(waveText);
        waveLbl.setStyle("-fx-font-size: 10px; -fx-font-weight: 800; -fx-text-fill: " + waveColor + ";");

        box.getChildren().addAll(avatarCircle, nameLbl, waveLbl);
        return box;
    }

    private VBox createOverlappingAvatar(String labelText) {
        VBox box = new VBox(4);
        box.setAlignment(Pos.CENTER);

        HBox stack = new HBox(-10);
        stack.setAlignment(Pos.CENTER);

        Circle c1 = new Circle(17, Color.web("#fbcfe8"));
        c1.setStroke(Color.WHITE);
        c1.setStrokeWidth(2);

        Circle c2 = new Circle(17, Color.web("#bfdbfe"));
        c2.setStroke(Color.WHITE);
        c2.setStrokeWidth(2);

        StackPane plus = new StackPane();
        Circle c3 = new Circle(17, Color.web("#e2e8f0"));
        c3.setStroke(Color.WHITE);
        c3.setStrokeWidth(2);
        Label plusLbl = new Label("+3");
        plusLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: 800; -fx-text-fill: #475569;");
        plus.getChildren().addAll(c3, plusLbl);

        stack.getChildren().addAll(c1, c2, plus);

        Label nameLbl = new Label(labelText);
        nameLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: #0f172a;");

        Region spacer = new Region();
        spacer.setPrefHeight(12);

        box.getChildren().addAll(stack, nameLbl, spacer);
        return box;
    }

    // =========================================================================
    // 7. ROOM SELECTOR POPUP MENU
    // =========================================================================
    private void showRoomSelectorMenu(javafx.scene.Node anchor) {
        ContextMenu menu = new ContextMenu();
        if (onLeaveRoom != null) {
            MenuItem loungeItem = new MenuItem("🚪 Rời phòng về sảnh chờ...");
            loungeItem.setOnAction(e -> onLeaveRoom.run());
            menu.getItems().add(loungeItem);
            menu.getItems().add(new SeparatorMenuItem());
        }
        for (MusicRoom r : player.getRooms()) {
            MenuItem item = new MenuItem((r.getId().equals(player.getCurrentRoom().getId()) ? "✔ " : "   ") + r.getName());
            item.setOnAction(e -> {
                player.switchRoom(r.getId());
                refreshAll();
            });
            menu.getItems().add(item);
        }
        menu.getItems().add(new SeparatorMenuItem());
        MenuItem createItem = new MenuItem("➕ Tạo phòng nghe nhạc mới...");
        createItem.setOnAction(e -> showCreateRoomDialog());
        menu.getItems().add(createItem);

        menu.show(anchor, javafx.geometry.Side.BOTTOM, 0, 4);
    }

    private void showCreateRoomDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Tạo phòng nghe nhạc mới");
        dialog.setHeaderText("Thiết lập phòng nghe nhạc của bạn");

        VBox form = new VBox(10);
        form.setPadding(new Insets(16));
        form.setPrefWidth(360);

        TextField nameField = new TextField();
        nameField.setPromptText("Tên phòng (vd: Học đêm cùng Lan, Focus Coding...)");

        TextField descField = new TextField();
        descField.setPromptText("Mô tả ngắn");

        Button submitBtn = new Button("Tạo phòng");
        submitBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 700;");
        submitBtn.setOnAction(e -> {
            String name = nameField.getText();
            if (name != null && !name.isBlank()) {
                player.createUserRoom(name.trim(), descField.getText(), null, "#4f46e5", null, currentUser.username(), currentUser.displayName());
                dialog.close();
                refreshAll();
                if (toastCallback != null) toastCallback.accept("Đã tạo phòng: " + name.trim());
            }
        });

        form.getChildren().addAll(new Label("Tên phòng:"), nameField, new Label("Mô tả:"), descField, submitBtn);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.show();
    }

    // =========================================================================
    // 8. INTERACTIVE SOUNDWAVE WAVEFORM PROGRESS BAR
    // =========================================================================
    public static class WaveformVisualizer extends HBox {
        private final int barCount;
        private final Rectangle[] bars;
        private final double[] defaultHeights;
        private double progress = 0.0;
        private final Consumer<Double> onSeek;

        public WaveformVisualizer(int barCount, Consumer<Double> onSeek) {
            this.barCount = barCount;
            this.onSeek = onSeek;
            this.bars = new Rectangle[barCount];
            this.defaultHeights = new double[barCount];

            setAlignment(Pos.CENTER);
            setSpacing(3);
            setPrefHeight(32);
            setMinHeight(32);
            setMaxHeight(32);
            setCursor(Cursor.HAND);

            // Generate pseudo soundwave profile (like in Image 2)
            java.util.Random rnd = new java.util.Random(42);
            for (int i = 0; i < barCount; i++) {
                double base = 6.0 + Math.sin((double)i / barCount * Math.PI) * 16.0 + rnd.nextDouble() * 8.0;
                defaultHeights[i] = Math.max(6.0, Math.min(28.0, base));

                Rectangle r = new Rectangle(4, defaultHeights[i]);
                r.setArcWidth(4);
                r.setArcHeight(4);
                r.setFill(Color.web("#e2e8f0")); // unplayed default
                bars[i] = r;
                getChildren().add(r);
            }

            setOnMouseClicked(e -> {
                double w = getWidth();
                if (w > 0) {
                    double r = Math.max(0.0, Math.min(1.0, e.getX() / w));
                    setProgress(r);
                    if (onSeek != null) onSeek.accept(r);
                }
            });
        }

        public void setProgress(double p) {
            this.progress = Math.max(0.0, Math.min(1.0, p));
            int playedIndex = (int)(progress * barCount);
            for (int i = 0; i < barCount; i++) {
                if (i <= playedIndex) {
                    bars[i].setFill(Color.web("#f43f5e")); // Played: Vibrant Pink/Rose
                } else {
                    bars[i].setFill(Color.web("#e2e8f0")); // Unplayed: Soft Gray
                }
            }
        }

        public void updateAudioBands(float[] bands) {
            if (bands == null || bands.length == 0) return;
            for (int i = 0; i < barCount; i++) {
                int bandIdx = i % bands.length;
                float amp = bands[bandIdx];
                double h = defaultHeights[i] + amp * 6.0;
                bars[i].setHeight(Math.max(4.0, Math.min(30.0, h)));
            }
        }
    }
}
