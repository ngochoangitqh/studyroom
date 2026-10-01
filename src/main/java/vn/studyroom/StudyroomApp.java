package vn.studyroom;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

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
        Label me = new Label("●  " + user.displayName() + "\n     Đang hoạt động"); me.getStyleClass().add("presence");
        bar.getChildren().addAll(logo, spacer(18), chat, room, music, profile, gap, me); return bar;
    }
    private Button nav(String icon, String label, Runnable action) { Button b = new Button(icon + "   " + label); b.getStyleClass().add("nav-item"); b.setMaxWidth(Double.MAX_VALUE); b.setOnAction(e -> action.run()); return b; }
    private Region spacer(double h) { Region r = new Region(); r.setMinHeight(h); return r; }
    private void base(String title, String caption) { content.getChildren().clear(); content.getStyleClass().setAll("workspace"); content.setPadding(new Insets(26, 30, 20, 30)); Label h = new Label(title); h.getStyleClass().add("page-title"); Label c = new Label(caption); c.getStyleClass().add("muted"); content.getChildren().addAll(h, c, spacer(18)); }

    private void showChat() { showChat(null, "", false); }
    private void showChat(String roomId, String conversationName, boolean group) {
        selectedRoomId = roomId;
        content.getChildren().clear(); content.getStyleClass().setAll("chat-workspace"); content.setPadding(Insets.EMPTY); content.setSpacing(0);
        VBox threads = new VBox(8); threads.getStyleClass().add("thread-list"); threads.setPrefWidth(420); threads.setMinWidth(360);
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
                StackPane photo = avatar(initials(person.displayName()), "avatar-person");
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
        if (roomId == null) { showEmptyConversation(conversation); showChatColumns(threads, conversation); return; }
        List<String> memberNames = group ? chatRepository.membersOf(roomId) : List.of();
        String metaText = group ? memberNames.size() + " thành viên" : "Đang hoạt động";
        HBox head = new HBox(12); head.getStyleClass().add("conversation-header"); StackPane groupAvatar = avatar(initials(conversationName), group ? "avatar-group" : "avatar-person"); VBox groupCopy = new VBox(2); Label groupName = new Label(conversationName); groupName.getStyleClass().add("group-name"); Label groupMeta = new Label(metaText); groupMeta.getStyleClass().add("group-meta"); groupCopy.getChildren().addAll(groupName, groupMeta); Region push = new Region(); HBox.setHgrow(push, Priority.ALWAYS);
        Button addMember = iconButton("＋", "Thêm thành viên"); if (group) addMember.setOnAction(e -> showAddMemberDialog(roomId, conversationName)); else addMember.setVisible(false);
        Button call = iconButton("☎", "Gọi thoại"); Button video = iconButton("▣", "Bật video"); Button search = iconButton("⌕", "Tìm trong trò chuyện"); Button more = iconButton("•••", "Thêm tuỳ chọn");
        call.setOnAction(e -> startOrJoinCall(roomId, conversationName, group ? "GROUP" : "DIRECT"));
        video.setOnAction(e -> startOrJoinCall(roomId, conversationName, group ? "GROUP" : "DIRECT"));
        head.getChildren().addAll(groupAvatar, groupCopy, push, addMember, call, video, search, more);

        CallRepository.CallSession activeCall = callRepo.getActiveCall(roomId);
        if (group && activeCall != null) {
            HBox callBanner = new HBox(12); callBanner.setAlignment(Pos.CENTER_LEFT);
            callBanner.setStyle("-fx-background-color: #2b1f4d; -fx-padding: 10 20; -fx-border-color: #7c5cff transparent transparent transparent;");
            Label bannerLabel = new Label("🟢 Cuộc gọi nhóm đang diễn ra"); bannerLabel.setStyle("-fx-text-fill: #55efc4; -fx-font-weight: bold;");
            Region bSpacer = new Region(); HBox.setHgrow(bSpacer, Priority.ALWAYS);
            Button joinBtn = new Button("Tham gia ngay"); joinBtn.getStyleClass().addAll("button", "button-primary");
            joinBtn.setOnAction(e -> startOrJoinCall(roomId, conversationName, "GROUP"));
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

        HBox composerShell = new HBox(8); composerShell.getStyleClass().add("composer-shell"); Button attach = iconButton("⌇", "Đính kèm tệp"); TextField composer = new TextField(); composer.setPromptText("Nhắn tin..."); composer.getStyleClass().add("composer"); Button emoji = iconButton("☺", "Biểu tượng cảm xúc"); Button send = iconButton("➤", "Gửi tin nhắn"); send.getStyleClass().add("send-icon"); Runnable sendMessage = () -> { if (!composer.getText().isBlank()) { String text = composer.getText().trim(); addMessage(roomId, user.displayName(), text, true, true); if (node != null) node.broadcast(user.displayName(), text); composer.clear(); scroll.setVvalue(1.0); } }; send.setOnAction(e -> sendMessage.run()); composer.setOnAction(e -> sendMessage.run()); HBox.setHgrow(composer, Priority.ALWAYS); composerShell.getChildren().addAll(attach, composer, emoji, send);
        conversation.getChildren().addAll(head, scroll, composerShell); showChatColumns(threads, conversation);
    }
    private void showChatColumns(VBox threads, VBox conversation) { HBox columns = new HBox(threads, conversation); HBox.setHgrow(conversation, Priority.ALWAYS); VBox.setVgrow(columns, Priority.ALWAYS); content.getChildren().add(columns); }
    private Button chip(String text, boolean selected) { Button chip = new Button(text); chip.getStyleClass().addAll("filter-chip", selected ? "filter-chip-active" : ""); return chip; }
    private HBox thread(ChatRepository.Room room, boolean active) { return thread(room.id(), room.name(), "avatar-group", "Mở nhóm ", active, true); }
    private HBox thread(User person, boolean active) { return thread(directRoomId(user.username(), person.username()), person.displayName(), "avatar-person", "Nhắn tin với ", active, false); }
    private HBox thread(String roomId, String nameText, String avatarStyle, String action, boolean active, boolean group) { StackPane photo = avatar(initials(nameText), avatarStyle); VBox copy = new VBox(3); copy.getStyleClass().add("thread-copy"); Label name = new Label(nameText); name.getStyleClass().add("thread-name"); List<ChatRepository.Message> latest = chatRepository.recent(roomId, 1); Label preview = new Label(latest.isEmpty() ? "Chưa có tin nhắn" : latest.getFirst().sender() + ": " + latest.getFirst().body()); preview.getStyleClass().add("thread-preview"); copy.getChildren().addAll(name, preview); HBox row = new HBox(12, photo, copy); HBox.setHgrow(copy, Priority.ALWAYS); row.getStyleClass().addAll("thread", active ? "thread-active" : ""); row.setAccessibleText(action + nameText); row.setOnMouseClicked(e -> showChat(roomId, nameText, group)); return row; }
    private StackPane avatar(String initials, String style) { Label mark = new Label(initials); mark.getStyleClass().add("avatar-text"); StackPane avatar = new StackPane(mark); avatar.getStyleClass().addAll("avatar", style); avatar.setMinSize(40, 40); avatar.setMaxSize(40, 40); return avatar; }
    private void addMessage(String roomId, String sender, String text, boolean mine, boolean persist) { if (persist) chatRepository.save(roomId, sender, text); messages.getChildren().removeIf(node -> node.getStyleClass().contains("empty-conversation")); VBox bubble = new VBox(4); bubble.getStyleClass().addAll("bubble", mine ? "bubble-mine" : "bubble-peer"); Label body = new Label(text); body.setWrapText(true); body.setMaxWidth(390); Label time = new Label(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + (mine ? "  ✓✓" : "")); time.getStyleClass().add("message-time"); bubble.getChildren().addAll(body, time); VBox cluster = new VBox(4); if (!mine) { Label who = new Label(sender); who.getStyleClass().add("message-sender"); cluster.getChildren().add(who); } cluster.getChildren().add(bubble); HBox line = new HBox(12); line.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT); if (!mine) line.getChildren().addAll(avatar(initials(sender), "avatar-person"), cluster); else line.getChildren().add(cluster); messages.getChildren().add(line); }
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
    private void startOrJoinCall(String roomId, String roomName, String callType) {
        CallRepository.CallSession session = callRepo.getActiveCall(roomId);
        if (session == null) {
            String myIp = VoiceEngine.getLocalIp();
            session = callRepo.startCall(roomId, roomName, user.username(), user.displayName(), callType, 5100, myIp);
        }
        CallWindow callWin = new CallWindow(session, user, callRepo);
        callWin.start();
        showChat(roomId, roomName, "GROUP".equals(callType));
    }

    private void promptIncomingCall(CallRepository.CallSession incoming) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle("Cuộc gọi đến");
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().getStylesheets().addAll(
            getClass().getResource("/tokens.css").toExternalForm(),
            getClass().getResource("/studyroom.css").toExternalForm()
        );
        dialog.getDialogPane().getStyleClass().add("custom-dialog");

        VBox box = new VBox(12);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(16, 24, 16, 24));
        box.setMinWidth(340);

        Label icon = new Label("☎");
        icon.setStyle("-fx-font-size: 36px; -fx-text-fill: #7c5cff;");
        Label title = new Label("Cuộc gọi thoại đến");
        title.getStyleClass().add("dialog-title");
        Label caller = new Label(incoming.roomName() + " đang gọi cho bạn...");
        caller.getStyleClass().add("dialog-desc");

        box.getChildren().addAll(icon, title, caller);
        dialog.getDialogPane().setContent(box);

        ButtonType answerType = new ButtonType("Trả lời", ButtonBar.ButtonData.OK_DONE);
        ButtonType rejectType = new ButtonType("Từ chối", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(rejectType, answerType);

        Button answerBtn = (Button) dialog.getDialogPane().lookupButton(answerType);
        answerBtn.getStyleClass().addAll("button", "button-primary");
        Button rejectBtn = (Button) dialog.getDialogPane().lookupButton(rejectType);
        rejectBtn.getStyleClass().add("button");

        dialog.setResultConverter(btn -> btn == answerType);
        dialog.showAndWait().ifPresent(accepted -> {
            if (accepted) {
                CallWindow callWin = new CallWindow(incoming, user, callRepo);
                callWin.start();
            } else {
                callRepo.endCall(incoming.callId());
            }
        });
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

        final int[] currentTab = {0};

        Runnable renderActiveTab = new Runnable() {
            @Override
            public void run() {
                workspaceArea.getChildren().clear();
                tabLive.getStyleClass().setAll(currentTab[0] == 0 ? "tab-pill-active" : "tab-pill");
                tabMaterials.getStyleClass().setAll(currentTab[0] == 1 ? "tab-pill-active" : "tab-pill");
                tabSchedule.getStyleClass().setAll(currentTab[0] == 2 ? "tab-pill-active" : "tab-pill");

                if (currentTab[0] == 0) {
                    renderLiveClassroom(course, workspaceArea);
                } else if (currentTab[0] == 1) {
                    renderMaterialsTab(course, workspaceArea);
                } else {
                    renderScheduleTab(course, workspaceArea, () -> {
                        currentTab[0] = 0;
                        tabLive.fire();
                    });
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

    private void renderLiveClassroom(CourseRepository.Course course, VBox container) {
        HBox body = new HBox(16);
        VBox.setVgrow(body, Priority.ALWAYS);

        // LEFT: Slide Frame & Dock Bar
        VBox leftPane = new VBox(12);
        HBox.setHgrow(leftPane, Priority.ALWAYS);
        VBox.setVgrow(leftPane, Priority.ALWAYS);

        VBox deck = new VBox(16);
        deck.getStyleClass().add("slide-frame");
        VBox.setVgrow(deck, Priority.ALWAYS);

        // Slide header bar
        HBox slideHeader = new HBox(14);
        slideHeader.setAlignment(Pos.CENTER_LEFT);

        final int[] slideNumber = {Math.max(1, Math.min(course.currentSlide(), 12))};

        Label slideTitleHeader = new Label("Slide " + slideNumber[0] + ": Phản ứng Este hóa");
        slideTitleHeader.setStyle("-fx-font-weight: 800; -fx-font-size: 14px; -fx-text-fill: #374151;");

        Region navGap = new Region();
        HBox.setHgrow(navGap, Priority.ALWAYS);

        HBox navBox = new HBox(8);
        navBox.setAlignment(Pos.CENTER);
        Button prevSlide = new Button("‹");
        prevSlide.getStyleClass().add("button");
        prevSlide.setStyle("-fx-font-weight: bold; -fx-padding: 2 10;");
        Label pageIndicator = new Label(slideNumber[0] + " / 12");
        pageIndicator.setStyle("-fx-font-weight: 700; -fx-text-fill: #4b5563;");
        Button nextSlide = new Button("›");
        nextSlide.getStyleClass().add("button");
        nextSlide.setStyle("-fx-font-weight: bold; -fx-padding: 2 10;");
        navBox.getChildren().addAll(prevSlide, pageIndicator, nextSlide);

        Region rightGap = new Region();
        HBox.setHgrow(rightGap, Priority.ALWAYS);

        Label zoom = new Label("100%");
        zoom.setStyle("-fx-font-size: 13px; -fx-text-fill: #6b7280;");
        Button fullscreenBtn = new Button("⛶");
        fullscreenBtn.getStyleClass().add("button");
        fullscreenBtn.setStyle("-fx-padding: 2 8;");
        fullscreenBtn.setOnAction(e -> toast("Chế độ toàn màn hình trình chiếu."));

        slideHeader.getChildren().addAll(slideTitleHeader, navGap, navBox, rightGap, zoom, fullscreenBtn);

        // Slide Content
        VBox slideContent = new VBox(20);
        slideContent.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow(slideContent, Priority.ALWAYS);

        Label mainTitle = new Label("Phản ứng Este hóa");
        mainTitle.getStyleClass().add("slide-title");

        Label introText = new Label("Este được tạo thành từ phản ứng giữa axit cacboxylic và ancol, trong môi trường axit.");
        introText.setStyle("-fx-font-size: 15px; -fx-text-fill: #4b5563; -fx-line-spacing: 4;");
        introText.setWrapText(true);

        // Formula Card
        HBox formulaBox = new HBox();
        formulaBox.setAlignment(Pos.CENTER);
        formulaBox.setStyle("-fx-background-color: #1e1e2d; -fx-background-radius: 12; -fx-padding: 18 24;");
        Label formulaLabel = new Label("R—COOH   +   R'—OH    ⇌    R—COOR'   +   H₂O");
        formulaLabel.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #f3f4f6;");
        formulaBox.getChildren().add(formulaLabel);

        // Purple note box
        VBox noteBox = new VBox(8);
        noteBox.getStyleClass().add("slide-note-box");
        Label noteTitle = new Label("Lưu ý");
        noteTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-text-fill: #6b21a8;");
        Label noteItem1 = new Label("•  Phản ứng thuận nghịch");
        noteItem1.setStyle("-fx-font-size: 14px; -fx-text-fill: #581c87;");
        Label noteItem2 = new Label("•  Cần môi trường axit và đun nóng");
        noteItem2.setStyle("-fx-font-size: 14px; -fx-text-fill: #581c87;");
        noteBox.getChildren().addAll(noteTitle, noteItem1, noteItem2);

        slideContent.getChildren().addAll(mainTitle, introText, formulaBox, noteBox);

        Runnable updateSlideView = () -> {
            pageIndicator.setText(slideNumber[0] + " / 12");
            slideTitleHeader.setText("Slide " + slideNumber[0] + ": " + (slideNumber[0] == 4 ? "Phản ứng Este hóa" : "Chuyên đề Este - Lipit (Phần " + slideNumber[0] + ")"));
            courseRepo.updateSlide(course.id(), slideNumber[0]);
        };

        prevSlide.setOnAction(e -> {
            if (slideNumber[0] > 1) {
                slideNumber[0]--;
                updateSlideView.run();
            }
        });
        nextSlide.setOnAction(e -> {
            if (slideNumber[0] < 12) {
                slideNumber[0]++;
                updateSlideView.run();
            }
        });

        // Slide Actions
        HBox slideActions = new HBox(10);
        Button presentBtn = new Button("🖥️  Trình chiếu");
        presentBtn.getStyleClass().addAll("button", "button-primary");
        presentBtn.setOnAction(e -> toast("Đang phát trực tiếp bài giảng cho các thành viên trong phòng."));

        Button boardBtn = new Button("✏️  Bảng trắng");
        boardBtn.getStyleClass().add("button");
        boardBtn.setOnAction(e -> toast("Mở bảng trắng tương tác trực tiếp."));

        Button noteBtn = new Button("📄  Ghi chú");
        noteBtn.getStyleClass().add("button");
        noteBtn.setOnAction(e -> toast("Ghi chú cá nhân đã lưu."));

        slideActions.getChildren().addAll(presentBtn, boardBtn, noteBtn);

        deck.getChildren().addAll(slideHeader, slideContent, slideActions);

        // BOTTOM DOCK BAR
        HBox dock = new HBox(14);
        dock.setAlignment(Pos.CENTER);
        dock.getStyleClass().add("dock-bar");

        Button micBtn = new Button("🎙️ Tắt mic");
        micBtn.getStyleClass().add("dock-btn");
        final boolean[] micMuted = {false};
        micBtn.setOnAction(e -> {
            micMuted[0] = !micMuted[0];
            micBtn.setText(micMuted[0] ? "🔇 Bật mic" : "🎙️ Tắt mic");
            toast(micMuted[0] ? "Đã tắt micro." : "Đã bật micro.");
        });

        Button camBtn = new Button("📹 Tắt camera");
        camBtn.getStyleClass().add("dock-btn");
        final boolean[] camOff = {false};
        camBtn.setOnAction(e -> {
            camOff[0] = !camOff[0];
            camBtn.setText(camOff[0] ? "📷 Bật camera" : "📹 Tắt camera");
            toast(camOff[0] ? "Đã tắt camera." : "Đã bật camera.");
        });

        Button shareScreenBtn = new Button("🖥️ Chia sẻ màn hình");
        shareScreenBtn.getStyleClass().add("dock-btn");
        shareScreenBtn.setOnAction(e -> toast("Đang kết nối luồng chia sẻ màn hình..."));

        Button leaveBtn = new Button("🔴 Rời phòng");
        leaveBtn.getStyleClass().addAll("dock-btn-danger");
        leaveBtn.setOnAction(e -> {
            activeCourseId = null;
            showCourseList();
        });

        Button membersBtn = new Button("👥 Thành viên");
        membersBtn.getStyleClass().add("dock-btn");
        membersBtn.setOnAction(e -> toast("Danh sách 6 người đang tham dự phòng học."));

        Button chatBtn = new Button("💬 Trò chuyện");
        chatBtn.getStyleClass().add("dock-btn");
        chatBtn.setOnAction(e -> toast("Mở khung trò chuyện nhanh trong lớp."));

        dock.getChildren().addAll(micBtn, camBtn, shareScreenBtn, leaveBtn, membersBtn, chatBtn);

        leftPane.getChildren().addAll(deck, dock);

        // RIGHT: Video Sidebar
        VBox rightPane = new VBox(10);
        rightPane.setPrefWidth(220);
        rightPane.setMinWidth(200);

        Label sidebarTitle = new Label("Thành viên (6)");
        sidebarTitle.setStyle("-fx-font-weight: 800; -fx-font-size: 14px; -fx-text-fill: -ink;");
        rightPane.getChildren().add(sidebarTitle);

        VBox videoList = new VBox(8);
        videoList.getChildren().addAll(
            createVideoTile("Lan", true, false, false),
            createVideoTile("Minh", false, true, false),
            createVideoTile("Huy", false, false, true),
            createVideoTile("Chi", false, false, true),
            createVideoTile("Nam", false, false, false),
            createVideoTile("Bạn (" + user.displayName() + ")", false, false, false, true)
        );

        ScrollPane videoScroll = new ScrollPane(videoList);
        videoScroll.setFitToWidth(true);
        videoScroll.getStyleClass().add("thread-scroll");
        VBox.setVgrow(videoScroll, Priority.ALWAYS);

        rightPane.getChildren().add(videoScroll);

        body.getChildren().addAll(leftPane, rightPane);
        container.getChildren().add(body);
    }

    private Pane createVideoTile(String name, boolean speaking, boolean cameraOn, boolean muted) {
        return createVideoTile(name, speaking, cameraOn, muted, false);
    }

    private Pane createVideoTile(String name, boolean speaking, boolean cameraOn, boolean muted, boolean isMe) {
        VBox tile = new VBox(6);
        tile.getStyleClass().add("video-tile");
        if (speaking) tile.getStyleClass().add("video-tile-active");
        if (isMe) tile.setStyle(tile.getStyle() + "; -fx-border-color: #6366f1; -fx-border-width: 1.5; -fx-border-radius: 12;");

        HBox top = new HBox();
        Region tgap = new Region();
        HBox.setHgrow(tgap, Priority.ALWAYS);

        if (speaking) {
            Label speakBadge = new Label("● Đang nói");
            speakBadge.setStyle("-fx-font-size: 10px; -fx-text-fill: #10b981; -fx-font-weight: bold;");
            top.getChildren().addAll(tgap, speakBadge);
        } else if (muted) {
            Label muteBadge = new Label("🔇");
            muteBadge.setStyle("-fx-font-size: 10px; -fx-text-fill: #9ca3af;");
            top.getChildren().addAll(tgap, muteBadge);
        } else if (cameraOn) {
            Label camBadge = new Label("📹");
            camBadge.setStyle("-fx-font-size: 10px; -fx-text-fill: #60a5fa;");
            top.getChildren().addAll(tgap, camBadge);
        } else {
            top.getChildren().add(tgap);
        }

        Region mid = new Region();
        VBox.setVgrow(mid, Priority.ALWAYS);

        Label nameLbl = new Label(name);
        nameLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: white;");

        tile.getChildren().addAll(top, mid, nameLbl);
        return tile;
    }

    private void renderMaterialsTab(CourseRepository.Course course, VBox container) {
        VBox root = new VBox(14);
        VBox.setVgrow(root, Priority.ALWAYS);

        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Tài liệu, Đề ôn tập & Slide bài giảng");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: -ink;");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        Button addBtn = new Button("＋ Thêm tài liệu mới");
        addBtn.getStyleClass().addAll("button", "button-primary");
        addBtn.setOnAction(e -> showAddMaterialDialog(course.id(), () -> renderMaterialsTab(course, container)));

        bar.getChildren().addAll(title, gap, addBtn);
        root.getChildren().add(bar);

        List<CourseRepository.Material> materials = courseRepo.materialsOf(course.id());
        VBox list = new VBox(10);
        list.setPadding(new Insets(6, 0, 16, 0));

        if (materials.isEmpty()) {
            Label empty = new Label("Chưa có tài liệu nào trong phòng học.");
            empty.getStyleClass().add("muted");
            list.getChildren().add(empty);
        } else {
            for (CourseRepository.Material m : materials) {
                HBox item = new HBox(14);
                item.setAlignment(Pos.CENTER_LEFT);
                item.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-padding: 14 18; -fx-border-color: -line; -fx-border-radius: 12;");

                String icon = switch (m.fileType().toUpperCase()) {
                    case "SLIDE" -> "📑";
                    case "PDF" -> "📕";
                    case "EXAM" -> "📝";
                    default -> "📁";
                };

                Label iconLbl = new Label(icon);
                iconLbl.setStyle("-fx-font-size: 24px;");

                VBox info = new VBox(4);
                HBox.setHgrow(info, Priority.ALWAYS);
                Label nameLbl = new Label(m.title());
                nameLbl.setStyle("-fx-font-size: 14px; -fx-font-weight: 800; -fx-text-fill: -ink;");
                Label metaLbl = new Label(m.fileSize() + "  ·  Đăng bởi @" + m.uploadedBy());
                metaLbl.getStyleClass().add("muted");
                metaLbl.setStyle("-fx-font-size: 12px;");
                info.getChildren().addAll(nameLbl, metaLbl);

                Button viewBtn = new Button("Xem trước 👁");
                viewBtn.getStyleClass().add("button");
                viewBtn.setOnAction(e -> toast("Đang mở xem trước: " + m.title()));

                Button downloadBtn = new Button("Tải về ⬇");
                downloadBtn.getStyleClass().addAll("button", "button-primary");
                downloadBtn.setOnAction(e -> toast("Đang tải tài liệu: " + m.title() + " về máy..."));

                item.getChildren().addAll(iconLbl, info, viewBtn, downloadBtn);
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
        root.setPadding(new Insets(16, 20, 16, 20));
        root.setMinWidth(400);

        Label title = new Label("Thêm tài liệu hoặc Slide mới");
        title.getStyleClass().add("dialog-title");

        TextField titleInput = new TextField();
        titleInput.setPromptText("Tên tài liệu / Slide (VD: Đề thi thử THPT Quốc Gia số 1)");
        titleInput.getStyleClass().add("input");

        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("SLIDE", "PDF", "EXAM", "TÀI LIỆU");
        typeCombo.setValue("SLIDE");
        typeCombo.setMaxWidth(Double.MAX_VALUE);

        TextField sizeInput = new TextField();
        sizeInput.setPromptText("Kích thước / Ghi chú (VD: 15 Trang · 3.2 MB)");
        sizeInput.getStyleClass().add("input");

        Button submit = new Button("Thêm vào phòng");
        submit.getStyleClass().addAll("button", "button-primary");
        submit.setMaxWidth(Double.MAX_VALUE);

        submit.setOnAction(e -> {
            if (!titleInput.getText().isBlank()) {
                courseRepo.addMaterial(
                    courseId,
                    titleInput.getText().trim(),
                    typeCombo.getValue(),
                    sizeInput.getText().isBlank() ? "Tài liệu trực tuyến" : sizeInput.getText().trim(),
                    user.username()
                );
                dialog.close();
                onAdded.run();
                toast("Đã thêm tài liệu mới thành công!");
            }
        });

        root.getChildren().addAll(title, titleInput, typeCombo, sizeInput, submit);
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
    private void showProfile() { base("Hồ sơ", "Thiết lập không gian học tập của bạn"); VBox card = new VBox(12, new Label("" + user.displayName()), new Label("@" + user.username()), new Separator(), new Label("Trạng thái: Đang hoạt động")); card.getStyleClass().add("profile-card"); content.getChildren().add(card); }
    private Button iconButton(String icon, String hint) { Button b = new Button(icon); b.setAccessibleText(hint); b.setTooltip(new Tooltip(hint)); b.getStyleClass().add("icon-button"); return b; }
    private void connectConfiguredPeer() {
        String peer = System.getProperty("studyroom.peer", "").trim();
        if (peer.isEmpty()) return;
        String[] hostPort = peer.split(":", 2);
        try { node.connect(hostPort[0], hostPort.length == 2 ? Integer.parseInt(hostPort[1]) : 5050); toast("Đã nối peer " + peer); }
        catch (IOException | NumberFormatException e) { toast("Chưa thể nối peer " + peer); }
    }
    private void toast(String message) { Label note = new Label(message); note.getStyleClass().add("toast"); content.getChildren().add(note); }
    @Override public void stop() { if (node != null) node.close(); }
    public static void main(String[] args) { launch(args); }
}
