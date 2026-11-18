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
 * Floating & Integrated Study Notes + Task Checklist window.
 * Allows students to take lecture notes, manage study checklists, save to PostgreSQL, and export to Markdown (.md).
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
    private TextField titleField;
    private TextArea contentArea;
    private VBox checklistBox;
    private TextField newTodoField;
    private Label statusLbl;

    private final List<NoteItem> notes = new ArrayList<>();
    private NoteItem activeNote = null;

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
        stage.setTitle("📝 Sổ tay ghi chú & Mục tiêu học tập - " + user.displayName());
        stage.setAlwaysOnTop(false);

        BorderPane root = buildContentPane();
        Scene scene = new Scene(root, 860, 580);
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
        root.setStyle("-fx-background-color: #f8fafc;");

        // Left Sidebar: List of notes
        VBox sidebar = new VBox(10);
        sidebar.setPrefWidth(240);
        sidebar.setPadding(new Insets(16));
        sidebar.setStyle("-fx-background-color: white; -fx-border-color: #e2e8f0; -fx-border-width: 0 1 0 0;");

        Label sideTitle = new Label("📚 Danh sách sổ tay");
        sideTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        Button newNoteBtn = new Button("＋ Tạo trang ghi chú mới");
        newNoteBtn.setMaxWidth(Double.MAX_VALUE);
        newNoteBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 12px; -fx-background-radius: 10; -fx-padding: 8 12; -fx-cursor: hand;");
        newNoteBtn.setOnAction(e -> createNewNote());

        noteListContainer = new VBox(6);
        ScrollPane listScroll = new ScrollPane(noteListContainer);
        listScroll.setFitToWidth(true);
        listScroll.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        VBox.setVgrow(listScroll, Priority.ALWAYS);

        sidebar.getChildren().addAll(sideTitle, newNoteBtn, listScroll);
        root.setLeft(sidebar);

        // Right Main Editor
        VBox editor = new VBox(12);
        editor.setPadding(new Insets(18, 22, 18, 22));
        HBox.setHgrow(editor, Priority.ALWAYS);

        // Top bar: Title + Actions
        HBox topBar = new HBox(10);
        topBar.setAlignment(Pos.CENTER_LEFT);

        titleField = new TextField();
        titleField.setPromptText("Tiêu đề bài ghi chú (VD: Công thức Hóa hữu cơ chương 1...)");
        titleField.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-background-color: white; -fx-border-color: #cbd5e1; -fx-border-radius: 10; -fx-background-radius: 10; -fx-padding: 8 14;");
        HBox.setHgrow(titleField, Priority.ALWAYS);

        Button saveBtn = new Button("💾 Lưu lại");
        saveBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: 700; -fx-font-size: 12px; -fx-background-radius: 10; -fx-padding: 8 16; -fx-cursor: hand;");
        saveBtn.setOnAction(e -> saveCurrentNote(true));

        Button exportBtn = new Button("📥 Xuất .MD");
        exportBtn.setStyle("-fx-background-color: #eff6ff; -fx-text-fill: #2563eb; -fx-border-color: #bfdbfe; -fx-border-radius: 10; -fx-font-weight: 700; -fx-font-size: 12px; -fx-background-radius: 10; -fx-padding: 8 14; -fx-cursor: hand;");
        exportBtn.setOnAction(e -> exportCurrentNoteToMarkdown());

        Button deleteBtn = new Button("🗑 Xóa");
        deleteBtn.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #dc2626; -fx-font-weight: 700; -fx-font-size: 12px; -fx-background-radius: 10; -fx-padding: 8 12; -fx-cursor: hand;");
        deleteBtn.setOnAction(e -> deleteCurrentNote());

        topBar.getChildren().addAll(titleField, saveBtn, exportBtn, deleteBtn);

        // Split content: Left = Note TextArea, Right = Checklist To-do
        HBox splitBody = new HBox(14);
        VBox.setVgrow(splitBody, Priority.ALWAYS);

        VBox noteBox = new VBox(6);
        HBox.setHgrow(noteBox, Priority.ALWAYS);
        Label noteLbl = new Label("✍️ Nội dung ghi chú bài học (Hỗ trợ Markdown):");
        noteLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #475569;");

        contentArea = new TextArea();
        contentArea.setPromptText("Ghi chép kiến thức, định lý, lưu ý quan trọng của giáo viên tại đây...\n- Ý chính 1\n- Công thức cần nhớ...");
        contentArea.setWrapText(true);
        contentArea.setStyle("-fx-font-family: 'Segoe UI', sans-serif; -fx-font-size: 13.5px; -fx-background-color: white; -fx-border-color: #cbd5e1; -fx-border-radius: 10; -fx-background-radius: 10;");
        VBox.setVgrow(contentArea, Priority.ALWAYS);
        noteBox.getChildren().addAll(noteLbl, contentArea);

        // Checklist Box
        VBox todoPane = new VBox(8);
        todoPane.setPrefWidth(255);
        todoPane.setMinWidth(240);
        todoPane.setPadding(new Insets(12));
        todoPane.setStyle("-fx-background-color: white; -fx-border-color: #e2e8f0; -fx-border-radius: 12; -fx-background-radius: 12;");

        Label todoTitle = new Label("☑️ Mục tiêu cần hoàn thành");
        todoTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: 800; -fx-text-fill: #1e293b;");

        HBox addTodoRow = new HBox(6);
        newTodoField = new TextField();
        newTodoField.setPromptText("Thêm việc cần làm...");
        newTodoField.setStyle("-fx-font-size: 12px;");
        HBox.setHgrow(newTodoField, Priority.ALWAYS);

        Button addTodoBtn = new Button("＋");
        addTodoBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 800; -fx-background-radius: 8; -fx-cursor: hand;");
        Runnable addTodoAction = () -> {
            String t = newTodoField.getText().trim();
            if (!t.isBlank()) {
                addChecklistRow(false, t);
                newTodoField.clear();
                saveCurrentNote(false);
            }
        };
        addTodoBtn.setOnAction(e -> addTodoAction.run());
        newTodoField.setOnAction(e -> addTodoAction.run());
        addTodoRow.getChildren().addAll(newTodoField, addTodoBtn);

        checklistBox = new VBox(6);
        ScrollPane todoScroll = new ScrollPane(checklistBox);
        todoScroll.setFitToWidth(true);
        todoScroll.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        VBox.setVgrow(todoScroll, Priority.ALWAYS);

        todoPane.getChildren().addAll(todoTitle, addTodoRow, todoScroll);

        splitBody.getChildren().addAll(noteBox, todoPane);

        // Status bar
        HBox bottomBar = new HBox();
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        statusLbl = new Label("💡 Mẹo: Bạn có thể vừa mở sổ tay này vừa xem chia sẻ màn hình hoặc nghe nhạc.");
        statusLbl.setStyle("-fx-font-size: 11.5px; -fx-text-fill: #64748b;");
        bottomBar.getChildren().add(statusLbl);

        editor.getChildren().addAll(topBar, splitBody, bottomBar);
        root.setCenter(editor);

        return root;
    }

    private void addChecklistRow(boolean checked, String text) {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 6, 4, 6));
        row.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 6;");

        CheckBox cb = new CheckBox(text);
        cb.setSelected(checked);
        cb.setWrapText(true);
        cb.setMaxWidth(175);
        cb.setStyle(checked ? "-fx-text-fill: #94a3b8; -fx-strikethrough: true; -fx-font-size: 12px;" : "-fx-text-fill: #1e293b; -fx-font-size: 12px;");
        cb.selectedProperty().addListener((obs, oldV, newV) -> {
            cb.setStyle(newV ? "-fx-text-fill: #94a3b8; -fx-strikethrough: true; -fx-font-size: 12px;" : "-fx-text-fill: #1e293b; -fx-font-size: 12px;");
            saveCurrentNote(false);
        });
        HBox.setHgrow(cb, Priority.ALWAYS);

        Button rmBtn = new Button("✕");
        rmBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #94a3b8; -fx-font-size: 10px; -fx-cursor: hand; -fx-padding: 2 4;");
        rmBtn.setOnAction(e -> {
            checklistBox.getChildren().remove(row);
            saveCurrentNote(false);
        });

        row.getChildren().addAll(cb, rmBtn);
        row.setUserData(cb);
        checklistBox.getChildren().add(row);
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
        if (raw == null || raw.isBlank()) return;
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
        noteListContainer.getChildren().clear();
        for (NoteItem item : notes) {
            boolean isSel = activeNote != null && activeNote.id().equals(item.id());
            VBox card = new VBox(3);
            card.setPadding(new Insets(8, 10, 8, 10));
            card.setStyle(isSel
                ? "-fx-background-color: #eef2ff; -fx-border-color: #6366f1; -fx-border-radius: 8; -fx-background-radius: 8; -fx-cursor: hand;"
                : "-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 8; -fx-background-radius: 8; -fx-cursor: hand;");

            Label tLbl = new Label(item.title().isBlank() ? "Trang ghi chú chưa đặt tên" : item.title());
            tLbl.setStyle("-fx-font-weight: 700; -fx-font-size: 12.5px; -fx-text-fill: " + (isSel ? "#4338ca;" : "#1e293b;"));

            Label timeLbl = new Label("🕒 " + (item.updatedAt() != null ? item.updatedAt() : "Mới tạo"));
            timeLbl.setStyle("-fx-font-size: 10.5px; -fx-text-fill: #64748b;");

            card.getChildren().addAll(tLbl, timeLbl);
            card.setOnMouseClicked(e -> {
                saveCurrentNote(false);
                selectNote(item);
                refreshNoteListUI();
            });
            noteListContainer.getChildren().add(card);
        }
    }

    private void selectNote(NoteItem item) {
        this.activeNote = item;
        titleField.setText(item.title());
        contentArea.setText(item.content());
        deserializeChecklist(item.todoText());
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
        if (activeNote == null || database == null || currentUser == null) return;
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
                statusLbl.setText("✅ Đã lưu tự động lúc " + nowStr);
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
