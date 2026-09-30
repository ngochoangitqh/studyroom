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
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public final class StudyroomApp extends Application {
    private final Database database = new Database();
    private final AuthStore auth = new AuthStore(database);
    private final ChatRepository chatRepository = new ChatRepository(database);
    private final FriendRepository friendRepo = new FriendRepository(database);
    private final CallRepository callRepo = new CallRepository(database);
    private final BorderPane shell = new BorderPane();
    private final VBox content = new VBox();
    private User user;
    private TcpChatNode node;
    private VBox messages;
    private String selectedRoomId;
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

        messages = new VBox(16); messages.getStyleClass().add("messages"); messages.setPadding(new Insets(30, 28, 22, 28));
        List<ChatRepository.Message> history = chatRepository.recent(roomId, 50);
        history.forEach(item -> addMessage(roomId, item.sender(), item.body(), item.sender().equals(user.displayName()), false));
        if (history.isEmpty()) { Label empty = new Label("Chưa có tin nhắn. Hãy bắt đầu cuộc trò chuyện."); empty.getStyleClass().add("empty-conversation"); messages.getChildren().add(empty); }
        Label today = new Label("Hôm nay"); today.getStyleClass().add("day-label"); messages.getChildren().add(0, today); VBox.setMargin(today, new Insets(0, 0, 12, 0));
        ScrollPane scroll = new ScrollPane(messages); scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.getStyleClass().add("message-scroll"); VBox.setVgrow(scroll, Priority.ALWAYS);
        HBox composerShell = new HBox(8); composerShell.getStyleClass().add("composer-shell"); Button attach = iconButton("⌇", "Đính kèm tệp"); TextField composer = new TextField(); composer.setPromptText("Nhắn tin..."); composer.getStyleClass().add("composer"); Button emoji = iconButton("☺", "Biểu tượng cảm xúc"); Button send = iconButton("➤", "Gửi tin nhắn"); send.getStyleClass().add("send-icon"); Runnable sendMessage = () -> { if (!composer.getText().isBlank()) { String text = composer.getText().trim(); addMessage(roomId, user.displayName(), text, true, true); if (node != null) node.broadcast(user.displayName(), text); composer.clear(); } }; send.setOnAction(e -> sendMessage.run()); composer.setOnAction(e -> sendMessage.run()); HBox.setHgrow(composer, Priority.ALWAYS); composerShell.getChildren().addAll(attach, composer, emoji, send);
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
            session = callRepo.startCall(roomId, roomName, user.username(), user.displayName(), callType, 5100);
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

    private void showRoom() { base("Phòng chung · Ôn thi Hóa Hữu Cơ", "6 người đang có mặt"); HBox body = new HBox(18); VBox.setVgrow(body, Priority.ALWAYS); VBox deck = new VBox(14); deck.getStyleClass().add("deck"); HBox.setHgrow(deck, Priority.ALWAYS); Label slide = new Label("Phản ứng Este hóa\n\nEste được tạo thành từ phản ứng giữa axit cacboxylic và ancol, trong môi trường axit.\n\nR—COOH  +  R'—OH   ⇌   R—COOR'  +  H₂O\n\nLưu ý\n• Phản ứng thuận nghịch\n• Cần môi trường axit và đun nóng"); slide.getStyleClass().add("slide"); VBox.setVgrow(slide, Priority.ALWAYS); HBox actions = new HBox(8); Button present = new Button("▣  Trình chiếu"); present.getStyleClass().addAll("button", "button-primary"); Button board = new Button("✎  Bảng trắng"); board.getStyleClass().add("button"); board.setOnAction(e -> toast("Bảng trắng đồng bộ là hạng mục kế tiếp của room protocol.")); actions.getChildren().addAll(present, board); deck.getChildren().addAll(new Label("Slide 4 / 12"), slide, actions); VBox people = new VBox(10, new Label("Thành viên")); people.getStyleClass().add("people"); for (String n : List.of("Lan · đang nói", "Minh · camera bật", "Huy · đã tắt mic", "Chi · đã tắt mic", "Bạn")) { Label p = new Label("●  " + n); p.getStyleClass().add("person"); people.getChildren().add(p); } body.getChildren().addAll(deck, people); content.getChildren().add(body); }
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
