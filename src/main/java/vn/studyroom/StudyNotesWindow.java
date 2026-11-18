package vn.studyroom;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Floating & Integrated Study Notes + Task Checklist view.
 * Designed with a soft, rounded Notion/Craft-inspired aesthetic.
 */
public final class StudyNotesWindow {
    private static StudyNotesWindow instance;

    public static synchronized StudyNotesWindow getInstance() {
        if (instance == null) {
            instance = new StudyNotesWindow();
        }
        return instance;
    }

    public record NoteItem(String id, String username, String title, String content, String todoText, String updatedAt) {}

    private Stage stage;
    private Database database;
    private User currentUser;
    private Consumer<String> toastCallback;

    private VBox noteListContainer;
    private Label noteCountBadge;
    private TextField searchNoteField;
    private TextField titleField;
    private TextArea contentArea;
    private Label wordCountLbl;
    private VBox checklistBox;
    private Label todoProgressLbl;
    private ProgressBar todoProgressBar;
    private TextField newTodoField;
    private Label statusLbl;

    private final List<NoteItem> notes = new ArrayList<>();
    private NoteItem activeNote = null;
    private boolean isLoadingNote = false;

    private StudyNotesWindow() {}

    public void show(Database database, User user, Consumer<String> toastCallback) {
        this.database = database;
        this.currentUser = user;
        this.toastCallback = toastCallback;

        if (stage != null && stage.isShowing()) {
            loadNotesFromDb();
            stage.toFront();
            stage.requestFocus();
            return;
        }

        stage = new Stage();
        stage.setTitle("📝 Sổ tay ghi chú & Mục tiêu học tập · " + user.displayName());
        stage.setAlwaysOnTop(false);

        BorderPane root = buildContentPane();
        Scene scene = new Scene(root, 980, 620);
        try {
            var tUrl = getClass().getResource("/tokens.css");
            var sUrl = getClass().getResource("/studyroom.css");
            if (tUrl != null) scene.getStylesheets().add(tUrl.toExternalForm());
            if (sUrl != null) scene.getStylesheets().add(sUrl.toExternalForm());
        } catch (Exception ignored) {}

        stage.setScene(scene);
        loadNotesFromDb();
        stage.show();
    }

    public BorderPane buildEmbeddedPane(Database database, User user, Consumer<String> toastCallback) {
        this.database = database;
        this.currentUser = user;
        this.toastCallback = toastCallback;
        BorderPane pane = buildContentPane();
        loadNotesFromDb();
        return pane;
    }

    private BorderPane buildContentPane() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: transparent;");

        HBox mainLayout = new HBox(16);
        mainLayout.setPadding(new Insets(4));
        VBox.setVgrow(mainLayout, Priority.ALWAYS);

        // =========================================================================
        // 1. LEFT CARD: Notebook List Sidebar (Soft Floating Card)
        // =========================================================================
        VBox sidebarCard = new VBox(12);
        sidebarCard.setPrefWidth(250);
        sidebarCard.setMinWidth(235);
        sidebarCard.setMaxWidth(265);
        sidebarCard.setPadding(new Insets(18, 16, 18, 16));
        sidebarCard.setStyle(
            "-fx-background-color: white;" +
            "-fx-background-radius: 20;" +
            "-fx-border-color: #eef2f6;" +
            "-fx-border-radius: 20;" +
            "-fx-effect: dropshadow(gaussian, rgba(15, 23, 42, 0.04), 16, 0, 0, 4);"
        );

        HBox sideHeader = new HBox(8);
        sideHeader.setAlignment(Pos.CENTER_LEFT);
        Label sideTitle = new Label("📚 Sổ tay của tôi");
        sideTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");
        Region sideSpacer = new Region();
        HBox.setHgrow(sideSpacer, Priority.ALWAYS);
        noteCountBadge = new Label("1 trang");
        noteCountBadge.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #64748b; -fx-font-size: 11px; -fx-font-weight: 700; -fx-padding: 3 9; -fx-background-radius: 999;");
        sideHeader.getChildren().addAll(sideTitle, sideSpacer, noteCountBadge);

        Button newNoteBtn = new Button("＋  Tạo trang ghi chú mới");
        newNoteBtn.setMaxWidth(Double.MAX_VALUE);
        newNoteBtn.setMinHeight(38);
        newNoteBtn.setMaxHeight(38);
        newNoteBtn.setStyle(
            "-fx-background-color: linear-gradient(to right, #6366f1, #8b5cf6);" +
            "-fx-text-fill: white;" +
            "-fx-font-weight: 700;" +
            "-fx-font-size: 12.5px;" +
            "-fx-background-radius: 13;" +
            "-fx-border-color: transparent;" +
            "-fx-cursor: hand;" +
            "-fx-effect: dropshadow(gaussian, rgba(99, 102, 241, 0.25), 10, 0, 0, 3);"
        );
        newNoteBtn.setOnMouseEntered(e -> newNoteBtn.setStyle(
            "-fx-background-color: linear-gradient(to right, #4f46e5, #7c3aed);" +
            "-fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 12.5px;" +
            "-fx-background-radius: 13; -fx-border-color: transparent; -fx-cursor: hand;" +
            "-fx-effect: dropshadow(gaussian, rgba(99, 102, 241, 0.35), 12, 0, 0, 4);"
        ));
        newNoteBtn.setOnMouseExited(e -> newNoteBtn.setStyle(
            "-fx-background-color: linear-gradient(to right, #6366f1, #8b5cf6);" +
            "-fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 12.5px;" +
            "-fx-background-radius: 13; -fx-border-color: transparent; -fx-cursor: hand;" +
            "-fx-effect: dropshadow(gaussian, rgba(99, 102, 241, 0.25), 10, 0, 0, 3);"
        ));
        newNoteBtn.setOnAction(e -> createNewNote());

        searchNoteField = new TextField();
        searchNoteField.setPromptText("🔍 Tìm nhanh ghi chú...");
        searchNoteField.setMinHeight(34);
        searchNoteField.setMaxHeight(34);
        searchNoteField.setStyle(
            "-fx-background-color: #f8fafc;" +
            "-fx-background-radius: 11;" +
            "-fx-border-color: #e2e8f0;" +
            "-fx-border-radius: 11;" +
            "-fx-padding: 0 12;" +
            "-fx-font-size: 12px;" +
            "-fx-text-fill: #334155;"
        );
        searchNoteField.textProperty().addListener((obs, oldV, newV) -> refreshNoteListUI());

        noteListContainer = new VBox(8);
        noteListContainer.setPadding(new Insets(2, 2, 8, 0));
        ScrollPane listScroll = new ScrollPane(noteListContainer);
        listScroll.setFitToWidth(true);
        listScroll.getStyleClass().addAll("thread-scroll", "soft-scroll");
        VBox.setVgrow(listScroll, Priority.ALWAYS);

        sidebarCard.getChildren().addAll(sideHeader, newNoteBtn, searchNoteField, listScroll);

        // =========================================================================
        // 2. CENTER CARD: Main Note Editor (Soft Floating Card)
        // =========================================================================
        VBox editorCard = new VBox(12);
        editorCard.setPadding(new Insets(18, 22, 16, 22));
        HBox.setHgrow(editorCard, Priority.ALWAYS);
        editorCard.setStyle(
            "-fx-background-color: white;" +
            "-fx-background-radius: 20;" +
            "-fx-border-color: #eef2f6;" +
            "-fx-border-radius: 20;" +
            "-fx-effect: dropshadow(gaussian, rgba(15, 23, 42, 0.04), 16, 0, 0, 4);"
        );

        // Top Bar: Title Input + Soft Action Pills
        HBox topBar = new HBox(10);
        topBar.setAlignment(Pos.CENTER_LEFT);

        titleField = new TextField();
        titleField.setPromptText("Tiêu đề trang ghi chú...");
        titleField.setMinHeight(40);
        titleField.setMaxHeight(40);
        titleField.setStyle(
            "-fx-font-size: 16px;" +
            "-fx-font-weight: 800;" +
            "-fx-text-fill: #0f172a;" +
            "-fx-background-color: #f8fafc;" +
            "-fx-border-color: #e2e8f0;" +
            "-fx-border-radius: 13;" +
            "-fx-background-radius: 13;" +
            "-fx-padding: 0 14;"
        );
        HBox.setHgrow(titleField, Priority.ALWAYS);

        Button saveBtn = createSoftActionPill("✔ Lưu lại", "#ecfdf5", "#059669", "#a7f3d0", "#d1fae5");
        saveBtn.setOnAction(e -> saveCurrentNote(true));

        Button exportBtn = createSoftActionPill("⬇ Xuất .MD", "#eff6ff", "#2563eb", "#bfdbfe", "#dbeafe");
        exportBtn.setOnAction(e -> exportCurrentNoteToMarkdown());

        Button deleteBtn = createSoftActionPill("🗑 Xóa", "#fff1f2", "#e11d48", "#fecdd3", "#ffe4e6");
        deleteBtn.setOnAction(e -> deleteCurrentNote());

        topBar.getChildren().addAll(titleField, saveBtn, exportBtn, deleteBtn);

        // Quick Formatting / Insert Chips Bar
        HBox formatBar = new HBox(6);
        formatBar.setAlignment(Pos.CENTER_LEFT);
        formatBar.setPadding(new Insets(2, 0, 2, 0));

        Button h1Chip = createFormatChip("📌 Tiêu đề mục", "\n# Tiêu đề phần học\n");
        Button listChip = createFormatChip("• Danh sách ý", "\n- Ý chính 1\n- Ý chính 2\n");
        Button noteChip = createFormatChip("💡 Lưu ý quan trọng", "\n> 💡 Lưu ý: ");
        Button mathChip = createFormatChip("📐 Công thức", "\n📐 Công thức: ");
        Button timeChip = createFormatChip("🕒 Chèn giờ", "");
        timeChip.setOnAction(e -> {
            String tStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm - dd/MM/yyyy"));
            insertAtCaret(" [" + tStr + "] ");
        });

        Region fmtSpacer = new Region();
        HBox.setHgrow(fmtSpacer, Priority.ALWAYS);

        wordCountLbl = new Label("0 từ · 0 ký tự");
        wordCountLbl.setStyle("-fx-font-size: 11px; -fx-font-weight: 600; -fx-text-fill: #94a3b8; -fx-background-color: #f8fafc; -fx-padding: 3 10; -fx-background-radius: 999;");

        formatBar.getChildren().addAll(h1Chip, listChip, noteChip, mathChip, timeChip, fmtSpacer, wordCountLbl);

        // Text Area Editor
        contentArea = new TextArea();
        contentArea.setPromptText("Ghi chép kiến thức, định lý, công thức hoặc tóm tắt bài giảng tại đây...\n\n• Bạn có thể dùng các nút chèn nhanh phía trên để trình bày đẹp mắt.\n• Nội dung được tự động lưu an toàn.");
        contentArea.setWrapText(true);
        contentArea.getStyleClass().add("soft-textarea");
        VBox.setVgrow(contentArea, Priority.ALWAYS);
        contentArea.textProperty().addListener((obs, oldV, newV) -> {
            updateWordCount(newV);
        });

        // Bottom Status Bar
        HBox bottomBar = new HBox(8);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        Label dot = new Label("●");
        dot.setStyle("-fx-text-fill: #10b981; -fx-font-size: 10px;");
        statusLbl = new Label("Đã lưu tự động · Sổ tay học tập thông minh");
        statusLbl.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #64748b; -fx-font-weight: 500;");
        bottomBar.getChildren().addAll(dot, statusLbl);

        editorCard.getChildren().addAll(topBar, formatBar, contentArea, bottomBar);

        // =========================================================================
        // 3. RIGHT CARD: Study Goals & Checklist (Soft Floating Card)
        // =========================================================================
        VBox todoCard = new VBox(12);
        todoCard.setPrefWidth(275);
        todoCard.setMinWidth(260);
        todoCard.setMaxWidth(290);
        todoCard.setPadding(new Insets(18, 16, 18, 16));
        todoCard.setStyle(
            "-fx-background-color: white;" +
            "-fx-background-radius: 20;" +
            "-fx-border-color: #eef2f6;" +
            "-fx-border-radius: 20;" +
            "-fx-effect: dropshadow(gaussian, rgba(15, 23, 42, 0.04), 16, 0, 0, 4);"
        );

        HBox todoHeader = new HBox(8);
        todoHeader.setAlignment(Pos.CENTER_LEFT);
        Label todoTitle = new Label("🎯 Mục tiêu cần làm");
        todoTitle.setStyle("-fx-font-size: 14.5px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");
        Region todoGap = new Region();
        HBox.setHgrow(todoGap, Priority.ALWAYS);
        todoProgressLbl = new Label("0/0");
        todoProgressLbl.setStyle("-fx-background-color: #eef2ff; -fx-text-fill: #4f46e5; -fx-font-size: 11px; -fx-font-weight: 800; -fx-padding: 3 9; -fx-background-radius: 999;");
        todoHeader.getChildren().addAll(todoTitle, todoGap, todoProgressLbl);

        todoProgressBar = new ProgressBar(0);
        todoProgressBar.setMaxWidth(Double.MAX_VALUE);
        todoProgressBar.getStyleClass().add("soft-progress");

        HBox addTodoRow = new HBox(6);
        addTodoRow.setAlignment(Pos.CENTER_LEFT);
        newTodoField = new TextField();
        newTodoField.setPromptText("Thêm mục tiêu mới...");
        newTodoField.setMinHeight(34);
        newTodoField.setMaxHeight(34);
        newTodoField.setStyle(
            "-fx-background-color: #f8fafc;" +
            "-fx-background-radius: 11;" +
            "-fx-border-color: #e2e8f0;" +
            "-fx-border-radius: 11;" +
            "-fx-padding: 0 11;" +
            "-fx-font-size: 12px;"
        );
        HBox.setHgrow(newTodoField, Priority.ALWAYS);

        Button addTodoBtn = new Button("＋ Thêm");
        addTodoBtn.setMinSize(Region.USE_PREF_SIZE, 34);
        addTodoBtn.setMaxHeight(34);
        addTodoBtn.setStyle(
            "-fx-background-color: #6366f1;" +
            "-fx-text-fill: white;" +
            "-fx-font-weight: 700;" +
            "-fx-font-size: 12px;" +
            "-fx-background-radius: 11;" +
            "-fx-border-color: transparent;" +
            "-fx-padding: 0 12;" +
            "-fx-cursor: hand;"
        );
        Runnable addTodoAction = () -> {
            String t = newTodoField.getText().trim();
            if (!t.isBlank()) {
                addChecklistRow(false, t);
                newTodoField.clear();
                updateTodoProgress();
                saveCurrentNote(false);
            }
        };
        addTodoBtn.setOnAction(e -> addTodoAction.run());
        newTodoField.setOnAction(e -> addTodoAction.run());
        addTodoRow.getChildren().addAll(newTodoField, addTodoBtn);

        checklistBox = new VBox(8);
        checklistBox.setPadding(new Insets(2, 2, 8, 0));
        ScrollPane todoScroll = new ScrollPane(checklistBox);
        todoScroll.setFitToWidth(true);
        todoScroll.getStyleClass().addAll("thread-scroll", "soft-scroll");
        VBox.setVgrow(todoScroll, Priority.ALWAYS);

        todoCard.getChildren().addAll(todoHeader, todoProgressBar, addTodoRow, todoScroll);

        mainLayout.getChildren().addAll(sidebarCard, editorCard, todoCard);
        root.setCenter(mainLayout);

        return root;
    }

    private Button createSoftActionPill(String text, String bgHex, String textHex, String borderHex, String hoverBgHex) {
        Button btn = new Button(text);
        btn.setMinSize(Region.USE_PREF_SIZE, 36);
        btn.setMaxHeight(36);
        String normalStyle = String.format(
            "-fx-background-color: %s; -fx-text-fill: %s; -fx-border-color: %s;" +
            "-fx-border-radius: 12; -fx-background-radius: 12; -fx-font-weight: 700;" +
            "-fx-font-size: 12px; -fx-padding: 0 14; -fx-cursor: hand;",
            bgHex, textHex, borderHex
        );
        String hoverStyle = String.format(
            "-fx-background-color: %s; -fx-text-fill: %s; -fx-border-color: %s;" +
            "-fx-border-radius: 12; -fx-background-radius: 12; -fx-font-weight: 700;" +
            "-fx-font-size: 12px; -fx-padding: 0 14; -fx-cursor: hand;",
            hoverBgHex, textHex, borderHex
        );
        btn.setStyle(normalStyle);
        btn.setOnMouseEntered(e -> btn.setStyle(hoverStyle));
        btn.setOnMouseExited(e -> btn.setStyle(normalStyle));
        return btn;
    }

    private Button createFormatChip(String label, String snippet) {
        Button chip = new Button(label);
        chip.setMinSize(Region.USE_PREF_SIZE, 28);
        chip.setMaxHeight(28);
        String normal = "-fx-background-color: #f8fafc; -fx-text-fill: #475569; -fx-border-color: #e2e8f0; -fx-border-radius: 999; -fx-background-radius: 999; -fx-font-size: 11.5px; -fx-font-weight: 600; -fx-padding: 0 11; -fx-cursor: hand;";
        String hover  = "-fx-background-color: #eef2ff; -fx-text-fill: #4f46e5; -fx-border-color: #c7d2fe; -fx-border-radius: 999; -fx-background-radius: 999; -fx-font-size: 11.5px; -fx-font-weight: 600; -fx-padding: 0 11; -fx-cursor: hand;";
        chip.setStyle(normal);
        chip.setOnMouseEntered(e -> chip.setStyle(hover));
        chip.setOnMouseExited(e -> chip.setStyle(normal));
        if (!snippet.isEmpty()) {
            chip.setOnAction(e -> insertAtCaret(snippet));
        }
        return chip;
    }

    private void insertAtCaret(String text) {
        if (contentArea == null) return;
        int pos = contentArea.getCaretPosition();
        contentArea.insertText(pos, text);
        contentArea.requestFocus();
    }

    private void updateWordCount(String text) {
        if (wordCountLbl == null) return;
        if (text == null || text.isBlank()) {
            wordCountLbl.setText("0 từ · 0 ký tự");
            return;
        }
        String trimmed = text.trim();
        int words = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
        wordCountLbl.setText(words + " từ · " + text.length() + " ký tự");
    }

    private void addChecklistRow(boolean checked, String text) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 10, 8, 10));

        String uncheckedRowStyle = "-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 12; -fx-background-radius: 12;";
        String checkedRowStyle   = "-fx-background-color: #f0fdf4; -fx-border-color: #bbf7d0; -fx-border-radius: 12; -fx-background-radius: 12;";
        row.setStyle(checked ? checkedRowStyle : uncheckedRowStyle);

        CheckBox cb = new CheckBox(text);
        cb.setSelected(checked);
        cb.setWrapText(true);
        cb.setMaxWidth(185);
        cb.getStyleClass().add("soft-checkbox");
        cb.setStyle(checked
            ? "-fx-text-fill: #64748b; -fx-strikethrough: true; -fx-font-size: 12.5px; -fx-font-weight: 500;"
            : "-fx-text-fill: #1e293b; -fx-font-size: 12.5px; -fx-font-weight: 600;");

        cb.selectedProperty().addListener((obs, oldV, newV) -> {
            cb.setStyle(newV
                ? "-fx-text-fill: #64748b; -fx-strikethrough: true; -fx-font-size: 12.5px; -fx-font-weight: 500;"
                : "-fx-text-fill: #1e293b; -fx-font-size: 12.5px; -fx-font-weight: 600;");
            row.setStyle(newV ? checkedRowStyle : uncheckedRowStyle);
            updateTodoProgress();
            if (!isLoadingNote) {
                saveCurrentNote(false);
            }
        });
        HBox.setHgrow(cb, Priority.ALWAYS);

        Button rmBtn = new Button("×");
        rmBtn.setMinSize(24, 24);
        rmBtn.setPrefSize(24, 24);
        rmBtn.setMaxSize(24, 24);
        String rmNormal = "-fx-background-color: transparent; -fx-text-fill: #94a3b8; -fx-font-size: 14px; -fx-font-weight: 700; -fx-padding: 0; -fx-background-radius: 999; -fx-border-color: transparent; -fx-cursor: hand;";
        String rmHover  = "-fx-background-color: #fee2e2; -fx-text-fill: #e11d48; -fx-font-size: 14px; -fx-font-weight: 700; -fx-padding: 0; -fx-background-radius: 999; -fx-border-color: transparent; -fx-cursor: hand;";
        rmBtn.setStyle(rmNormal);
        rmBtn.setOnMouseEntered(e -> rmBtn.setStyle(rmHover));
        rmBtn.setOnMouseExited(e -> rmBtn.setStyle(rmNormal));
        rmBtn.setOnAction(e -> {
            checklistBox.getChildren().remove(row);
            updateTodoProgress();
            saveCurrentNote(false);
        });

        row.getChildren().addAll(cb, rmBtn);
        row.setUserData(cb);
        checklistBox.getChildren().add(row);
    }

    private void updateTodoProgress() {
        if (checklistBox == null || todoProgressLbl == null || todoProgressBar == null) return;
        int total = 0;
        int done = 0;
        for (var node : checklistBox.getChildren()) {
            if (node instanceof HBox row && row.getUserData() instanceof CheckBox cb) {
                total++;
                if (cb.isSelected()) done++;
            }
        }
        todoProgressLbl.setText(done + "/" + total + " hoàn thành");
        todoProgressBar.setProgress(total == 0 ? 0 : (double) done / total);
    }

    private String serializeChecklist() {
        StringBuilder sb = new StringBuilder();
        for (var node : checklistBox.getChildren()) {
            if (node instanceof HBox row && row.getUserData() instanceof CheckBox cb) {
                sb.append(cb.isSelected() ? "[x] " : "[ ] ").append(cb.getText().replace("\n", " ")).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private void deserializeChecklist(String raw) {
        checklistBox.getChildren().clear();
        if (raw != null && !raw.isBlank()) {
            for (String line : raw.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[x] ")) {
                    addChecklistRow(true, trimmed.substring(4));
                } else if (trimmed.startsWith("[ ] ")) {
                    addChecklistRow(false, trimmed.substring(4));
                } else if (!trimmed.isEmpty()) {
                    addChecklistRow(false, trimmed);
                }
            }
        }
        updateTodoProgress();
    }

    private void loadNotesFromDb() {
        if (database == null || currentUser == null) return;
        notes.clear();
        String sql = "SELECT note_id, username, title, content, todo_text, TO_CHAR(updated_at, 'DD/MM HH24:MI') FROM study_note WHERE username = ? ORDER BY updated_at DESC";
        try (Connection c = database.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, currentUser.username());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                notes.add(new NoteItem(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6)
                ));
            }
        } catch (Exception ignored) {}

        if (notes.isEmpty()) {
            createNewNote();
        } else {
            selectNote(notes.get(0));
            refreshNoteListUI();
        }
    }

    private void refreshNoteListUI() {
        if (noteListContainer == null) return;
        noteListContainer.getChildren().clear();
        if (noteCountBadge != null) {
            noteCountBadge.setText(notes.size() + " trang");
        }

        String filter = searchNoteField != null && searchNoteField.getText() != null
            ? searchNoteField.getText().trim().toLowerCase()
            : "";

        for (NoteItem item : notes) {
            if (!filter.isEmpty()) {
                boolean matchTitle = item.title() != null && item.title().toLowerCase().contains(filter);
                boolean matchContent = item.content() != null && item.content().toLowerCase().contains(filter);
                if (!matchTitle && !matchContent) continue;
            }

            boolean isSel = activeNote != null && activeNote.id().equals(item.id());
            VBox card = new VBox(4);
            card.setPadding(new Insets(10, 12, 10, 12));

            String selStyle = "-fx-background-color: linear-gradient(to bottom right, #eef2ff, #f5f3ff); -fx-border-color: #818cf8; -fx-border-width: 1.5; -fx-border-radius: 13; -fx-background-radius: 13; -fx-cursor: hand;";
            String unselStyle = "-fx-background-color: #f8fafc; -fx-border-color: #eef2f6; -fx-border-width: 1; -fx-border-radius: 13; -fx-background-radius: 13; -fx-cursor: hand;";
            card.setStyle(isSel ? selStyle : unselStyle);

            Label tLbl = new Label(item.title().isBlank() ? "Trang ghi chú chưa đặt tên" : item.title());
            tLbl.setStyle("-fx-font-weight: 800; -fx-font-size: 13px; -fx-text-fill: " + (isSel ? "#3730a3;" : "#1e293b;"));

            String previewRaw = item.content() != null
                ? item.content().replaceAll("[#*>-]", "").replaceAll("\\s+", " ").trim()
                : "";
            if (previewRaw.isEmpty()) previewRaw = "Chưa có nội dung...";
            if (previewRaw.length() > 38) previewRaw = previewRaw.substring(0, 38) + "...";

            Label previewLbl = new Label(previewRaw);
            previewLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: " + (isSel ? "#6366f1;" : "#64748b;"));

            Label timeLbl = new Label("🕒 " + (item.updatedAt() != null ? item.updatedAt() : "Mới tạo"));
            timeLbl.setStyle("-fx-font-size: 10.5px; -fx-text-fill: #94a3b8; -fx-font-weight: 600;");

            card.getChildren().addAll(tLbl, previewLbl, timeLbl);
            card.setOnMouseClicked(e -> {
                saveCurrentNote(false);
                selectNote(item);
                refreshNoteListUI();
            });
            noteListContainer.getChildren().add(card);
        }
    }

    private void selectNote(NoteItem item) {
        isLoadingNote = true;
        try {
            this.activeNote = item;
            titleField.setText(item.title());
            contentArea.setText(item.content());
            updateWordCount(item.content());
            deserializeChecklist(item.todoText());
        } finally {
            isLoadingNote = false;
        }
    }

    private void createNewNote() {
        if (activeNote != null) {
            saveCurrentNote(false);
        }
        String id = "note_" + UUID.randomUUID().toString().substring(0, 8);
        String nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM HH:mm"));
        NoteItem newItem = new NoteItem(
            id,
            currentUser != null ? currentUser.username() : "guest",
            "Ghi chú buổi học (" + nowStr + ")",
            "# Kiến thức trọng tâm\n- \n\n# Công thức / Lưu ý cần nhớ\n- ",
            "[ ] Ôn tập lại lý thuyết sau buổi học\n[ ] Làm bài tập trắc nghiệm củng cố",
            nowStr
        );
        notes.add(0, newItem);
        selectNote(newItem);
        saveCurrentNote(false);
        refreshNoteListUI();
    }

    private void saveCurrentNote(boolean notifyUser) {
        if (isLoadingNote || activeNote == null || database == null || currentUser == null) return;
        String newTitle = titleField.getText().trim();
        if (newTitle.isBlank()) newTitle = "Ghi chú không tiêu đề";
        String newContent = contentArea.getText();
        String newTodo = serializeChecklist();
        String nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM HH:mm"));

        NoteItem updated = new NoteItem(activeNote.id(), currentUser.username(), newTitle, newContent, newTodo, nowStr);
        for (int i = 0; i < notes.size(); i++) {
            if (notes.get(i).id().equals(activeNote.id())) {
                notes.set(i, updated);
                break;
            }
        }
        activeNote = updated;

        String sql = """
            INSERT INTO study_note (note_id, username, title, content, todo_text, updated_at)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (note_id) DO UPDATE SET
                title = EXCLUDED.title,
                content = EXCLUDED.content,
                todo_text = EXCLUDED.todo_text,
                updated_at = CURRENT_TIMESTAMP
        """;
        try (Connection c = database.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, updated.id());
            ps.setString(2, updated.username());
            ps.setString(3, updated.title());
            ps.setString(4, updated.content());
            ps.setString(5, updated.todoText());
            ps.executeUpdate();
            if (statusLbl != null) {
                statusLbl.setText("Đã lưu tự động lúc " + nowStr + " · Sổ tay học tập thông minh");
            }
            if (notifyUser && toastCallback != null) {
                toastCallback.accept("Đã lưu sổ tay \"" + newTitle + "\"");
            }
        } catch (Exception ignored) {}
        refreshNoteListUI();
    }

    private void deleteCurrentNote() {
        if (activeNote == null || database == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Xóa trang ghi chú");
        confirm.setHeaderText("Xóa \"" + activeNote.title() + "\"?");
        confirm.setContentText("Bạn có chắc muốn xóa trang ghi chú này không?");
        if (stage != null && stage.isShowing()) confirm.initOwner(stage);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try (Connection c = database.connect(); PreparedStatement ps = c.prepareStatement("DELETE FROM study_note WHERE note_id = ?")) {
                ps.setString(1, activeNote.id());
                ps.executeUpdate();
            } catch (Exception ignored) {}
            notes.removeIf(n -> n.id().equals(activeNote.id()));
            activeNote = null;
            if (notes.isEmpty()) {
                createNewNote();
            } else {
                selectNote(notes.get(0));
                refreshNoteListUI();
            }
        }
    }

    private void exportCurrentNoteToMarkdown() {
        if (activeNote == null) return;
        saveCurrentNote(false);
        FileChooser fc = new FileChooser();
        fc.setTitle("Xuất ghi chú ra tệp Markdown (.md)");
        fc.setInitialFileName(activeNote.title().replaceAll("[^a-zA-Z0-9\\-_\\p{L} ]", "_") + ".md");
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Markdown File (*.md)", "*.md"),
            new FileChooser.ExtensionFilter("Text File (*.txt)", "*.txt")
        );
        File file = fc.showSaveDialog(stage);
        if (file != null) {
            try {
                StringBuilder md = new StringBuilder();
                md.append("# ").append(activeNote.title()).append("\n");
                md.append("> Người tạo: @").append(activeNote.username()).append(" | Cập nhật: ").append(activeNote.updatedAt()).append("\n\n");
                md.append(activeNote.content()).append("\n\n");
                if (!activeNote.todoText().isBlank()) {
                    md.append("## Danh sách mục tiêu (Checklist)\n");
                    for (String line : activeNote.todoText().split("\n")) {
                        md.append("- ").append(line).append("\n");
                    }
                }
                Files.writeString(file.toPath(), md.toString(), StandardCharsets.UTF_8);
                if (toastCallback != null) toastCallback.accept("Đã xuất tệp: " + file.getName());
            } catch (Exception ex) {
                if (toastCallback != null) toastCallback.accept("Lỗi xuất tệp: " + ex.getMessage());
            }
        }
    }
}
