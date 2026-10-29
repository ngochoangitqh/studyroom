package vn.studyroom;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.scene.web.WebView;
import javafx.scene.web.WebEngine;
import javafx.concurrent.Worker;
import netscape.javascript.JSObject;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import javafx.animation.Animation;
import java.util.concurrent.ConcurrentHashMap;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.animation.Interpolator;
import javafx.util.Duration;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.FloatControl;


public final class StudyroomApp extends Application {
    private final Database database = new Database();
    private final AuthStore auth = new AuthStore(database);
    private final ChatRepository chatRepository = new ChatRepository(database);
    private final FriendRepository friendRepo = new FriendRepository(database);
    private final CallRepository callRepo = new CallRepository(database);
    private final CourseRepository courseRepo = new CourseRepository(database);
    private final QuizRepository quizRepo = new QuizRepository(database);
    private final GeminiAiService aiService = new GeminiAiService(database);
    private final MusicPresenceRepository musicPresenceRepo = new MusicPresenceRepository(database);
    private final BorderPane shell = new BorderPane();
    private final VBox content = new VBox();
    private final MusicPlayerService musicPlayer = MusicPlayerService.getInstance();
    private User user;
    private TcpChatNode node;
    private VBox messages;
    private String selectedRoomId;
    private String activeCourseId;
    private long lastLoadedMessageId = 0;
    private Timer messageSyncTimer;
    private Timer classroomSyncTimer;
    private boolean isCameraOn = false;
    private boolean isMicOn = false;
    private boolean isSpeakerOn = true;
    private final VoiceEngine classroomVoice = new VoiceEngine();
    private final WebcamStreamEngine webcamStream = WebcamStreamEngine.getInstance();
    private int myVoicePort = 0;
    private int myCameraPort = 0;
    private String myVoiceIp = "";
    private volatile boolean isLocalSpeaking = false;
    private final Map<String, Long> peerSpeakingLastTime = new ConcurrentHashMap<>();
    private final Map<String, Runnable> tileSpeakingUpdateCallbacks = new ConcurrentHashMap<>();
    private Button playButton;
    private Scene scene;

    @Override public void start(Stage stage) {
        scene = new Scene(loginView(), 1280, 760);
        scene.getStylesheets().add(getClass().getResource("/studyroom.css").toExternalForm());
        stage.setTitle("Studyroom"); stage.setMinWidth(980); stage.setMinHeight(640); stage.setScene(scene); stage.show();
    }

    private Pane loginView() {
        return loginView(null);
    }

    private Pane loginView(String prefillUsername) {
        StackPane root = new StackPane(); root.getStyleClass().add("auth-root");
        VBox card = new VBox(16); card.getStyleClass().add("auth-card"); card.setMaxWidth(440);
        Label brand = new Label("◉  Studyroom"); brand.getStyleClass().add("brand");
        Label title = new Label("Học cùng nhau,\nkhông học một mình."); title.getStyleClass().add("auth-title");
        Label subtitle = new Label("Tạo một không gian chung để nhắn tin, họp, trình chiếu và nghe nhạc cùng nhóm."); subtitle.getStyleClass().add("muted"); subtitle.setWrapText(true);
        TextField name = field("Tên hiển thị", "Nguyễn Minh");
        TextField username = field("Tên đăng nhập", "minh.study");
        if (prefillUsername != null && !prefillUsername.isBlank()) {
            username.setText(prefillUsername);
        }
        PasswordField password = new PasswordField(); password.setPromptText("Mật khẩu"); password.getStyleClass().add("input");
        Label error = new Label(); error.getStyleClass().add("form-error"); error.setWrapText(true);
        Button submit = new Button("Đăng nhập"); submit.getStyleClass().addAll("button", "button-primary"); submit.setMaxWidth(Double.MAX_VALUE);
        Hyperlink switchMode = new Hyperlink("Chưa có tài khoản? Đăng ký");
        final boolean[] registering = {false};
        VBox quickAccountsBox = buildQuickAccountsBox(username, password, registering);
        Runnable refresh = () -> {
            name.setManaged(registering[0]);
            name.setVisible(registering[0]);
            quickAccountsBox.setManaged(!registering[0]);
            quickAccountsBox.setVisible(!registering[0]);
            submit.setText(registering[0] ? "Tạo tài khoản" : "Đăng nhập");
            switchMode.setText(registering[0] ? "Đã có tài khoản? Đăng nhập" : "Chưa có tài khoản? Đăng ký");
            error.setText("");
        };
        switchMode.setOnAction(e -> { registering[0] = !registering[0]; refresh.run(); });
        submit.setOnAction(e -> {
            try {
                user = registering[0] ? auth.register(name.getText(), username.getText(), password.getText()) : auth.login(username.getText(), password.getText());
                openWorkspace();
            } catch (Exception ex) {
                error.setText(ex.getMessage());
                ex.printStackTrace();
            }
        });
        card.getChildren().addAll(brand, title, subtitle, quickAccountsBox, name, username, password, error, submit, switchMode);
        refresh.run();
        root.getChildren().add(card);
        return root;
    }

    private VBox buildQuickAccountsBox(TextField usernameF, PasswordField passF, boolean[] registering) {
        VBox box = new VBox(6);
        List<UserDataServer.UserProfile> users = UserDataServer.getInstance().getAllUsers();
        if (users.isEmpty()) {
            UserDataServer.getInstance().syncFromDatabase(database);
            users = UserDataServer.getInstance().getAllUsers();
        }
        if (users.isEmpty()) {
            box.setManaged(false);
            box.setVisible(false);
            return box;
        }

        Label promptLbl = new Label("💡 Chọn nhanh tài khoản có sẵn:");
        promptLbl.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #4f46e5; -fx-font-weight: 700;");

        HBox chipsRow = new HBox(6);
        chipsRow.setAlignment(Pos.CENTER_LEFT);

        for (var p : users) {
            Button chip = new Button(p.displayName() + " (@" + p.username() + ")");
            chip.setStyle("-fx-background-color: #f8fafc; -fx-text-fill: #334155; -fx-border-color: #cbd5e1; -fx-border-radius: 14; -fx-background-radius: 14; -fx-font-size: 11px; -fx-font-weight: 600; -fx-padding: 4 9; -fx-cursor: hand;");
            chip.setOnAction(e -> {
                usernameF.setText(p.username());
                if (p.password() != null && !p.password().isBlank() && !p.password().equals("******")) {
                    passF.setText(p.password());
                } else {
                    passF.clear();
                }
                passF.requestFocus();
            });
            chipsRow.getChildren().add(chip);
        }

        ScrollPane scroll = new ScrollPane(chipsRow);
        scroll.setFitToHeight(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0;");
        scroll.setMaxHeight(36);

        box.getChildren().addAll(promptLbl, scroll);
        return box;
    }
    private TextField field(String label, String prompt) { TextField f = new TextField(); f.setPromptText(label + " · " + prompt); f.getStyleClass().add("input"); return f; }

    private void openWorkspace() {
        musicPlayer.setMusicPresenceRepository(musicPresenceRepo);
        musicPlayer.setCurrentUsername(user.username());
        musicPlayer.setPeerNode(node, user.displayName());
        shell.getStyleClass().add("app-shell"); shell.setLeft(sidebar()); shell.setCenter(content); showChat();
        scene.setRoot(shell);
        courseRepo.seedInitialDemoIfEmpty(user.username());
        try {
            node = new TcpChatNode(packet -> Platform.runLater(() -> {
                if (packet.body().startsWith("MUSIC_SYNC|")) {
                    String[] parts = packet.body().split("\\|", 5);
                    if (parts.length >= 4 && "INVITE".equals(parts[2]) && user.username().equalsIgnoreCase(parts[3])) {
                        String roomId = parts[1];
                        Alert invAlert = new Alert(Alert.AlertType.CONFIRMATION);
                        invAlert.setTitle("Lời mời nghe nhạc");
                        invAlert.setHeaderText(packet.sender() + " mời bạn vào phòng nghe nhạc!");
                        invAlert.setContentText("Bạn có muốn vào phòng nghe nhạc cùng " + packet.sender() + " không?");
                        if (scene != null && scene.getWindow() != null) invAlert.initOwner(scene.getWindow());
                        invAlert.showAndWait().ifPresent(res -> {
                            if (res == ButtonType.OK) {
                                musicPlayer.switchRoom(roomId);
                                showMusic();
                            }
                        });
                    } else {
                        musicPlayer.handlePeerSync(packet.sender(), packet.body());
                    }
                } else if (packet.body().startsWith("INCOMING_CALL|")) {
                    String[] parts = packet.body().split("\\|", 7);
                    if (parts.length >= 6 && user.username().equalsIgnoreCase(parts[5].trim())) {
                        String callId = parts[1];
                        String roomId = parts[2];
                        String callerUser = parts[3];
                        String callerName = parts[4];
                        String callType = parts.length > 6 ? parts[6] : "VOICE";
                        CallRepository.CallSession s = new CallRepository.CallSession(callId, roomId, callerName, callerUser, callType, "RINGING");
                        promptIncomingCall(s);
                    }
                } else if (selectedRoomId != null) {
                    addMessage(selectedRoomId, packet.sender(), packet.body(), false, true);
                }
            }));
            node.start(5050);
            connectConfiguredPeer();
            musicPlayer.setPeerNode(node, user.displayName());
        }
        catch (IOException ignored) { /* another Studyroom node owns the local port */ }

        Timer callChecker = new Timer(true);
        callChecker.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    CallRepository.CallSession incoming = callRepo.getIncomingDirectCall(user.username());
                    if (incoming != null) {
                        Platform.runLater(() -> promptIncomingCall(incoming));
                    }
                    MusicPresenceRepository.MusicInvitation musicInv = musicPresenceRepo.getLatestPendingInvite(user.username());
                    if (musicInv != null) {
                        Platform.runLater(() -> promptIncomingMusicInvite(musicInv));
                    }
                } catch (Exception ignored) { }
            }
        }, 1000, 1500);
    }

    private final Set<Long> promptedMusicInviteIds = Collections.synchronizedSet(new HashSet<>());
    private void promptIncomingMusicInvite(MusicPresenceRepository.MusicInvitation inv) {
        if (inv == null || !promptedMusicInviteIds.add(inv.id())) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Lời mời nghe nhạc");
        alert.setHeaderText("🎵 " + inv.senderDisplayName() + " mời bạn vào nghe nhạc cùng!");
        alert.setContentText("Phòng: \"" + inv.roomName() + "\"\nBạn có muốn tham gia phòng nghe nhạc cùng bạn bè ngay bây giờ không?");
        ButtonType joinType = new ButtonType("🎧 Tham gia ngay", ButtonBar.ButtonData.OK_DONE);
        ButtonType laterType = new ButtonType("Để sau", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(joinType, laterType);
        if (scene != null && scene.getWindow() != null) alert.initOwner(scene.getWindow());
        alert.showAndWait().ifPresent(res -> {
            if (res == joinType) {
                musicPresenceRepo.acceptInvite(inv.id());
                musicPlayer.switchRoom(inv.roomId(), inv.roomName());
                showMusic();
            } else {
                musicPresenceRepo.dismissInvite(inv.id());
            }
        });
    }
    private VBox sidebar() {
        VBox bar = new VBox(12); bar.getStyleClass().add("sidebar");
        Label logo = new Label("✨  Studyroom"); logo.getStyleClass().add("brand");
        Button chat = nav("💬", "Trò chuyện", this::showChat); Button room = nav("👥", "Phòng chung", this::showRoom); Button music = nav("🎵", "Nghe nhạc", this::showMusicLounge); Button aiAssistant = nav("🤖", "Trợ lý AI", this::showAiAssistant); Button profile = nav("👤", "Hồ sơ", this::showProfile);
        Region gap = new Region(); VBox.setVgrow(gap, Priority.ALWAYS);
        HBox meBox = new HBox(10);
        meBox.setAlignment(Pos.CENTER_LEFT);
        meBox.setStyle("-fx-padding: 8 10; -fx-background-color: rgba(255,255,255,0.06); -fx-background-radius: 12; -fx-cursor: hand;");
        StackPane meAvt = userAvatar(user.username(), initials(user.displayName()), "avatar-person", 36);
        VBox meCopy = new VBox(2);
        Label meName = new Label(user.displayName());
        meName.setStyle("-fx-font-weight: 700; -fx-font-size: 13px; -fx-text-fill: #111827;");
        Label meStatus = new Label("📷  Hồ sơ & Đổi ảnh");
        meStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #6366f1; -fx-font-weight: 600;");
        meCopy.getChildren().addAll(meName, meStatus);
        meBox.getChildren().addAll(meAvt, meCopy);
        meBox.setOnMouseClicked(e -> showProfile());

        HBox meActions = new HBox(6);
        meActions.setAlignment(Pos.CENTER);

        Button switchBtn = new Button("🔄 Đổi tài khoản");
        switchBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(switchBtn, Priority.ALWAYS);
        switchBtn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #334155; -fx-font-size: 11px; -fx-font-weight: 700; -fx-background-radius: 8; -fx-padding: 6 4; -fx-cursor: hand; -fx-border-color: #e2e8f0; -fx-border-radius: 8;");
        switchBtn.setOnAction(e -> showSwitchAccountDialog());

        Button logoutBtn = new Button("🚪 Đăng xuất");
        logoutBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(logoutBtn, Priority.ALWAYS);
        logoutBtn.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #dc2626; -fx-font-size: 11px; -fx-font-weight: 700; -fx-background-radius: 8; -fx-padding: 6 4; -fx-cursor: hand; -fx-border-color: #fecaca; -fx-border-radius: 8;");
        logoutBtn.setOnAction(e -> confirmAndLogout());

        meActions.getChildren().addAll(switchBtn, logoutBtn);

        bar.getChildren().addAll(logo, spacer(18), chat, room, music, aiAssistant, profile, gap, meBox, meActions);
        return bar;
    }
    private Button nav(String icon, String label, Runnable action) {
        Label iconNode = new Label(icon);
        iconNode.setStyle("-fx-font-family: 'Segoe UI Emoji', 'Segoe UI Symbol', sans-serif; -fx-font-size: 15px; -fx-min-width: 22px; -fx-text-fill: #4b5563;");
        Label textNode = new Label(label);
        textNode.setStyle("-fx-font-family: 'Segoe UI', sans-serif; -fx-font-size: 13px; -fx-font-weight: 600; -fx-text-fill: -ink;");
        HBox row = new HBox(8, iconNode, textNode);
        row.setAlignment(Pos.CENTER_LEFT);
        Button b = new Button();
        b.setGraphic(row);
        b.getStyleClass().add("nav-item");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> action.run());
        return b;
    }
    private Region spacer(double h) { Region r = new Region(); r.setMinHeight(h); return r; }
    private void base(String title, String caption) { content.getChildren().clear(); content.getStyleClass().setAll("workspace"); content.setPadding(new Insets(26, 30, 20, 30)); Label h = new Label(title); h.getStyleClass().add("page-title"); Label c = new Label(caption); c.getStyleClass().add("muted"); content.getChildren().addAll(h, c, spacer(18)); }

    private String currentDirectUsername = null;

    private void showChat() { showChat(null, "", false, null); }
    private void showChat(String roomId, String conversationName, boolean group) {
        showChat(roomId, conversationName, group, null);
    }
    private void showChat(String roomId, String conversationName, boolean group, String directUsername) {
        this.currentDirectUsername = directUsername;
        if (!group && currentDirectUsername == null && roomId != null) {
            for (User f : friendRepo.friends(user.username())) {
                if (directRoomId(user.username(), f.username()).equals(roomId)) {
                    this.currentDirectUsername = f.username();
                    break;
                }
            }
            if (this.currentDirectUsername == null) {
                List<ChatRepository.Message> msgs = chatRepository.recent(roomId, 10);
                for (ChatRepository.Message m : msgs) {
                    if (!m.sender().equalsIgnoreCase(user.displayName()) && !m.sender().equalsIgnoreCase(user.username())) {
                        this.currentDirectUsername = m.sender();
                        break;
                    }
                }
            }
        }
        refreshSidebar();
        selectedRoomId = roomId;
        content.getChildren().clear(); content.getStyleClass().setAll("chat-workspace"); content.setPadding(Insets.EMPTY); content.setSpacing(0);
        VBox threads = new VBox(8); threads.getStyleClass().add("thread-list"); threads.setPrefWidth(380); threads.setMinWidth(320);
        HBox threadTitle = new HBox(); threadTitle.getStyleClass().add("thread-title-row"); Label title = new Label("Trò chuyện"); title.getStyleClass().add("chat-page-title"); Region titlePush = new Region(); HBox.setHgrow(titlePush, Priority.ALWAYS); Button create = iconButton("✎", "Tạo nhóm mới"); create.setOnAction(e -> createGroup()); threadTitle.getChildren().addAll(title, titlePush, create);
        HBox searchBox = new HBox(8);
        searchBox.getStyleClass().add("chat-search");
        searchBox.setAlignment(Pos.CENTER_LEFT);
        Label searchIcon = new Label("🔍");
        searchIcon.setStyle("-fx-font-family: 'Segoe UI Emoji', 'Segoe UI Symbol', sans-serif; -fx-font-size: 13px; -fx-text-fill: #6b7280;");
        TextField find = new TextField();
        find.setPromptText("Tìm kiếm cuộc trò chuyện...");
        find.setStyle("-fx-background-color: transparent; -fx-padding: 0; -fx-font-family: 'Segoe UI', sans-serif; -fx-font-size: 13px;");
        HBox.setHgrow(find, Priority.ALWAYS);
        searchBox.getChildren().addAll(searchIcon, find);
        HBox filters = new HBox(8, chip("Tất cả", true), chip("Nhóm", false), chip("Cá nhân", false), chip("Chưa đọc", false)); filters.getStyleClass().add("chat-filters");
        VBox threadItems = new VBox(2); threadItems.getStyleClass().add("thread-items");
        List<ChatRepository.Room> rooms = chatRepository.roomsFor(user.username());
        List<User> friendList = friendRepo.friends(user.username());
        List<User> pendingList = friendRepo.pendingReceived(user.username());
        Label groupSection = new Label("NHÓM"); groupSection.getStyleClass().add("thread-section"); threadItems.getChildren().add(groupSection);
        rooms.forEach(room -> threadItems.getChildren().add(thread(room, room.id().equals(roomId))));
        if (rooms.isEmpty()) { Label empty = new Label("Chưa có nhóm nào"); empty.getStyleClass().add("empty-thread-list"); threadItems.getChildren().add(empty); }
        if (!pendingList.isEmpty()) {
            Label reqSection = new Label("LỜI MỜI KẾT BẠN  (" + pendingList.size() + ")"); reqSection.getStyleClass().add("thread-section"); threadItems.getChildren().add(reqSection);
            pendingList.forEach(person -> {
                HBox row = new HBox(8); row.getStyleClass().add("thread"); row.setAlignment(Pos.CENTER_LEFT);
                StackPane photo = userAvatar(person.username(), initials(person.displayName()), "avatar-person", 40);
                Label name = new Label(person.displayName()); name.getStyleClass().add("thread-name");
                Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
                Button accept = new Button("✓"); accept.getStyleClass().addAll("icon-button"); accept.setTooltip(new Tooltip("Chấp nhận"));
                accept.setOnAction(e -> { friendRepo.acceptRequest(person.username(), user.username()); showChat(roomId, conversationName, group); });
                Button reject = new Button("✕"); reject.getStyleClass().addAll("icon-button"); reject.setTooltip(new Tooltip("Từ chối"));
                reject.setOnAction(e -> { friendRepo.rejectRequest(person.username(), user.username()); showChat(roomId, conversationName, group); });
                row.getChildren().addAll(photo, name, gap, accept, reject);
                threadItems.getChildren().add(row);
            });
        }
        Label friendSection = new Label("BẠN BÈ"); friendSection.getStyleClass().add("thread-section"); threadItems.getChildren().add(friendSection);
        friendList.forEach(person -> threadItems.getChildren().add(thread(person, directRoomId(user.username(), person.username()).equals(roomId))));
        if (friendList.isEmpty()) { Label empty = new Label("Chưa có bạn bè"); empty.getStyleClass().add("empty-thread-list"); threadItems.getChildren().add(empty); }
        Button addFriendBtn = new Button("＋  Kết bạn"); addFriendBtn.getStyleClass().addAll("button"); addFriendBtn.setMaxWidth(Double.MAX_VALUE);
        addFriendBtn.setOnAction(e -> showAddFriendDialog());
        threadItems.getChildren().add(addFriendBtn);
        ScrollPane threadScroll = new ScrollPane(threadItems); threadScroll.setFitToWidth(true); threadScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); threadScroll.getStyleClass().add("thread-scroll"); VBox.setVgrow(threadScroll, Priority.ALWAYS); threads.getChildren().addAll(threadTitle, searchBox, filters, threadScroll);
        VBox conversation = new VBox(); conversation.getStyleClass().add("conversation"); HBox.setHgrow(conversation, Priority.ALWAYS);
        if (roomId == null) { showEmptyConversation(conversation); showChatColumns(threads, conversation, null); return; }
        List<String> memberNames = group ? chatRepository.membersOf(roomId) : List.of();
        String metaText = group ? memberNames.size() + " thành viên" : "Đang hoạt động";
        
        HBox head = new HBox(12); head.getStyleClass().add("conversation-header"); head.setAlignment(Pos.CENTER_LEFT);
        
        StackPane groupAvatar = group ? groupAvatar(roomId, conversationName, "avatar-group", 42)
                                      : userAvatar(conversationName, initials(conversationName), "avatar-person", 42);
        groupAvatar.setCursor(javafx.scene.Cursor.HAND);
        if (group) {
            Tooltip.install(groupAvatar, new Tooltip("Nhấp để đổi ảnh đại diện nhóm"));
            groupAvatar.setOnMouseClicked(e -> showChangeGroupAvatarDialog(roomId, conversationName));
        } else {
            Tooltip.install(groupAvatar, new Tooltip("Nhấp để đổi ảnh đại diện cá nhân"));
            groupAvatar.setOnMouseClicked(e -> showChangeUserAvatarDialog());
        }
        
        VBox groupCopy = new VBox(2); Label groupName = new Label(conversationName); groupName.getStyleClass().add("group-name"); Label groupMeta = new Label(metaText); groupMeta.getStyleClass().add("group-meta"); groupCopy.getChildren().addAll(groupName, groupMeta); Region push = new Region(); HBox.setHgrow(push, Priority.ALWAYS);
        Button addMember = iconButton("＋", "Thêm thành viên"); if (group) addMember.setOnAction(e -> showAddMemberDialog(roomId, conversationName)); else addMember.setVisible(false);
        Button call = iconButton("📞", "Gọi thoại"); Button video = iconButton("📹", "Bật video");
        Button musicRoomBtn = iconButton("🎵", "Phòng nghe nhạc cùng nhau");
        musicRoomBtn.setOnAction(e -> {
            if (!group && currentDirectUsername != null) {
                MusicPresenceRepository.FriendMusicPresence fp = musicPresenceRepo.getFriendPresence(currentDirectUsername);
                if (fp != null) {
                    musicPlayer.switchRoom(fp.roomId(), fp.roomName());
                    showMusic();
                    return;
                }
            }
            showInviteMusicFriendsDialog();
        });
        Button search = iconButton("🔍", "Tìm trong trò chuyện"); Button more = iconButton("⋯", "Thông tin cuộc trò chuyện");
        call.setOnAction(e -> startOrJoinCall(roomId, conversationName, group ? "GROUP" : "DIRECT", false));
        video.setOnAction(e -> startOrJoinCall(roomId, conversationName, group ? "GROUP" : "DIRECT", true));
        head.getChildren().addAll(groupAvatar, groupCopy, push, addMember, call, video, musicRoomBtn, search, more);

        Node infoPanel = buildChatInfoPanel(roomId, conversationName, group, conversationName);

        more.setOnAction(e -> {
            infoPanel.setVisible(!infoPanel.isVisible());
            infoPanel.setManaged(infoPanel.isVisible());
        });

        CallRepository.CallSession activeCall = callRepo.getActiveCall(roomId);
        if (group && activeCall != null) {
            HBox callBanner = new HBox(12); callBanner.setAlignment(Pos.CENTER_LEFT);
            callBanner.setStyle("-fx-background-color: #2b1f4d; -fx-padding: 10 20; -fx-border-color: #7c5cff transparent transparent transparent;");
            Label bannerLabel = new Label("🟢 Cuộc gọi nhóm đang diễn ra"); bannerLabel.setStyle("-fx-text-fill: #55efc4; -fx-font-weight: bold;");
            Region bSpacer = new Region(); HBox.setHgrow(bSpacer, Priority.ALWAYS);
            Button joinBtn = new Button("Tham gia ngay"); joinBtn.getStyleClass().addAll("button", "button-primary");
            joinBtn.setOnAction(e -> startOrJoinCall(roomId, conversationName, "GROUP", false));
            callBanner.getChildren().addAll(bannerLabel, bSpacer, joinBtn);
            conversation.getChildren().add(callBanner);
        }

        if (!group && currentDirectUsername != null) {
            MusicPresenceRepository.FriendMusicPresence friendMusic = musicPresenceRepo.getFriendPresence(currentDirectUsername);
            if (friendMusic != null) {
                HBox musicBanner = new HBox(12);
                musicBanner.setAlignment(Pos.CENTER_LEFT);
                musicBanner.setStyle("-fx-background-color: #1e1b4b; -fx-padding: 10 20; -fx-border-color: #6366f1 transparent transparent transparent;");
                Label mIcon = new Label("🎵");
                mIcon.setStyle("-fx-font-size: 16px;");
                Label mLabel = new Label(conversationName + " đang nghe nhạc tại phòng \"" + friendMusic.roomName() + "\"");
                mLabel.setStyle("-fx-text-fill: #a5b4fc; -fx-font-weight: bold; -fx-font-size: 13px;");
                Region mSpacer = new Region();
                HBox.setHgrow(mSpacer, Priority.ALWAYS);
                Button joinMusicBtn = new Button("🎧 Tham gia nghe cùng");
                joinMusicBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 800; -fx-background-radius: 12; -fx-padding: 6 16; -fx-cursor: hand;");
                joinMusicBtn.setOnAction(e -> {
                    musicPlayer.switchRoom(friendMusic.roomId(), friendMusic.roomName());
                    showMusic();
                });
                musicBanner.getChildren().addAll(mIcon, mLabel, mSpacer, joinMusicBtn);
                conversation.getChildren().add(musicBanner);
            }
        }

        if (messageSyncTimer != null) {
            messageSyncTimer.cancel();
            messageSyncTimer = null;
        }
        messages = new VBox(16); messages.getStyleClass().add("messages"); messages.setPadding(new Insets(30, 28, 22, 28));
        List<ChatRepository.Message> history = chatRepository.recent(roomId, 50);
        lastLoadedMessageId = 0;
        history.forEach(item -> {
            addMessage(roomId, item.sender(), item.body(), item.sender().equals(user.displayName()), false);
            if (item.id() > lastLoadedMessageId) lastLoadedMessageId = item.id();
        });
        if (history.isEmpty()) { Label empty = new Label("Chưa có tin nhắn. Hãy bắt đầu cuộc trò chuyện."); empty.getStyleClass().add("empty-conversation"); messages.getChildren().add(empty); }
        Label today = new Label("Hôm nay"); today.getStyleClass().add("day-label"); messages.getChildren().add(0, today); VBox.setMargin(today, new Insets(0, 0, 12, 0));
        ScrollPane scroll = new ScrollPane(messages); scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.getStyleClass().add("message-scroll"); VBox.setVgrow(scroll, Priority.ALWAYS);
        Platform.runLater(() -> scroll.setVvalue(1.0));

        messageSyncTimer = new Timer(true);
        messageSyncTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (selectedRoomId == null || !selectedRoomId.equals(roomId)) return;
                try {
                    List<ChatRepository.Message> newMsgs = chatRepository.messagesSince(roomId, lastLoadedMessageId);
                    if (!newMsgs.isEmpty()) {
                        Platform.runLater(() -> {
                            for (ChatRepository.Message m : newMsgs) {
                                if (m.id() > lastLoadedMessageId) {
                                    lastLoadedMessageId = m.id();
                                    if (!m.sender().equals(user.displayName())) {
                                        addMessage(roomId, m.sender(), m.body(), false, false);
                                    }
                                }
                            }
                            scroll.setVvalue(1.0);
                        });
                    }
                } catch (Exception ignored) { }
            }
        }, 500, 500);

        HBox composerShell = new HBox(8);
        composerShell.getStyleClass().add("composer-shell");
        Button attach = iconButton("📎", "Đính kèm tệp hoặc ảnh");
        TextField composer = new TextField();
        composer.setPromptText("Nhắn tin...");
        composer.getStyleClass().add("composer");
        Button emoji = iconButton("😊", "Biểu tượng cảm xúc");
        Button send = iconButton("➤", "Gửi tin nhắn");
        send.getStyleClass().add("send-icon");

        Popup emojiPopup = createEmojiPicker(composer, roomId, scroll);
        emoji.setOnAction(e -> {
            if (emojiPopup.isShowing()) {
                emojiPopup.hide();
            } else {
                Bounds bounds = emoji.localToScreen(emoji.getBoundsInLocal());
                if (bounds != null) {
                    emojiPopup.show(emoji, bounds.getMinX() - 170, bounds.getMinY() - 340);
                }
            }
        });

        ContextMenu attachMenu = new ContextMenu();
        MenuItem sendImgItem = new MenuItem("📷  Gửi hình ảnh...");
        sendImgItem.setOnAction(e -> handleSendImage(roomId, scroll));
        MenuItem sendFileItem = new MenuItem("📎  Gửi tệp tài liệu...");
        sendFileItem.setOnAction(e -> handleSendFile(roomId, scroll));
        attachMenu.getItems().addAll(sendImgItem, sendFileItem);

        attach.setOnAction(e -> {
            Bounds b = attach.localToScreen(attach.getBoundsInLocal());
            if (b != null) {
                attachMenu.show(attach, b.getMinX(), b.getMinY() - 75);
            }
        });

        Runnable sendMessage = () -> {
            if (!composer.getText().isBlank()) {
                String text = composer.getText().trim();
                addMessage(roomId, user.displayName(), text, true, true);
                if (node != null) node.broadcast(user.displayName(), text);
                composer.clear();
                scroll.setVvalue(1.0);
            }
        };
        send.setOnAction(e -> sendMessage.run());
        composer.setOnAction(e -> sendMessage.run());
        HBox.setHgrow(composer, Priority.ALWAYS);
        composerShell.getChildren().addAll(attach, composer, emoji, send);
        conversation.getChildren().addAll(head, scroll, composerShell);
        showChatColumns(threads, conversation, infoPanel);
    }
    private void showChatColumns(VBox threads, VBox conversation, Node infoPanel) {
        HBox columns = (infoPanel != null) ? new HBox(threads, conversation, infoPanel) : new HBox(threads, conversation);
        HBox.setHgrow(conversation, Priority.ALWAYS);
        VBox.setVgrow(columns, Priority.ALWAYS);
        content.getChildren().add(columns);
    }
    private Button chip(String text, boolean selected) { Button chip = new Button(text); chip.getStyleClass().addAll("filter-chip", selected ? "filter-chip-active" : ""); return chip; }
    private HBox thread(ChatRepository.Room room, boolean active) { return thread(room.id(), room.name(), "avatar-group", "Mở nhóm ", active, true, null); }
    private HBox thread(User person, boolean active) { return thread(directRoomId(user.username(), person.username()), person.displayName(), "avatar-person", "Nhắn tin với ", active, false, person.username()); }
    private HBox thread(String roomId, String nameText, String avatarStyle, String action, boolean active, boolean group, String personUsername) {
        StackPane photo = group ? groupAvatar(roomId, nameText, avatarStyle, 40)
                                : userAvatar(personUsername != null ? personUsername : nameText, initials(nameText), avatarStyle, 40);
        VBox copy = new VBox(3);
        copy.getStyleClass().add("thread-copy");
        Label name = new Label(nameText);
        name.getStyleClass().add("thread-name");
        List<ChatRepository.Message> latest = chatRepository.recent(roomId, 1);
        String previewText = "Chưa có tin nhắn";
        if (!latest.isEmpty()) {
            String b = latest.getFirst().body();
            if (b != null && b.startsWith("[STICKER:")) {
                previewText = latest.getFirst().sender() + ": 🎨 [Icon màu sắc]";
            } else if (b != null && b.startsWith("[IMAGE:")) {
                previewText = latest.getFirst().sender() + ": 📷 [Hình ảnh]";
            } else if (b != null && b.startsWith("[FILE:")) {
                String fn = "Tệp tin";
                try {
                    String[] p = b.substring(6, b.length() - 1).split(":", 3);
                    if (p.length > 1) fn = p[1];
                } catch (Exception ignored) {}
                previewText = latest.getFirst().sender() + ": 📎 " + fn;
            } else if (b != null && b.startsWith("[CALL:")) {
                String inner2 = b.substring(6, b.length() - 1);
                boolean isMissed = inner2.startsWith("missed");
                previewText = isMissed ? "📵 Cuộc gọi nhỡ" : "📞 Cuộc gọi thoại";
            } else {
                previewText = latest.getFirst().sender() + ": " + b;
            }

        }
        Label preview = new Label(previewText);
        preview.getStyleClass().add("thread-preview");
        copy.getChildren().addAll(name, preview);
        HBox row = new HBox(12, photo, copy);
        HBox.setHgrow(copy, Priority.ALWAYS);
        row.getStyleClass().addAll("thread", active ? "thread-active" : "");
        row.setAccessibleText(action + nameText);
        row.setOnMouseClicked(e -> showChat(roomId, nameText, group, personUsername));
        return row;
    }
    private StackPane userAvatar(String usernameOrName, String initials, String style, double size) {
        Image img = auth.getUserAvatarImage(usernameOrName);
        if (img != null && !img.isError() && img.getWidth() > 0) {
            return imageAvatar(img, style, size);
        }
        return avatar(initials, style, size);
    }

    private StackPane groupAvatar(String roomId, String name, String style, double size) {
        Image img = chatRepository.getGroupAvatarImage(roomId);
        if (img != null && !img.isError() && img.getWidth() > 0) {
            return imageAvatar(img, style, size);
        }
        return avatar(initials(name), style, size);
    }

    private StackPane imageAvatar(Image img, String style, double size) {
        if (img == null || img.isError() || img.getWidth() <= 0 || img.getHeight() <= 0) {
            return avatar("?", style, size);
        }

        ImageView iv = new ImageView(img);
        iv.setSmooth(true);
        iv.setPreserveRatio(true);

        double w = img.getWidth();
        double h = img.getHeight();
        double scale = Math.max(size / w, size / h);
        iv.setFitWidth(w * scale);
        iv.setFitHeight(h * scale);

        double radius = size / 2.0;
        javafx.scene.shape.Circle clipCircle = new javafx.scene.shape.Circle(radius, radius, radius);

        StackPane container = new StackPane(iv);
        container.getStyleClass().add("avatar");
        container.setStyle("-fx-background-color: transparent;");
        container.setMinSize(size, size);
        container.setMaxSize(size, size);
        container.setPrefSize(size, size);
        container.setAlignment(Pos.CENTER);
        container.setClip(clipCircle);
        return container;
    }

    private StackPane avatar(String initials, String style, double size) {
        Label mark = new Label(initials);
        mark.getStyleClass().add("avatar-text");
        mark.setStyle("-fx-font-size: " + (int)(size * 0.38) + "px; -fx-font-weight: 800;");

        StackPane avatarPane = new StackPane(mark);
        avatarPane.getStyleClass().addAll("avatar", style);
        avatarPane.setMinSize(size, size);
        avatarPane.setMaxSize(size, size);
        avatarPane.setPrefSize(size, size);
        avatarPane.setAlignment(Pos.CENTER);

        double radius = size / 2.0;
        javafx.scene.shape.Circle clipCircle = new javafx.scene.shape.Circle(radius, radius, radius);
        avatarPane.setClip(clipCircle);
        return avatarPane;
    }

    private StackPane avatar(String initials, String style) {
        return avatar(initials, style, 40);
    }

    private Node buildChatInfoPanel(String roomId, String conversationName, boolean group, String targetUsername) {
        VBox container = new VBox(16);
        container.setPrefWidth(300);
        container.setMinWidth(280);
        container.setMaxWidth(320);
        container.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e5e7eb; -fx-border-width: 0 0 0 1; -fx-padding: 24 16;");
        container.setAlignment(Pos.TOP_CENTER);

        StackPane avatarNode = group ? groupAvatar(roomId, conversationName, "avatar-group", 80)
                                      : userAvatar(targetUsername != null ? targetUsername : conversationName, initials(conversationName), "avatar-person", 80);
        
        Label camBadge = new Label("📷");
        camBadge.setStyle("-fx-font-family: 'Segoe UI Emoji', 'Segoe UI Symbol', sans-serif; -fx-background-color: #6366f1; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 3 6; -fx-background-radius: 10; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 3, 0, 0, 1);");
        StackPane.setAlignment(camBadge, Pos.BOTTOM_RIGHT);

        StackPane avatarPane = new StackPane(avatarNode, camBadge);
        avatarPane.setMinSize(80, 80);
        avatarPane.setMaxSize(80, 80);
        avatarPane.setPrefSize(80, 80);
        avatarPane.setAlignment(Pos.CENTER);
        avatarPane.setCursor(javafx.scene.Cursor.HAND);

        if (group) {
            Tooltip.install(avatarPane, new Tooltip("Nhấp để đổi ảnh đại diện nhóm"));
            avatarPane.setOnMouseClicked(e -> showChangeGroupAvatarDialog(roomId, conversationName));
        } else {
            Tooltip.install(avatarPane, new Tooltip("Nhấp để đổi ảnh đại diện cá nhân"));
            avatarPane.setOnMouseClicked(e -> showChangeUserAvatarDialog());
        }

        Label nameLbl = new Label(conversationName);
        nameLbl.setStyle("-fx-font-size: 17px; -fx-font-weight: 800; -fx-text-fill: #111827; -fx-text-alignment: center;");
        nameLbl.setWrapText(true);

        List<String> memberNames = group ? chatRepository.membersOf(roomId) : List.of();
        String metaText = group ? (memberNames.size() + " thành viên") : "Đang hoạt động";
        Label metaLbl = new Label(metaText);
        metaLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #6b7280;");

        HBox quickActions = new HBox(20);
        quickActions.setAlignment(Pos.CENTER);
        VBox muteBtn = quickActionItem("🔔", "Tắt thông báo", () -> toast("Đã chuyển đổi thông báo cuộc trò chuyện"));
        VBox searchBtn = quickActionItem("🔍", "Tìm kiếm", () -> toast("Tìm kiếm trong trò chuyện"));
        quickActions.getChildren().addAll(muteBtn, searchBtn);

        VBox headerBox = new VBox(10, avatarPane, nameLbl, metaLbl, spacer(4), quickActions);
        headerBox.setAlignment(Pos.CENTER);

        VBox customSection = createInfoSection("Tùy chỉnh đoạn chat", java.util.Arrays.asList(
            group ? createSectionButton("📷", "Đổi ảnh đại diện nhóm", e -> showChangeGroupAvatarDialog(roomId, conversationName)) : createSectionButton("📷", "Đổi ảnh đại diện cá nhân", e -> showChangeUserAvatarDialog()),
            group ? createSectionButton("✎", "Đổi tên nhóm", e -> showRenameGroupDialog(roomId, conversationName)) : null
        ));

        VBox membersSection = null;
        if (group) {
            VBox memberItems = new VBox(8);
            for (String mName : memberNames) {
                HBox mRow = new HBox(8);
                mRow.setAlignment(Pos.CENTER_LEFT);
                StackPane mAvt = userAvatar(mName, initials(mName), "avatar-person", 28);
                Label mLbl = new Label(mName);
                mLbl.setStyle("-fx-font-size: 13px; -fx-text-fill: #374151; -fx-font-weight: 600;");
                mRow.getChildren().addAll(mAvt, mLbl);
                memberItems.getChildren().add(mRow);
            }
            Button addMemBtn = createSectionButton("＋", "Thêm thành viên", e -> showAddMemberDialog(roomId, conversationName));
            memberItems.getChildren().add(addMemBtn);

            membersSection = createInfoSectionContent("Thành viên trong đoạn chat (" + memberNames.size() + ")", memberItems);
        }

        VBox privacySection = createInfoSection("Quyền riêng tư & hỗ trợ", java.util.Arrays.asList(
            group ? createSectionButton("🚪", "Rời khỏi nhóm", e -> leaveGroup(roomId, conversationName)) : null
        ));

        container.getChildren().add(headerBox);
        container.getChildren().add(new Separator());
        if (customSection != null) container.getChildren().add(customSection);
        if (membersSection != null) container.getChildren().add(membersSection);
        if (privacySection != null) container.getChildren().add(privacySection);

        ScrollPane scroll = new ScrollPane(container);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return scroll;
    }

    private VBox quickActionItem(String icon, String text, Runnable action) {
        Label iconLbl = new Label(icon);
        iconLbl.setStyle("-fx-font-family: 'Segoe UI Emoji', 'Segoe UI Symbol', sans-serif; -fx-font-size: 16px; -fx-text-fill: #374151;");
        Button btn = new Button();
        btn.setGraphic(iconLbl);
        btn.getStyleClass().add("action-circle-btn");
        btn.setOnAction(e -> action.run());
        btn.setOnMouseEntered(e -> iconLbl.setStyle("-fx-font-family: 'Segoe UI Emoji', 'Segoe UI Symbol', sans-serif; -fx-font-size: 16px; -fx-text-fill: #6558f5;"));
        btn.setOnMouseExited(e -> iconLbl.setStyle("-fx-font-family: 'Segoe UI Emoji', 'Segoe UI Symbol', sans-serif; -fx-font-size: 16px; -fx-text-fill: #374151;"));
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #4b5563; -fx-text-alignment: center; -fx-font-weight: 500;");
        VBox box = new VBox(6, btn, lbl);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private VBox createInfoSection(String title, List<Button> buttons) {
        VBox box = new VBox(6);
        Label titleLbl = new Label(title);
        titleLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #374151;");
        box.getChildren().add(titleLbl);

        boolean hasAny = false;
        for (Button btn : buttons) {
            if (btn != null) {
                box.getChildren().add(btn);
                hasAny = true;
            }
        }
        return hasAny ? box : null;
    }

    private VBox createInfoSectionContent(String title, Node content) {
        VBox box = new VBox(8);
        Label titleLbl = new Label(title);
        titleLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #374151;");
        box.getChildren().addAll(titleLbl, content);
        return box;
    }

    private Button createSectionButton(String icon, String text, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        boolean isDanger = "🚪".equals(icon) || text.contains("Rời");
        Label iconNode = new Label(icon);
        iconNode.setStyle("-fx-font-family: 'Segoe UI Emoji', 'Segoe UI Symbol', sans-serif; -fx-font-size: 14px; -fx-text-fill: " + (isDanger ? "#dc2626;" : "#4f46e5;"));
        StackPane iconBadge = new StackPane(iconNode);
        iconBadge.setMinSize(28, 28);
        iconBadge.setMaxSize(28, 28);
        iconBadge.setStyle("-fx-background-color: " + (isDanger ? "#fee2e2;" : "#eeeffe;") + " -fx-background-radius: 7; -fx-alignment: center;");

        Label textNode = new Label(text);
        textNode.setStyle("-fx-font-family: 'Segoe UI', sans-serif; -fx-font-size: 13px; -fx-font-weight: 600; -fx-text-fill: " + (isDanger ? "#dc2626;" : "#1f2937;"));
        HBox row = new HBox(10, iconBadge, textNode);
        row.setAlignment(Pos.CENTER_LEFT);
        Button btn = new Button();
        btn.setGraphic(row);
        btn.getStyleClass().add("info-section-btn");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setOnAction(handler);
        return btn;
    }

    private void showRenameGroupDialog(String roomId, String currentName) {
        TextInputDialog dialog = new TextInputDialog(currentName);
        dialog.setTitle("Đổi tên nhóm");
        dialog.setHeaderText("Nhập tên mới cho nhóm " + currentName);
        dialog.setContentText("Tên nhóm mới:");
        dialog.showAndWait().ifPresent(newName -> {
            if (!newName.isBlank()) {
                try (Connection c = database.connect();
                     PreparedStatement q = c.prepareStatement("UPDATE study_group SET room_name = ? WHERE room_id = ?")) {
                    q.setString(1, newName.trim());
                    q.setString(2, roomId);
                    q.executeUpdate();
                    showChat(roomId, newName.trim(), true);
                } catch (SQLException ex) {
                    toast("Không thể đổi tên nhóm: " + ex.getMessage());
                }
            }
        });
    }

    private void leaveGroup(String roomId, String roomName) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Bạn có chắc muốn rời khỏi nhóm " + roomName + "?", ButtonType.YES, ButtonType.NO);
        alert.setTitle("Xác nhận rời nhóm");
        alert.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                chatRepository.removeMember(roomId, user.username());
                showChat();
            }
        });
    }

    private void addMessage(String roomId, String sender, String text, boolean mine, boolean persist) {
        if (persist) chatRepository.save(roomId, sender, text);
        messages.getChildren().removeIf(node -> node.getStyleClass().contains("empty-conversation"));
        VBox bubble = new VBox(4);
        bubble.getStyleClass().addAll("bubble", mine ? "bubble-mine" : "bubble-peer");
        Label time = new Label(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + (mine ? "  ✓✓" : ""));
        time.getStyleClass().add("message-time");

        if (text != null && text.startsWith("[STICKER:") && text.endsWith("]")) {
            String code = text.substring(9, text.length() - 1);
            var is = getClass().getResourceAsStream("/emojis/" + code + ".png");
            if (is != null) {
                Image stickerImg = new Image(is);
                ImageView iv = new ImageView(stickerImg);
                iv.setFitWidth(64);
                iv.setFitHeight(64);
                iv.setPreserveRatio(true);
                iv.setSmooth(true);

                bubble.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
                bubble.getChildren().addAll(iv, time);
            } else {
                Label fallback = new Label("🎨 [Icon: " + code + "]");
                bubble.getChildren().addAll(fallback, time);
            }
        } else if (text != null && text.startsWith("[CALL:") && text.endsWith("]")) {
            // === Messenger-style Call Summary Card ===
            String inner = text.substring(6, text.length() - 1); // duration:roomName OR missed:roomName
            String[] parts = inner.split(":", 2);
            String durationOrMissed = parts[0];
            boolean missed = "missed".equals(durationOrMissed);

            // Build card
            HBox callCard = new HBox(10);
            callCard.setAlignment(Pos.CENTER_LEFT);
            callCard.setPadding(new Insets(10, 14, 10, 14));
            callCard.setStyle(missed
                ? (mine ? "-fx-background-color: rgba(239,68,68,0.18); -fx-background-radius: 16;" : "-fx-background-color: rgba(239,68,68,0.12); -fx-background-radius: 16;")
                : (mine ? "-fx-background-color: rgba(255,255,255,0.1); -fx-background-radius: 16;"  : "-fx-background-color: #f0f9ff; -fx-background-radius: 16;"));

            Label phoneIcon = new Label(missed ? "📵" : "📞");
            phoneIcon.setStyle("-fx-font-size: 22px;");

            VBox callInfo = new VBox(2);
            Label callTitle = new Label(missed ? "Cuộc gọi nhỡ" : "Cuộc gọi thoại");
            callTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; " +
                (missed ? "-fx-text-fill: #ef4444;" : (mine ? "-fx-text-fill: white;" : "-fx-text-fill: #1f2937;")));
            Label callMeta = new Label(missed ? "Không có ai trả lời" : "Thời lượng: " + durationOrMissed);
            callMeta.setStyle("-fx-font-size: 11px; " + (mine ? "-fx-text-fill: rgba(255,255,255,0.65);" : "-fx-text-fill: #6b7280;"));
            callInfo.getChildren().addAll(callTitle, callMeta);

            callCard.getChildren().addAll(phoneIcon, callInfo);

            bubble.setStyle(missed
                ? (mine ? "-fx-background-color: transparent; -fx-padding: 0;" : "-fx-background-color: transparent; -fx-padding: 0;")
                : "-fx-background-color: transparent; -fx-padding: 0;");
            bubble.getChildren().addAll(callCard, time);

        } else if (text != null && text.startsWith("[MUSIC_INVITE:") && text.endsWith("]")) {
            // === Shared Music Room Invitation Card ===
            String inner = text.substring(14, text.length() - 1); // roomId:roomName
            String[] parts = inner.split(":", 2);
            String invRoomId = parts[0];
            String invRoomName = parts.length > 1 ? parts[1] : "Phòng nghe nhạc";

            VBox inviteCard = new VBox(10);
            inviteCard.setPadding(new Insets(14, 16, 14, 16));
            inviteCard.setStyle("-fx-background-color: linear-gradient(to bottom right, #1e1b4b, #312e81); -fx-background-radius: 18; -fx-min-width: 280; -fx-max-width: 340; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 10, 0, 0, 3);");

            HBox cardHead = new HBox(8);
            cardHead.setAlignment(Pos.CENTER_LEFT);
            Label musicIcon = new Label("🎧");
            musicIcon.setStyle("-fx-font-family: 'Segoe UI Emoji', sans-serif; -fx-font-size: 20px;");
            Label cardBadge = new Label("LỜI MỜI NGHE NHẠC");
            cardBadge.setStyle("-fx-font-size: 11px; -fx-font-weight: 800; -fx-text-fill: #a5b4fc;");
            cardHead.getChildren().addAll(musicIcon, cardBadge);

            Label titleLbl = new Label(invRoomName);
            titleLbl.setStyle("-fx-font-family: 'Segoe UI', Arial, sans-serif; -fx-font-size: 15px; -fx-font-weight: 800; -fx-text-fill: white;");
            titleLbl.setWrapText(true);

            Label descLbl = new Label(mine ? "Bạn đã gửi lời mời nghe nhạc tại phòng này" : (sender + " đã mời bạn cùng vào nghe nhạc!"));
            descLbl.setStyle("-fx-font-family: 'Segoe UI', Arial, sans-serif; -fx-font-size: 12px; -fx-text-fill: #c7d2fe;");
            descLbl.setWrapText(true);

            Button joinBtn = new Button("🎧 Tham gia phòng nghe nhạc");
            joinBtn.setStyle("-fx-font-family: 'Segoe UI Emoji', 'Segoe UI', sans-serif; -fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 12px; -fx-background-radius: 12; -fx-padding: 8 16; -fx-cursor: hand;");
            joinBtn.setMaxWidth(Double.MAX_VALUE);
            joinBtn.setOnMouseEntered(e -> joinBtn.setStyle("-fx-font-family: 'Segoe UI Emoji', 'Segoe UI', sans-serif; -fx-background-color: #6366f1; -fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 12px; -fx-background-radius: 12; -fx-padding: 8 16; -fx-cursor: hand;"));
            joinBtn.setOnMouseExited(e -> joinBtn.setStyle("-fx-font-family: 'Segoe UI Emoji', 'Segoe UI', sans-serif; -fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 12px; -fx-background-radius: 12; -fx-padding: 8 16; -fx-cursor: hand;"));
            joinBtn.setOnAction(e -> {
                musicPlayer.switchRoom(invRoomId, invRoomName);
                showMusic();
            });

            inviteCard.getChildren().addAll(cardHead, titleLbl, descLbl, joinBtn);
            bubble.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
            bubble.getChildren().addAll(inviteCard, time);

        } else if (text != null && text.startsWith("[IMAGE:") && text.endsWith("]")) {

            String inner = text.substring(7, text.length() - 1);
            int colonIdx = inner.indexOf(':');
            String attId = colonIdx != -1 ? inner.substring(0, colonIdx) : inner;
            String fileName = colonIdx != -1 ? inner.substring(colonIdx + 1) : "image.png";

            ChatRepository.Attachment att = chatRepository.getAttachment(attId);
            if (att != null && att.data() != null) {
                try {
                    Image img = new Image(new ByteArrayInputStream(att.data()));

                    // === Messenger-style image bubble ===
                    ImageView iv = new ImageView(img);
                    double imgW = img.getWidth();
                    double imgH = img.getHeight();
                    double maxW = 240.0, maxH = 200.0;
                    double scale = Math.min(maxW / Math.max(imgW, 1), maxH / Math.max(imgH, 1));
                    double dispW = Math.max(imgW * scale, 80);
                    double dispH = Math.max(imgH * scale, 60);
                    iv.setFitWidth(dispW);
                    iv.setFitHeight(dispH);
                    iv.setPreserveRatio(true);
                    iv.setSmooth(true);

                    // Dim overlay khi hover (label zoom icon)
                    Label zoomLbl = new Label("🔍");
                    zoomLbl.setStyle("-fx-font-size: 22px; -fx-text-fill: white;");
                    zoomLbl.setVisible(false);

                    StackPane dimOverlay = new StackPane(zoomLbl);
                    dimOverlay.setPrefSize(dispW, dispH);
                    dimOverlay.setMaxSize(dispW, dispH);
                    dimOverlay.setStyle("-fx-background-color: rgba(0,0,0,0.32); -fx-background-radius: 14;");
                    dimOverlay.setOpacity(0);

                    StackPane imgBox = new StackPane(iv, dimOverlay);
                    imgBox.setStyle("-fx-background-radius: 14; -fx-cursor: hand; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.18), 6, 0, 0, 2);");
                    imgBox.setMaxSize(dispW, dispH);

                    // Hover effects
                    imgBox.setOnMouseEntered(e -> {
                        dimOverlay.setOpacity(1);
                        zoomLbl.setVisible(true);
                    });
                    imgBox.setOnMouseExited(e -> {
                        dimOverlay.setOpacity(0);
                        zoomLbl.setVisible(false);
                    });
                    imgBox.setOnMouseClicked(e -> showImagePreviewDialog(fileName, img, att.data()));

                    // Bubble transparent — ảnh IS the bubble
                    bubble.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
                    bubble.getChildren().addAll(imgBox, time);
                } catch (Exception ex) {
                    Label err = new Label("📷 [Lỗi hiển thị ảnh: " + fileName + "]");
                    bubble.getChildren().addAll(err, time);
                }
            } else {
                Label missing = new Label("📷 [Hình ảnh: " + fileName + "]");
                bubble.getChildren().addAll(missing, time);
            }
        } else if (text != null && text.startsWith("[FILE:") && text.endsWith("]")) {
            String inner = text.substring(6, text.length() - 1);
            String[] parts = inner.split(":", 3);
            String attId = parts[0];
            String fileName = parts.length > 1 ? parts[1] : "Tệp tin";
            String sizeStr = parts.length > 2 ? parts[2] : "";

            HBox fileCard = new HBox(10);
            fileCard.setAlignment(Pos.CENTER_LEFT);
            fileCard.setStyle(mine ?
                "-fx-background-color: rgba(255,255,255,0.18); -fx-background-radius: 10; -fx-padding: 8 12; -fx-cursor: hand;" :
                "-fx-background-color: #f3f4f6; -fx-background-radius: 10; -fx-padding: 8 12; -fx-cursor: hand;");

            String fileIcon = "📄";
            String lowerName = fileName.toLowerCase();
            if (lowerName.endsWith(".pdf")) fileIcon = "📕";
            else if (lowerName.endsWith(".doc") || lowerName.endsWith(".docx")) fileIcon = "📘";
            else if (lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx")) fileIcon = "📗";
            else if (lowerName.endsWith(".ppt") || lowerName.endsWith(".pptx")) fileIcon = "📙";
            else if (lowerName.endsWith(".zip") || lowerName.endsWith(".rar") || lowerName.endsWith(".7z")) fileIcon = "📦";
            else if (lowerName.endsWith(".mp3") || lowerName.endsWith(".wav")) fileIcon = "🎵";
            else if (lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv")) fileIcon = "🎬";

            Label iconLbl = new Label(fileIcon);
            iconLbl.setStyle("-fx-font-size: 24px;");

            VBox fileMeta = new VBox(2);
            Label nameLbl = new Label(fileName);
            nameLbl.setStyle("-fx-font-weight: 700; -fx-font-size: 13px; " + (mine ? "-fx-text-fill: white;" : "-fx-text-fill: #1f2937;"));
            nameLbl.setMaxWidth(200);
            nameLbl.setWrapText(true);

            Label sLbl = new Label(sizeStr);
            sLbl.setStyle("-fx-font-size: 11px; " + (mine ? "-fx-text-fill: #e0e7ff;" : "-fx-text-fill: #6b7280;"));
            fileMeta.getChildren().addAll(nameLbl, sLbl);
            HBox.setHgrow(fileMeta, Priority.ALWAYS);

            Button dlBtn = new Button("⬇ Tải về");
            dlBtn.setStyle(mine ?
                "-fx-background-color: white; -fx-text-fill: #6366f1; -fx-font-weight: 700; -fx-font-size: 11px; -fx-background-radius: 6; -fx-cursor: hand;" :
                "-fx-background-color: #6366f1; -fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 11px; -fx-background-radius: 6; -fx-cursor: hand;");
            dlBtn.setOnAction(e -> {
                ChatRepository.Attachment att = chatRepository.getAttachment(attId);
                if (att != null && att.data() != null) {
                    FileChooser saveChooser = new FileChooser();
                    saveChooser.setInitialFileName(fileName);
                    File target = saveChooser.showSaveDialog(scene != null && scene.getWindow() != null ? scene.getWindow() : null);
                    if (target != null) {
                        try {
                            Files.write(target.toPath(), att.data());
                            toast("Đã lưu tệp: " + target.getName());
                        } catch (Exception ex) {
                            toast("Lỗi lưu tệp: " + ex.getMessage());
                        }
                    }
                } else {
                    toast("Không tìm thấy dữ liệu tệp.");
                }
            });

            fileCard.getChildren().addAll(iconLbl, fileMeta, dlBtn);
            bubble.getChildren().addAll(fileCard, time);
        } else {
            Label body = new Label(text);
            body.setWrapText(true);
            body.setMaxWidth(390);
            bubble.getChildren().addAll(body, time);
        }

        VBox cluster = new VBox(4);
        if (!mine) {
            Label who = new Label(sender);
            who.getStyleClass().add("message-sender");
            cluster.getChildren().add(who);
        }
        cluster.getChildren().add(bubble);
        HBox line = new HBox(12);
        line.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        if (!mine) line.getChildren().addAll(avatar(initials(sender), "avatar-person"), cluster);
        else line.getChildren().add(cluster);
        messages.getChildren().add(line);
    }

    private record ColorEmojiItem(String code, String name, String category) {}

    private static final List<ColorEmojiItem> COLOR_EMOJIS = List.of(
        // Mặt cười & Cảm xúc
        new ColorEmojiItem("1f600", "Cười tươi", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f603", "Cười lớn", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f604", "Hớn hở", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f601", "Cười tít mắt", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f606", "Cười sảng khoái", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f602", "Cười ra nước mắt", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f923", "Cười nghiêng ngả", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f60a", "Thẹn thùng", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f607", "Thiên thần", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f642", "Mỉm cười", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f643", "Ngược đời", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f609", "Nháy mắt", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f60d", "Mê mẩn", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f970", "Yêu thương", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f618", "Hôn gió", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f60b", "Ngon miệng", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f61b", "Lè lưỡi", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f61c", "Lém lỉnh", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f92a", "Nghịch ngợm", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f917", "Ôm ấm áp", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f914", "Suy nghĩ", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f92b", "Suỵt im lặng", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f925", "Nói dối", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f60e", "Cực ngầu", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f973", "Tiệc tùng", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f92f", "Bùng nổ não", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f631", "Hốt hoảng", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f62d", "Khóc to", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f621", "Tức giận", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f97a", "Năn nỉ đáng yêu", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f924", "Thèm thuồng", "Mặt cười & Cảm xúc"),
        new ColorEmojiItem("1f634", "Ngủ ngon", "Mặt cười & Cảm xúc"),

        // Cử chỉ & Thả tim
        new ColorEmojiItem("1f44d", "Tuyệt vời (Like)", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("1f44e", "Dislike", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("1f44f", "Vỗ tay tán thưởng", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("1f64c", "Hoan hô", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("1f91d", "Bắt tay hợp tác", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("270c", "Chiến thắng (Peace)", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("1f91e", "Chúc may mắn", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("1f64f", "Cảm ơn / Cầu chúc", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("1f4aa", "Cố lên (Cơ bắp)", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("2764", "Trái tim đỏ", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("1f496", "Tim lấp lánh", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("1f494", "Tan vỡ", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("1f495", "Hai trái tim", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("1f525", "Cháy quá (Fire)", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("2728", "Lấp lánh", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("1f389", "Pháo tiệc chúc mừng", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("1f4af", "100 điểm", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("1f680", "Tên lửa bay cao", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("2b50", "Ngôi sao vàng", "Cử chỉ & Thả tim"),
        new ColorEmojiItem("1f4a5", "Bùng nổ", "Cử chỉ & Thả tim"),

        // Học tập & Đồ vật
        new ColorEmojiItem("1f4da", "Sách vở học tập", "Học tập & Đồ vật"),
        new ColorEmojiItem("1f4d6", "Mở sách", "Học tập & Đồ vật"),
        new ColorEmojiItem("1f4dd", "Ghi chú bài học", "Học tập & Đồ vật"),
        new ColorEmojiItem("270f", "Bút chì", "Học tập & Đồ vật"),
        new ColorEmojiItem("1f393", "Tốt nghiệp / Cử nhân", "Học tập & Đồ vật"),
        new ColorEmojiItem("1f392", "Balo đi học", "Học tập & Đồ vật"),
        new ColorEmojiItem("1f4bb", "Laptop máy tính", "Học tập & Đồ vật"),
        new ColorEmojiItem("1f4ca", "Biểu đồ cột", "Học tập & Đồ vật"),
        new ColorEmojiItem("1f4c8", "Biểu đồ tăng trưởng", "Học tập & Đồ vật"),
        new ColorEmojiItem("23f0", "Đồng hồ báo thức", "Học tập & Đồ vật"),
        new ColorEmojiItem("1f3c6", "Cúp vàng vô địch", "Học tập & Đồ vật"),
        new ColorEmojiItem("1f947", "Huy chương vàng", "Học tập & Đồ vật"),
        new ColorEmojiItem("1f3af", "Trúng đích", "Học tập & Đồ vật"),
        new ColorEmojiItem("1f4a1", "Ý tưởng sáng tạo", "Học tập & Đồ vật"),
        new ColorEmojiItem("1f4cc", "Ghim bài", "Học tập & Đồ vật"),
        new ColorEmojiItem("1f514", "Chuông thông báo", "Học tập & Đồ vật"),
        new ColorEmojiItem("2615", "Cà phê tỉnh táo", "Học tập & Đồ vật"),
        new ColorEmojiItem("1f355", "Pizza tiếp sức", "Học tập & Đồ vật"),
        new ColorEmojiItem("2705", "Đã hoàn thành", "Học tập & Đồ vật"),
        new ColorEmojiItem("274c", "Sai / Chưa đúng", "Học tập & Đồ vật"),
        new ColorEmojiItem("26a0", "Lưu ý quan trọng", "Học tập & Đồ vật")
    );

    private Popup createEmojiPicker(TextField targetField, String roomId, ScrollPane scroll) {
        Popup popup = new Popup();
        popup.setAutoHide(true);

        VBox box = new VBox(10);
        box.setStyle("-fx-background-color: white; -fx-background-radius: 14; -fx-padding: 12; " +
                "-fx-border-color: #e5e7eb; -fx-border-radius: 14; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.18), 18, 0.2, 0, 4);");
        box.setPrefWidth(350);
        box.setMaxWidth(350);

        HBox tabBar = new HBox(8);
        tabBar.setAlignment(Pos.CENTER_LEFT);

        Button tabColorBtn = new Button("🎨 Icon Màu Sắc");
        Button tabTextBtn = new Button("🔤 Ký tự Text");

        String activeTabStyle = "-fx-background-color: #6366f1; -fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 11px; -fx-background-radius: 20; -fx-padding: 5 14; -fx-cursor: hand;";
        String inactiveTabStyle = "-fx-background-color: #f3f4f6; -fx-text-fill: #4b5563; -fx-font-weight: 700; -fx-font-size: 11px; -fx-background-radius: 20; -fx-padding: 5 14; -fx-cursor: hand;";

        tabColorBtn.setStyle(activeTabStyle);
        tabTextBtn.setStyle(inactiveTabStyle);
        tabBar.getChildren().addAll(tabColorBtn, tabTextBtn);

        StackPane contentStack = new StackPane();

        // 1. Color Emojis / Stickers View
        VBox colorContainer = new VBox(8);
        java.util.Map<String, FlowPane> catPanes = new java.util.LinkedHashMap<>();
        for (ColorEmojiItem item : COLOR_EMOJIS) {
            FlowPane pane = catPanes.computeIfAbsent(item.category(), cat -> {
                FlowPane f = new FlowPane(4, 4);
                f.setPrefWrapLength(320);
                return f;
            });

            var is = getClass().getResourceAsStream("/emojis/" + item.code() + ".png");
            if (is != null) {
                Image img = new Image(is);
                ImageView iv = new ImageView(img);
                iv.setFitWidth(28);
                iv.setFitHeight(28);
                iv.setPreserveRatio(true);
                iv.setSmooth(true);

                Button btn = new Button("", iv);
                btn.setStyle("-fx-background-color: transparent; -fx-padding: 3; -fx-cursor: hand; -fx-background-radius: 6;");
                btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: #e0e7ff; -fx-padding: 3; -fx-cursor: hand; -fx-background-radius: 6;"));
                btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: transparent; -fx-padding: 3; -fx-cursor: hand; -fx-background-radius: 6;"));
                Tooltip.install(btn, new Tooltip(item.name() + " (Bấm để gửi)"));

                btn.setOnAction(e -> {
                    String payload = "[STICKER:" + item.code() + "]";
                    addMessage(roomId, user.displayName(), payload, true, true);
                    if (node != null) node.broadcast(user.displayName(), payload);
                    scroll.setVvalue(1.0);
                    popup.hide();
                });
                pane.getChildren().add(btn);
            }
        }

        for (java.util.Map.Entry<String, FlowPane> entry : catPanes.entrySet()) {
            Label catTitle = new Label(entry.getKey());
            catTitle.setStyle("-fx-font-size: 11px; -fx-font-weight: 800; -fx-text-fill: #6366f1; -fx-padding: 4 0 2 0;");
            colorContainer.getChildren().addAll(catTitle, entry.getValue());
        }

        ScrollPane colorScroll = new ScrollPane(colorContainer);
        colorScroll.setFitToWidth(true);
        colorScroll.setPrefHeight(250);
        colorScroll.setMaxHeight(250);
        colorScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-padding: 0;");
        colorScroll.getStyleClass().add("thread-scroll");

        // 2. Text Emojis View
        VBox textContainer = new VBox(8);
        String[][] categories = {
            {"Cảm xúc", "😀,😃,😄,😁,😆,😅,😂,🤣,😊,😇,🙂,🙃,😉,😌,😍,🥰,😘,😋,😛,😜,🤪,😝,🤗,🤭,🤔,🤫,🤐,🤨,😐,😑,😶,😏,😒,🙄,😬,😴,😷,🤯,🥳,😎"},
            {"Cử chỉ & Tim", "👍,👎,👏,🙌,🤝,✌,🤞,🤟,🤙,👈,👉,👆,👇,☝,✋,🙏,❤,🧡,💛,💚,💙,💜,🖤,💔,❣,💕,💞,💓,💗,💖,💘,✨,🔥,🌟,⭐,💯,🎉,🎊,🚀,💡"},
            {"Học tập & Đồ vật", "📚,📖,📝,✏,🖊,🎓,🎒,💻,📱,📊,📈,📅,🕒,⏰,🏆,🥇,🎯,📌,📎,☕,🍕,🍔,🍰,🎁,⚽,🏀,🎨,🎵,🎶,🔔,📣,🔍,🔒,🔑,✅,❌,⚠,❓,❗"}
        };
        for (String[] cat : categories) {
            Label catLabel = new Label(cat[0]);
            catLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #6b7280; -fx-padding: 2 0 0 0;");
            FlowPane flow = new FlowPane(4, 4);
            flow.setPrefWrapLength(320);
            for (String em : cat[1].replace("\uFE0F", "").split(",")) {
                Button btn = new Button(em);
                btn.setStyle("-fx-background-color: transparent; -fx-font-family: 'Segoe UI Emoji', 'Segoe UI Symbol', sans-serif; -fx-font-size: 18px; -fx-padding: 4 6; -fx-cursor: hand; -fx-background-radius: 6;");
                btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: #f3f4f6; -fx-font-family: 'Segoe UI Emoji', 'Segoe UI Symbol', sans-serif; -fx-font-size: 18px; -fx-padding: 4 6; -fx-cursor: hand; -fx-background-radius: 6;"));
                btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: transparent; -fx-font-family: 'Segoe UI Emoji', 'Segoe UI Symbol', sans-serif; -fx-font-size: 18px; -fx-padding: 4 6; -fx-cursor: hand; -fx-background-radius: 6;"));
                btn.setOnAction(e -> {
                    int caret = targetField.getCaretPosition();
                    String cur = targetField.getText();
                    if (cur == null) cur = "";
                    if (caret < 0 || caret > cur.length()) caret = cur.length();
                    targetField.setText(cur.substring(0, caret) + em + cur.substring(caret));
                    targetField.positionCaret(caret + em.length());
                    targetField.requestFocus();
                });
                flow.getChildren().add(btn);
            }
            textContainer.getChildren().addAll(catLabel, flow);
        }
        ScrollPane textScroll = new ScrollPane(textContainer);
        textScroll.setFitToWidth(true);
        textScroll.setPrefHeight(250);
        textScroll.setMaxHeight(250);
        textScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-padding: 0;");
        textScroll.getStyleClass().add("thread-scroll");
        textScroll.setVisible(false);

        tabColorBtn.setOnAction(e -> {
            tabColorBtn.setStyle(activeTabStyle);
            tabTextBtn.setStyle(inactiveTabStyle);
            colorScroll.setVisible(true);
            textScroll.setVisible(false);
        });

        tabTextBtn.setOnAction(e -> {
            tabTextBtn.setStyle(activeTabStyle);
            tabColorBtn.setStyle(inactiveTabStyle);
            textScroll.setVisible(true);
            colorScroll.setVisible(false);
        });

        contentStack.getChildren().addAll(colorScroll, textScroll);
        box.getChildren().addAll(tabBar, contentStack);
        popup.getContent().add(box);
        return popup;
    }

    private void handleSendImage(String roomId, ScrollPane scroll) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn hình ảnh để gửi");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Hình ảnh (*.png, *.jpg, *.jpeg, *.gif, *.webp)", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.bmp"));
        File file = chooser.showOpenDialog(scene != null && scene.getWindow() != null ? scene.getWindow() : null);
        if (file != null) {
            if (file.length() > 25 * 1024 * 1024) {
                toast("File ảnh quá lớn (> 25MB).");
                return;
            }
            try {
                byte[] data = Files.readAllBytes(file.toPath());
                String ext = file.getName().contains(".") ? file.getName().substring(file.getName().lastIndexOf('.') + 1).toLowerCase() : "png";
                String attId = chatRepository.saveAttachment(roomId, user.displayName(), file.getName(), "image/" + ext, file.length(), data);
                String payload = "[IMAGE:" + attId + ":" + file.getName() + "]";
                addMessage(roomId, user.displayName(), payload, true, true);
                if (node != null) node.broadcast(user.displayName(), payload);
                scroll.setVvalue(1.0);
            } catch (Exception ex) {
                toast("Lỗi khi gửi ảnh: " + ex.getMessage());
            }
        }
    }

    private void handleSendFile(String roomId, ScrollPane scroll) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn tệp tài liệu để gửi");
        File file = chooser.showOpenDialog(scene != null && scene.getWindow() != null ? scene.getWindow() : null);
        if (file != null) {
            if (file.length() > 50 * 1024 * 1024) {
                toast("Tệp tin quá lớn (> 50MB).");
                return;
            }
            try {
                byte[] data = Files.readAllBytes(file.toPath());
                String ext = file.getName().contains(".") ? file.getName().substring(file.getName().lastIndexOf('.') + 1).toLowerCase() : "bin";
                String sizeStr = formatFileSize(file.length());
                String attId = chatRepository.saveAttachment(roomId, user.displayName(), file.getName(), ext, file.length(), data);
                String payload = "[FILE:" + attId + ":" + file.getName() + ":" + sizeStr + "]";
                addMessage(roomId, user.displayName(), payload, true, true);
                if (node != null) node.broadcast(user.displayName(), payload);
                scroll.setVvalue(1.0);
            } catch (Exception ex) {
                toast("Lỗi khi gửi tệp: " + ex.getMessage());
            }
        }
    }

    private void showImagePreviewDialog(String fileName, Image img, byte[] rawBytes) {
        // === Messenger-style Lightbox ===
        Stage lightbox = new Stage();
        lightbox.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        lightbox.initStyle(javafx.stage.StageStyle.UNDECORATED);
        lightbox.setTitle("Xem ảnh · " + fileName);

        ImageView iv = new ImageView(img);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);

        double screenW = javafx.stage.Screen.getPrimary().getVisualBounds().getWidth();
        double screenH = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight();
        double maxW = screenW * 0.85;
        double maxH = screenH * 0.78;
        iv.setFitWidth(maxW);
        iv.setFitHeight(maxH);

        // File name label top
        Label nameLbl = new Label(fileName);
        nameLbl.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(255,255,255,0.75); -fx-font-weight: 500;");

        // Top bar
        Button closeTopBtn = new Button("✕");
        closeTopBtn.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: white; -fx-font-size: 14px; -fx-background-radius: 20; -fx-min-width: 32; -fx-min-height: 32; -fx-cursor: hand; -fx-padding: 0;");
        closeTopBtn.setOnAction(e -> lightbox.close());

        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        HBox topBar = new HBox(12, nameLbl, topSpacer, closeTopBtn);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(12, 16, 12, 16));
        topBar.setStyle("-fx-background-color: rgba(0,0,0,0.45);");

        // Image container
        StackPane imageContainer = new StackPane(iv);
        imageContainer.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(imageContainer, Priority.ALWAYS);

        // Bottom action bar
        Button saveBtn = new Button("💾  Lưu về máy");
        saveBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 13px; -fx-background-radius: 20; -fx-padding: 8 20; -fx-cursor: hand;");
        saveBtn.setOnAction(e -> {
            FileChooser saveChooser = new FileChooser();
            saveChooser.setInitialFileName(fileName);
            File target = saveChooser.showSaveDialog(lightbox);
            if (target != null) {
                try {
                    Files.write(target.toPath(), rawBytes);
                    toast("Đã lưu ảnh thành công!");
                } catch (Exception ex) {
                    toast("Lỗi lưu ảnh: " + ex.getMessage());
                }
            }
        });

        Button closeBotBtn = new Button("Đóng");
        closeBotBtn.setStyle("-fx-background-color: rgba(255,255,255,0.12); -fx-text-fill: white; -fx-font-size: 13px; -fx-background-radius: 20; -fx-padding: 8 20; -fx-cursor: hand;");
        closeBotBtn.setOnAction(e -> lightbox.close());

        HBox bottomBar = new HBox(10, saveBtn, closeBotBtn);
        bottomBar.setAlignment(Pos.CENTER);
        bottomBar.setPadding(new Insets(14, 16, 14, 16));
        bottomBar.setStyle("-fx-background-color: rgba(0,0,0,0.55);");

        VBox root = new VBox(topBar, imageContainer, bottomBar);
        root.setStyle("-fx-background-color: rgba(10,10,10,0.92);");

        // Click nền tối để đóng
        imageContainer.setOnMouseClicked(e -> {
            if (e.getTarget() == imageContainer) lightbox.close();
        });

        Scene lbScene = new Scene(root, Math.min(img.getWidth() + 80, maxW + 80), Math.min(img.getHeight() + 120, maxH + 120));
        lbScene.setFill(javafx.scene.paint.Color.rgb(10, 10, 10, 0.92));
        lbScene.getStylesheets().addAll(
            getClass().getResource("/tokens.css").toExternalForm(),
            getClass().getResource("/studyroom.css").toExternalForm()
        );
        // ESC để đóng
        lbScene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) lightbox.close();
        });

        lightbox.setScene(lbScene);
        lightbox.centerOnScreen();
        lightbox.show();
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
    private void createGroup() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Tạo nhóm mới");
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().getStylesheets().addAll(
            getClass().getResource("/tokens.css").toExternalForm(),
            getClass().getResource("/studyroom.css").toExternalForm()
        );
        dialog.getDialogPane().getStyleClass().add("custom-dialog");

        VBox box = new VBox(12);
        box.setPadding(new Insets(16, 20, 16, 20));
        box.setMinWidth(380);

        Label title = new Label("Tạo nhóm học tập");
        title.getStyleClass().add("dialog-title");
        Label desc = new Label("Đặt tên cho nhóm để cùng bạn bè trao đổi tài liệu và học tập.");
        desc.getStyleClass().add("dialog-desc");
        desc.setWrapText(true);

        TextField nameInput = new TextField();
        nameInput.setPromptText("Ví dụ: Nhóm Ôn thi Toán 12, CLB Tiếng Anh...");
        nameInput.getStyleClass().add("input");

        box.getChildren().addAll(title, desc, nameInput);
        dialog.getDialogPane().setContent(box);

        ButtonType createType = new ButtonType("Tạo nhóm", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(cancelType, createType);

        Button createBtn = (Button) dialog.getDialogPane().lookupButton(createType);
        createBtn.getStyleClass().addAll("button", "button-primary");
        Button cancelBtn = (Button) dialog.getDialogPane().lookupButton(cancelType);
        cancelBtn.getStyleClass().add("button");

        nameInput.setOnAction(e -> createBtn.fire());
        Platform.runLater(nameInput::requestFocus);

        dialog.setResultConverter(btn -> btn == createType ? nameInput.getText().trim() : null);

        dialog.showAndWait().ifPresent(name -> {
            if (name.isEmpty()) return;
            try {
                ChatRepository.Room room = chatRepository.createRoom(user.username(), name);
                showChat(room.id(), room.name(), true);
            } catch (IllegalArgumentException e) {
                toast(e.getMessage());
            }
        });
    }
    private void showAddMemberDialog(String roomId, String roomName) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Thêm thành viên");
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().getStylesheets().addAll(
            getClass().getResource("/tokens.css").toExternalForm(),
            getClass().getResource("/studyroom.css").toExternalForm()
        );
        dialog.getDialogPane().getStyleClass().add("custom-dialog");

        VBox root = new VBox(12);
        root.setPadding(new Insets(16, 20, 16, 20));
        root.setMinWidth(380);

        Label title = new Label("Thêm thành viên");
        title.getStyleClass().add("dialog-title");
        Label desc = new Label("Thêm người dùng vào nhóm \"" + roomName + "\"");
        desc.getStyleClass().add("dialog-desc");

        VBox body = new VBox(8);
        List<User> available = chatRepository.nonMembers(roomId);
        if (available.isEmpty()) {
            Label empty = new Label("Không còn người dùng nào để thêm.");
            empty.getStyleClass().add("muted");
            body.getChildren().add(empty);
        } else {
            available.forEach(person -> {
                HBox row = new HBox(10);
                row.setAlignment(Pos.CENTER_LEFT);
                Label name = new Label(person.displayName());
                name.setStyle("-fx-font-weight: bold;");
                HBox.setHgrow(name, Priority.ALWAYS);
                Button add = new Button("Thêm");
                add.getStyleClass().addAll("button", "button-primary");
                add.setOnAction(e -> {
                    chatRepository.addMember(roomId, person.username());
                    add.setText("Đã thêm ✓");
                    add.setDisable(true);
                });
                row.getChildren().addAll(name, add);
                body.getChildren().add(row);
            });
        }
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setMaxHeight(240);
        scroll.getStyleClass().add("thread-scroll");

        root.getChildren().addAll(title, desc, scroll);
        dialog.getDialogPane().setContent(root);

        ButtonType closeType = new ButtonType("Đóng", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeType);
        Button closeBtn = (Button) dialog.getDialogPane().lookupButton(closeType);
        closeBtn.getStyleClass().add("button");

        dialog.setOnHidden(e -> showChat(roomId, roomName, true));
        dialog.show();
    }
    private void showAddFriendDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Kết bạn");
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().getStylesheets().addAll(
            getClass().getResource("/tokens.css").toExternalForm(),
            getClass().getResource("/studyroom.css").toExternalForm()
        );
        dialog.getDialogPane().getStyleClass().add("custom-dialog");

        VBox root = new VBox(12);
        root.setPadding(new Insets(16, 20, 16, 20));
        root.setMinWidth(400);

        Label title = new Label("Kết bạn mới");
        title.getStyleClass().add("dialog-title");
        Label desc = new Label("Tìm kiếm người dùng bằng tên hoặc tên đăng nhập");
        desc.getStyleClass().add("dialog-desc");

        HBox searchRow = new HBox(8); searchRow.setAlignment(Pos.CENTER_LEFT);
        TextField searchInput = new TextField(); searchInput.setPromptText("Nhập tên đăng nhập hoặc tên...");
        searchInput.getStyleClass().add("input"); HBox.setHgrow(searchInput, Priority.ALWAYS);
        Button searchBtn = new Button("Tìm kiếm"); searchBtn.getStyleClass().addAll("button", "button-primary");
        searchRow.getChildren().addAll(searchInput, searchBtn);

        VBox resultsBox = new VBox(8);
        ScrollPane scrollPane = new ScrollPane(resultsBox); scrollPane.setFitToWidth(true);
        scrollPane.setMinHeight(180); scrollPane.setMaxHeight(280); scrollPane.getStyleClass().add("thread-scroll");

        Label hintLabel = new Label("Nhập tên đăng nhập hoặc tên để tìm người dùng."); hintLabel.getStyleClass().add("muted");
        resultsBox.getChildren().add(hintLabel);

        Runnable doSearch = () -> {
            resultsBox.getChildren().clear();
            String q = searchInput.getText().trim();
            if (q.isEmpty()) {
                resultsBox.getChildren().add(new Label("Vui lòng nhập tên đăng nhập để tìm kiếm."));
                return;
            }
            List<FriendRepository.UserSearchResult> matches = friendRepo.searchUsers(user.username(), q);
            if (matches.isEmpty()) {
                resultsBox.getChildren().add(new Label("Không tìm thấy người dùng phù hợp."));
            } else {
                matches.forEach(item -> {
                    HBox row = new HBox(10); row.setAlignment(Pos.CENTER_LEFT); row.setPadding(new Insets(4, 0, 4, 0));
                    VBox userCopy = new VBox(2);
                    Label dn = new Label(item.user().displayName()); dn.setStyle("-fx-font-weight: bold;");
                    Label un = new Label("@" + item.user().username()); un.getStyleClass().add("muted"); un.setStyle("-fx-font-size: 11px;");
                    userCopy.getChildren().addAll(dn, un);
                    HBox.setHgrow(userCopy, Priority.ALWAYS);

                    Button actionBtn = new Button();
                    switch (item.status()) {
                        case "friend" -> {
                            actionBtn.setText("Đã là bạn");
                            actionBtn.setDisable(true);
                            actionBtn.getStyleClass().add("button");
                        }
                        case "pending_sent" -> {
                            actionBtn.setText("Đã gửi lời mời");
                            actionBtn.setDisable(true);
                            actionBtn.getStyleClass().add("button");
                        }
                        case "pending_received" -> {
                            actionBtn.setText("Chấp nhận");
                            actionBtn.getStyleClass().addAll("button", "button-primary");
                            actionBtn.setOnAction(e -> {
                                friendRepo.acceptRequest(item.user().username(), user.username());
                                actionBtn.setText("Đã là bạn");
                                actionBtn.setDisable(true);
                            });
                        }
                        default -> {
                            actionBtn.setText("Kết bạn");
                            actionBtn.getStyleClass().addAll("button", "button-primary");
                            actionBtn.setOnAction(e -> {
                                friendRepo.sendRequest(user.username(), item.user().username());
                                actionBtn.setText("Đã gửi ✓");
                                actionBtn.setDisable(true);
                            });
                        }
                    }
                    row.getChildren().addAll(userCopy, actionBtn);
                    resultsBox.getChildren().add(row);
                });
            }
        };

        searchBtn.setOnAction(e -> doSearch.run());
        searchInput.setOnAction(e -> doSearch.run());
        searchInput.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isBlank()) doSearch.run();
            else { resultsBox.getChildren().clear(); resultsBox.getChildren().add(hintLabel); }
        });

        root.getChildren().addAll(searchRow, scrollPane);
        dialog.getDialogPane().setContent(root);
        ButtonType closeType = new ButtonType("Đóng", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeType);
        Button closeBtn = (Button) dialog.getDialogPane().lookupButton(closeType);
        closeBtn.getStyleClass().add("button");
        dialog.setOnHidden(e -> showChat());
        dialog.show();
    }
    private void startOrJoinCall(String roomId, String roomName, String callType, boolean startWithVideo) {
        CallRepository.CallSession session = callRepo.getActiveCall(roomId);
        if (session == null) {
            String myIp = VoiceEngine.getLocalIp();
            String targetUser = "DIRECT".equalsIgnoreCase(callType) ? currentDirectUsername : null;
            if (targetUser == null && !"GROUP".equalsIgnoreCase(callType) && roomId != null && roomId.startsWith("direct:")) {
                for (User f : friendRepo.friends(user.username())) {
                    if (directRoomId(user.username(), f.username()).equals(roomId)) {
                        targetUser = f.username();
                        break;
                    }
                }
            }
            session = callRepo.startCall(roomId, roomName, user.username(), user.displayName(), targetUser, callType, 5100, myIp);
            if (node != null) {
                String pkt = "INCOMING_CALL|" + session.callId() + "|" + roomId + "|" + user.username() + "|" + user.displayName() + "|" + (targetUser != null ? targetUser : "") + "|" + callType;
                node.broadcast(user.displayName(), pkt);
            }
        }
        final CallRepository.CallSession finalSession = session;
        CallWindow callWin = new CallWindow(session, user, callRepo);
        if (startWithVideo) {
            callWin.setAutoStartCamera(true);
        }
        callWin.setOnCallEnded(duration -> {
            String payload;
            if (duration != null) {
                payload = "[CALL:" + duration + ":" + finalSession.roomName() + "]";
            } else {
                payload = "[CALL:missed:" + finalSession.roomName() + "]";
            }
            addMessage(finalSession.roomId(), user.displayName(), payload, true, true);
            if (node != null) node.broadcast(user.displayName(), payload);
        });
        callWin.start();
        showChat(roomId, roomName, "GROUP".equals(callType));
    }



    // ─── Ringtone helper ────────────────────────────────────────────────────────
    private List<Clip> startRingtone() {
        List<Clip> clips = new ArrayList<>();
        try {
            float sampleRate = 44100f;
            int durationMs = 3200; // 3.2 sec of audio data
            int numSamples = (int) (sampleRate * durationMs / 1000);
            byte[] buf = new byte[numSamples * 2];

            // Phone-style ring: alternating tones (dual-tone: 480Hz + 620Hz) with on/off envelope
            // Ring pattern: 0.4s ON, 0.2s OFF, 0.4s ON, 0.2s OFF, 2.0s silence
            double ringOn1Start = 0, ringOn1End = 0.4;
            double ringOff1Start = 0.4, ringOff1End = 0.6;
            double ringOn2Start = 0.6, ringOn2End = 1.0;

            for (int i = 0; i < numSamples; i++) {
                double t = i / sampleRate;
                double cycleLen = 2.0; // full pattern = 2s
                double tInCycle = t % cycleLen;

                boolean on = (tInCycle >= ringOn1Start && tInCycle < ringOn1End)
                          || (tInCycle >= ringOn2Start && tInCycle < ringOn2End);

                double sample = 0;
                if (on) {
                    // Dual-tone: 480 Hz + 620 Hz (US telephone ring)
                    sample = 0.45 * Math.sin(2 * Math.PI * 480 * t)
                           + 0.45 * Math.sin(2 * Math.PI * 620 * t);
                    // Soft attack/release at edges of ring burst
                    double fadeLen = 0.025;
                    double inCycle = tInCycle >= ringOn2Start ? tInCycle - ringOn2Start : tInCycle - ringOn1Start;
                    double burstLen = tInCycle >= ringOn2Start ? (ringOn2End - ringOn2Start) : (ringOn1End - ringOn1Start);
                    double fadeIn = Math.min(inCycle / fadeLen, 1.0);
                    double fadeOut = Math.min((burstLen - inCycle) / fadeLen, 1.0);
                    sample *= Math.min(fadeIn, fadeOut);
                }

                short val = (short) (sample * 32000);
                buf[2 * i]     = (byte) (val & 0xFF);
                buf[2 * i + 1] = (byte) ((val >> 8) & 0xFF);
            }

            AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(Clip.class, fmt);

            // Ring across all active playback devices:
            // This guarantees ringtone plays on built-in speakers even if headphones are unplugged
            // (or if headphones are plugged in but left on the desk)!
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                String desc = mi.getDescription();
                String name = mi.getName();
                if (desc.contains("Playback") || name.contains("Primary") || name.contains("Speakers")
                        || name.contains("Headphones") || name.contains("Loa") || name.contains("Tai nghe")) {
                    try {
                        Mixer m = AudioSystem.getMixer(mi);
                        if (m.isLineSupported(info)) {
                            Clip clip = (Clip) m.getLine(info);
                            clip.open(fmt, buf, 0, buf.length);
                            try {
                                FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                                gain.setValue(gain.getMaximum());
                            } catch (Exception ignored) {}
                            clip.loop(Clip.LOOP_CONTINUOUSLY);
                            clips.add(clip);
                        }
                    } catch (Exception ignored) { }
                }
            }

            // Fallback to default system line if no specific mixer opened
            if (clips.isEmpty()) {
                try {
                    Clip defaultClip = (Clip) AudioSystem.getLine(info);
                    defaultClip.open(fmt, buf, 0, buf.length);
                    try {
                        FloatControl gain = (FloatControl) defaultClip.getControl(FloatControl.Type.MASTER_GAIN);
                        gain.setValue(gain.getMaximum());
                    } catch (Exception ignored) {}
                    defaultClip.loop(Clip.LOOP_CONTINUOUSLY);
                    clips.add(defaultClip);
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return clips;
    }

    private final Set<String> promptedCallIds = Collections.synchronizedSet(new HashSet<>());

    private void promptIncomingCall(CallRepository.CallSession incoming) {
        if (incoming == null || incoming.callId() == null) return;
        if (!promptedCallIds.add(incoming.callId())) {
            return;
        }
        // ── 1. Start ringtone across all playback devices ───────────────────────
        List<Clip> ringClips = startRingtone();
        Runnable stopRing = () -> {
            for (Clip c : ringClips) {
                try {
                    c.stop();
                    c.close();
                } catch (Exception ignored) { }
            }
            ringClips.clear();
        };

        // ── 2. Build Incoming Call Stage (floating window) ───────────────────
        Stage callStage = new Stage();
        callStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        callStage.initStyle(javafx.stage.StageStyle.UNDECORATED);
        callStage.setAlwaysOnTop(true);
        callStage.setTitle("Cuộc gọi đến · " + incoming.roomName());

        // ── 3. Root layout (dark, rounded) ──────────────────────────────────
        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: #1a1a2e; -fx-background-radius: 20;");
        root.setPrefSize(320, 420);

        // ── 4. Ripple rings ──────────────────────────────────────────────────
        StackPane rippleContainer = new StackPane();
        rippleContainer.setPrefSize(320, 420);

        for (int r = 0; r < 3; r++) {
            javafx.scene.shape.Circle ring = new javafx.scene.shape.Circle(55 + r * 26);
            ring.setFill(javafx.scene.paint.Color.TRANSPARENT);
            ring.setStroke(javafx.scene.paint.Color.rgb(99, 102, 241, 0.35 - r * 0.1));
            ring.setStrokeWidth(2);

            Timeline ripple = new Timeline(
                new KeyFrame(Duration.ZERO,
                    new KeyValue(ring.scaleXProperty(), 1.0, Interpolator.EASE_BOTH),
                    new KeyValue(ring.scaleYProperty(), 1.0, Interpolator.EASE_BOTH),
                    new KeyValue(ring.opacityProperty(), 0.8, Interpolator.EASE_BOTH)
                ),
                new KeyFrame(Duration.seconds(1.6),
                    new KeyValue(ring.scaleXProperty(), 1.5, Interpolator.EASE_BOTH),
                    new KeyValue(ring.scaleYProperty(), 1.5, Interpolator.EASE_BOTH),
                    new KeyValue(ring.opacityProperty(), 0.0, Interpolator.EASE_BOTH)
                )
            );
            ripple.setDelay(Duration.seconds(r * 0.45));
            ripple.setCycleCount(Timeline.INDEFINITE);
            ripple.play();
            rippleContainer.getChildren().add(ring);
        }

        // ── 5. Caller avatar ─────────────────────────────────────────────────
        String callerInitials = initials(incoming.roomName());
        Label avatarText = new Label(callerInitials);
        avatarText.setStyle("-fx-font-size: 26px; -fx-font-weight: 800; -fx-text-fill: white;");
        StackPane avatar = new StackPane(avatarText);
        avatar.setPrefSize(90, 90);
        avatar.setMaxSize(90, 90);
        avatar.setStyle("-fx-background-color: linear-gradient(to bottom right, #6366f1, #8b5cf6); -fx-background-radius: 45;");

        // Shake animation on avatar (simulates phone vibration)
        Timeline shake = new Timeline(
            new KeyFrame(Duration.ZERO,       new KeyValue(avatar.translateXProperty(), 0)),
            new KeyFrame(Duration.millis(80),  new KeyValue(avatar.translateXProperty(), -6, Interpolator.EASE_BOTH)),
            new KeyFrame(Duration.millis(160), new KeyValue(avatar.translateXProperty(),  6, Interpolator.EASE_BOTH)),
            new KeyFrame(Duration.millis(240), new KeyValue(avatar.translateXProperty(), -4, Interpolator.EASE_BOTH)),
            new KeyFrame(Duration.millis(320), new KeyValue(avatar.translateXProperty(),  4, Interpolator.EASE_BOTH)),
            new KeyFrame(Duration.millis(400), new KeyValue(avatar.translateXProperty(),  0))
        );
        shake.setCycleCount(Timeline.INDEFINITE);
        shake.setDelay(Duration.seconds(0.5));
        shake.play();

        // ── 6. Text labels ───────────────────────────────────────────────────
        Label callerName = new Label(incoming.roomName());
        callerName.setStyle("-fx-font-size: 22px; -fx-font-weight: 800; -fx-text-fill: white;");

        Label ringingLbl = new Label("Đang gọi cho bạn...");
        ringingLbl.setStyle("-fx-font-size: 13px; -fx-text-fill: rgba(255,255,255,0.6);");

        // Blinking dots animation on "Đang gọi"
        final String[] dots = {"", ".", "..", "..."};
        final int[] dotIdx = {0};
        Timeline blinkDots = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            dotIdx[0] = (dotIdx[0] + 1) % dots.length;
            ringingLbl.setText("Đang gọi cho bạn" + dots[dotIdx[0]]);
        }));
        blinkDots.setCycleCount(Timeline.INDEFINITE);
        blinkDots.play();

        // ── 7. Buttons ───────────────────────────────────────────────────────
        // Decline button
        StackPane declineBtn = new StackPane();
        declineBtn.setPrefSize(64, 64);
        declineBtn.setMaxSize(64, 64);
        declineBtn.setStyle("-fx-background-color: #ef4444; -fx-background-radius: 32; -fx-cursor: hand;");
        Label declineIcon = new Label("📵");
        declineIcon.setStyle("-fx-font-size: 24px;");
        declineBtn.getChildren().add(declineIcon);

        VBox declineBox = new VBox(6, declineBtn, new Label("Từ chối") {{
            setStyle("-fx-font-size: 11px; -fx-text-fill: rgba(255,255,255,0.6);");
        }});
        declineBox.setAlignment(Pos.CENTER);

        // Answer button
        StackPane answerBtn = new StackPane();
        answerBtn.setPrefSize(64, 64);
        answerBtn.setMaxSize(64, 64);
        answerBtn.setStyle("-fx-background-color: #22c55e; -fx-background-radius: 32; -fx-cursor: hand;");
        Label answerIcon = new Label("📞");
        answerIcon.setStyle("-fx-font-size: 24px;");
        answerBtn.getChildren().add(answerIcon);

        // Pulse animation on answer button
        Timeline answerPulse = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(answerBtn.scaleXProperty(), 1.0),
                new KeyValue(answerBtn.scaleYProperty(), 1.0)
            ),
            new KeyFrame(Duration.millis(600),
                new KeyValue(answerBtn.scaleXProperty(), 1.12, Interpolator.EASE_BOTH),
                new KeyValue(answerBtn.scaleYProperty(), 1.12, Interpolator.EASE_BOTH)
            ),
            new KeyFrame(Duration.millis(1200),
                new KeyValue(answerBtn.scaleXProperty(), 1.0),
                new KeyValue(answerBtn.scaleYProperty(), 1.0)
            )
        );
        answerPulse.setCycleCount(Timeline.INDEFINITE);
        answerPulse.play();

        VBox answerBox = new VBox(6, answerBtn, new Label("Trả lời") {{
            setStyle("-fx-font-size: 11px; -fx-text-fill: rgba(255,255,255,0.6);");
        }});
        answerBox.setAlignment(Pos.CENTER);

        HBox btnRow = new HBox(60, declineBox, answerBox);
        btnRow.setAlignment(Pos.CENTER);

        // ── 8. Main content VBox ─────────────────────────────────────────────
        VBox content = new VBox(16, avatar, callerName, ringingLbl, btnRow);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(40, 24, 40, 24));

        root.getChildren().addAll(rippleContainer, content);

        // ── 9. Button actions ────────────────────────────────────────────────
        Timeline[] holder = new Timeline[2];
        Runnable closeAll = () -> {
            stopRing.run();
            shake.stop();
            blinkDots.stop();
            answerPulse.stop();
            if (holder[0] != null) holder[0].stop();
            if (holder[1] != null) holder[1].stop();
            callStage.close();
        };

        answerBtn.setOnMouseClicked(e -> {
            closeAll.run();
            CallWindow callWin = new CallWindow(incoming, user, callRepo);
            callWin.setOnCallEnded(duration -> {
                String payload = (duration != null)
                    ? "[CALL:" + duration + ":" + incoming.roomName() + "]"
                    : "[CALL:missed:" + incoming.roomName() + "]";
                addMessage(incoming.roomId(), user.displayName(), payload, false, true);
                if (node != null) node.broadcast(user.displayName(), payload);
            });
            callWin.start();
        });


        declineBtn.setOnMouseClicked(e -> {
            closeAll.run();
            callRepo.endCall(incoming.callId());
        });

        // Auto-dismiss after 30s if no answer
        Timeline autoDismiss = new Timeline(new KeyFrame(Duration.seconds(30), e -> {
            closeAll.run();
            callRepo.endCall(incoming.callId());
        }));
        autoDismiss.setCycleCount(1);
        autoDismiss.play();
        holder[0] = autoDismiss;

        // Check if caller hung up or canceled
        Timeline livenessCheck = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            List<CallRepository.Participant> pList = callRepo.getParticipants(incoming.callId());
            if (pList.isEmpty()) {
                closeAll.run();
            }
        }));
        livenessCheck.setCycleCount(Timeline.INDEFINITE);
        livenessCheck.play();
        holder[1] = livenessCheck;

        // ── 10. Show ─────────────────────────────────────────────────────────
        Scene callScene = new Scene(root, 320, 420);
        callScene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        callScene.getStylesheets().addAll(
            getClass().getResource("/tokens.css").toExternalForm(),
            getClass().getResource("/studyroom.css").toExternalForm()
        );
        callStage.setScene(callScene);

        // Fade-in entrance
        root.setOpacity(0);
        root.setScaleX(0.85);
        root.setScaleY(0.85);
        callStage.show();
        callStage.centerOnScreen();

        Timeline fadeIn = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(root.opacityProperty(), 0),
                new KeyValue(root.scaleXProperty(), 0.85),
                new KeyValue(root.scaleYProperty(), 0.85)
            ),
            new KeyFrame(Duration.millis(280),
                new KeyValue(root.opacityProperty(), 1.0, Interpolator.EASE_OUT),
                new KeyValue(root.scaleXProperty(), 1.0, Interpolator.EASE_OUT),
                new KeyValue(root.scaleYProperty(), 1.0, Interpolator.EASE_OUT)
            )
        );
        fadeIn.play();
    }



    private void showEmptyConversation(VBox conversation) { Label title = new Label("Chọn một nhóm để bắt đầu"); title.getStyleClass().add("empty-title"); Label hint = new Label("Các nhóm bạn tạo sẽ xuất hiện ở cột bên trái."); hint.getStyleClass().add("empty-conversation"); VBox empty = new VBox(8, title, hint); empty.getStyleClass().add("conversation-empty"); conversation.getChildren().add(empty); VBox.setVgrow(empty, Priority.ALWAYS); }
    private String initials(String name) { String[] parts = name.trim().split("\\s+"); return parts.length == 1 ? parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase() : ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase(); }
    private String directRoomId(String first, String second) { String pair = first.compareTo(second) < 0 ? first + "\u0000" + second : second + "\u0000" + first; return "direct:" + UUID.nameUUIDFromBytes(pair.getBytes(StandardCharsets.UTF_8)); }

    private void showRoom() {
        if (activeCourseId != null) {
            showCourseWorkspace(activeCourseId);
        } else {
            showCourseList();
        }
    }

    private void showCourseList() {
        base("Phòng học chung", "Không gian học nhóm trực tuyến, slide bài giảng & lịch học");

        HBox topBar = new HBox(12);
        topBar.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button joinBtn = new Button("🔑 Tham gia bằng mã");
        joinBtn.getStyleClass().addAll("button");
        joinBtn.setOnAction(e -> showJoinCourseDialog());

        Button createBtn = new Button("＋ Tạo khóa học mới");
        createBtn.getStyleClass().addAll("button", "button-primary");
        createBtn.setOnAction(e -> showCreateCourseDialog());

        topBar.getChildren().addAll(spacer, joinBtn, createBtn);
        content.getChildren().add(topBar);

        List<CourseRepository.Course> courses = courseRepo.coursesFor(user.username());
        if (courses.isEmpty()) {
            VBox empty = new VBox(12);
            empty.setAlignment(Pos.CENTER);
            empty.setPadding(new Insets(60, 20, 40, 20));
            Label emptyTitle = new Label("Chưa tham gia phòng học nào");
            emptyTitle.getStyleClass().add("empty-title");
            Label emptyHint = new Label("Hãy tạo khóa học mới hoặc nhập mã phòng học và mật khẩu từ bạn bè để bắt đầu.");
            emptyHint.getStyleClass().add("empty-conversation");
            Button actionBtn = new Button("＋ Tạo phòng học đầu tiên");
            actionBtn.getStyleClass().addAll("button", "button-primary");
            actionBtn.setOnAction(e -> showCreateCourseDialog());
            empty.getChildren().addAll(emptyTitle, emptyHint, actionBtn);
            content.getChildren().add(empty);
            return;
        }

        FlowPane grid = new FlowPane(18, 18);
        grid.setPadding(new Insets(14, 0, 20, 0));

        for (CourseRepository.Course c : courses) {
            VBox card = new VBox(12);
            card.getStyleClass().add("course-card");

            HBox cardHeader = new HBox(8);
            cardHeader.setAlignment(Pos.CENTER_LEFT);
            Label iconLbl = new Label("👥");
            iconLbl.setStyle("-fx-font-size: 18px;");
            Label codeBadge = new Label("MÃ: " + c.code());
            codeBadge.getStyleClass().add("course-badge");
            Region hgap = new Region();
            HBox.setHgrow(hgap, Priority.ALWAYS);
            Label membersLbl = new Label("● " + c.memberCount() + " thành viên");
            membersLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #10b981; -fx-font-weight: bold;");
            cardHeader.getChildren().addAll(iconLbl, codeBadge, hgap, membersLbl);

            Label titleLbl = new Label(c.title());
            titleLbl.setStyle("-fx-font-size: 17px; -fx-font-weight: 800; -fx-text-fill: -ink;");
            titleLbl.setWrapText(true);

            Label descLbl = new Label(c.description() != null && !c.description().isBlank() ? c.description() : "Chưa có mô tả khóa học");
            descLbl.getStyleClass().add("muted");
            descLbl.setWrapText(true);
            descLbl.setMaxHeight(40);

            HBox meta = new HBox(12);
            meta.setAlignment(Pos.CENTER_LEFT);
            Label hostLbl = new Label("Chủ phòng: @" + c.ownerUsername());
            hostLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: -muted;");
            HBox.setHgrow(hostLbl, Priority.ALWAYS);

            Label passLbl = new Label("MK: " + c.password());
            passLbl.setStyle("-fx-font-size: 11px; -fx-background-color: #f3f4f6; -fx-padding: 2 6; -fx-background-radius: 4; -fx-text-fill: #4b5563;");
            meta.getChildren().addAll(hostLbl, passLbl);

            Button enterBtn = new Button("Vào lớp học →");
            enterBtn.getStyleClass().addAll("button", "button-primary");
            enterBtn.setMaxWidth(Double.MAX_VALUE);
            enterBtn.setOnAction(e -> {
                activeCourseId = c.id();
                showCourseWorkspace(c.id());
            });

            card.getChildren().addAll(cardHeader, titleLbl, descLbl, meta, enterBtn);
            grid.getChildren().add(card);
        }

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("thread-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        content.getChildren().add(scroll);
    }

    private void showCourseWorkspace(String courseId) {
        CourseRepository.Course course = courseRepo.getCourse(courseId);
        if (course == null) {
            activeCourseId = null;
            showCourseList();
            return;
        }

        content.getChildren().clear();
        content.getStyleClass().setAll("workspace");
        content.setPadding(new Insets(20, 26, 16, 26));

        // Top Header
        HBox topBar = new HBox(12);
        topBar.setAlignment(Pos.CENTER_LEFT);

        Button backBtn = new Button("← Danh sách");
        backBtn.getStyleClass().add("button");
        backBtn.setOnAction(e -> {
            CameraEngine.getInstance().stop();
            CameraEngine.getInstance().setRawFrameCallback(null);
            classroomVoice.stop();
            webcamStream.stop();
            tileSpeakingUpdateCallbacks.clear();
            isCameraOn = false;
            ScreenShareEngine.getInstance().stop();
            if (activeCourseId != null) {
                courseRepo.leavePresence(activeCourseId, user.username());
            }
            if (classroomSyncTimer != null) { classroomSyncTimer.cancel(); classroomSyncTimer = null; }
            activeCourseId = null;
            showCourseList();
        });

        Label title = new Label("👥 " + course.title() + " · " + course.memberCount() + " người");
        title.getStyleClass().add("page-title");
        title.setStyle("-fx-font-size: 20px;");

        Label codeTag = new Label("Mã: " + course.code());
        codeTag.getStyleClass().add("course-badge");

        Label passTag = new Label("MK: " + course.password());
        passTag.setStyle("-fx-font-size: 12px; -fx-background-color: #e0e7ff; -fx-text-fill: #4338ca; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 8;");

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        Button shareBtn = new Button("🔗 Chia sẻ liên kết");
        shareBtn.getStyleClass().add("button");
        shareBtn.setOnAction(e -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent cc = new ClipboardContent();
            cc.putString("Tham gia khóa học \"" + course.title() + "\" trên Studyroom!\nMã phòng: " + course.code() + "\nMật khẩu: " + course.password());
            clipboard.setContent(cc);
            toast("Đã sao chép mã phòng (" + course.code() + ") và mật khẩu vào bộ nhớ tạm!");
        });

        Button moreBtn = new Button("•••");
        moreBtn.getStyleClass().add("button");

        topBar.getChildren().addAll(backBtn, title, codeTag, passTag, gap, shareBtn, moreBtn);

        courseRepo.heartbeatPresence(courseId, user.username(), user.displayName(), isCameraOn, isMicOn, myVoiceIp, myVoicePort, isLocalSpeaking, myCameraPort);

        // Seed default quiz if empty
        quizRepo.seedDefaultQuizIfEmpty(course.id(), course.ownerUsername());

        // Sub Tabs
        HBox tabs = new HBox(8);
        tabs.setPadding(new Insets(10, 0, 14, 0));
        Button tabLive = new Button("💻 Phòng học trực tuyến");
        Button tabMaterials = new Button("📚 Slide & Tài liệu học tập");
        Button tabSchedule = new Button("📅 Lịch học");
        Button tabQuizzes = new Button("📝 Bài kiểm tra & Điểm số");

        tabLive.getStyleClass().add("tab-pill-active");
        tabMaterials.getStyleClass().add("tab-pill");
        tabSchedule.getStyleClass().add("tab-pill");
        tabQuizzes.getStyleClass().add("tab-pill");

        VBox workspaceArea = new VBox();
        VBox.setVgrow(workspaceArea, Priority.ALWAYS);
        workspaceArea.setMinHeight(0);

        final int[] currentTab = {0};

        Runnable renderActiveTab = new Runnable() {
            @Override
            public void run() {
                workspaceArea.getChildren().clear();
                tabLive.getStyleClass().setAll(currentTab[0] == 0 ? "tab-pill-active" : "tab-pill");
                tabMaterials.getStyleClass().setAll(currentTab[0] == 1 ? "tab-pill-active" : "tab-pill");
                tabSchedule.getStyleClass().setAll(currentTab[0] == 2 ? "tab-pill-active" : "tab-pill");
                tabQuizzes.getStyleClass().setAll(currentTab[0] == 3 ? "tab-pill-active" : "tab-pill");

                if (currentTab[0] == 0) {
                    if (classroomSyncTimer != null) classroomSyncTimer.cancel();
                    myVoiceIp = VoiceEngine.getLocalIp();
                    myVoicePort = classroomVoice.start(5100);
                    myCameraPort = webcamStream.start(5300);
                    CameraEngine.getInstance().setRawFrameCallback(rawBytes -> {
                        if (isCameraOn) {
                            webcamStream.broadcastFrame(user.username(), rawBytes);
                        }
                    });
                    classroomVoice.setMuted(!isMicOn);
                    classroomVoice.setDeafened(!isSpeakerOn);
                    classroomVoice.setLocalSpeakingCallback(speaking -> {
                        isLocalSpeaking = speaking;
                        Platform.runLater(() -> {
                            Runnable cb = tileSpeakingUpdateCallbacks.get(user.username());
                            if (cb != null) cb.run();
                        });
                    });
                    classroomVoice.setRemoteSpeakingCallback((senderIp, senderPort) -> {
                        long now = System.currentTimeMillis();
                        peerSpeakingLastTime.put(senderIp, now);
                        peerSpeakingLastTime.put(senderIp + ":" + senderPort, now);
                        Platform.runLater(() -> {
                            tileSpeakingUpdateCallbacks.forEach((u, cb) -> {
                                if (!u.equals(user.username())) {
                                    cb.run();
                                }
                            });
                        });
                    });

                    classroomSyncTimer = new Timer(true);
                    classroomSyncTimer.scheduleAtFixedRate(new TimerTask() {
                        private boolean lastPresenting = course.isPresenting();
                        private int lastMemberCount = -1;
                        private boolean lastCam = isCameraOn;
                        private boolean lastMic = isMicOn;
                        @Override
                        public void run() {
                            if (activeCourseId == null || currentTab[0] != 0) return;
                            try {
                                courseRepo.heartbeatPresence(activeCourseId, user.username(), user.displayName(), isCameraOn, isMicOn, myVoiceIp, myVoicePort, isLocalSpeaking, myCameraPort);
                                CourseRepository.Course fresh = courseRepo.getCourse(activeCourseId);
                                List<CourseRepository.OnlineMember> freshMembers = courseRepo.getOnlineMembers(activeCourseId);

                                for (CourseRepository.OnlineMember m : freshMembers) {
                                    if (!m.username().equals(user.username()) && m.ip() != null && !m.ip().isBlank()) {
                                        if (m.voicePort() > 0) classroomVoice.addPeer(m.ip(), m.voicePort());
                                        if (m.camPort() > 0) webcamStream.addPeer(m.ip(), m.camPort());
                                    }
                                }

                                if (fresh != null && (fresh.isPresenting() != lastPresenting || freshMembers.size() != lastMemberCount || isCameraOn != lastCam || isMicOn != lastMic)) {
                                    lastPresenting = fresh.isPresenting();
                                    lastMemberCount = freshMembers.size();
                                    lastCam = isCameraOn;
                                    lastMic = isMicOn;
                                    Platform.runLater(() -> {
                                        if (currentTab[0] == 0) {
                                            renderLiveClassroom(fresh, workspaceArea, tabMaterials::fire);
                                        }
                                    });
                                } else {
                                    Platform.runLater(() -> {
                                        tileSpeakingUpdateCallbacks.forEach((u, cb) -> cb.run());
                                    });
                                }
                            } catch (Exception ignored) { }
                        }
                    }, 1200, 1200);

                    renderLiveClassroom(course, workspaceArea, tabMaterials::fire);
                } else {
                    classroomVoice.stop();
                    webcamStream.stop();
                    CameraEngine.getInstance().setRawFrameCallback(null);
                    tileSpeakingUpdateCallbacks.clear();
                    if (classroomSyncTimer != null) { classroomSyncTimer.cancel(); classroomSyncTimer = null; }
                    if (currentTab[0] == 1) {
                        renderMaterialsTab(course, workspaceArea);
                    } else if (currentTab[0] == 2) {
                        renderScheduleTab(course, workspaceArea, () -> {
                            currentTab[0] = 0;
                            tabLive.fire();
                        });
                    } else if (currentTab[0] == 3) {
                        renderQuizzesTab(course, workspaceArea);
                    }
                }
            }
        };

        tabLive.setOnAction(e -> { currentTab[0] = 0; renderActiveTab.run(); });
        tabMaterials.setOnAction(e -> { currentTab[0] = 1; renderActiveTab.run(); });
        tabSchedule.setOnAction(e -> { currentTab[0] = 2; renderActiveTab.run(); });
        tabQuizzes.setOnAction(e -> { currentTab[0] = 3; renderActiveTab.run(); });

        tabs.getChildren().addAll(tabLive, tabMaterials, tabSchedule, tabQuizzes);
        content.getChildren().addAll(topBar, tabs, workspaceArea);

        renderActiveTab.run();
    }

    private void renderLiveClassroom(CourseRepository.Course course, VBox container, Runnable onGoToMaterials) {
        container.getChildren().clear();
        boolean isHost = course.ownerUsername().equalsIgnoreCase(user.username());

        HBox body = new HBox(14);
        VBox.setVgrow(body, Priority.ALWAYS);
        body.setMinHeight(0);

        // LEFT: Slide Frame & Dock Bar
        VBox leftPane = new VBox(10);
        HBox.setHgrow(leftPane, Priority.ALWAYS);
        VBox.setVgrow(leftPane, Priority.ALWAYS);
        leftPane.setMinHeight(0);
        leftPane.setMinWidth(0);

        VBox deck = new VBox(10);
        deck.getStyleClass().add("slide-frame");
        VBox.setVgrow(deck, Priority.ALWAYS);
        deck.setMinHeight(0);
        deck.setMinWidth(0);

        ImageView screenView = new ImageView();
        screenView.setPreserveRatio(true);
        screenView.setSmooth(true);

        if (course.isPresenting()) {
            if (isHost) {
                ScreenShareEngine.getInstance().startHost(course.screenPort(), screenView::setImage);
            } else {
                ScreenShareEngine.getInstance().startClient(course.hostIp(), course.screenPort(), screenView::setImage);
            }
        } else {
            ScreenShareEngine.getInstance().stop();
        }

        if (!course.isPresenting()) {
            // STANDBY STATE (No screen sharing currently active)
            HBox standbyHeader = new HBox(10);
            standbyHeader.setAlignment(Pos.CENTER_LEFT);
            Label standbyStatus = new Label(isHost ? "Khu vực Trình chiếu Màn hình của Chủ phòng" : "Màn hình Trình chiếu Trực tuyến");
            standbyStatus.setStyle("-fx-font-weight: 800; -fx-font-size: 14px; -fx-text-fill: #4b5563;");
            Region hgap = new Region();
            HBox.setHgrow(hgap, Priority.ALWAYS);
            Label badge = new Label("● Chưa phát sóng");
            badge.setStyle("-fx-background-color: #f3f4f6; -fx-text-fill: #6b7280; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 8;");
            standbyHeader.getChildren().addAll(standbyStatus, hgap, badge);

            VBox centerBox = new VBox(18);
            centerBox.setAlignment(Pos.CENTER);
            VBox.setVgrow(centerBox, Priority.ALWAYS);
            centerBox.setPadding(new Insets(30, 40, 30, 40));

            Label iconLbl = new Label(isHost ? "💻" : "📡");
            iconLbl.setStyle("-fx-font-size: 50px; -fx-background-color: " + (isHost ? "-violet-soft" : "#f3f4f6") + "; -fx-background-radius: 36; -fx-padding: 16 22;");

            if (isHost) {
                Label titleLbl = new Label("Khu vực Trình chiếu Màn hình của Chủ phòng");
                titleLbl.setStyle("-fx-font-size: 20px; -fx-font-weight: 800; -fx-text-fill: -ink;");

                Label descLbl = new Label("Bạn là chủ phòng của lớp học này.\nKhi bạn nhấn \"Bắt đầu chia sẻ màn hình\", toàn bộ màn hình máy tính của bạn sẽ được truyền hình trực tiếp với độ nét cao cho tất cả học viên trong phòng cùng theo dõi bài giảng, slide, mã nguồn hoặc tài liệu.");
                descLbl.setStyle("-fx-text-fill: -muted; -fx-font-size: 14px; -fx-text-alignment: CENTER; -fx-line-spacing: 4;");
                descLbl.setWrapText(true);

                Button startBtn = new Button("💻  Bắt đầu chia sẻ màn hình");
                startBtn.getStyleClass().addAll("button", "button-primary");
                startBtn.setStyle("-fx-font-size: 15px; -fx-padding: 10 26; -fx-font-weight: 800;");
                startBtn.setOnAction(e -> {
                    String myIp = VoiceEngine.getLocalIp();
                    int port = 5200;
                    courseRepo.startScreenShare(course.id(), myIp, port);
                    CourseRepository.Course fresh = courseRepo.getCourse(course.id());
                    renderLiveClassroom(fresh, container, onGoToMaterials);
                    toast("Đang phát trực tiếp màn hình máy tính của bạn cho cả phòng!");
                });

                HBox subActions = new HBox(12);
                subActions.setAlignment(Pos.CENTER);
                Button prepDocBtn = new Button("📚 Xem kho tài liệu & Slide");
                prepDocBtn.getStyleClass().add("button");
                prepDocBtn.setOnAction(e -> onGoToMaterials.run());

                Button testBoardBtn = new Button("✎ Bảng trắng");
                testBoardBtn.getStyleClass().add("button");
                testBoardBtn.setOnAction(e -> toast("Bảng trắng tương tác sẵn sàng khi bắt đầu buổi học."));

                subActions.getChildren().addAll(prepDocBtn, testBoardBtn);

                centerBox.getChildren().addAll(iconLbl, titleLbl, descLbl, startBtn, subActions);
            } else {
                Label titleLbl = new Label("Chờ chủ phòng chia sẻ màn hình...");
                titleLbl.setStyle("-fx-font-size: 20px; -fx-font-weight: 800; -fx-text-fill: -ink;");

                Label descLbl = new Label("Chủ phòng (@" + course.ownerUsername() + ") hiện chưa bật tính năng chia sẻ màn hình máy tính.\nMàn hình trình chiếu của chủ phòng sẽ tự động xuất hiện tại đây ngay khi chủ phòng phát sóng.");
                descLbl.setStyle("-fx-text-fill: -muted; -fx-font-size: 14px; -fx-text-alignment: CENTER; -fx-line-spacing: 4;");
                descLbl.setWrapText(true);

                Label waitBadge = new Label("⏳ Đang kết nối luồng phát sóng của chủ phòng...");
                waitBadge.setStyle("-fx-background-color: #fef3c7; -fx-text-fill: #b45309; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 8 16; -fx-background-radius: 14;");

                Button viewDocBtn = new Button("📚 Xem trước Slide & Tài liệu học tập");
                viewDocBtn.getStyleClass().addAll("button", "button-primary");
                viewDocBtn.setOnAction(e -> onGoToMaterials.run());

                centerBox.getChildren().addAll(iconLbl, titleLbl, descLbl, waitBadge, viewDocBtn);
            }

            deck.getChildren().addAll(standbyHeader, centerBox);
        } else {
            // LIVE SCREEN SHARING ACTIVE
            HBox streamHeader = new HBox(14);
            streamHeader.setAlignment(Pos.CENTER_LEFT);
            streamHeader.setMinHeight(30);

            Label liveBadge = new Label("🔴 ĐANG CHIA SẺ MÀN HÌNH");
            liveBadge.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #dc2626; -fx-font-weight: 800; -fx-font-size: 11px; -fx-padding: 4 8; -fx-background-radius: 6;");

            Label hostInfo = new Label(isHost ? "Màn hình của bạn đang phát trực tiếp" : "Màn hình trực tiếp của Chủ phòng @" + course.ownerUsername());
            hostInfo.setStyle("-fx-font-weight: 800; -fx-font-size: 13px; -fx-text-fill: #374151;");

            Region hgap = new Region();
            HBox.setHgrow(hgap, Priority.ALWAYS);

            Label resBadge = new Label("1280 × 720 · 15 FPS · HD");
            resBadge.setStyle("-fx-font-size: 11px; -fx-text-fill: #6b7280;");

            Button fullscreenBtn = new Button("⛶ Toàn màn hình");
            fullscreenBtn.getStyleClass().add("button");
            fullscreenBtn.setStyle("-fx-font-weight: bold; -fx-padding: 3 8; -fx-font-size: 12px;");
            fullscreenBtn.setOnAction(e -> {
                Stage fullStage = new Stage();
                fullStage.setTitle("Màn hình trình chiếu · " + course.title());
                ImageView fullView = new ImageView();
                fullView.setPreserveRatio(true);
                fullView.imageProperty().bind(screenView.imageProperty());
                StackPane fullRoot = new StackPane(fullView);
                fullRoot.setStyle("-fx-background-color: #0b0c10;");
                fullView.fitWidthProperty().bind(fullRoot.widthProperty());
                fullView.fitHeightProperty().bind(fullRoot.heightProperty());
                Scene fullScene = new Scene(fullRoot, 1280, 720);
                fullStage.setScene(fullScene);
                fullStage.setFullScreen(true);
                fullStage.show();
            });

            streamHeader.getChildren().addAll(liveBadge, hostInfo, hgap, resBadge, fullscreenBtn);

            Label loadingNotice = new Label(isHost ? "Đang phát trực tiếp màn hình máy tính của bạn..." : "Đang nhận luồng hình ảnh màn hình bài giảng...");
            loadingNotice.setStyle("-fx-text-fill: #9ca3af; -fx-font-size: 13px;");

            // Responsive Screen Box: Constrained within deck bounds, avoids pushing parent controls
            Pane screenBox = new Pane() {
                @Override
                protected void layoutChildren() {
                    double w = getWidth();
                    double h = getHeight();
                    if (w <= 0 || h <= 0) return;
                    Image img = screenView.getImage();
                    if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                        double scale = Math.min((w - 12) / img.getWidth(), (h - 12) / img.getHeight());
                        double tw = Math.max(1, img.getWidth() * scale);
                        double th = Math.max(1, img.getHeight() * scale);
                        screenView.setFitWidth(tw);
                        screenView.setFitHeight(th);
                        screenView.relocate((w - tw) / 2.0, (h - th) / 2.0);
                    }
                    if (loadingNotice.isVisible()) {
                        double nw = loadingNotice.prefWidth(-1);
                        double nh = loadingNotice.prefHeight(-1);
                        loadingNotice.resizeRelocate((w - nw) / 2.0, (h - nh) / 2.0, nw, nh);
                    }
                }
            };
            screenBox.setStyle("-fx-background-color: #0c0d14; -fx-background-radius: 12;");
            screenBox.setMinSize(0, 0);
            VBox.setVgrow(screenBox, Priority.ALWAYS);

            screenBox.getChildren().addAll(loadingNotice, screenView);
            screenView.imageProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null) loadingNotice.setVisible(false);
                screenBox.requestLayout();
            });

            // Action Bar
            HBox streamActions = new HBox(10);
            streamActions.setAlignment(Pos.CENTER_LEFT);
            streamActions.setMinHeight(34);

            if (isHost) {
                Button stopBtn = new Button("■  Dừng chia sẻ màn hình");
                stopBtn.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #dc2626; -fx-font-weight: 800; -fx-background-radius: 8; -fx-padding: 6 14; -fx-cursor: hand;");
                stopBtn.setOnAction(e -> {
                    courseRepo.stopScreenShare(course.id());
                    ScreenShareEngine.getInstance().stop();
                    CourseRepository.Course fresh = courseRepo.getCourse(course.id());
                    renderLiveClassroom(fresh, container, onGoToMaterials);
                });

                Button minBtn = new Button("🗕  Thu nhỏ để bắt đầu giảng bài");
                minBtn.getStyleClass().add("button");
                minBtn.setStyle("-fx-font-weight: bold; -fx-padding: 6 12; -fx-font-size: 12px;");
                minBtn.setOnAction(e -> {
                    Stage stage = (Stage) container.getScene().getWindow();
                    if (stage != null) stage.setIconified(true);
                });

                Button docBtn = new Button("📚  Slide & Tài liệu");
                docBtn.getStyleClass().add("button");
                docBtn.setStyle("-fx-padding: 6 12; -fx-font-size: 12px;");
                docBtn.setOnAction(e -> onGoToMaterials.run());

                Button boardBtn = new Button("✎  Bảng trắng");
                boardBtn.getStyleClass().add("button");
                boardBtn.setStyle("-fx-padding: 6 12; -fx-font-size: 12px;");
                boardBtn.setOnAction(e -> {});

                streamActions.getChildren().addAll(stopBtn, minBtn, docBtn, boardBtn);
            } else {
                Label studentStatus = new Label("Đang theo dõi màn hình bài giảng trực tiếp từ chủ phòng");
                studentStatus.setStyle("-fx-text-fill: -muted; -fx-font-size: 13px;");
                HBox.setHgrow(studentStatus, Priority.ALWAYS);

                Button handBtn = new Button("🙋  Giơ tay phát biểu");
                handBtn.getStyleClass().addAll("button");
                handBtn.setStyle("-fx-padding: 6 12; -fx-font-size: 12px;");
                handBtn.setOnAction(e -> {});

                Button noteBtn = new Button("📄  Ghi chú cá nhân");
                noteBtn.getStyleClass().add("button");
                noteBtn.setStyle("-fx-padding: 6 12; -fx-font-size: 12px;");
                noteBtn.setOnAction(e -> {});

                streamActions.getChildren().addAll(studentStatus, handBtn, noteBtn);
            }

            deck.getChildren().addAll(streamHeader, screenBox, streamActions);
        }

        // Camera Preview for User
        ImageView myCamView = new ImageView();
        myCamView.setPreserveRatio(true);
        myCamView.setSmooth(true);
        if (isCameraOn) {
            CameraEngine.getInstance().start(myCamView::setImage);
        }

        // BOTTOM DOCK BAR
        HBox dock = new HBox(8);
        dock.setAlignment(Pos.CENTER);
        dock.getStyleClass().add("dock-bar");
        dock.setMinHeight(50);
        dock.setPrefHeight(50);
        dock.setMaxHeight(50);

        // Nút Mic
        Button micBtn = new Button(isMicOn ? "🎤 Mic" : "🔇 Mic");
        micBtn.getStyleClass().setAll(isMicOn ? "dock-btn" : "dock-btn-danger");
        micBtn.setOnAction(e -> {
            isMicOn = !isMicOn;
            classroomVoice.setMuted(!isMicOn);
            micBtn.setText(isMicOn ? "🎤 Mic" : "🔇 Mic");
            micBtn.getStyleClass().setAll(isMicOn ? "dock-btn" : "dock-btn-danger");
            courseRepo.heartbeatPresence(course.id(), user.username(), user.displayName(), isCameraOn, isMicOn, myVoiceIp, myVoicePort, isLocalSpeaking, myCameraPort);
            Runnable myCb = tileSpeakingUpdateCallbacks.get(user.username());
            if (myCb != null) myCb.run();
        });

        // Nút Loa
        Button speakerBtn = new Button(isSpeakerOn ? "🔊 Loa" : "🔈 Loa");
        speakerBtn.getStyleClass().setAll(isSpeakerOn ? "dock-btn" : "dock-btn-danger");
        speakerBtn.setOnAction(e -> {
            isSpeakerOn = !isSpeakerOn;
            classroomVoice.setDeafened(!isSpeakerOn);
            speakerBtn.setText(isSpeakerOn ? "🔊 Loa" : "🔈 Loa");
            speakerBtn.getStyleClass().setAll(isSpeakerOn ? "dock-btn" : "dock-btn-danger");
        });

        // Nút Camera
        Button camBtn = new Button(isCameraOn ? "📹 Cam" : "📷 Cam");
        camBtn.getStyleClass().setAll(isCameraOn ? "dock-btn" : "dock-btn-danger");
        camBtn.setOnAction(e -> {
            isCameraOn = !isCameraOn;
            if (isCameraOn) {
                CameraEngine.getInstance().start(myCamView::setImage);
            } else {
                CameraEngine.getInstance().stop();
                myCamView.setImage(null);
            }
            camBtn.setText(isCameraOn ? "📹 Cam" : "📷 Cam");
            camBtn.getStyleClass().setAll(isCameraOn ? "dock-btn" : "dock-btn-danger");
            courseRepo.heartbeatPresence(course.id(), user.username(), user.displayName(), isCameraOn, isMicOn, myVoiceIp, myVoicePort, isLocalSpeaking, myCameraPort);
            CourseRepository.Course fresh = courseRepo.getCourse(course.id());
            renderLiveClassroom(fresh, container, onGoToMaterials);
        });

        Button shareScreenBtn = new Button(course.isPresenting() && isHost ? "■ Dừng share" : "💻 Chia sẻ");
        shareScreenBtn.getStyleClass().setAll(course.isPresenting() && isHost ? "dock-btn-danger" : "dock-btn");
        shareScreenBtn.setOnAction(e -> {
            if (isHost) {
                if (course.isPresenting()) {
                    courseRepo.stopScreenShare(course.id());
                    ScreenShareEngine.getInstance().stop();
                    CourseRepository.Course fresh = courseRepo.getCourse(course.id());
                    renderLiveClassroom(fresh, container, onGoToMaterials);
                } else {
                    String myIp = VoiceEngine.getLocalIp();
                    int port = 5200;
                    courseRepo.startScreenShare(course.id(), myIp, port);
                    CourseRepository.Course fresh = courseRepo.getCourse(course.id());
                    renderLiveClassroom(fresh, container, onGoToMaterials);
                }
            }
        });

        Button leaveBtn = new Button("🚪 Rời phòng");
        leaveBtn.getStyleClass().setAll("dock-btn-danger");
        leaveBtn.setOnAction(e -> {
            CameraEngine.getInstance().stop();
            CameraEngine.getInstance().setRawFrameCallback(null);
            classroomVoice.stop();
            webcamStream.stop();
            tileSpeakingUpdateCallbacks.clear();
            isCameraOn = false;
            ScreenShareEngine.getInstance().stop();
            courseRepo.leavePresence(course.id(), user.username());
            if (classroomSyncTimer != null) { classroomSyncTimer.cancel(); classroomSyncTimer = null; }
            activeCourseId = null;
            showCourseList();
        });

        List<CourseRepository.OnlineMember> onlineMembers = courseRepo.getOnlineMembers(course.id());
        if (onlineMembers.stream().noneMatch(m -> m.username().equals(user.username()))) {
            onlineMembers.add(0, new CourseRepository.OnlineMember(user.username(), user.displayName(), isCameraOn, isMicOn, myVoiceIp, myVoicePort, isLocalSpeaking, myCameraPort));
        }

        Button membersBtn = new Button("👥 " + onlineMembers.size());
        membersBtn.getStyleClass().setAll("dock-btn");

        Button chatBtn = new Button("💬 Chat");
        chatBtn.getStyleClass().setAll("dock-btn");
        chatBtn.setOnAction(e -> showChat());

        dock.getChildren().addAll(micBtn, speakerBtn, camBtn, shareScreenBtn, leaveBtn, membersBtn, chatBtn);

        leftPane.getChildren().addAll(deck, dock);

        // RIGHT: Video & Member Sidebar (Always visible)
        VBox rightPane = new VBox(10);
        rightPane.setPrefWidth(220);
        rightPane.setMinWidth(200);
        rightPane.setMaxWidth(230);
        rightPane.setMinHeight(0);

        Label sidebarTitle = new Label("Thành viên trong phòng (" + onlineMembers.size() + ")");
        sidebarTitle.setStyle("-fx-font-weight: 800; -fx-font-size: 13px; -fx-text-fill: -ink;");
        rightPane.getChildren().add(sidebarTitle);

        VBox videoList = new VBox(8);
        for (CourseRepository.OnlineMember m : onlineMembers) {
            boolean isMe = m.username().equals(user.username());
            Pane tile = createMemberVideoTile(m, isMe, myCamView);
            videoList.getChildren().add(tile);
        }

        ScrollPane videoScroll = new ScrollPane(videoList);
        videoScroll.setFitToWidth(true);
        videoScroll.getStyleClass().add("thread-scroll");
        VBox.setVgrow(videoScroll, Priority.ALWAYS);

        rightPane.getChildren().add(videoScroll);

        body.getChildren().addAll(leftPane, rightPane);

        container.getChildren().add(body);
    }

    private Pane createMemberVideoTile(CourseRepository.OnlineMember m, boolean isMe, ImageView myCamView) {
        if (isMe) {
            if (isCameraOn) {
                StackPane camBox = new StackPane();
                camBox.setPrefHeight(100);
                camBox.setMinHeight(100);

                myCamView.setFitWidth(190);
                myCamView.setFitHeight(90);

                HBox overlay = new HBox();
                overlay.setAlignment(Pos.BOTTOM_LEFT);
                overlay.setPadding(new Insets(4));
                Label meTag = new Label("Bạn (" + m.displayName() + ")");
                meTag.setStyle("-fx-background-color: rgba(0,0,0,0.65); -fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 2 6; -fx-background-radius: 4;");
                Region ogap = new Region();
                HBox.setHgrow(ogap, Priority.ALWAYS);

                Label speakTag = new Label("ılı. Đang nói");
                speakTag.setStyle("-fx-background-color: rgba(34, 197, 94, 0.18); -fx-text-fill: #4ade80; -fx-font-size: 10px; -fx-font-weight: 700; -fx-padding: 2 6; -fx-background-radius: 6; -fx-border-color: rgba(74, 222, 128, 0.35); -fx-border-radius: 6;");
                speakTag.setVisible(false);
                speakTag.setManaged(false);

                Label camTag = new Label("📹");
                camTag.setStyle("-fx-background-color: rgba(99, 102, 241, 0.2); -fx-text-fill: #a5b4fc; -fx-font-size: 10px; -fx-padding: 2 5; -fx-background-radius: 4;");
                overlay.getChildren().addAll(meTag, ogap, speakTag, camTag);

                camBox.getChildren().addAll(myCamView, overlay);

                Runnable updateVisuals = () -> {
                    boolean speaking = isMicOn && isLocalSpeaking;
                    if (speaking) {
                        camBox.setStyle("-fx-background-color: #0b0c10; -fx-background-radius: 12; -fx-border-color: rgba(74, 222, 128, 0.55); -fx-border-width: 1.5; -fx-border-radius: 12; -fx-effect: dropshadow(gaussian, rgba(74, 222, 128, 0.35), 8, 0.2, 0, 0);");
                        speakTag.setVisible(true);
                        speakTag.setManaged(true);
                    } else {
                        camBox.setStyle("-fx-background-color: #0b0c10; -fx-background-radius: 12; -fx-border-color: #3b3d5b; -fx-border-width: 1.5; -fx-border-radius: 12; -fx-effect: null;");
                        speakTag.setVisible(false);
                        speakTag.setManaged(false);
                    }
                };
                tileSpeakingUpdateCallbacks.put(m.username(), updateVisuals);
                updateVisuals.run();
                return camBox;
            } else {
                VBox tile = new VBox(6);
                tile.getStyleClass().add("video-tile");
                tile.setPrefHeight(100);
                tile.setMinHeight(100);
                tile.setStyle("-fx-background-color: #1c1d2b; -fx-background-radius: 12; -fx-padding: 8 10; -fx-border-color: #3b3d5b; -fx-border-width: 1.5; -fx-border-radius: 12;");

                HBox top = new HBox();
                top.setAlignment(Pos.CENTER_LEFT);

                StackPane avatar = new StackPane();
                Label mark = new Label(initials(m.displayName()));
                mark.setStyle("-fx-font-weight: 800; -fx-font-size: 12px; -fx-text-fill: white;");
                avatar.getChildren().add(mark);
                avatar.setMinSize(28, 28);
                avatar.setMaxSize(28, 28);
                avatar.setStyle("-fx-background-color: #6366f1; -fx-background-radius: 14; -fx-alignment: center;");

                Region tgap = new Region();
                HBox.setHgrow(tgap, Priority.ALWAYS);

                Label statusBadge = new Label();
                top.getChildren().addAll(avatar, tgap, statusBadge);

                Region mid = new Region();
                VBox.setVgrow(mid, Priority.ALWAYS);

                Label nameLbl = new Label(m.displayName() + " (Bạn)");
                nameLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: white;");

                tile.getChildren().addAll(top, mid, nameLbl);

                Runnable updateVisuals = () -> {
                    boolean isSpeaking = isMicOn && isLocalSpeaking;
                    if (isSpeaking) {
                        tile.setStyle("-fx-background-color: #1c1d2b; -fx-background-radius: 12; -fx-padding: 8 10; -fx-border-color: rgba(74, 222, 128, 0.55); -fx-border-width: 1.5; -fx-border-radius: 12; -fx-effect: dropshadow(gaussian, rgba(74, 222, 128, 0.35), 8, 0.2, 0, 0);");
                        avatar.setStyle("-fx-background-color: #6366f1; -fx-background-radius: 14; -fx-alignment: center;");
                        statusBadge.setText("ılı. Đang nói");
                        statusBadge.setStyle("-fx-font-size: 10px; -fx-text-fill: #4ade80; -fx-font-weight: 700; -fx-background-color: rgba(34, 197, 94, 0.18); -fx-padding: 2 6; -fx-background-radius: 6; -fx-border-color: rgba(74, 222, 128, 0.35); -fx-border-radius: 6;");
                    } else {
                        tile.setStyle("-fx-background-color: #1c1d2b; -fx-background-radius: 12; -fx-padding: 8 10; -fx-border-color: #3b3d5b; -fx-border-width: 1.5; -fx-border-radius: 12; -fx-effect: null;");
                        avatar.setStyle("-fx-background-color: #6366f1; -fx-background-radius: 14; -fx-alignment: center;");
                        if (isMicOn) {
                            statusBadge.setText("🎤 Mic bật");
                            statusBadge.setStyle("-fx-font-size: 10px; -fx-text-fill: #94a3b8; -fx-font-weight: bold; -fx-background-color: #25283d; -fx-padding: 2 6; -fx-background-radius: 4;");
                        } else {
                            statusBadge.setText("🔇 Mic tắt");
                            statusBadge.setStyle("-fx-font-size: 10px; -fx-text-fill: #f87171; -fx-background-color: #381a20; -fx-padding: 2 6; -fx-background-radius: 4;");
                        }
                    }
                };
                tileSpeakingUpdateCallbacks.put(m.username(), updateVisuals);
                updateVisuals.run();
                return tile;
            }
        }

        // Remote Peer Tile (e.g. Nhật Huy)
        StackPane rootBox = new StackPane();
        rootBox.setPrefHeight(100);
        rootBox.setMinHeight(100);
        rootBox.setStyle("-fx-background-color: #1c1d2b; -fx-background-radius: 12; -fx-border-color: #2e3048; -fx-border-width: 1.5; -fx-border-radius: 12;");

        // 1. Remote Camera Stream View
        ImageView remoteCamView = new ImageView();
        remoteCamView.setFitWidth(190);
        remoteCamView.setFitHeight(90);
        remoteCamView.setPreserveRatio(true);
        remoteCamView.setSmooth(true);
        remoteCamView.setVisible(false);

        // 2. Video Overlay (Name & badges on top of live camera)
        HBox videoOverlay = new HBox();
        videoOverlay.setAlignment(Pos.BOTTOM_LEFT);
        videoOverlay.setPadding(new Insets(4));
        Label vNameTag = new Label(m.displayName());
        vNameTag.setStyle("-fx-background-color: rgba(0,0,0,0.65); -fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 2 6; -fx-background-radius: 4;");
        Region vogap = new Region();
        HBox.setHgrow(vogap, Priority.ALWAYS);
        Label vSpeakTag = new Label("ılı. Đang nói");
        vSpeakTag.setStyle("-fx-background-color: rgba(34, 197, 94, 0.18); -fx-text-fill: #4ade80; -fx-font-size: 10px; -fx-font-weight: 700; -fx-padding: 2 6; -fx-background-radius: 6; -fx-border-color: rgba(74, 222, 128, 0.35); -fx-border-radius: 6;");
        vSpeakTag.setVisible(false);
        vSpeakTag.setManaged(false);
        Label vCamTag = new Label("📹");
        vCamTag.setStyle("-fx-background-color: rgba(99, 102, 241, 0.2); -fx-text-fill: #a5b4fc; -fx-font-size: 10px; -fx-padding: 2 5; -fx-background-radius: 4;");
        videoOverlay.getChildren().addAll(vNameTag, vogap, vSpeakTag, vCamTag);
        videoOverlay.setVisible(false);

        // 3. Avatar Box (displayed when peer camera is OFF)
        VBox avatarBox = new VBox(6);
        avatarBox.getStyleClass().add("video-tile");
        avatarBox.setPrefHeight(100);
        avatarBox.setMinHeight(100);
        avatarBox.setStyle("-fx-background-color: #1c1d2b; -fx-background-radius: 12; -fx-padding: 8 10; -fx-border-color: #2e3048; -fx-border-width: 1.5; -fx-border-radius: 12;");

        HBox atop = new HBox();
        atop.setAlignment(Pos.CENTER_LEFT);
        StackPane aAvatar = new StackPane();
        Label aMark = new Label(initials(m.displayName()));
        aMark.setStyle("-fx-font-weight: 800; -fx-font-size: 12px; -fx-text-fill: white;");
        aAvatar.getChildren().add(aMark);
        aAvatar.setMinSize(28, 28);
        aAvatar.setMaxSize(28, 28);
        aAvatar.setStyle("-fx-background-color: #8b5cf6; -fx-background-radius: 14; -fx-alignment: center;");

        Region atgap = new Region();
        HBox.setHgrow(atgap, Priority.ALWAYS);
        Label aStatusBadge = new Label();
        atop.getChildren().addAll(aAvatar, atgap, aStatusBadge);

        Region amid = new Region();
        VBox.setVgrow(amid, Priority.ALWAYS);
        Label aNameLbl = new Label(m.displayName());
        aNameLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: white;");
        avatarBox.getChildren().addAll(atop, amid, aNameLbl);

        rootBox.getChildren().addAll(avatarBox, remoteCamView, videoOverlay);

        // Register callback for receiving camera video stream!
        webcamStream.registerMemberCallback(m.username(), img -> {
            remoteCamView.setImage(img);
            if (!remoteCamView.isVisible()) {
                remoteCamView.setVisible(true);
                videoOverlay.setVisible(true);
                avatarBox.setVisible(false);
                rootBox.setStyle("-fx-background-color: #0b0c10; -fx-background-radius: 12; -fx-border-color: #2e3048; -fx-border-width: 1.5; -fx-border-radius: 12;");
            }
        });

        Runnable updateVisuals = () -> {
            boolean hasVideo = webcamStream.hasRecentVideo(m.username()) && m.cameraOn();
            if (!hasVideo && remoteCamView.isVisible()) {
                remoteCamView.setVisible(false);
                videoOverlay.setVisible(false);
                avatarBox.setVisible(true);
            }

            Long lastPacket = peerSpeakingLastTime.get(m.ip());
            if (lastPacket == null && m.voicePort() > 0) {
                lastPacket = peerSpeakingLastTime.get(m.ip() + ":" + m.voicePort());
            }
            boolean recentPacket = lastPacket != null && (System.currentTimeMillis() - lastPacket < 500);
            boolean isSpeaking = m.micOn() && (recentPacket || m.speaking());

            if (isSpeaking) {
                if (hasVideo) {
                    rootBox.setStyle("-fx-background-color: #0b0c10; -fx-background-radius: 12; -fx-border-color: rgba(74, 222, 128, 0.55); -fx-border-width: 1.5; -fx-border-radius: 12; -fx-effect: dropshadow(gaussian, rgba(74, 222, 128, 0.35), 8, 0.2, 0, 0);");
                    vSpeakTag.setVisible(true);
                    vSpeakTag.setManaged(true);
                } else {
                    avatarBox.setStyle("-fx-background-color: #1c1d2b; -fx-background-radius: 12; -fx-padding: 8 10; -fx-border-color: rgba(74, 222, 128, 0.55); -fx-border-width: 1.5; -fx-border-radius: 12; -fx-effect: dropshadow(gaussian, rgba(74, 222, 128, 0.35), 8, 0.2, 0, 0);");
                    aAvatar.setStyle("-fx-background-color: #8b5cf6; -fx-background-radius: 14; -fx-alignment: center;");
                    aStatusBadge.setText("ılı. Đang nói");
                    aStatusBadge.setStyle("-fx-font-size: 10px; -fx-text-fill: #4ade80; -fx-font-weight: 700; -fx-background-color: rgba(34, 197, 94, 0.18); -fx-padding: 2 6; -fx-background-radius: 6; -fx-border-color: rgba(74, 222, 128, 0.35); -fx-border-radius: 6;");
                }
            } else {
                if (hasVideo) {
                    rootBox.setStyle("-fx-background-color: #0b0c10; -fx-background-radius: 12; -fx-border-color: #2e3048; -fx-border-width: 1.5; -fx-border-radius: 12; -fx-effect: null;");
                    vSpeakTag.setVisible(false);
                    vSpeakTag.setManaged(false);
                } else {
                    avatarBox.setStyle("-fx-background-color: #1c1d2b; -fx-background-radius: 12; -fx-padding: 8 10; -fx-border-color: #2e3048; -fx-border-width: 1.5; -fx-border-radius: 12; -fx-effect: null;");
                    aAvatar.setStyle("-fx-background-color: #8b5cf6; -fx-background-radius: 14; -fx-alignment: center;");
                    if (m.cameraOn()) {
                        aStatusBadge.setText("📹 Đang kết nối cam...");
                        aStatusBadge.setStyle("-fx-font-size: 10px; -fx-text-fill: #60a5fa; -fx-font-weight: bold; -fx-background-color: #1e293b; -fx-padding: 2 6; -fx-background-radius: 4;");
                    } else if (m.micOn()) {
                        aStatusBadge.setText("🎤 Mic bật");
                        aStatusBadge.setStyle("-fx-font-size: 10px; -fx-text-fill: #94a3b8; -fx-font-weight: bold; -fx-background-color: #25283d; -fx-padding: 2 6; -fx-background-radius: 4;");
                    } else {
                        aStatusBadge.setText("🔇 Mic tắt");
                        aStatusBadge.setStyle("-fx-font-size: 10px; -fx-text-fill: #f87171; -fx-background-color: #381a20; -fx-padding: 2 6; -fx-background-radius: 4;");
                    }
                }
            }
        };

        tileSpeakingUpdateCallbacks.put(m.username(), updateVisuals);
        updateVisuals.run();
        return rootBox;
    }

    private void renderMaterialsTab(CourseRepository.Course course, VBox container) {
        boolean isHost = course.ownerUsername().equalsIgnoreCase(user.username());
        VBox root = new VBox(14);
        VBox.setVgrow(root, Priority.ALWAYS);

        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Tài liệu, Đề ôn tập & Slide bài giảng");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: -ink;");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        bar.getChildren().addAll(title, gap);

        if (isHost) {
            Button addBtn = new Button("＋ Thêm tài liệu mới");
            addBtn.getStyleClass().addAll("button", "button-primary");
            addBtn.setOnAction(e -> showAddMaterialDialog(course.id(), () -> renderMaterialsTab(course, container)));
            bar.getChildren().add(addBtn);
        }

        root.getChildren().add(bar);

        List<CourseRepository.Material> materials = courseRepo.materialsOf(course.id());
        VBox list = new VBox(10);
        list.setPadding(new Insets(6, 0, 16, 0));

        if (materials.isEmpty()) {
            VBox emptyBox = new VBox(10);
            emptyBox.setAlignment(Pos.CENTER);
            emptyBox.setPadding(new Insets(40, 0, 20, 0));
            Label emptyIcon = new Label("📂");
            emptyIcon.setStyle("-fx-font-size: 40px;");
            Label emptyMsg = new Label(isHost ? "Chưa có tài liệu nào.\nNhấn \"+ Thêm tài liệu mới\" để tải lên slide bài giảng, đề thi..." : "Chủ phòng chưa tải tài liệu nào lên.\nHãy quay lại sau!");
            emptyMsg.getStyleClass().add("muted");
            emptyMsg.setWrapText(true);
            emptyMsg.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            emptyBox.getChildren().addAll(emptyIcon, emptyMsg);
            list.getChildren().add(emptyBox);
        } else {
            for (CourseRepository.Material m : materials) {
                HBox item = new HBox(14);
                item.setAlignment(Pos.CENTER_LEFT);
                item.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-padding: 14 18; -fx-border-color: #e5e7eb; -fx-border-radius: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.04), 4, 0, 0, 1);");

                String ext = (m.originalFileName() != null && m.originalFileName().contains("."))
                    ? m.originalFileName().substring(m.originalFileName().lastIndexOf('.') + 1).toUpperCase() : m.fileType();
                String icon = switch (ext.toUpperCase()) {
                    case "PDF" -> "📕";
                    case "PPT", "PPTX", "SLIDE" -> "📑";
                    case "DOC", "DOCX" -> "📘";
                    case "XLS", "XLSX" -> "📗";
                    case "ZIP", "RAR", "7Z" -> "📦";
                    case "PNG", "JPG", "JPEG" -> "📷";
                    case "EXAM" -> "📝";
                    default -> "📁";
                };

                Label iconLbl = new Label(icon);
                iconLbl.setStyle("-fx-font-size: 28px;");

                VBox info = new VBox(4);
                HBox.setHgrow(info, Priority.ALWAYS);
                Label nameLbl = new Label(m.title());
                nameLbl.setStyle("-fx-font-size: 14px; -fx-font-weight: 800; -fx-text-fill: #111827;");
                nameLbl.setWrapText(true);
                nameLbl.setMaxWidth(320);

                String metaText = m.fileSize() != null ? m.fileSize() : "";
                if (m.originalFileName() != null) metaText += (metaText.isEmpty() ? "" : "  ·  ") + m.originalFileName();
                metaText += "  ·  Đăng bởi @" + m.uploadedBy();
                Label metaLbl = new Label(metaText);
                metaLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #6b7280;");
                metaLbl.setWrapText(true);
                metaLbl.setMaxWidth(320);
                info.getChildren().addAll(nameLbl, metaLbl);

                // Download button — tải file thật
                boolean hasData = m.originalFileName() != null; // indicator that real file was uploaded
                Button downloadBtn = new Button("⬇  Tải về");
                downloadBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 12px; -fx-background-radius: 20; -fx-padding: 6 16; -fx-cursor: hand;");
                downloadBtn.setOnAction(e -> {
                    byte[] data = courseRepo.getMaterialData(m.id());
                    if (data == null || data.length == 0) {
                        toast("Tài liệu này chưa có file để tải (chỉ có metadata).");
                        return;
                    }
                    FileChooser saveChooser = new FileChooser();
                    saveChooser.setTitle("Lưu tài liệu về máy");
                    String defaultName = m.originalFileName() != null ? m.originalFileName() : m.title().replaceAll("[^a-zA-Z0-9\\-_.\\s]", "_") + ".pdf";
                    saveChooser.setInitialFileName(defaultName);
                    File target = saveChooser.showSaveDialog(scene != null && scene.getWindow() != null ? scene.getWindow() : null);
                    if (target != null) {
                        try {
                            Files.write(target.toPath(), data);
                            toast("✅ Đã lưu \"" + m.title() + "\" về máy thành công!");
                        } catch (Exception ex) {
                            toast("Lỗi lưu file: " + ex.getMessage());
                        }
                    }
                });

                HBox actions = new HBox(8, downloadBtn);
                actions.setAlignment(Pos.CENTER_RIGHT);

                // Host: thêm nút Xóa
                if (isHost) {
                    Button delBtn = new Button("🗑");
                    delBtn.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #dc2626; -fx-font-size: 13px; -fx-background-radius: 20; -fx-padding: 6 10; -fx-cursor: hand;");
                    delBtn.setTooltip(new Tooltip("Xóa tài liệu này"));
                    delBtn.setOnAction(e -> {
                        courseRepo.deleteMaterial(m.id());
                        renderMaterialsTab(course, container);
                        toast("Đã xóa tài liệu \"" + m.title() + "\"");
                    });
                    actions.getChildren().add(delBtn);
                }

                item.getChildren().addAll(iconLbl, info, actions);
                list.getChildren().add(item);
            }
        }

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("thread-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.getChildren().add(scroll);

        container.getChildren().clear();
        container.getChildren().add(root);
    }



    private void renderScheduleTab(CourseRepository.Course course, VBox container, Runnable onEnterClassroom) {
        VBox root = new VBox(14);
        VBox.setVgrow(root, Priority.ALWAYS);

        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Lịch học & Các buổi ôn tập trực tuyến");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: -ink;");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        Button addBtn = new Button("＋ Lên lịch buổi học mới");
        addBtn.getStyleClass().addAll("button", "button-primary");
        addBtn.setOnAction(e -> showAddScheduleDialog(course.id(), () -> renderScheduleTab(course, container, onEnterClassroom)));

        bar.getChildren().addAll(title, gap, addBtn);
        root.getChildren().add(bar);

        List<CourseRepository.Schedule> schedules = courseRepo.schedulesOf(course.id());
        VBox list = new VBox(12);
        list.setPadding(new Insets(6, 0, 16, 0));

        if (schedules.isEmpty()) {
            Label empty = new Label("Chưa có lịch học nào được lên kế hoạch.");
            empty.getStyleClass().add("muted");
            list.getChildren().add(empty);
        } else {
            for (CourseRepository.Schedule s : schedules) {
                HBox item = new HBox(16);
                item.setAlignment(Pos.CENTER_LEFT);
                item.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-padding: 16 20; -fx-border-color: -line; -fx-border-radius: 12;");

                VBox dateBox = new VBox(4);
                dateBox.setAlignment(Pos.CENTER);
                dateBox.setStyle("-fx-background-color: -violet-soft; -fx-background-radius: 10; -fx-padding: 10 14; -fx-min-width: 140;");
                Label timeLbl = new Label(s.sessionTime());
                timeLbl.setStyle("-fx-font-weight: 800; -fx-text-fill: -violet; -fx-font-size: 12px;");
                timeLbl.setWrapText(true);
                dateBox.getChildren().add(timeLbl);

                VBox info = new VBox(4);
                HBox.setHgrow(info, Priority.ALWAYS);
                Label nameLbl = new Label(s.sessionTitle());
                nameLbl.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-text-fill: -ink;");
                Label descLbl = new Label(s.description() != null ? s.description() : "");
                descLbl.getStyleClass().add("muted");
                descLbl.setStyle("-fx-font-size: 13px;");
                info.getChildren().addAll(nameLbl, descLbl);

                Button enterClassBtn = new Button("Vào học ngay 🚀");
                enterClassBtn.getStyleClass().addAll("button", "button-primary");
                enterClassBtn.setOnAction(e -> onEnterClassroom.run());

                item.getChildren().addAll(dateBox, info, enterClassBtn);
                list.getChildren().add(item);
            }
        }

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("thread-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.getChildren().add(scroll);

        container.getChildren().clear();
        container.getChildren().add(root);
    }

    private void showCreateCourseDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Tạo khóa học mới");
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().getStylesheets().addAll(
            getClass().getResource("/tokens.css").toExternalForm(),
            getClass().getResource("/studyroom.css").toExternalForm()
        );
        dialog.getDialogPane().getStyleClass().add("custom-dialog");

        VBox root = new VBox(14);
        root.setPadding(new Insets(16, 20, 16, 20));
        root.setMinWidth(420);

        Label title = new Label("Tạo khóa học mới");
        title.getStyleClass().add("dialog-title");
        Label desc = new Label("Thiết lập phòng học, mã phòng và mật khẩu để mời bạn bè tham gia.");
        desc.getStyleClass().add("dialog-desc");

        TextField titleInput = new TextField();
        titleInput.setPromptText("Tên khóa học (VD: Luyện đề Toán 12)");
        titleInput.getStyleClass().add("input");

        TextField codeInput = new TextField();
        codeInput.setPromptText("Mã phòng học (VD: TOAN12 - viết liền, không dấu)");
        codeInput.getStyleClass().add("input");

        PasswordField passInput = new PasswordField();
        passInput.setPromptText("Mật khẩu vào phòng học");
        passInput.getStyleClass().add("input");

        TextField descInput = new TextField();
        descInput.setPromptText("Mô tả khóa học (tùy chọn)");
        descInput.getStyleClass().add("input");

        Label errorLbl = new Label();
        errorLbl.getStyleClass().add("form-error");

        Button submitBtn = new Button("Tạo khóa học");
        submitBtn.getStyleClass().addAll("button", "button-primary");
        submitBtn.setMaxWidth(Double.MAX_VALUE);

        submitBtn.setOnAction(e -> {
            try {
                CourseRepository.Course created = courseRepo.createCourse(
                    titleInput.getText(),
                    codeInput.getText(),
                    passInput.getText(),
                    descInput.getText(),
                    user.username()
                );
                activeCourseId = created.id();
                dialog.close();
                showRoom();
                toast("Đã tạo thành công phòng học: " + created.title());
            } catch (Exception ex) {
                errorLbl.setText(ex.getMessage());
            }
        });

        root.getChildren().addAll(title, desc, titleInput, codeInput, passInput, descInput, errorLbl, submitBtn);
        dialog.getDialogPane().setContent(root);

        ButtonType closeType = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeType);
        Button closeBtn = (Button) dialog.getDialogPane().lookupButton(closeType);
        closeBtn.getStyleClass().add("button");

        dialog.show();
    }

    private void showJoinCourseDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Tham gia khóa học");
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().getStylesheets().addAll(
            getClass().getResource("/tokens.css").toExternalForm(),
            getClass().getResource("/studyroom.css").toExternalForm()
        );
        dialog.getDialogPane().getStyleClass().add("custom-dialog");

        VBox root = new VBox(14);
        root.setPadding(new Insets(16, 20, 16, 20));
        root.setMinWidth(380);

        Label title = new Label("Tham gia khóa học bằng mã");
        title.getStyleClass().add("dialog-title");
        Label desc = new Label("Nhập mã phòng học và mật khẩu được chủ phòng chia sẻ.");
        desc.getStyleClass().add("dialog-desc");

        TextField codeInput = new TextField();
        codeInput.setPromptText("Mã phòng học (VD: HOA12)");
        codeInput.getStyleClass().add("input");

        PasswordField passInput = new PasswordField();
        passInput.setPromptText("Mật khẩu phòng học");
        passInput.getStyleClass().add("input");

        Label errorLbl = new Label();
        errorLbl.getStyleClass().add("form-error");

        Button submitBtn = new Button("Vào phòng học");
        submitBtn.getStyleClass().addAll("button", "button-primary");
        submitBtn.setMaxWidth(Double.MAX_VALUE);

        submitBtn.setOnAction(e -> {
            try {
                CourseRepository.Course joined = courseRepo.joinCourse(
                    codeInput.getText(),
                    passInput.getText(),
                    user.username()
                );
                activeCourseId = joined.id();
                dialog.close();
                showRoom();
                toast("Đã tham gia phòng học: " + joined.title());
            } catch (Exception ex) {
                errorLbl.setText(ex.getMessage());
            }
        });

        root.getChildren().addAll(title, desc, codeInput, passInput, errorLbl, submitBtn);
        dialog.getDialogPane().setContent(root);

        ButtonType closeType = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeType);
        Button closeBtn = (Button) dialog.getDialogPane().lookupButton(closeType);
        closeBtn.getStyleClass().add("button");

        dialog.show();
    }

    private void showAddMaterialDialog(String courseId, Runnable onAdded) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Thêm tài liệu");
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().getStylesheets().addAll(
            getClass().getResource("/tokens.css").toExternalForm(),
            getClass().getResource("/studyroom.css").toExternalForm()
        );
        dialog.getDialogPane().getStyleClass().add("custom-dialog");

        VBox root = new VBox(14);
        root.setPadding(new Insets(20, 24, 20, 24));
        root.setMinWidth(440);

        Label title = new Label("Thêm tài liệu / Slide vào phòng học");
        title.getStyleClass().add("dialog-title");

        // File chooser area
        final File[] selectedFile = {null};
        final byte[][] selectedBytes = {null};

        HBox filePickerRow = new HBox(10);
        filePickerRow.setAlignment(Pos.CENTER_LEFT);

        Button pickFileBtn = new Button("📂  Chọn file...");
        pickFileBtn.setStyle("-fx-background-color: #f3f4f6; -fx-border-color: #d1d5db; -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 8 14; -fx-cursor: hand; -fx-font-size: 13px;");
        pickFileBtn.setMinWidth(140);

        Label fileNameLbl = new Label("Chưa chọn file nào");
        fileNameLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #9ca3af; -fx-font-style: italic;");
        fileNameLbl.setWrapText(true);
        fileNameLbl.setMaxWidth(240);
        filePickerRow.getChildren().addAll(pickFileBtn, fileNameLbl);

        // File preview card (hidden until file chosen)
        HBox previewCard = new HBox(10);
        previewCard.setAlignment(Pos.CENTER_LEFT);
        previewCard.setStyle("-fx-background-color: #f0f9ff; -fx-background-radius: 10; -fx-border-color: #bae6fd; -fx-border-radius: 10; -fx-padding: 10 14;");
        previewCard.setVisible(false);
        previewCard.setManaged(false);

        Label previewIcon = new Label("📄");
        previewIcon.setStyle("-fx-font-size: 24px;");
        VBox previewMeta = new VBox(2);
        Label previewName = new Label();
        previewName.setStyle("-fx-font-weight: 700; -fx-font-size: 13px; -fx-text-fill: #0369a1;");
        previewName.setWrapText(true);
        previewName.setMaxWidth(300);
        Label previewSize = new Label();
        previewSize.setStyle("-fx-font-size: 11px; -fx-text-fill: #0ea5e9;");
        previewMeta.getChildren().addAll(previewName, previewSize);
        previewCard.getChildren().addAll(previewIcon, previewMeta);

        // Title input
        Label titleLabel = new Label("Tên hiển thị trong phòng học");
        titleLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: #374151;");
        TextField titleInput = new TextField();
        titleInput.setPromptText("VD: Đề thi thử THPT Quốc Gia số 1 · Môn Hóa học");
        titleInput.getStyleClass().add("input");

        // File type combo
        Label typeLabel = new Label("Loại tài liệu");
        typeLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: #374151;");
        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("SLIDE", "PDF", "EXAM", "TÀI LIỆU");
        typeCombo.setValue("SLIDE");
        typeCombo.setMaxWidth(Double.MAX_VALUE);

        // Submit button
        Button submitBtn = new Button("📤  Tải lên \u0026 Thêm vào phòng");
        submitBtn.getStyleClass().addAll("button", "button-primary");
        submitBtn.setMaxWidth(Double.MAX_VALUE);
        submitBtn.setDisable(true);

        // File picker action
        pickFileBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Chọn tài liệu để tải lên");
            chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Tất cả tài liệu", "*.pdf", "*.ppt", "*.pptx", "*.doc", "*.docx", "*.xls", "*.xlsx", "*.txt", "*.zip", "*.png", "*.jpg", "*.jpeg"),
                new FileChooser.ExtensionFilter("PDF (*.pdf)", "*.pdf"),
                new FileChooser.ExtensionFilter("Slide PowerPoint (*.ppt, *.pptx)", "*.ppt", "*.pptx"),
                new FileChooser.ExtensionFilter("Word (*.doc, *.docx)", "*.doc", "*.docx"),
                new FileChooser.ExtensionFilter("Excel (*.xls, *.xlsx)", "*.xls", "*.xlsx"),
                new FileChooser.ExtensionFilter("Hình ảnh (*.png, *.jpg)", "*.png", "*.jpg", "*.jpeg")
            );
            File f = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (f != null) {
                if (f.length() > 100 * 1024 * 1024) {
                    toast("File quá lớn (> 100MB). Vui lòng chọn file nhỏ hơn.");
                    return;
                }
                try {
                    selectedFile[0] = f;
                    selectedBytes[0] = Files.readAllBytes(f.toPath());

                    // Update UI
                    String ext = f.getName().contains(".") ? f.getName().substring(f.getName().lastIndexOf('.') + 1).toUpperCase() : "FILE";
                    String sizeStr = formatFileSize(f.length());

                    fileNameLbl.setText(f.getName());
                    fileNameLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #374151;");

                    // Preview card
                    String pIcon = switch (ext) {
                        case "PDF" -> "📕";
                        case "PPT", "PPTX" -> "📑";
                        case "DOC", "DOCX" -> "📘";
                        case "XLS", "XLSX" -> "📗";
                        case "ZIP", "RAR" -> "📦";
                        case "PNG", "JPG", "JPEG" -> "📷";
                        default -> "📄";
                    };
                    previewIcon.setText(pIcon);
                    previewName.setText(f.getName());
                    previewSize.setText(ext + "  ·  " + sizeStr);
                    previewCard.setVisible(true);
                    previewCard.setManaged(true);

                    // Auto-set title if blank
                    if (titleInput.getText().isBlank()) {
                        String baseName = f.getName().contains(".") ? f.getName().substring(0, f.getName().lastIndexOf('.')) : f.getName();
                        titleInput.setText(baseName);
                    }

                    // Auto-select type based on extension
                    if (ext.equals("PDF")) typeCombo.setValue("PDF");
                    else if (ext.equals("PPT") || ext.equals("PPTX")) typeCombo.setValue("SLIDE");
                    else if (ext.equals("EXAM")) typeCombo.setValue("EXAM");

                    submitBtn.setDisable(false);
                } catch (Exception ex) {
                    toast("Không đọc được file: " + ex.getMessage());
                }
            }
        });

        submitBtn.setOnAction(e -> {
            if (selectedFile[0] == null || selectedBytes[0] == null) {
                toast("Vui lòng chọn file trước!");
                return;
            }
            if (titleInput.getText().isBlank()) {
                toast("Vui lòng nhập tên tài liệu!");
                return;
            }
            try {
                String ext = selectedFile[0].getName().contains(".") ? selectedFile[0].getName().substring(selectedFile[0].getName().lastIndexOf('.') + 1).toUpperCase() : "FILE";
                String sizeStr = ext + "  ·  " + formatFileSize(selectedFile[0].length());
                courseRepo.addMaterial(
                    courseId,
                    titleInput.getText().trim(),
                    typeCombo.getValue(),
                    sizeStr,
                    user.username(),
                    selectedBytes[0],
                    selectedFile[0].getName()
                );
                dialog.close();
                onAdded.run();
                toast("✅ Đã tải lên \"" + titleInput.getText().trim() + "\" thành công!");
            } catch (Exception ex) {
                toast("Lỗi khi tải lên: " + ex.getMessage());
            }
        });

        root.getChildren().addAll(title, filePickerRow, previewCard, titleLabel, titleInput, typeLabel, typeCombo, submitBtn);
        dialog.getDialogPane().setContent(root);

        ButtonType closeType = new ButtonType("Đóng", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeType);
        Button closeBtn = (Button) dialog.getDialogPane().lookupButton(closeType);
        closeBtn.getStyleClass().add("button");

        dialog.show();
    }


    private void renderQuizzesTab(CourseRepository.Course course, VBox container) {
        boolean isHost = course.ownerUsername().equalsIgnoreCase(user.username());
        VBox root = new VBox(14);
        VBox.setVgrow(root, Priority.ALWAYS);

        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Bài kiểm tra, Khảo sát & Bảng điểm");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: -ink;");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        Button addBtn = new Button("＋ Tạo bài kiểm tra mới");
        addBtn.getStyleClass().addAll("button", "button-primary");
        addBtn.setOnAction(e -> showCreateQuizDialog(course.id(), () -> renderQuizzesTab(course, container)));

        bar.getChildren().addAll(title, gap, addBtn);
        root.getChildren().add(bar);

        List<QuizRepository.Quiz> quizzes = quizRepo.getQuizzesForCourse(course.id(), user.username());
        VBox list = new VBox(12);
        list.setPadding(new Insets(6, 0, 16, 0));

        if (quizzes.isEmpty()) {
            VBox emptyBox = new VBox(10);
            emptyBox.setAlignment(Pos.CENTER);
            emptyBox.setPadding(new Insets(40, 0, 20, 0));
            Label emptyIcon = new Label("📝");
            emptyIcon.setStyle("-fx-font-size: 40px;");
            Label emptyMsg = new Label("Chưa có bài kiểm tra nào trong khóa học này.\nNhấn \"+ Tạo bài kiểm tra mới\" để tạo bài thi trắc nghiệm và chấm điểm tự động!");
            emptyMsg.getStyleClass().add("muted");
            emptyMsg.setWrapText(true);
            emptyMsg.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            emptyBox.getChildren().addAll(emptyIcon, emptyMsg);
            list.getChildren().add(emptyBox);
        } else {
            for (QuizRepository.Quiz q : quizzes) {
                HBox card = new HBox(16);
                card.setAlignment(Pos.CENTER_LEFT);
                card.setStyle("-fx-background-color: white; -fx-background-radius: 14; -fx-padding: 16 20; -fx-border-color: #e5e7eb; -fx-border-radius: 14; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.04), 6, 0, 0, 2);");

                Label iconLbl = new Label("📝");
                iconLbl.setStyle("-fx-font-size: 30px; -fx-background-color: #eef2ff; -fx-padding: 10 14; -fx-background-radius: 12;");

                VBox info = new VBox(6);
                HBox.setHgrow(info, Priority.ALWAYS);

                HBox titleRow = new HBox(8);
                titleRow.setAlignment(Pos.CENTER_LEFT);
                Label qTitle = new Label(q.title());
                qTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");
                titleRow.getChildren().add(qTitle);

                Label descLbl = new Label(q.description() != null ? q.description() : "");
                descLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");
                descLbl.setWrapText(true);

                HBox metaRow = new HBox(8);
                metaRow.setAlignment(Pos.CENTER_LEFT);

                Label timeBadge = new Label("⏱ " + q.durationMinutes() + " phút");
                timeBadge.setStyle("-fx-font-size: 11px; -fx-font-weight: 600; -fx-background-color: #eff6ff; -fx-text-fill: #2563eb; -fx-padding: 2 8; -fx-background-radius: 6;");

                Label countBadge = new Label("❓ " + q.questionCount() + " câu hỏi");
                countBadge.setStyle("-fx-font-size: 11px; -fx-font-weight: 600; -fx-background-color: #f5f3ff; -fx-text-fill: #7c3aed; -fx-padding: 2 8; -fx-background-radius: 6;");

                Label authorBadge = new Label("👤 @" + q.createdBy());
                authorBadge.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b; -fx-background-color: #f1f5f9; -fx-padding: 2 8; -fx-background-radius: 6;");

                Label statusBadge = new Label();
                if (q.userScore() != null) {
                    statusBadge.setText(String.format("✅ Đã làm: %.1f/10 điểm", q.userScore()));
                    statusBadge.setStyle("-fx-font-size: 11px; -fx-font-weight: 700; -fx-background-color: #ecfdf5; -fx-text-fill: #059669; -fx-padding: 2 8; -fx-background-radius: 6;");
                } else {
                    statusBadge.setText("⚪ Chưa làm bài");
                    statusBadge.setStyle("-fx-font-size: 11px; -fx-font-weight: 600; -fx-background-color: #f8fafc; -fx-text-fill: #94a3b8; -fx-padding: 2 8; -fx-background-radius: 6; -fx-border-color: #cbd5e1; -fx-border-radius: 6;");
                }

                metaRow.getChildren().addAll(timeBadge, countBadge, authorBadge, statusBadge);
                info.getChildren().addAll(titleRow, descLbl, metaRow);

                HBox actions = new HBox(8);
                actions.setAlignment(Pos.CENTER_RIGHT);

                Button takeBtn = new Button(q.userScore() != null ? "🔄 Làm lại" : "▶ Làm bài");
                takeBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 12px; -fx-background-radius: 20; -fx-padding: 7 16; -fx-cursor: hand;");
                takeBtn.setOnAction(e -> showTakeQuizDialog(q, () -> renderQuizzesTab(course, container)));

                Button scoresBtn = new Button("🏆 Bảng điểm");
                scoresBtn.setStyle("-fx-background-color: #fef3c7; -fx-text-fill: #b45309; -fx-font-weight: 700; -fx-font-size: 12px; -fx-background-radius: 20; -fx-padding: 7 14; -fx-cursor: hand;");
                scoresBtn.setOnAction(e -> showQuizScoresDialog(q));

                actions.getChildren().addAll(takeBtn, scoresBtn);

                if (isHost || q.createdBy().equalsIgnoreCase(user.username())) {
                    Button delBtn = new Button("🗑");
                    delBtn.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #dc2626; -fx-font-size: 13px; -fx-background-radius: 20; -fx-padding: 6 10; -fx-cursor: hand;");
                    delBtn.setTooltip(new Tooltip("Xóa bài kiểm tra này"));
                    delBtn.setOnAction(e -> {
                        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                        alert.setTitle("Xác nhận xóa");
                        alert.setHeaderText("Xóa bài kiểm tra \"" + q.title() + "\"?");
                        alert.setContentText("Tất cả câu hỏi và điểm số của học sinh trong bài này sẽ bị xóa vĩnh viễn.");
                        alert.showAndWait().ifPresent(res -> {
                            if (res == ButtonType.OK) {
                                quizRepo.deleteQuiz(q.id());
                                renderQuizzesTab(course, container);
                                toast("Đã xóa bài kiểm tra \"" + q.title() + "\"");
                            }
                        });
                    });
                    actions.getChildren().add(delBtn);
                }

                card.getChildren().addAll(iconLbl, info, actions);
                list.getChildren().add(card);
            }
        }

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("thread-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root.getChildren().add(scroll);

        container.getChildren().clear();
        container.getChildren().add(root);
    }

    private void showCreateQuizDialog(String courseId, Runnable onCreated) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Tạo bài kiểm tra mới");
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().getStylesheets().addAll(
            getClass().getResource("/tokens.css").toExternalForm(),
            getClass().getResource("/studyroom.css").toExternalForm()
        );
        dialog.getDialogPane().getStyleClass().add("custom-dialog");

        VBox root = new VBox(14);
        root.setPadding(new Insets(20, 24, 20, 24));
        root.setPrefWidth(680);
        root.setMaxWidth(680);

        Label titleLbl = new Label("Tạo bài kiểm tra trắc nghiệm");
        titleLbl.getStyleClass().add("dialog-title");

        TextField titleInput = new TextField();
        titleInput.setPromptText("Tiêu đề bài kiểm tra (VD: Kiểm tra 15 phút Este & Hóa Hữu cơ...)");
        titleInput.getStyleClass().add("input");

        TextField descInput = new TextField();
        descInput.setPromptText("Mô tả / Hướng dẫn làm bài...");
        descInput.getStyleClass().add("input");

        HBox timeRow = new HBox(12);
        timeRow.setAlignment(Pos.CENTER_LEFT);
        Label timeLbl = new Label("Thời gian làm bài:");
        timeLbl.setStyle("-fx-font-weight: 600; -fx-text-fill: #374151;");
        Spinner<Integer> durationSpinner = new Spinner<>(5, 180, 15, 5);
        durationSpinner.setEditable(true);
        durationSpinner.setPrefWidth(90);
        Label minLbl = new Label("phút");
        timeRow.getChildren().addAll(timeLbl, durationSpinner, minLbl);

        Label questListLbl = new Label("Danh sách câu hỏi trắc nghiệm (Chọn 1 trong 4 đáp án đúng):");
        questListLbl.setStyle("-fx-font-weight: 700; -fx-font-size: 13px; -fx-text-fill: #1f2937;");

        VBox questionsContainer = new VBox(14);

        class QuestionItem {
            TextArea qText = new TextArea();
            TextField optA = new TextField();
            TextField optB = new TextField();
            TextField optC = new TextField();
            TextField optD = new TextField();
            ComboBox<String> correctBox = new ComboBox<>();
            VBox card = new VBox(8);

            QuestionItem(int index, String q, String a, String b, String c, String d, String correct) {
                card.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 10; -fx-padding: 12 14; -fx-border-color: #e2e8f0; -fx-border-radius: 10;");

                HBox cardHeader = new HBox(8);
                cardHeader.setAlignment(Pos.CENTER_LEFT);
                Label numLbl = new Label("Câu " + index + ":");
                numLbl.setStyle("-fx-font-weight: 800; -fx-text-fill: #4338ca; -fx-font-size: 13px;");
                Region chGap = new Region();
                HBox.setHgrow(chGap, Priority.ALWAYS);

                Button delBtn = new Button("✕ Xóa");
                delBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #ef4444; -fx-cursor: hand; -fx-font-size: 11px; -fx-font-weight: 600;");
                cardHeader.getChildren().addAll(numLbl, chGap, delBtn);

                qText.setPromptText("Nội dung câu hỏi...");
                qText.setPrefRowCount(2);
                qText.setWrapText(true);
                if (q != null) qText.setText(q);

                optA.setPromptText("Đáp án A");
                optB.setPromptText("Đáp án B");
                optC.setPromptText("Đáp án C");
                optD.setPromptText("Đáp án D");
                if (a != null) optA.setText(a);
                if (b != null) optB.setText(b);
                if (c != null) optC.setText(c);
                if (d != null) optD.setText(d);

                HBox rowAB = new HBox(8, new Label("A."), optA, new Label("B."), optB);
                rowAB.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(optA, Priority.ALWAYS);
                HBox.setHgrow(optB, Priority.ALWAYS);

                HBox rowCD = new HBox(8, new Label("C."), optC, new Label("D."), optD);
                rowCD.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(optC, Priority.ALWAYS);
                HBox.setHgrow(optD, Priority.ALWAYS);

                correctBox.getItems().addAll("A", "B", "C", "D");
                correctBox.setValue(correct != null ? correct : "A");

                HBox correctRow = new HBox(8);
                correctRow.setAlignment(Pos.CENTER_LEFT);
                Label cLbl = new Label("Đáp án đúng:");
                cLbl.setStyle("-fx-font-weight: 700; -fx-text-fill: #059669;");
                correctRow.getChildren().addAll(cLbl, correctBox);

                card.getChildren().addAll(cardHeader, qText, rowAB, rowCD, correctRow);
            }

            void setIndex(int idx) {
                if (!card.getChildren().isEmpty() && card.getChildren().get(0) instanceof HBox h) {
                    if (!h.getChildren().isEmpty() && h.getChildren().get(0) instanceof Label l) {
                        l.setText("Câu " + idx + ":");
                    }
                }
            }

            boolean isValid() {
                return !qText.getText().isBlank() && !optA.getText().isBlank() && !optB.getText().isBlank() && !optC.getText().isBlank() && !optD.getText().isBlank();
            }

            QuizRepository.QuestionInput toInput() {
                return new QuizRepository.QuestionInput(
                    qText.getText(),
                    optA.getText(),
                    optB.getText(),
                    optC.getText(),
                    optD.getText(),
                    correctBox.getValue()
                );
            }
        }

        List<QuestionItem> questionItems = new ArrayList<>();

        java.util.function.Consumer<QuestionItem> attachDelete = item -> {
            Button delBtn = (Button) ((HBox) item.card.getChildren().get(0)).getChildren().get(2);
            delBtn.setOnAction(ev -> {
                questionItems.remove(item);
                questionsContainer.getChildren().remove(item.card);
                for (int i = 0; i < questionItems.size(); i++) {
                    questionItems.get(i).setIndex(i + 1);
                }
            });
        };

        Runnable addQuestionCard = () -> {
            int nextIdx = questionItems.size() + 1;
            QuestionItem item = new QuestionItem(nextIdx, "", "", "", "", "", "A");
            questionItems.add(item);
            questionsContainer.getChildren().add(item.card);
            attachDelete.accept(item);
        };

        // Add 2 initial question cards
        addQuestionCard.run();
        addQuestionCard.run();

        Button addQBtn = new Button("＋ Thêm câu hỏi");
        addQBtn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #4338ca; -fx-font-weight: 700; -fx-border-color: #cbd5e1; -fx-border-radius: 8; -fx-background-radius: 8; -fx-cursor: hand; -fx-padding: 6 14;");
        addQBtn.setOnAction(e -> addQuestionCard.run());

        Button addSampleBtn = new Button("💡 Thêm 2 câu hỏi mẫu");
        addSampleBtn.setStyle("-fx-background-color: #fef3c7; -fx-text-fill: #92400e; -fx-font-weight: 600; -fx-border-color: #fde68a; -fx-border-radius: 8; -fx-background-radius: 8; -fx-cursor: hand; -fx-padding: 6 12;");
        addSampleBtn.setOnAction(e -> {
            int idx1 = questionItems.size() + 1;
            QuestionItem item1 = new QuestionItem(idx1, "Este no, đơn chức, mạch hở có công thức tổng quát là:", "CnH2nO2 (n >= 2)", "CnH2n-2O2 (n >= 4)", "CnH2n+2O (n >= 1)", "CnH2nO (n >= 1)", "A");
            questionItems.add(item1);
            questionsContainer.getChildren().add(item1.card);
            attachDelete.accept(item1);

            int idx2 = questionItems.size() + 1;
            QuestionItem item2 = new QuestionItem(idx2, "Thủy phân hoàn toàn 8.8 gam etyl axetat trong dung dịch NaOH đun nóng, khối lượng muối thu được là:", "8.2 gam", "6.8 gam", "4.1 gam", "9.4 gam", "A");
            questionItems.add(item2);
            questionsContainer.getChildren().add(item2.card);
            attachDelete.accept(item2);
        });

        HBox btnRow = new HBox(10, addQBtn, addSampleBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        ScrollPane qScroll = new ScrollPane(questionsContainer);
        qScroll.setFitToWidth(true);
        qScroll.setPrefHeight(280);
        qScroll.setMaxHeight(320);
        qScroll.setStyle("-fx-background-color: transparent; -fx-border-color: #e2e8f0; -fx-border-radius: 8;");

        Label errorLbl = new Label();
        errorLbl.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 12px;");

        HBox actionRow = new HBox(10);
        actionRow.setAlignment(Pos.CENTER_RIGHT);

        Button cancelBtn = new Button("Hủy");
        cancelBtn.getStyleClass().add("button");
        cancelBtn.setOnAction(e -> dialog.close());

        Button submitBtn = new Button("Hoàn tất tạo đề");
        submitBtn.getStyleClass().addAll("button", "button-primary");
        submitBtn.setOnAction(e -> {
            String title = titleInput.getText().trim();
            if (title.isBlank()) {
                errorLbl.setText("Vui lòng nhập tiêu đề bài kiểm tra.");
                return;
            }
            if (questionItems.isEmpty()) {
                errorLbl.setText("Bài kiểm tra phải có ít nhất 1 câu hỏi.");
                return;
            }
            for (int i = 0; i < questionItems.size(); i++) {
                if (!questionItems.get(i).isValid()) {
                    errorLbl.setText("Câu hỏi " + (i + 1) + " chưa nhập đầy đủ nội dung hoặc các đáp án.");
                    return;
                }
            }

            List<QuizRepository.QuestionInput> inputs = new ArrayList<>();
            for (QuestionItem qi : questionItems) {
                inputs.add(qi.toInput());
            }

            try {
                quizRepo.createQuiz(courseId, title, descInput.getText().trim(), durationSpinner.getValue(), user.username(), inputs);
                toast("Đã tạo bài kiểm tra \"" + title + "\" thành công!");
                dialog.close();
                if (onCreated != null) onCreated.run();
            } catch (Exception ex) {
                errorLbl.setText(ex.getMessage());
            }
        });

        actionRow.getChildren().addAll(cancelBtn, submitBtn);

        root.getChildren().addAll(titleLbl, titleInput, descInput, timeRow, questListLbl, qScroll, btnRow, errorLbl, actionRow);

        dialog.getDialogPane().setContent(root);
        ButtonType closeType = new ButtonType("", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeType);
        Button sysClose = (Button) dialog.getDialogPane().lookupButton(closeType);
        if (sysClose != null) sysClose.setVisible(false);

        dialog.show();
    }

    private void showTakeQuizDialog(QuizRepository.Quiz quiz, Runnable onSubmitted) {
        List<QuizRepository.Question> questions = quizRepo.getQuestionsForQuiz(quiz.id());
        if (questions.isEmpty()) {
            toast("Bài kiểm tra này chưa có câu hỏi nào.");
            return;
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Làm bài kiểm tra: " + quiz.title());
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().getStylesheets().addAll(
            getClass().getResource("/tokens.css").toExternalForm(),
            getClass().getResource("/studyroom.css").toExternalForm()
        );
        dialog.getDialogPane().getStyleClass().add("custom-dialog");

        VBox root = new VBox(14);
        root.setPadding(new Insets(20, 24, 20, 24));
        root.setPrefWidth(720);
        root.setMaxWidth(720);

        // Header
        HBox topBar = new HBox(12);
        topBar.setAlignment(Pos.CENTER_LEFT);

        VBox titleBox = new VBox(2);
        HBox.setHgrow(titleBox, Priority.ALWAYS);
        Label titleLbl = new Label(quiz.title());
        titleLbl.setStyle("-fx-font-size: 17px; -fx-font-weight: 800; -fx-text-fill: #111827;");
        Label subLbl = new Label("Số câu: " + questions.size() + " câu  ·  Thời gian: " + quiz.durationMinutes() + " phút");
        subLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #6b7280;");
        titleBox.getChildren().addAll(titleLbl, subLbl);

        // Timer badge
        final int[] secondsLeft = {quiz.durationMinutes() * 60};
        Label timerLbl = new Label(String.format("⏱ %02d:%02d", secondsLeft[0] / 60, secondsLeft[0] % 60));
        timerLbl.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-background-color: #fee2e2; -fx-text-fill: #dc2626; -fx-padding: 6 14; -fx-background-radius: 20;");

        topBar.getChildren().addAll(titleBox, timerLbl);

        // Map to track user's selected answers
        Map<String, String> userAnswers = new HashMap<>();

        VBox questionsList = new VBox(14);
        questionsList.setPadding(new Insets(4, 0, 16, 0));

        Label progressLbl = new Label("Đã trả lời: 0 / " + questions.size() + " câu");
        progressLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: #4b5563;");

        Runnable updateProgress = () -> {
            progressLbl.setText("Đã trả lời: " + userAnswers.size() + " / " + questions.size() + " câu");
        };

        for (int i = 0; i < questions.size(); i++) {
            QuizRepository.Question q = questions.get(i);
            VBox card = new VBox(10);
            card.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-padding: 16 18; -fx-border-color: #e5e7eb; -fx-border-radius: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.03), 4, 0, 0, 1);");

            Label qTextLbl = new Label("Câu " + (i + 1) + ": " + q.questionText());
            qTextLbl.setStyle("-fx-font-size: 14px; -fx-font-weight: 700; -fx-text-fill: #1e1b4b;");
            qTextLbl.setWrapText(true);

            ToggleGroup group = new ToggleGroup();

            VBox optionsBox = new VBox(8);
            optionsBox.setPadding(new Insets(2, 0, 0, 10));

            RadioButton rbA = new RadioButton("A. " + q.optionA());
            RadioButton rbB = new RadioButton("B. " + q.optionB());
            RadioButton rbC = new RadioButton("C. " + q.optionC());
            RadioButton rbD = new RadioButton("D. " + q.optionD());

            rbA.setToggleGroup(group);
            rbB.setToggleGroup(group);
            rbC.setToggleGroup(group);
            rbD.setToggleGroup(group);

            rbA.setStyle("-fx-font-size: 13px; -fx-text-fill: #374151;");
            rbB.setStyle("-fx-font-size: 13px; -fx-text-fill: #374151;");
            rbC.setStyle("-fx-font-size: 13px; -fx-text-fill: #374151;");
            rbD.setStyle("-fx-font-size: 13px; -fx-text-fill: #374151;");

            group.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal == rbA) userAnswers.put(q.id(), "A");
                else if (newVal == rbB) userAnswers.put(q.id(), "B");
                else if (newVal == rbC) userAnswers.put(q.id(), "C");
                else if (newVal == rbD) userAnswers.put(q.id(), "D");
                updateProgress.run();
            });

            optionsBox.getChildren().addAll(rbA, rbB, rbC, rbD);
            card.getChildren().addAll(qTextLbl, optionsBox);
            questionsList.getChildren().add(card);
        }

        ScrollPane scroll = new ScrollPane(questionsList);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(380);
        scroll.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");

        // Bottom action bar
        HBox bottomBar = new HBox(12);
        bottomBar.setAlignment(Pos.CENTER_LEFT);

        Region bottomGap = new Region();
        HBox.setHgrow(bottomGap, Priority.ALWAYS);

        Button cancelBtn = new Button("Thoát");
        cancelBtn.getStyleClass().add("button");

        Button submitBtn = new Button("Nộp bài kiểm tra");
        submitBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 13px; -fx-background-radius: 20; -fx-padding: 8 22; -fx-cursor: hand;");

        bottomBar.getChildren().addAll(progressLbl, bottomGap, cancelBtn, submitBtn);

        root.getChildren().addAll(topBar, scroll, bottomBar);

        dialog.getDialogPane().setContent(root);
        ButtonType closeType = new ButtonType("", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeType);
        Button sysClose = (Button) dialog.getDialogPane().lookupButton(closeType);
        if (sysClose != null) sysClose.setVisible(false);

        // Timer
        Timeline timer = new Timeline();
        timer.setCycleCount(Animation.INDEFINITE);
        KeyFrame kf = new KeyFrame(Duration.seconds(1), ev -> {
            secondsLeft[0]--;
            int m = secondsLeft[0] / 60;
            int s = secondsLeft[0] % 60;
            timerLbl.setText(String.format("⏱ %02d:%02d", m, s));
            if (secondsLeft[0] <= 60) {
                timerLbl.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-background-color: #dc2626; -fx-text-fill: white; -fx-padding: 6 14; -fx-background-radius: 20;");
            }
            if (secondsLeft[0] <= 0) {
                timer.stop();
                toast("Hết giờ làm bài! Hệ thống đang tự động nộp bài...");
                dialog.close();
                QuizRepository.QuizSubmission sub = quizRepo.submitQuiz(quiz.id(), user.username(), userAnswers);
                showQuizResultDialog(sub, questions, userAnswers, onSubmitted);
            }
        });
        timer.getKeyFrames().add(kf);
        timer.play();

        cancelBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Thoát làm bài");
            confirm.setHeaderText("Bạn có chắc muốn thoát?");
            confirm.setContentText("Bài làm của bạn sẽ không được lưu nếu thoát.");
            confirm.showAndWait().ifPresent(res -> {
                if (res == ButtonType.OK) {
                    timer.stop();
                    dialog.close();
                }
            });
        });

        submitBtn.setOnAction(e -> {
            int unanswered = questions.size() - userAnswers.size();
            if (unanswered > 0) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("Xác nhận nộp bài");
                confirm.setHeaderText("Bạn còn " + unanswered + " câu chưa làm!");
                confirm.setContentText("Bạn có chắc chắn muốn nộp bài ngay bây giờ không?");
                var res = confirm.showAndWait();
                if (res.isEmpty() || res.get() != ButtonType.OK) {
                    return;
                }
            }
            timer.stop();
            dialog.close();
            QuizRepository.QuizSubmission sub = quizRepo.submitQuiz(quiz.id(), user.username(), userAnswers);
            showQuizResultDialog(sub, questions, userAnswers, onSubmitted);
        });

        dialog.setOnCloseRequest(e -> timer.stop());
        dialog.show();
    }

    private void showQuizResultDialog(QuizRepository.QuizSubmission sub, List<QuizRepository.Question> questions, Map<String, String> userAnswers, Runnable onDone) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Kết quả bài kiểm tra");
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().getStylesheets().addAll(
            getClass().getResource("/tokens.css").toExternalForm(),
            getClass().getResource("/studyroom.css").toExternalForm()
        );
        dialog.getDialogPane().getStyleClass().add("custom-dialog");

        VBox root = new VBox(14);
        root.setPadding(new Insets(20, 24, 20, 24));
        root.setPrefWidth(640);
        root.setMaxWidth(640);

        // Score Card
        VBox scoreCard = new VBox(8);
        scoreCard.setAlignment(Pos.CENTER);
        scoreCard.setStyle("-fx-background-color: linear-gradient(to bottom right, #4f46e5, #7c3aed); -fx-background-radius: 14; -fx-padding: 20;");

        Label scoreLbl = new Label(String.format("%.1f / 10", sub.score()));
        scoreLbl.setStyle("-fx-font-size: 36px; -fx-font-weight: 900; -fx-text-fill: white;");

        Label detailLbl = new Label("Đúng " + sub.correctCount() + " / " + sub.totalQuestions() + " câu");
        detailLbl.setStyle("-fx-font-size: 15px; -fx-font-weight: 700; -fx-text-fill: #e0e7ff;");

        String praise = sub.score() >= 8.0 ? "🎉 Xuất sắc! Bạn nắm rất vững kiến thức."
                      : (sub.score() >= 5.0 ? "👍 Khá tốt! Hãy ôn lại các câu chưa chính xác." : "💪 Cố gắng thêm nhé! Hãy xem lại lời giải chi tiết bên dưới.");
        Label praiseLbl = new Label(praise);
        praiseLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #c7d2fe;");

        scoreCard.getChildren().addAll(scoreLbl, detailLbl, praiseLbl);

        Label reviewTitle = new Label("Chi tiết từng câu hỏi & Đáp án đúng:");
        reviewTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #1f2937;");

        VBox reviewList = new VBox(10);
        for (int i = 0; i < questions.size(); i++) {
            QuizRepository.Question q = questions.get(i);
            String userAns = userAnswers.getOrDefault(q.id(), "");
            boolean isCorrect = userAns.equalsIgnoreCase(q.correctOption());

            VBox item = new VBox(6);
            item.setStyle("-fx-background-color: " + (isCorrect ? "#f0fdf4" : "#fef2f2") + "; -fx-background-radius: 10; -fx-padding: 10 14; -fx-border-color: " + (isCorrect ? "#bbf7d0" : "#fecaca") + "; -fx-border-radius: 10;");

            Label qLbl = new Label("Câu " + (i + 1) + ": " + q.questionText());
            qLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #111827;");
            qLbl.setWrapText(true);

            Label ansLbl = new Label();
            if (isCorrect) {
                ansLbl.setText("✔ Bạn chọn: " + userAns + " (Chính xác!)");
                ansLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #16a34a;");
            } else {
                ansLbl.setText("✘ Bạn chọn: " + (userAns.isEmpty() ? "Bỏ trống" : userAns) + "  ·  Đáp án đúng là: " + q.correctOption());
                ansLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #dc2626;");
            }

            item.getChildren().addAll(qLbl, ansLbl);
            reviewList.getChildren().add(item);
        }

        ScrollPane scroll = new ScrollPane(reviewList);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(220);
        scroll.setStyle("-fx-background-color: transparent; -fx-border-color: #e2e8f0; -fx-border-radius: 8;");

        HBox btnRow = new HBox(10);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        Button closeBtn = new Button("Hoàn tất");
        closeBtn.getStyleClass().addAll("button", "button-primary");
        closeBtn.setOnAction(e -> {
            dialog.close();
            if (onDone != null) onDone.run();
        });

        btnRow.getChildren().add(closeBtn);
        root.getChildren().addAll(scoreCard, reviewTitle, scroll, btnRow);

        dialog.getDialogPane().setContent(root);
        ButtonType ct = new ButtonType("", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(ct);
        Button sysClose = (Button) dialog.getDialogPane().lookupButton(ct);
        if (sysClose != null) sysClose.setVisible(false);

        dialog.show();
    }

    private void showQuizScoresDialog(QuizRepository.Quiz quiz) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Bảng điểm: " + quiz.title());
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().getStylesheets().addAll(
            getClass().getResource("/tokens.css").toExternalForm(),
            getClass().getResource("/studyroom.css").toExternalForm()
        );
        dialog.getDialogPane().getStyleClass().add("custom-dialog");

        VBox root = new VBox(14);
        root.setPadding(new Insets(20, 24, 20, 24));
        root.setPrefWidth(580);
        root.setMaxWidth(580);

        Label titleLbl = new Label("🏆 Bảng điểm & Xếp hạng học sinh");
        titleLbl.getStyleClass().add("dialog-title");

        Label quizName = new Label("Bài kiểm tra: " + quiz.title());
        quizName.setStyle("-fx-font-size: 13px; -fx-text-fill: #6b7280;");

        List<QuizRepository.QuizSubmission> submissions = quizRepo.getSubmissionsForQuiz(quiz.id());

        // Stats Bar
        HBox statsBar = new HBox(16);
        statsBar.setAlignment(Pos.CENTER);
        statsBar.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 10; -fx-padding: 12 16; -fx-border-color: #e2e8f0; -fx-border-radius: 10;");

        double maxScore = 0;
        double sumScore = 0;
        for (var s : submissions) {
            if (s.score() > maxScore) maxScore = s.score();
            sumScore += s.score();
        }
        double avgScore = submissions.isEmpty() ? 0 : Math.round((sumScore / submissions.size()) * 10.0) / 10.0;

        Label statCount = new Label("👥 Đã nộp: " + submissions.size() + " người");
        statCount.setStyle("-fx-font-weight: 700; -fx-font-size: 12px; -fx-text-fill: #4338ca;");

        Label statMax = new Label(String.format("⭐ Cao nhất: %.1f điểm", maxScore));
        statMax.setStyle("-fx-font-weight: 700; -fx-font-size: 12px; -fx-text-fill: #059669;");

        Label statAvg = new Label(String.format("📊 Trung bình: %.1f điểm", avgScore));
        statAvg.setStyle("-fx-font-weight: 700; -fx-font-size: 12px; -fx-text-fill: #d97706;");

        statsBar.getChildren().addAll(statCount, statMax, statAvg);

        VBox list = new VBox(8);
        list.setPadding(new Insets(4, 0, 10, 0));

        if (submissions.isEmpty()) {
            VBox emptyBox = new VBox(8);
            emptyBox.setAlignment(Pos.CENTER);
            emptyBox.setPadding(new Insets(30, 0, 20, 0));
            Label emptyLbl = new Label("Chưa có thành viên nào nộp bài kiểm tra này.");
            emptyLbl.setStyle("-fx-text-fill: #9ca3af; -fx-font-size: 13px;");
            emptyBox.getChildren().add(emptyLbl);
            list.getChildren().add(emptyBox);
        } else {
            for (int i = 0; i < submissions.size(); i++) {
                QuizRepository.QuizSubmission s = submissions.get(i);
                HBox row = new HBox(12);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setStyle("-fx-background-color: white; -fx-background-radius: 10; -fx-padding: 10 14; -fx-border-color: #f1f5f9; -fx-border-radius: 10;");

                String rankBadge = switch (i) {
                    case 0 -> "🥇";
                    case 1 -> "🥈";
                    case 2 -> "🥉";
                    default -> "#" + (i + 1);
                };
                Label rankLbl = new Label(rankBadge);
                rankLbl.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-min-width: 30;");

                StackPane avt = userAvatar(s.username(), initials(s.displayName()), "avatar-person", 34);

                VBox userBox = new VBox(2);
                HBox.setHgrow(userBox, Priority.ALWAYS);
                Label nameLbl = new Label(s.displayName());
                nameLbl.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #111827;");
                Label subTimeLbl = new Label("@" + s.username() + " · Đúng " + s.correctCount() + "/" + s.totalQuestions() + " câu");
                subTimeLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #6b7280;");
                userBox.getChildren().addAll(nameLbl, subTimeLbl);

                String scoreColor = s.score() >= 8.0 ? "#059669; -fx-background-color: #ecfdf5;"
                                  : (s.score() >= 5.0 ? "#2563eb; -fx-background-color: #eff6ff;" : "#dc2626; -fx-background-color: #fef2f2;");
                Label scoreBadge = new Label(String.format("%.1f / 10", s.score()));
                scoreBadge.setStyle("-fx-font-size: 14px; -fx-font-weight: 800; -fx-text-fill: " + scoreColor + " -fx-padding: 4 12; -fx-background-radius: 12;");

                row.getChildren().addAll(rankLbl, avt, userBox, scoreBadge);
                list.getChildren().add(row);
            }
        }

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(280);
        scroll.setStyle("-fx-background-color: transparent; -fx-border-color: #e2e8f0; -fx-border-radius: 8;");

        HBox bottomBar = new HBox();
        bottomBar.setAlignment(Pos.CENTER_RIGHT);
        Button closeBtn = new Button("Đóng");
        closeBtn.getStyleClass().add("button");
        closeBtn.setOnAction(e -> dialog.close());
        bottomBar.getChildren().add(closeBtn);

        root.getChildren().addAll(titleLbl, quizName, statsBar, scroll, bottomBar);

        dialog.getDialogPane().setContent(root);
        ButtonType closeType = new ButtonType("", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeType);
        Button sysClose = (Button) dialog.getDialogPane().lookupButton(closeType);
        if (sysClose != null) sysClose.setVisible(false);

        dialog.show();
    }

    private void showAddScheduleDialog(String courseId, Runnable onAdded) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Lên lịch buổi học");
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().getStylesheets().addAll(
            getClass().getResource("/tokens.css").toExternalForm(),
            getClass().getResource("/studyroom.css").toExternalForm()
        );
        dialog.getDialogPane().getStyleClass().add("custom-dialog");

        VBox root = new VBox(14);
        root.setPadding(new Insets(16, 20, 16, 20));
        root.setMinWidth(400);

        Label title = new Label("Lên lịch buổi học trực tuyến");
        title.getStyleClass().add("dialog-title");

        TextField titleInput = new TextField();
        titleInput.setPromptText("Chủ đề buổi học (VD: Ôn tập Este đa chức)");
        titleInput.getStyleClass().add("input");

        TextField timeInput = new TextField();
        timeInput.setPromptText("Thời gian (VD: Thứ Bảy · 20:00 - 21:30)");
        timeInput.getStyleClass().add("input");

        TextField descInput = new TextField();
        descInput.setPromptText("Ghi chú nội dung cần chuẩn bị");
        descInput.getStyleClass().add("input");

        Button submit = new Button("Lên lịch");
        submit.getStyleClass().addAll("button", "button-primary");
        submit.setMaxWidth(Double.MAX_VALUE);

        submit.setOnAction(e -> {
            if (!titleInput.getText().isBlank()) {
                courseRepo.addSchedule(
                    courseId,
                    titleInput.getText().trim(),
                    timeInput.getText().isBlank() ? "Sắp xếp sau" : timeInput.getText().trim(),
                    descInput.getText().trim()
                );
                dialog.close();
                onAdded.run();
                toast("Đã thêm lịch học mới thành công!");
            }
        });

        root.getChildren().addAll(title, titleInput, timeInput, descInput, submit);
        dialog.getDialogPane().setContent(root);

        ButtonType closeType = new ButtonType("Đóng", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeType);
        Button closeBtn = (Button) dialog.getDialogPane().lookupButton(closeType);
        closeBtn.getStyleClass().add("button");

        dialog.show();
    }
    private void showMusic() {
        refreshSidebar();
        content.getChildren().clear();
        content.getStyleClass().setAll("workspace");
        content.setPadding(Insets.EMPTY);
        content.setSpacing(0);
        musicPlayer.setMusicPresenceRepository(musicPresenceRepo);
        musicPlayer.setCurrentUsername(user.username());
        MusicTrack curTrk = musicPlayer.getCurrentTrack();
        String curTitle = curTrk != null ? curTrk.title() : "";
        musicPresenceRepo.updatePresence(user.username(), user.displayName(), musicPlayer.getCurrentRoom().getId(), musicPlayer.getCurrentRoom().getName(), curTitle);
        MusicRoomView roomView = new MusicRoomView(musicPlayer, user, this::showInviteMusicFriendsDialog, () -> {
            musicPresenceRepo.clearPresence(user.username());
            showMusicLounge();
        }, this::toast, auth);
        content.getChildren().add(roomView);
    }

    private void showMusicLounge() {
        refreshSidebar();
        base("Sảnh phòng nghe nhạc · StudyTogether 🎧", "Không gian âm nhạc trực tuyến, cùng nghe & tập trung học tập");

        HBox topBar = new HBox(12);
        topBar.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button currentRoomBtn = new Button("🎵 Phòng đang nghe");
        currentRoomBtn.getStyleClass().addAll("button");
        currentRoomBtn.setOnAction(e -> showMusic());

        Button inviteBtn = new Button("👥 Mời bạn bè");
        inviteBtn.getStyleClass().addAll("button");
        inviteBtn.setOnAction(e -> showInviteMusicFriendsDialog());

        Button createBtn = new Button("＋ Tạo phòng nghe nhạc");
        createBtn.getStyleClass().addAll("button", "button-primary");
        createBtn.setOnAction(e -> showCreateMusicRoomDialog());

        topBar.getChildren().addAll(spacer, currentRoomBtn, inviteBtn, createBtn);
        content.getChildren().add(topBar);

        List<MusicPresenceRepository.FriendMusicPresence> friendsListening = musicPresenceRepo.getFriendsListening(user.username());
        if (!friendsListening.isEmpty()) {
            VBox friendsBox = new VBox(10);
            friendsBox.setPadding(new Insets(10, 0, 10, 0));
            Label fSectionTitle = new Label("👥 Bạn bè đang nghe nhạc");
            fSectionTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-text-fill: #111827;");
            FlowPane friendsGrid = new FlowPane(14, 14);
            for (MusicPresenceRepository.FriendMusicPresence fp : friendsListening) {
                HBox fCard = new HBox(12);
                fCard.setAlignment(Pos.CENTER_LEFT);
                fCard.setPadding(new Insets(12, 16, 12, 16));
                fCard.setStyle("-fx-background-color: #f5f3ff; -fx-border-color: #ddd6fe; -fx-border-radius: 16; -fx-background-radius: 16; -fx-pref-width: 330;");
                StackPane fAvt = userAvatar(fp.username(), initials(fp.displayName()), "avatar-person", 40);
                VBox fMeta = new VBox(3);
                Label fName = new Label(fp.displayName());
                fName.setStyle("-fx-font-weight: 800; -fx-font-size: 13px; -fx-text-fill: #1e1b4b;");
                Label fRoom = new Label("Đang ở phòng: " + fp.roomName());
                fRoom.setStyle("-fx-font-size: 11px; -fx-text-fill: #6366f1; -fx-font-weight: 600;");
                fMeta.getChildren().addAll(fName, fRoom);
                Region fSp = new Region();
                HBox.setHgrow(fSp, Priority.ALWAYS);
                Button joinFBtn = new Button("🎧 Tham gia");
                joinFBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 11px; -fx-background-radius: 12; -fx-padding: 6 12; -fx-cursor: hand;");
                joinFBtn.setOnAction(e -> {
                    musicPlayer.switchRoom(fp.roomId(), fp.roomName());
                    showMusic();
                });
                fCard.getChildren().addAll(fAvt, fMeta, fSp, joinFBtn);
                friendsGrid.getChildren().add(fCard);
            }
            friendsBox.getChildren().addAll(fSectionTitle, friendsGrid);
            content.getChildren().add(friendsBox);
        }

        FlowPane grid = new FlowPane(18, 18);
        grid.setPadding(new Insets(14, 0, 20, 0));

        List<MusicRoom> rooms = musicPlayer.getRooms();
        for (MusicRoom r : rooms) {
            VBox card = new VBox(12);
            card.getStyleClass().add("course-card");
            card.setStyle("-fx-background-color: white; -fx-background-radius: 18; -fx-padding: 18; -fx-border-color: #f1f5f9; -fx-border-radius: 18; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.04), 10, 0, 0, 2); -fx-pref-width: 330;");

            HBox cardHeader = new HBox(8);
            cardHeader.setAlignment(Pos.CENTER_LEFT);
            Label iconLbl = new Label("🎧");
            iconLbl.setStyle("-fx-font-size: 18px;");

            Label codeBadge = new Label(r.isUserCreated() ? "PHÒNG BẠN TẠO" : "STUDYTOGETHER");
            codeBadge.setStyle("-fx-background-color: #ede9fe; -fx-text-fill: #6366f1; -fx-font-weight: 800; -fx-font-size: 11px; -fx-padding: 3 8; -fx-background-radius: 6;");

            Region hgap = new Region();
            HBox.setHgrow(hgap, Priority.ALWAYS);

            int listenerCount = r.getListeners().size();
            Label membersLbl = new Label("📶 " + listenerCount + " đang nghe");
            membersLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #f43f5e; -fx-font-weight: bold;");
            cardHeader.getChildren().addAll(iconLbl, codeBadge, hgap, membersLbl);

            Label titleLbl = new Label(r.getName());
            titleLbl.setStyle("-fx-font-size: 17px; -fx-font-weight: 800; -fx-text-fill: -ink;");
            titleLbl.setWrapText(true);

            Label descLbl = new Label(r.getDescription() != null && !r.getDescription().isBlank() ? r.getDescription() : "Cùng nghe nhạc & tập trung học tập");
            descLbl.getStyleClass().add("muted");
            descLbl.setWrapText(true);
            descLbl.setMaxHeight(40);

            HBox trackPreview = new HBox(6);
            trackPreview.setAlignment(Pos.CENTER_LEFT);
            Label musicNote = new Label("🎵");
            String songTitle = !r.getPlaylist().isEmpty() ? r.getPlaylist().get(0).title() : "Chưa có bài hát trong playlist";
            Label songLbl = new Label(songTitle);
            songLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #4b5563; -fx-font-weight: 600;");
            trackPreview.getChildren().addAll(musicNote, songLbl);

            HBox meta = new HBox(12);
            meta.setAlignment(Pos.CENTER_LEFT);
            Label hostLbl = new Label("Chủ phòng: @" + r.getOwnerDisplayName());
            hostLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: -muted;");
            HBox.setHgrow(hostLbl, Priority.ALWAYS);

            Label tracksCountLbl = new Label(r.getPlaylist().size() + " bài");
            tracksCountLbl.setStyle("-fx-font-size: 11px; -fx-background-color: #f3f4f6; -fx-padding: 2 6; -fx-background-radius: 4; -fx-text-fill: #4b5563;");
            meta.getChildren().addAll(hostLbl, tracksCountLbl);

            boolean isCurrent = musicPlayer.getCurrentRoom() != null && musicPlayer.getCurrentRoom().getId().equals(r.getId());

            Button enterBtn = new Button(isCurrent ? "Đang ở phòng này · Quay lại 🎧" : "Vào phòng nghe ngay 🎧");
            enterBtn.getStyleClass().addAll("button", "button-primary");
            enterBtn.setStyle(isCurrent
                ? "-fx-background-color: linear-gradient(to right, #4f46e5, #7c3aed); -fx-text-fill: white; -fx-font-weight: 800; -fx-background-radius: 12; -fx-padding: 8 16; -fx-cursor: hand;"
                : "-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 700; -fx-background-radius: 12; -fx-padding: 8 16; -fx-cursor: hand;");
            enterBtn.setMaxWidth(Double.MAX_VALUE);
            enterBtn.setOnAction(e -> {
                musicPlayer.switchRoom(r.getId());
                showMusic();
            });

            card.getChildren().addAll(cardHeader, titleLbl, descLbl, trackPreview, meta, enterBtn);
            grid.getChildren().add(card);
        }

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("thread-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        content.getChildren().add(scroll);
    }

    private void showCreateMusicRoomDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Tạo phòng nghe nhạc mới");
        dialog.setHeaderText("Thiết lập phòng nghe nhạc của bạn");
        if (scene != null && scene.getWindow() != null) dialog.initOwner(scene.getWindow());

        VBox form = new VBox(12);
        form.setPadding(new Insets(16));
        form.setPrefWidth(380);

        TextField nameF = new TextField();
        nameF.setPromptText("Tên phòng (ví dụ: Chill cùng tôi, Lofi làm bài...)");

        TextField descF = new TextField();
        descF.setPromptText("Mô tả ngắn");

        form.getChildren().addAll(
            new Label("Tên phòng:"), nameF,
            new Label("Mô tả:"), descF
        );

        dialog.getDialogPane().setContent(form);
        ButtonType createType = new ButtonType("Tạo phòng", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

        dialog.setResultConverter(btn -> {
            if (btn == createType && !nameF.getText().isBlank()) {
                String rName = nameF.getText().trim();
                String desc = descF.getText().isBlank() ? "Cùng nghe nhạc & học tập" : descF.getText().trim();
                musicPlayer.createUserRoom(rName, desc, "linear-gradient(to bottom, #1e1b4b 0%, #0f172a 60%, #121212 100%)", "#7c5cff", "", user.username(), user.displayName());
                showMusic();
            }
            return null;
        });

        dialog.show();
    }

    private void showInviteMusicFriendsDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Mời bạn bè cùng nghe nhạc");
        dialog.setHeaderText("Chọn bạn bè để cùng nghe nhạc tại " + musicPlayer.getCurrentRoom().getName());
        if (scene != null && scene.getWindow() != null) dialog.initOwner(scene.getWindow());

        VBox root = new VBox(10);
        root.setPadding(new Insets(16));
        root.setPrefWidth(360);

        List<User> friends = friendRepo.friends(user.username());
        if (friends.isEmpty()) {
            Label noF = new Label("Bạn chưa có bạn bè nào để mời. Hãy kết bạn trước nhé!");
            noF.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 13px;");
            root.getChildren().add(noF);
        } else {
            for (User f : friends) {
                HBox row = new HBox(10);
                row.setAlignment(Pos.CENTER_LEFT);
                StackPane avt = userAvatar(f.username(), initials(f.displayName()), "avatar-person", 36);
                VBox info = new VBox(2);
                Label nameLbl = new Label(f.displayName());
                nameLbl.setStyle("-fx-font-weight: 700; -fx-font-size: 13px; -fx-text-fill: #111827;");
                Label uLbl = new Label("@" + f.username());
                uLbl.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 11px;");
                info.getChildren().addAll(nameLbl, uLbl);
                HBox.setHgrow(info, Priority.ALWAYS);

                Button invite = new Button("Mời vào phòng");
                invite.setStyle("-fx-background-color: #1db954; -fx-text-fill: black; -fx-font-weight: 800; -fx-font-size: 12px; -fx-background-radius: 16; -fx-padding: 6 14; -fx-cursor: hand;");
                invite.setOnAction(e -> {
                    invite.setDisable(true);
                    invite.setText("Đã mời ✓");
                    invite.setStyle("-fx-background-color: #282828; -fx-text-fill: #b3b3b3; -fx-font-size: 12px; -fx-background-radius: 16; -fx-padding: 6 14;");
                    musicPresenceRepo.sendInvite(user.username(), user.displayName(), f.username(), musicPlayer.getCurrentRoom().getId(), musicPlayer.getCurrentRoom().getName());
                    String dRoom = directRoomId(user.username(), f.username());
                    String invitePayload = "[MUSIC_INVITE:" + musicPlayer.getCurrentRoom().getId() + ":" + musicPlayer.getCurrentRoom().getName() + "]";
                    addMessage(dRoom, user.displayName(), invitePayload, true, true);
                    if (node != null) {
                        node.broadcast(user.displayName(), invitePayload);
                        node.broadcast(user.displayName(), "MUSIC_SYNC|" + musicPlayer.getCurrentRoom().getId() + "|INVITE|" + f.username() + "|" + user.displayName());
                    }
                    toast("Đã gửi lời mời nghe nhạc tới " + f.displayName());
                });

                row.getChildren().addAll(avt, info, invite);
                root.getChildren().add(row);
            }
        }

        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.show();
    }
    private void refreshSidebar() {
        shell.setLeft(sidebar());
    }

    public static class StudyAppBridge {
        private final java.util.function.Consumer<String> toastFn;
        public StudyAppBridge(java.util.function.Consumer<String> toastFn) { this.toastFn = toastFn; }
        public void copy(String text) {
            Platform.runLater(() -> {
                Clipboard clipboard = Clipboard.getSystemClipboard();
                ClipboardContent cc = new ClipboardContent();
                cc.putString(text);
                clipboard.setContent(cc);
                if (toastFn != null) toastFn.accept("Đã sao chép nội dung vào bộ nhớ tạm!");
            });
        }
    }

    private static String escapeJsForWeb(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private void showAiAssistant() {
        refreshSidebar();
        content.getChildren().clear();
        content.getStyleClass().setAll("workspace");
        content.setPadding(new Insets(18, 26, 16, 26));

        // Top bar
        HBox topBar = new HBox(12);
        topBar.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("🤖 Trợ lý Học tập AI · StudyBot");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: 800; -fx-text-fill: -ink;");

        Label badge = new Label("Gemini 3.5 Flash · KaTeX Math");
        badge.setStyle("-fx-font-size: 11px; -fx-font-weight: 700; -fx-background-color: #e0e7ff; -fx-text-fill: #4338ca; -fx-padding: 3 10; -fx-background-radius: 12;");

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        Button apiKeyBtn = new Button("⚙ API Key");
        apiKeyBtn.getStyleClass().add("button");
        apiKeyBtn.setStyle("-fx-font-size: 12px; -fx-padding: 6 12;");
        apiKeyBtn.setOnAction(e -> showAiApiKeyDialog());

        Button clearBtn = new Button("🗑 Xóa lịch sử chat");
        clearBtn.getStyleClass().add("button");
        clearBtn.setStyle("-fx-font-size: 12px; -fx-padding: 6 12; -fx-text-fill: #ef4444;");

        topBar.getChildren().addAll(title, badge, gap, apiKeyBtn, clearBtn);

        Label subtitle = new Label("Sẵn sàng giải bài tập, giải thích công thức, chữa đề thi & đồng hành học tập cùng bạn 24/7");
        subtitle.getStyleClass().add("muted");
        subtitle.setStyle("-fx-font-size: 13px;");

        // Quick Prompt Suggestions Chips
        HBox promptChips = new HBox(8);
        promptChips.setAlignment(Pos.CENTER_LEFT);
        promptChips.setPadding(new Insets(4, 0, 4, 0));

        String[] samplePrompts = {
            "📐 Giải bài tập: Tính khối lượng este thu được khi đun 6g CH3COOH với 4.6g C2H5OH",
            "📝 Tóm tắt lý thuyết trọng tâm chương Este - Lipit Hóa 12",
            "🇬🇧 Sửa lỗi ngữ pháp và viết đoạn văn Tiếng Anh về sở thích",
            "🎯 Cho tôi 3 câu hỏi trắc nghiệm Toán giải tích ôn thi THPT"
        };

        // WebView for interactive chat & KaTeX rendering
        WebView chatWebView = new WebView();
        VBox.setVgrow(chatWebView, Priority.ALWAYS);
        chatWebView.setMinHeight(200);
        chatWebView.setStyle("-fx-border-color: #e2e8f0; -fx-border-radius: 14; -fx-background-radius: 14;");

        WebEngine engine = chatWebView.getEngine();
        boolean[] pageLoaded = { false };
        List<Runnable> pendingScripts = new ArrayList<>();

        java.util.function.Consumer<String> runJs = script -> {
            Platform.runLater(() -> {
                if (pageLoaded[0]) {
                    try {
                        engine.executeScript(script);
                    } catch (Exception ex) {
                        System.err.println("AI Chat JS error: " + ex.getMessage());
                    }
                } else {
                    pendingScripts.add(() -> {
                        try {
                            engine.executeScript(script);
                        } catch (Exception ex) {
                            System.err.println("AI Chat JS error: " + ex.getMessage());
                        }
                    });
                }
            });
        };

        java.util.function.Consumer<String> appendUserBubble = textMsg -> {
            String js = "addUserMessage(" + escapeJsForWeb(textMsg) + ", " + escapeJsForWeb(initials(user.displayName())) + ");";
            runJs.accept(js);
        };

        java.util.function.Consumer<String> appendBotBubble = replyMsg -> {
            String js = "addBotMessage(" + escapeJsForWeb(replyMsg) + ");";
            runJs.accept(js);
        };

        java.util.function.Consumer<Boolean> setLoading = loading -> {
            String js = "setLoading(" + loading + ");";
            runJs.accept(js);
        };

        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                pageLoaded[0] = true;
                try {
                    JSObject win = (JSObject) engine.executeScript("window");
                    win.setMember("studyApp", new StudyAppBridge(this::toast));
                } catch (Exception ignored) {}

                // Load existing history
                List<GeminiAiService.ChatMessage> history = aiService.getHistory(user.username());
                if (history.isEmpty()) {
                    appendBotBubble.accept("👋 Chào " + user.displayName() + "! Mình là StudyBot - Trợ lý AI học tập thông minh trong Studyroom.\n\nMình có thể hỗ trợ bạn:\n✔ Hướng dẫn giải chi tiết bài tập Toán, Lý, Hóa, Sinh, Anh, Văn...\n✔ Hiển thị công thức Toán học, Vật lý, Hóa học chuẩn xác, đẹp mắt\n✔ Giải thích các định lý, công thức, khái niệm khó\n✔ Tóm tắt kiến thức bài học và tạo câu hỏi tự kiểm tra\n\nBạn đang muốn giải bài tập nào hay cần hỗ trợ gì? Hãy gõ câu hỏi bên dưới nhé!");
                } else {
                    for (var m : history) {
                        if ("user".equalsIgnoreCase(m.role())) {
                            appendUserBubble.accept(m.content());
                        } else {
                            appendBotBubble.accept(m.content());
                        }
                    }
                }

                for (Runnable r : pendingScripts) {
                    r.run();
                }
                pendingScripts.clear();
                runJs.accept("scrollToBottom(true);");
            }
        });

        // Load html template
        var chatHtmlUrl = getClass().getResource("/katex/ai_chat.html");
        if (chatHtmlUrl != null) {
            engine.load(chatHtmlUrl.toExternalForm());
        }

        // Input Area
        VBox inputContainer = new VBox(8);
        inputContainer.setMinHeight(Region.USE_PREF_SIZE);
        inputContainer.setStyle("-fx-background-color: white; -fx-background-radius: 16; -fx-padding: 12 16; -fx-border-color: #e2e8f0; -fx-border-radius: 16; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 6, 0, 0, 1);");

        TextArea promptInput = new TextArea();
        promptInput.setPromptText("Nhập câu hỏi, đề bài tập hoặc chủ đề cần giải thích... (Enter để gửi, Shift+Enter xuống dòng)");
        promptInput.setPrefRowCount(2);
        promptInput.setMaxHeight(90);
        promptInput.setWrapText(true);
        promptInput.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-font-size: 13.5px; -fx-text-fill: #111827;");

        HBox inputBottomRow = new HBox(10);
        inputBottomRow.setAlignment(Pos.CENTER_RIGHT);

        Label hintLbl = new Label("💡 Nhấn Enter để gửi");
        hintLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #9ca3af;");
        Region inputGap = new Region();
        HBox.setHgrow(inputGap, Priority.ALWAYS);

        Button sendBtn = new Button("➤ Gửi câu hỏi");
        sendBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 13px; -fx-background-radius: 20; -fx-padding: 7 20; -fx-cursor: hand;");

        inputBottomRow.getChildren().addAll(hintLbl, inputGap, sendBtn);
        inputContainer.getChildren().addAll(promptInput, inputBottomRow);

        // Send action
        Runnable doSend = () -> {
            try {
                String textMsg = promptInput.getText().trim();
                if (textMsg.isBlank()) return;

                promptInput.clear();
                appendUserBubble.accept(textMsg);

                setLoading.accept(true);
                sendBtn.setDisable(true);
                promptInput.setDisable(true);

                aiService.askGeminiAsync(user.username(), textMsg, reply -> {
                    setLoading.accept(false);
                    sendBtn.setDisable(false);
                    promptInput.setDisable(false);
                    appendBotBubble.accept(reply);
                    promptInput.requestFocus();
                }, err -> {
                    setLoading.accept(false);
                    sendBtn.setDisable(false);
                    promptInput.setDisable(false);
                    appendBotBubble.accept("⚠️ " + err);
                    promptInput.requestFocus();
                });
            } catch (Exception ex) {
                setLoading.accept(false);
                sendBtn.setDisable(false);
                promptInput.setDisable(false);
                toast("Không thể gửi tin nhắn: " + ex.getMessage());
            }
        };

        sendBtn.setOnAction(e -> doSend.run());

        promptInput.setOnKeyPressed(ke -> {
            if (ke.getCode() == javafx.scene.input.KeyCode.ENTER) {
                if (!ke.isShiftDown()) {
                    ke.consume();
                    doSend.run();
                }
            }
        });

        // Quick prompt buttons
        for (String sp : samplePrompts) {
            String shortText = sp.length() > 32 ? sp.substring(0, 32) + "..." : sp;
            Button chip = new Button(shortText);
            chip.setStyle("-fx-background-color: #f8fafc; -fx-text-fill: #475569; -fx-font-size: 11.5px; -fx-font-weight: 600; -fx-background-radius: 14; -fx-border-color: #cbd5e1; -fx-border-radius: 14; -fx-padding: 4 10; -fx-cursor: hand;");
            chip.setTooltip(new Tooltip(sp));
            chip.setOnAction(e -> {
                promptInput.setText(sp);
                promptInput.requestFocus();
                promptInput.positionCaret(sp.length());
            });
            promptChips.getChildren().add(chip);
        }

        clearBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Xóa lịch sử trò chuyện");
            confirm.setHeaderText("Xóa toàn bộ lịch sử với StudyBot?");
            confirm.setContentText("Các câu hỏi và câu trả lời trước đó sẽ bị xóa.");
            confirm.showAndWait().ifPresent(res -> {
                if (res == ButtonType.OK) {
                    aiService.clearHistory(user.username());
                    runJs.accept("clearMessages();");
                    appendBotBubble.accept("Đã xóa lịch sử trò chuyện.");
                    toast("Đã xóa lịch sử trò chuyện AI.");
                }
            });
        });

        content.getChildren().addAll(topBar, subtitle, promptChips, chatWebView, inputContainer);
    }

    private void showAiApiKeyDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Cài đặt API Key Gemini");
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().getStylesheets().addAll(
            getClass().getResource("/tokens.css").toExternalForm(),
            getClass().getResource("/studyroom.css").toExternalForm()
        );
        dialog.getDialogPane().getStyleClass().add("custom-dialog");

        VBox root = new VBox(12);
        root.setPadding(new Insets(20, 24, 20, 24));
        root.setPrefWidth(480);

        Label title = new Label("Cài đặt Google Gemini API Key");
        title.getStyleClass().add("dialog-title");

        Label desc = new Label("Khóa API dùng để kết nối với Google Gemini nhằm giải bài tập và trả lời thắc mắc.");
        desc.setStyle("-fx-font-size: 12px; -fx-text-fill: #6b7280;");
        desc.setWrapText(true);

        TextField keyField = new TextField(aiService.getApiKey());
        keyField.getStyleClass().add("input");

        HBox btnRow = new HBox(10);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        Button resetBtn = new Button("Mặc định");
        resetBtn.getStyleClass().add("button");
        resetBtn.setOnAction(e -> keyField.setText(GeminiAiService.DEFAULT_API_KEY));

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        Button cancelBtn = new Button("Đóng");
        cancelBtn.getStyleClass().add("button");
        cancelBtn.setOnAction(e -> dialog.close());

        Button saveBtn = new Button("Lưu khóa API");
        saveBtn.getStyleClass().addAll("button", "button-primary");
        saveBtn.setOnAction(e -> {
            String k = keyField.getText().trim();
            if (k.isBlank()) {
                toast("Khóa API không được để trống.");
                return;
            }
            aiService.setApiKey(k);
            toast("Đã lưu khóa API Gemini thành công!");
            dialog.close();
        });

        btnRow.getChildren().addAll(resetBtn, gap, cancelBtn, saveBtn);
        root.getChildren().addAll(title, desc, keyField, btnRow);

        dialog.getDialogPane().setContent(root);
        ButtonType ct = new ButtonType("", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(ct);
        Button sysClose = (Button) dialog.getDialogPane().lookupButton(ct);
        if (sysClose != null) sysClose.setVisible(false);

        dialog.show();
    }


    private void showProfile() {
        base("Hồ sơ cá nhân", "Quản lý thông tin và hình ảnh đại diện của bạn");
        refreshSidebar();
        VBox card = new VBox(16);
        card.setAlignment(Pos.CENTER);
        card.setStyle("-fx-background-color: white; -fx-background-radius: 16; -fx-padding: 32 40; -fx-border-color: #e5e7eb; -fx-border-radius: 16; -fx-max-width: 480; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 8, 0, 0, 2);");

        StackPane bigAvatar = userAvatar(user.username(), initials(user.displayName()), "avatar-person", 84);
        bigAvatar.setCursor(javafx.scene.Cursor.HAND);
        Tooltip.install(bigAvatar, new Tooltip("Nhấp để đổi ảnh đại diện"));
        bigAvatar.setOnMouseClicked(e -> showChangeUserAvatarDialog());

        Label nameLbl = new Label(user.displayName());
        nameLbl.setStyle("-fx-font-size: 20px; -fx-font-weight: 800; -fx-text-fill: #111827;");

        Label handleLbl = new Label("@" + user.username());
        handleLbl.setStyle("-fx-font-size: 14px; -fx-text-fill: #6b7280;");

        Button changeAvatarBtn = new Button("📷  Đổi ảnh đại diện");
        changeAvatarBtn.setStyle("-fx-background-color: #6366f1; -fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 13px; -fx-background-radius: 20; -fx-padding: 8 20; -fx-cursor: hand;");
        changeAvatarBtn.setOnAction(e -> showChangeUserAvatarDialog());

        Label statusLbl = new Label("🟢  Trạng thái: Đang hoạt động");
        statusLbl.setStyle("-fx-font-size: 13px; -fx-text-fill: #10b981; -fx-font-weight: 600;");

        HBox accountActions = new HBox(12);
        accountActions.setAlignment(Pos.CENTER);

        Button switchAccProfileBtn = new Button("🔄  Đổi tài khoản");
        switchAccProfileBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 13px; -fx-background-radius: 20; -fx-padding: 8 20; -fx-cursor: hand;");
        switchAccProfileBtn.setOnAction(e -> showSwitchAccountDialog());

        Button logoutProfileBtn = new Button("🚪  Đăng xuất");
        logoutProfileBtn.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #dc2626; -fx-font-weight: 700; -fx-font-size: 13px; -fx-background-radius: 20; -fx-padding: 8 20; -fx-cursor: hand; -fx-border-color: #fca5a5; -fx-border-radius: 20;");
        logoutProfileBtn.setOnAction(e -> confirmAndLogout());

        accountActions.getChildren().addAll(switchAccProfileBtn, logoutProfileBtn);

        card.getChildren().addAll(bigAvatar, nameLbl, handleLbl, changeAvatarBtn, new Separator(), statusLbl, new Separator(), accountActions);
        content.getChildren().add(card);
    }

    private void confirmAndLogout() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Đăng xuất");
        alert.setHeaderText("Xác nhận đăng xuất khỏi Studyroom");
        alert.setContentText("Bạn có chắc chắn muốn đăng xuất khỏi tài khoản @" + (user != null ? user.username() : "") + " không?");
        if (scene != null && scene.getWindow() != null) alert.initOwner(scene.getWindow());

        alert.showAndWait().ifPresent(res -> {
            if (res == ButtonType.OK) {
                performLogout();
            }
        });
    }

    private void performLogoutNoRedirect() {
        if (user != null) {
            UserDataServer.getInstance().recordLogout(user.username());
            musicPresenceRepo.clearPresence(user.username());
        }
        musicPlayer.stop();
        classroomVoice.stop();
        webcamStream.stop();
        ScreenShareEngine.getInstance().stop();
        if (node != null) {
            try {
                node.close();
            } catch (Exception ignored) {}
            node = null;
        }
        if (classroomSyncTimer != null) {
            classroomSyncTimer.cancel();
            classroomSyncTimer = null;
        }
        if (messageSyncTimer != null) {
            messageSyncTimer.cancel();
            messageSyncTimer = null;
        }
        isCameraOn = false;
        isMicOn = false;
        isLocalSpeaking = false;
        selectedRoomId = null;
        currentDirectUsername = null;
        lastLoadedMessageId = 0;
        content.getChildren().clear();
        shell.setLeft(null);
        shell.setCenter(null);
    }

    private void performLogout() {
        String loggedOutUsername = user != null ? user.username() : null;
        performLogoutNoRedirect();
        user = null;
        scene.setRoot(loginView(loggedOutUsername));
    }

    private void showSwitchAccountDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Đổi tài khoản");
        dialog.setHeaderText("Chọn tài khoản để đổi hoặc đăng nhập tài khoản khác");
        if (scene != null && scene.getWindow() != null) dialog.initOwner(scene.getWindow());

        VBox root = new VBox(12);
        root.setPrefWidth(440);
        root.setStyle("-fx-padding: 16;");

        List<UserDataServer.UserProfile> users = UserDataServer.getInstance().getAllUsers();
        if (users.isEmpty()) {
            UserDataServer.getInstance().syncFromDatabase(database);
            users = UserDataServer.getInstance().getAllUsers();
        }

        VBox listContainer = new VBox(8);
        for (var uProfile : users) {
            boolean isCurrent = user != null && user.username().equalsIgnoreCase(uProfile.username());
            HBox item = new HBox(12);
            item.setAlignment(Pos.CENTER_LEFT);
            item.setStyle("-fx-padding: 10 14; -fx-background-color: " + (isCurrent ? "#f0fdf4" : "#ffffff") + "; -fx-border-color: " + (isCurrent ? "#86efac" : "#e2e8f0") + "; -fx-border-radius: 12; -fx-background-radius: 12; -fx-cursor: hand;");

            StackPane avt = userAvatar(uProfile.username(), initials(uProfile.displayName()), "avatar-person", 40);
            VBox info = new VBox(2);
            HBox nameRow = new HBox(6);
            nameRow.setAlignment(Pos.CENTER_LEFT);
            Label nameLbl = new Label(uProfile.displayName());
            nameLbl.setStyle("-fx-font-weight: 700; -fx-font-size: 13.5px; -fx-text-fill: #1e293b;");
            nameRow.getChildren().add(nameLbl);

            if (isCurrent) {
                Label currBadge = new Label("Đang dùng");
                currBadge.setStyle("-fx-background-color: #dcfce7; -fx-text-fill: #15803d; -fx-font-size: 10.5px; -fx-font-weight: 700; -fx-padding: 2 6; -fx-background-radius: 8;");
                nameRow.getChildren().add(currBadge);
            }

            Label userLbl = new Label("@" + uProfile.username());
            userLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");
            info.getChildren().addAll(nameRow, userLbl);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button actionBtn = new Button(isCurrent ? "Đang chọn" : "Đăng nhập");
            actionBtn.setDisable(isCurrent);
            if (isCurrent) {
                actionBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #15803d; -fx-font-weight: 700; -fx-font-size: 12px;");
            } else {
                actionBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 12px; -fx-background-radius: 16; -fx-padding: 5 14; -fx-cursor: hand;");
            }

            actionBtn.setOnAction(e -> {
                dialog.close();
                switchToAccount(uProfile);
            });
            item.setOnMouseClicked(e -> {
                if (!isCurrent) {
                    dialog.close();
                    switchToAccount(uProfile);
                }
            });

            item.getChildren().addAll(avt, info, spacer, actionBtn);
            listContainer.getChildren().add(item);
        }

        ScrollPane scroll = new ScrollPane(listContainer);
        scroll.setFitToWidth(true);
        scroll.setMaxHeight(280);
        scroll.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");

        Separator sep = new Separator();

        Button newAccountBtn = new Button("➕ Đăng nhập tài khoản khác / Đăng ký mới");
        newAccountBtn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #334155; -fx-font-weight: 700; -fx-font-size: 13px; -fx-background-radius: 10; -fx-padding: 9 16; -fx-cursor: hand;");
        newAccountBtn.setMaxWidth(Double.MAX_VALUE);
        newAccountBtn.setOnAction(e -> {
            dialog.close();
            performLogout();
        });

        root.getChildren().addAll(scroll, sep, newAccountBtn);
        dialog.getDialogPane().setContent(root);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private void switchToAccount(UserDataServer.UserProfile profile) {
        Dialog<String> passDialog = new Dialog<>();
        passDialog.setTitle("Xác thực đổi tài khoản");
        passDialog.setHeaderText("Đăng nhập vào tài khoản: @" + profile.username() + " (" + profile.displayName() + ")");
        if (scene != null && scene.getWindow() != null) passDialog.initOwner(scene.getWindow());

        VBox content = new VBox(10);
        content.setStyle("-fx-padding: 16;");
        Label lbl = new Label("Nhập mật khẩu cho @" + profile.username() + ":");
        lbl.setStyle("-fx-font-size: 13px; -fx-font-weight: 600; -fx-text-fill: #1e293b;");
        PasswordField passField = new PasswordField();
        passField.setPromptText("Mật khẩu");
        passField.setStyle("-fx-padding: 8 12; -fx-font-size: 13px;");
        if (profile.password() != null && !profile.password().isBlank() && !profile.password().equals("******")) {
            passField.setText(profile.password());
        }

        content.getChildren().addAll(lbl, passField);
        passDialog.getDialogPane().setContent(content);

        ButtonType loginType = new ButtonType("Đăng nhập", ButtonBar.ButtonData.OK_DONE);
        passDialog.getDialogPane().getButtonTypes().addAll(loginType, ButtonType.CANCEL);

        passDialog.setResultConverter(btn -> {
            if (btn == loginType) return passField.getText();
            return null;
        });

        Optional<String> res = passDialog.showAndWait();
        if (res.isPresent()) {
            String enteredPass = res.get();
            try {
                User newUser = auth.login(profile.username(), enteredPass);
                performLogoutNoRedirect();
                this.user = newUser;
                openWorkspace();
            } catch (Exception ex) {
                showErrorAlert("Đổi tài khoản thất bại", "Mật khẩu không chính xác: " + ex.getMessage());
            }
        }
    }

    private void showErrorAlert(String titleText, String contentText) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(titleText);
        alert.setHeaderText(null);
        alert.setContentText(contentText);
        javafx.stage.Window win = scene != null ? scene.getWindow() : null;
        if (win != null) alert.initOwner(win);
        alert.showAndWait();
    }

    private BufferedImage fxImageToBufferedImage(Image fxImg) {
        if (fxImg == null || fxImg.isError()) return null;
        int width = (int) fxImg.getWidth();
        int height = (int) fxImg.getHeight();
        if (width <= 0 || height <= 0) return null;

        BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader pr = fxImg.getPixelReader();
        if (pr == null) return null;

        int[] buffer = new int[width * height];
        pr.getPixels(0, 0, width, height, javafx.scene.image.PixelFormat.getIntArgbInstance(), buffer, 0, width);
        bi.setRGB(0, 0, width, height, buffer, 0, width);
        return bi;
    }

    private void showChangeUserAvatarDialog() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn ảnh đại diện cá nhân mới");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Hình ảnh (*.png, *.jpg, *.jpeg, *.webp)", "*.png", "*.jpg", "*.jpeg", "*.webp", "*.bmp"));
        javafx.stage.Window win = scene != null ? scene.getWindow() : null;
        File file = chooser.showOpenDialog(win);
        if (file != null) {
            if (file.length() > 20 * 1024 * 1024) {
                showErrorAlert("Tệp quá lớn", "Dung lượng ảnh lớn hơn 20MB. Vui lòng chọn tệp nhỏ hơn.");
                return;
            }
            showAvatarCropDialog(file, "Căn chỉnh ảnh đại diện cá nhân", croppedData -> {
                try {
                    auth.saveUserAvatar(user.username(), croppedData);
                    refreshSidebar();
                    showProfile();
                } catch (Exception ex) {
                    showErrorAlert("Lỗi cập nhật ảnh", "Không thể lưu ảnh đại diện: " + ex.getMessage());
                }
            });
        }
    }

    private void showChangeGroupAvatarDialog(String roomId, String roomName) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn ảnh đại diện nhóm · " + roomName);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Hình ảnh (*.png, *.jpg, *.jpeg, *.webp)", "*.png", "*.jpg", "*.jpeg", "*.webp", "*.bmp"));
        javafx.stage.Window win = scene != null ? scene.getWindow() : null;
        File file = chooser.showOpenDialog(win);
        if (file != null) {
            if (file.length() > 20 * 1024 * 1024) {
                showErrorAlert("Tệp quá lớn", "Dung lượng ảnh lớn hơn 20MB. Vui lòng chọn tệp nhỏ hơn.");
                return;
            }
            showAvatarCropDialog(file, "Căn chỉnh ảnh đại diện nhóm · " + roomName, croppedData -> {
                try {
                    chatRepository.saveGroupAvatar(roomId, croppedData);
                    showChat(roomId, roomName, true);
                } catch (Exception ex) {
                    showErrorAlert("Lỗi cập nhật ảnh", "Không thể lưu ảnh nhóm: " + ex.getMessage());
                }
            });
        }
    }

    private void showAvatarCropDialog(File file, String titleText, java.util.function.Consumer<byte[]> onSave) {
        BufferedImage srcImage = null;
        try {
            srcImage = ImageIO.read(file);
        } catch (Exception ignored) { }

        Image fxImage = null;
        try {
            fxImage = new Image(file.toURI().toString());
        } catch (Exception ignored) { }

        if (srcImage == null && fxImage != null && !fxImage.isError() && fxImage.getWidth() > 0) {
            srcImage = fxImageToBufferedImage(fxImage);
        }

        if (srcImage == null) {
            showErrorAlert("Lỗi mở ảnh", "Không thể đọc định dạng ảnh được chọn. Vui lòng chọn tệp ảnh PNG, JPG hoặc WEBP khác.");
            return;
        }

        if (fxImage == null || fxImage.isError() || fxImage.getWidth() <= 0) {
            fxImage = new Image(file.toURI().toString());
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(titleText);
        dialog.setHeaderText(null);

        javafx.stage.Window win = scene != null ? scene.getWindow() : null;
        if (win != null) dialog.initOwner(win);

        VBox root = new VBox(14);
        root.setPadding(new Insets(20, 24, 16, 24));
        root.setAlignment(Pos.CENTER);
        root.setPrefWidth(420);

        Label title = new Label(titleText);
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: 800; -fx-text-fill: #111827;");

        Label hint = new Label("Thu phóng và kéo căn chỉnh vị trí hiển thị ảnh đại diện.");
        hint.setStyle("-fx-font-size: 12px; -fx-text-fill: #6b7280; -fx-text-alignment: center;");
        hint.setWrapText(true);

        double previewSize = 150;
        ImageView previewIv = new ImageView(fxImage);
        previewIv.setSmooth(true);
        previewIv.setPreserveRatio(true);

        double w = fxImage.getWidth();
        double h = fxImage.getHeight();
        double baseScale = Math.max(previewSize / Math.max(1.0, w), previewSize / Math.max(1.0, h));
        previewIv.setFitWidth(w * baseScale);
        previewIv.setFitHeight(h * baseScale);

        javafx.scene.shape.Circle clipCircle = new javafx.scene.shape.Circle(previewSize / 2, previewSize / 2, previewSize / 2);
        StackPane previewContainer = new StackPane(previewIv);
        previewContainer.setMinSize(previewSize, previewSize);
        previewContainer.setMaxSize(previewSize, previewSize);
        previewContainer.setPrefSize(previewSize, previewSize);
        previewContainer.setAlignment(Pos.CENTER);
        previewContainer.setClip(clipCircle);

        javafx.scene.shape.Circle borderRing = new javafx.scene.shape.Circle(previewSize / 2);
        borderRing.setFill(null);
        borderRing.setStroke(javafx.scene.paint.Color.web("#6366f1"));
        borderRing.setStrokeWidth(3.0);

        StackPane avatarPreviewFrame = new StackPane(previewContainer, borderRing);
        avatarPreviewFrame.setAlignment(Pos.CENTER);
        avatarPreviewFrame.setStyle("-fx-effect: dropshadow(gaussian, rgba(99, 102, 241, 0.35), 16, 0, 0, 4);");

        Label zoomLabel = new Label("🔍  Thu phóng");
        zoomLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #374151;");
        Slider zoomSlider = new Slider(1.0, 3.0, 1.0);

        Label posXLabel = new Label("↔  Vị trí ngang (Trái / Phải)");
        posXLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: 600; -fx-text-fill: #4b5563;");
        Slider posXSlider = new Slider(-100, 100, 0);

        Label posYLabel = new Label("↕  Vị trí dọc (Trên / Dưới)");
        posYLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: 600; -fx-text-fill: #4b5563;");
        Slider posYSlider = new Slider(-100, 100, 0);

        Runnable updatePreview = () -> {
            double zoom = zoomSlider.getValue();
            double scale = baseScale * zoom;
            previewIv.setFitWidth(w * scale);
            previewIv.setFitHeight(h * scale);
            previewIv.setTranslateX(posXSlider.getValue() * (previewSize / 200.0));
            previewIv.setTranslateY(posYSlider.getValue() * (previewSize / 200.0));
        };

        zoomSlider.valueProperty().addListener((obs, oldV, newV) -> updatePreview.run());
        posXSlider.valueProperty().addListener((obs, oldV, newV) -> updatePreview.run());
        posYSlider.valueProperty().addListener((obs, oldV, newV) -> updatePreview.run());

        Button resetBtn = new Button("↺  Đặt lại ban đầu");
        resetBtn.setStyle("-fx-background-color: #f3f4f6; -fx-text-fill: #374151; -fx-font-weight: 600; -fx-font-size: 12px; -fx-background-radius: 8; -fx-padding: 5 12; -fx-cursor: hand;");
        resetBtn.setOnAction(e -> {
            zoomSlider.setValue(1.0);
            posXSlider.setValue(0);
            posYSlider.setValue(0);
        });

        VBox controlsBox = new VBox(6, zoomLabel, zoomSlider, posXLabel, posXSlider, posYLabel, posYSlider, resetBtn);
        controlsBox.setAlignment(Pos.CENTER);
        controlsBox.setStyle("-fx-background-color: #f9fafb; -fx-padding: 12; -fx-background-radius: 12; -fx-border-color: #e5e7eb; -fx-border-radius: 12;");

        root.getChildren().addAll(title, hint, avatarPreviewFrame, controlsBox);
        dialog.getDialogPane().setContent(root);

        ButtonType saveType = new ButtonType("Lưu ảnh đại diện", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, cancelType);

        Button saveBtn = (Button) dialog.getDialogPane().lookupButton(saveType);
        saveBtn.getStyleClass().addAll("button", "button-primary");
        saveBtn.setStyle("-fx-background-color: #6366f1; -fx-text-fill: white; -fx-font-weight: 700; -fx-background-radius: 10; -fx-padding: 8 20;");

        final BufferedImage finalSrc = srcImage;
        final File finalFile = file;
        dialog.setResultConverter(btn -> {
            if (btn == saveType) {
                double zoom = zoomSlider.getValue();
                double offX = posXSlider.getValue();
                double offY = posYSlider.getValue();
                byte[] cropped = cropAndResampleImage(finalSrc, zoom, offX, offY, 400);
                if (cropped == null) {
                    try {
                        cropped = Files.readAllBytes(finalFile.toPath());
                    } catch (Exception ignored) { }
                }
                if (cropped != null) {
                    onSave.accept(cropped);
                } else {
                    showErrorAlert("Lỗi lưu ảnh", "Không thể xử lý dữ liệu ảnh.");
                }
            }
            return btn;
        });

        dialog.showAndWait();
    }

    private byte[] cropAndResampleImage(BufferedImage src, double zoom, double offsetX, double offsetY, int targetSize) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w <= 0 || h <= 0) return null;

            double minDim = Math.min(w, h);
            double cropSize = minDim / Math.max(1.0, zoom);

            double maxShiftX = (w - cropSize) / 2.0;
            double maxShiftY = (h - cropSize) / 2.0;

            double centerX = (w / 2.0) - (offsetX / 100.0) * maxShiftX;
            double centerY = (h / 2.0) - (offsetY / 100.0) * maxShiftY;

            double cropX = Math.max(0, Math.min(w - cropSize, centerX - (cropSize / 2.0)));
            double cropY = Math.max(0, Math.min(h - cropSize, centerY - (cropSize / 2.0)));

            BufferedImage dest = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = dest.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2d.drawImage(
                src,
                0, 0, targetSize, targetSize,
                (int)cropX, (int)cropY, (int)(cropX + cropSize), (int)(cropY + cropSize),
                null
            );
            g2d.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(dest, "png", baos);
            return baos.toByteArray();
        } catch (Exception ex) {
            return null;
        }
    }

    private Button iconButton(String icon, String hint) { Button b = new Button(icon); b.setAccessibleText(hint); b.setTooltip(new Tooltip(hint)); b.getStyleClass().add("icon-button"); return b; }
    private void connectConfiguredPeer() {
        String peer = System.getProperty("studyroom.peer", "").trim();
        if (peer.isEmpty()) return;
        String[] hostPort = peer.split(":", 2);
        try { node.connect(hostPort[0], hostPort.length == 2 ? Integer.parseInt(hostPort[1]) : 5050); toast("Đã nối peer " + peer); }
        catch (IOException | NumberFormatException e) { toast("Chưa thể nối peer " + peer); }
    }

    private void toast(String message) {
        // Disabled: do not spawn toast popup labels that cover buttons or stack up on UI
    }
    @Override public void stop() {
        if (user != null) musicPresenceRepo.clearPresence(user.username());
        musicPlayer.stop();
        classroomVoice.stop();
        webcamStream.stop();
        ScreenShareEngine.getInstance().stop();
        if (node != null) node.close();
        if (classroomSyncTimer != null) classroomSyncTimer.cancel();
        if (messageSyncTimer != null) messageSyncTimer.cancel();
    }
    public static void main(String[] args) { launch(args); }
}
