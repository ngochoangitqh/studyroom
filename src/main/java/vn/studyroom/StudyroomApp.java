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
import java.util.List;
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


public final class StudyroomApp extends Application {
    private final Database database = new Database();
    private final AuthStore auth = new AuthStore(database);
    private final ChatRepository chatRepository = new ChatRepository(database);
    private final FriendRepository friendRepo = new FriendRepository(database);
    private final CallRepository callRepo = new CallRepository(database);
    private final CourseRepository courseRepo = new CourseRepository(database);
    private final BorderPane shell = new BorderPane();
    private final VBox content = new VBox();
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
        StackPane root = new StackPane(); root.getStyleClass().add("auth-root");
        VBox card = new VBox(18); card.getStyleClass().add("auth-card"); card.setMaxWidth(420);
        Label brand = new Label("◉  Studyroom"); brand.getStyleClass().add("brand");
        Label title = new Label("Học cùng nhau,\nkhông học một mình."); title.getStyleClass().add("auth-title");
        Label subtitle = new Label("Tạo một không gian chung để nhắn tin, họp, trình chiếu và nghe nhạc cùng nhóm."); subtitle.getStyleClass().add("muted"); subtitle.setWrapText(true);
        TextField name = field("Tên hiển thị", "Nguyễn Minh"); TextField username = field("Tên đăng nhập", "minh.study");
        PasswordField password = new PasswordField(); password.setPromptText("Mật khẩu"); password.getStyleClass().add("input");
        Label error = new Label(); error.getStyleClass().add("form-error"); error.setWrapText(true);
        Button submit = new Button("Đăng nhập"); submit.getStyleClass().addAll("button", "button-primary"); submit.setMaxWidth(Double.MAX_VALUE);
        Hyperlink switchMode = new Hyperlink("Chưa có tài khoản? Đăng ký");
        final boolean[] registering = {false};
        Runnable refresh = () -> { name.setManaged(registering[0]); name.setVisible(registering[0]); submit.setText(registering[0] ? "Tạo tài khoản" : "Đăng nhập"); switchMode.setText(registering[0] ? "Đã có tài khoản? Đăng nhập" : "Chưa có tài khoản? Đăng ký"); error.setText(""); };
        switchMode.setOnAction(e -> { registering[0] = !registering[0]; refresh.run(); });
        submit.setOnAction(e -> { try { user = registering[0] ? auth.register(name.getText(), username.getText(), password.getText()) : auth.login(username.getText(), password.getText()); openWorkspace(); } catch (Exception ex) { error.setText(ex.getMessage()); ex.printStackTrace(); } });
        card.getChildren().addAll(brand, title, subtitle, name, username, password, error, submit, switchMode); refresh.run(); root.getChildren().add(card); return root;
    }
    private TextField field(String label, String prompt) { TextField f = new TextField(); f.setPromptText(label + " · " + prompt); f.getStyleClass().add("input"); return f; }

    private void openWorkspace() {
        shell.getStyleClass().add("app-shell"); shell.setLeft(sidebar()); shell.setCenter(content); showChat();
        scene.setRoot(shell);
        courseRepo.seedInitialDemoIfEmpty(user.username());
        try {
            node = new TcpChatNode(packet -> Platform.runLater(() -> { if (selectedRoomId != null) addMessage(selectedRoomId, packet.sender(), packet.body(), false, true); }));
            node.start(5050);
            connectConfiguredPeer();
        }
        catch (IOException ignored) { /* another Studyroom node owns the local port */ }

        Timer callChecker = new Timer(true);
        callChecker.scheduleAtFixedRate(new TimerTask() {
            private String handledCallId = null;
            @Override
            public void run() {
                try {
                    CallRepository.CallSession incoming = callRepo.getIncomingDirectCall(user.username());
                    if (incoming != null && !incoming.callId().equals(handledCallId)) {
                        handledCallId = incoming.callId();
                        Platform.runLater(() -> promptIncomingCall(incoming));
                    }
                } catch (Exception ignored) { }
            }
        }, 1500, 2000);
    }
    private VBox sidebar() {
        VBox bar = new VBox(12); bar.getStyleClass().add("sidebar");
        Label logo = new Label("◉  Studyroom"); logo.getStyleClass().add("brand");
        Button chat = nav("☷", "Trò chuyện", this::showChat); Button room = nav("◉", "Phòng chung", this::showRoom); Button music = nav("♫", "Nghe nhạc", this::showMusic); Button profile = nav("◎", "Hồ sơ", this::showProfile);
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
        bar.getChildren().addAll(logo, spacer(18), chat, room, music, profile, gap, meBox); return bar;
    }
    private Button nav(String icon, String label, Runnable action) { Button b = new Button(icon + "   " + label); b.getStyleClass().add("nav-item"); b.setMaxWidth(Double.MAX_VALUE); b.setOnAction(e -> action.run()); return b; }
    private Region spacer(double h) { Region r = new Region(); r.setMinHeight(h); return r; }
    private void base(String title, String caption) { content.getChildren().clear(); content.getStyleClass().setAll("workspace"); content.setPadding(new Insets(26, 30, 20, 30)); Label h = new Label(title); h.getStyleClass().add("page-title"); Label c = new Label(caption); c.getStyleClass().add("muted"); content.getChildren().addAll(h, c, spacer(18)); }

    private void showChat() { showChat(null, "", false); }
    private void showChat(String roomId, String conversationName, boolean group) {
        refreshSidebar();
        selectedRoomId = roomId;
        content.getChildren().clear(); content.getStyleClass().setAll("chat-workspace"); content.setPadding(Insets.EMPTY); content.setSpacing(0);
        VBox threads = new VBox(8); threads.getStyleClass().add("thread-list"); threads.setPrefWidth(380); threads.setMinWidth(320);
        HBox threadTitle = new HBox(); threadTitle.getStyleClass().add("thread-title-row"); Label title = new Label("Trò chuyện"); title.getStyleClass().add("chat-page-title"); Region titlePush = new Region(); HBox.setHgrow(titlePush, Priority.ALWAYS); Button create = iconButton("✎", "Tạo nhóm mới"); create.setOnAction(e -> createGroup()); threadTitle.getChildren().addAll(title, titlePush, create);
        TextField find = new TextField(); find.setPromptText("⌕  Tìm kiếm cuộc trò chuyện..."); find.getStyleClass().add("chat-search");
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
        ScrollPane threadScroll = new ScrollPane(threadItems); threadScroll.setFitToWidth(true); threadScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); threadScroll.getStyleClass().add("thread-scroll"); VBox.setVgrow(threadScroll, Priority.ALWAYS); threads.getChildren().addAll(threadTitle, find, filters, threadScroll);
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
        Button call = iconButton("☎", "Gọi thoại"); Button video = iconButton("📹", "Bật video"); Button search = iconButton("⌕", "Tìm trong trò chuyện"); Button more = iconButton("•••", "Thông tin cuộc trò chuyện");
        call.setOnAction(e -> startOrJoinCall(roomId, conversationName, group ? "GROUP" : "DIRECT", false));
        video.setOnAction(e -> startOrJoinCall(roomId, conversationName, group ? "GROUP" : "DIRECT", true));
        head.getChildren().addAll(groupAvatar, groupCopy, push, addMember, call, video, search, more);

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
        MenuItem sendImgItem = new MenuItem("🖼️  Gửi hình ảnh...");
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
                previewText = latest.getFirst().sender() + ": 🖼️ [Hình ảnh]";
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
        row.setOnMouseClicked(e -> showChat(roomId, nameText, group));
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
        container.setMinSize(size, size);
        container.setMaxSize(size, size);
        container.setPrefSize(size, size);
        container.setAlignment(Pos.CENTER);
        container.setClip(clipCircle);

        javafx.scene.shape.Circle border = new javafx.scene.shape.Circle(radius);
        border.setFill(null);
        border.setStroke(javafx.scene.paint.Color.web("#6366f1", 0.5));
        border.setStrokeWidth(Math.max(1.5, size * 0.035));

        StackPane avatarPane = new StackPane(container, border);
        avatarPane.getStyleClass().addAll("avatar", style);
        avatarPane.setMinSize(size, size);
        avatarPane.setMaxSize(size, size);
        avatarPane.setPrefSize(size, size);
        avatarPane.setAlignment(Pos.CENTER);
        return avatarPane;
    }

    private StackPane avatar(String initials, String style, double size) {
        Label mark = new Label(initials);
        mark.getStyleClass().add("avatar-text");
        mark.setStyle("-fx-font-size: " + (int)(size * 0.38) + "px; -fx-font-weight: 800;");

        StackPane inner = new StackPane(mark);
        inner.getStyleClass().addAll("avatar", style);
        inner.setMinSize(size, size);
        inner.setMaxSize(size, size);
        inner.setPrefSize(size, size);

        double radius = size / 2.0;
        javafx.scene.shape.Circle clipCircle = new javafx.scene.shape.Circle(radius, radius, radius);
        inner.setClip(clipCircle);

        javafx.scene.shape.Circle border = new javafx.scene.shape.Circle(radius);
        border.setFill(null);
        border.setStroke(javafx.scene.paint.Color.web("#6366f1", 0.3));
        border.setStrokeWidth(Math.max(1.5, size * 0.035));

        StackPane avatarPane = new StackPane(inner, border);
        avatarPane.setMinSize(size, size);
        avatarPane.setMaxSize(size, size);
        avatarPane.setPrefSize(size, size);
        avatarPane.setAlignment(Pos.CENTER);
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

        StackPane avatarPane = group ? groupAvatar(roomId, conversationName, "avatar-group", 80)
                                      : userAvatar(targetUsername != null ? targetUsername : conversationName, initials(conversationName), "avatar-person", 80);
        avatarPane.setCursor(javafx.scene.Cursor.HAND);
        
        Label camBadge = new Label("📷");
        camBadge.setStyle("-fx-background-color: #6366f1; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 3 6; -fx-background-radius: 10;");
        StackPane.setAlignment(camBadge, Pos.BOTTOM_RIGHT);
        avatarPane.getChildren().add(camBadge);

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
        VBox searchBtn = quickActionItem("⌕", "Tìm kiếm", () -> toast("Tìm kiếm trong trò chuyện"));
        quickActions.getChildren().addAll(muteBtn, searchBtn);

        VBox headerBox = new VBox(10, avatarPane, nameLbl, metaLbl, spacer(4), quickActions);
        headerBox.setAlignment(Pos.CENTER);

        VBox customSection = createInfoSection("Tùy chỉnh đoạn chat", List.of(
            group ? createSectionButton("🖼️  Đổi ảnh đại diện nhóm", e -> showChangeGroupAvatarDialog(roomId, conversationName)) : createSectionButton("📷  Đổi ảnh đại diện cá nhân", e -> showChangeUserAvatarDialog()),
            group ? createSectionButton("✏️  Đổi tên nhóm", e -> showRenameGroupDialog(roomId, conversationName)) : null
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
            Button addMemBtn = createSectionButton("＋  Thêm thành viên", e -> showAddMemberDialog(roomId, conversationName));
            memberItems.getChildren().add(addMemBtn);

            membersSection = createInfoSectionContent("Thành viên trong đoạn chat (" + memberNames.size() + ")", memberItems);
        }

        VBox privacySection = createInfoSection("Quyền riêng tư & hỗ trợ", List.of(
            group ? createSectionButton("🚪  Rời khỏi nhóm", e -> leaveGroup(roomId, conversationName)) : null
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
        Button btn = new Button(icon);
        btn.setStyle("-fx-background-color: #f3f4f6; -fx-background-radius: 50%; -fx-min-width: 38px; -fx-min-height: 38px; -fx-max-width: 38px; -fx-max-height: 38px; -fx-font-size: 16px; -fx-cursor: hand;");
        btn.setOnAction(e -> action.run());
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #4b5563; -fx-text-alignment: center;");
        VBox box = new VBox(4, btn, lbl);
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

    private Button createSectionButton(String text, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button btn = new Button(text);
        btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #1f2937; -fx-font-size: 13px; -fx-alignment: CENTER_LEFT; -fx-cursor: hand; -fx-padding: 6 8;");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setOnAction(handler);
        btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: #f3f4f6; -fx-text-fill: #1f2937; -fx-font-size: 13px; -fx-alignment: CENTER_LEFT; -fx-cursor: hand; -fx-padding: 6 8; -fx-background-radius: 6;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #1f2937; -fx-font-size: 13px; -fx-alignment: CENTER_LEFT; -fx-cursor: hand; -fx-padding: 6 8;"));
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
                    Label err = new Label("🖼️ [Lỗi hiển thị ảnh: " + fileName + "]");
                    bubble.getChildren().addAll(err, time);
                }
            } else {
                Label missing = new Label("🖼️ [Hình ảnh: " + fileName + "]");
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
            else if (lowerName.endsWith(".zip") || lowerName.endsWith(".rar") || lowerName.endsWith(".7z")) fileIcon = "🗜️";
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
            {"Cử chỉ & Tim", "👍,👎,👏,🙌,🤝,✌️,🤞,🤟,🤙,👈,👉,👆,👇,☝️,✋,🙏,❤️,🧡,💛,💚,💙,💜,🖤,💔,❣️,💕,💞,💓,💗,💖,💘,✨,🔥,🌟,⭐,💯,🎉,🎊,🚀,💡"},
            {"Học tập & Đồ vật", "📚,📖,📝,✏️,🖊️,🎓,🎒,💻,🖥️,📱,📊,📈,📅,🕒,⏰,🏆,🥇,🎯,📌,📎,☕,🍕,🍔,🍰,🎁,⚽,🏀,🎨,🎵,🎶,🔔,📣,🔍,🔒,🔑,✅,❌,⚠️,❓,❗"}
        };
        for (String[] cat : categories) {
            Label catLabel = new Label(cat[0]);
            catLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #6b7280; -fx-padding: 2 0 0 0;");
            FlowPane flow = new FlowPane(4, 4);
            flow.setPrefWrapLength(320);
            for (String em : cat[1].split(",")) {
                Button btn = new Button(em);
                btn.setStyle("-fx-background-color: transparent; -fx-font-size: 18px; -fx-padding: 4 6; -fx-cursor: hand; -fx-background-radius: 6;");
                btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: #f3f4f6; -fx-font-size: 18px; -fx-padding: 4 6; -fx-cursor: hand; -fx-background-radius: 6;"));
                btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: transparent; -fx-font-size: 18px; -fx-padding: 4 6; -fx-cursor: hand; -fx-background-radius: 6;"));
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
            session = callRepo.startCall(roomId, roomName, user.username(), user.displayName(), callType, 5100, myIp);
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
    private Clip generateRingtone() {
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
            double silence = 1.0;

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
            Clip clip = (Clip) AudioSystem.getLine(info);
            clip.open(fmt, buf, 0, buf.length);
            clip.loop(Clip.LOOP_CONTINUOUSLY);
            return clip;
        } catch (Exception e) {
            return null; // silently ignore if audio not available
        }
    }

    private void promptIncomingCall(CallRepository.CallSession incoming) {
        // ── 1. Start ringtone ────────────────────────────────────────────────
        Clip ringtone = generateRingtone();
        Runnable stopRing = () -> {
            if (ringtone != null) {
                try { ringtone.stop(); ringtone.close(); } catch (Exception ignored) {}
            }
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
        Runnable closeAll = () -> {
            stopRing.run();
            shake.stop();
            blinkDots.stop();
            answerPulse.stop();
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

        // Sub Tabs
        HBox tabs = new HBox(8);
        tabs.setPadding(new Insets(10, 0, 14, 0));
        Button tabLive = new Button("🖥️ Phòng học trực tuyến");
        Button tabMaterials = new Button("📚 Slide & Tài liệu học tập");
        Button tabSchedule = new Button("📅 Lịch học");

        tabLive.getStyleClass().add("tab-pill-active");
        tabMaterials.getStyleClass().add("tab-pill");
        tabSchedule.getStyleClass().add("tab-pill");

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
                    } else {
                        renderScheduleTab(course, workspaceArea, () -> {
                            currentTab[0] = 0;
                            tabLive.fire();
                        });
                    }
                }
            }
        };

        tabLive.setOnAction(e -> { currentTab[0] = 0; renderActiveTab.run(); });
        tabMaterials.setOnAction(e -> { currentTab[0] = 1; renderActiveTab.run(); });
        tabSchedule.setOnAction(e -> { currentTab[0] = 2; renderActiveTab.run(); });

        tabs.getChildren().addAll(tabLive, tabMaterials, tabSchedule);
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

            Label iconLbl = new Label(isHost ? "🖥️" : "📡");
            iconLbl.setStyle("-fx-font-size: 50px; -fx-background-color: " + (isHost ? "-violet-soft" : "#f3f4f6") + "; -fx-background-radius: 36; -fx-padding: 16 22;");

            if (isHost) {
                Label titleLbl = new Label("Khu vực Trình chiếu Màn hình của Chủ phòng");
                titleLbl.setStyle("-fx-font-size: 20px; -fx-font-weight: 800; -fx-text-fill: -ink;");

                Label descLbl = new Label("Bạn là chủ phòng của lớp học này.\nKhi bạn nhấn \"Bắt đầu chia sẻ màn hình\", toàn bộ màn hình máy tính của bạn sẽ được truyền hình trực tiếp với độ nét cao cho tất cả học viên trong phòng cùng theo dõi bài giảng, slide, mã nguồn hoặc tài liệu.");
                descLbl.setStyle("-fx-text-fill: -muted; -fx-font-size: 14px; -fx-text-alignment: CENTER; -fx-line-spacing: 4;");
                descLbl.setWrapText(true);

                Button startBtn = new Button("🖥️  Bắt đầu chia sẻ màn hình");
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

                Button testBoardBtn = new Button("✏️ Bảng trắng");
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
                Button stopBtn = new Button("⏹️  Dừng chia sẻ màn hình");
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

                Button boardBtn = new Button("✏️  Bảng trắng");
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
        Button micBtn = new Button(isMicOn ? "🎙️ Mic" : "🔇 Mic");
        micBtn.getStyleClass().setAll(isMicOn ? "dock-btn" : "dock-btn-danger");
        micBtn.setOnAction(e -> {
            isMicOn = !isMicOn;
            classroomVoice.setMuted(!isMicOn);
            micBtn.setText(isMicOn ? "🎙️ Mic" : "🔇 Mic");
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

        Button shareScreenBtn = new Button(course.isPresenting() && isHost ? "⏹️ Dừng share" : "🖥️ Chia sẻ");
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
                            statusBadge.setText("🎙️ Mic bật");
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
                        aStatusBadge.setText("🎙️ Mic bật");
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
                    case "ZIP", "RAR", "7Z" -> "🗜️";
                    case "PNG", "JPG", "JPEG" -> "🖼️";
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
                        case "ZIP", "RAR" -> "🗜️";
                        case "PNG", "JPG", "JPEG" -> "🖼️";
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
    private void showMusic() { base("Đang phát cùng phòng “Học đêm khuya”", "5 người đang nghe"); HBox body = new HBox(24); VBox.setVgrow(body, Priority.ALWAYS); VBox player = new VBox(18); player.getStyleClass().add("player"); HBox.setHgrow(player, Priority.ALWAYS); Label art = new Label("LOFI\nSTUDY\nBEATS"); art.getStyleClass().add("album-art"); Label song = new Label("Lofi Study Beats"); song.getStyleClass().add("now-playing"); Label artist = new Label("Chill Collective"); artist.getStyleClass().add("muted"); ProgressBar progress = new ProgressBar(.34); progress.setMaxWidth(Double.MAX_VALUE); playButton = new Button("▶"); playButton.getStyleClass().addAll("play", "button-primary"); playButton.setOnAction(e -> { boolean paused = "▶".equals(playButton.getText()); playButton.setText(paused ? "Ⅱ" : "▶"); toast(paused ? "Đang phát đồng bộ trong phòng." : "Đã tạm dừng cho cả phòng."); }); HBox controls = new HBox(18, new Button("↶"), playButton, new Button("↷")); controls.setAlignment(Pos.CENTER); player.setAlignment(Pos.CENTER); player.getChildren().addAll(art, song, artist, progress, controls, new Label("Đang nghe cùng: Lan · Minh · Bạn +2")); VBox queue = new VBox(10, new Label("Danh sách phát tiếp theo")); queue.getStyleClass().add("queue"); for (String track : List.of("Rainy Mood · 3:24", "Coffee Jazz · 4:12", "Night Piano · 5:28", "Morning Vibes · 3:17", "Deep Focus · 4:01")) { Button t = new Button(track); t.getStyleClass().add("track"); t.setOnAction(e -> toast("Đã chọn " + ((Button)e.getSource()).getText())); queue.getChildren().add(t); } body.getChildren().addAll(player, queue); content.getChildren().add(body); }
    private void refreshSidebar() {
        shell.setLeft(sidebar());
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

        card.getChildren().addAll(bigAvatar, nameLbl, handleLbl, changeAvatarBtn, new Separator(), statusLbl);
        content.getChildren().add(card);
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

        Label posXLabel = new Label("↔️  Vị trí ngang (Trái / Phải)");
        posXLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: 600; -fx-text-fill: #4b5563;");
        Slider posXSlider = new Slider(-100, 100, 0);

        Label posYLabel = new Label("↕️  Vị trí dọc (Trên / Dưới)");
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
        classroomVoice.stop();
        webcamStream.stop();
        ScreenShareEngine.getInstance().stop();
        if (node != null) node.close();
        if (classroomSyncTimer != null) classroomSyncTimer.cancel();
        if (messageSyncTimer != null) messageSyncTimer.cancel();
    }
    public static void main(String[] args) { launch(args); }
}
