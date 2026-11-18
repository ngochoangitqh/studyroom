package vn.studyroom;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Modular UI component for StudyBot AI Assistant (Gemini + Offline KaTeX math rendering).
 */
public final class AiAssistantView {
    private final GeminiAiService aiService;
    private final User user;
    private final String userInitials;
    private final Consumer<String> toastCallback;

    public static class StudyAppBridge {
        private final Consumer<String> toastFn;
        public StudyAppBridge(Consumer<String> toastFn) { this.toastFn = toastFn; }
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

    public AiAssistantView(GeminiAiService aiService, User user, String userInitials, Consumer<String> toastCallback) {
        this.aiService = aiService;
        this.user = user;
        this.userInitials = userInitials;
        this.toastCallback = toastCallback;
    }

    private void toast(String msg) {
        if (toastCallback != null) toastCallback.accept(msg);
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

    public void render(VBox content) {
        content.getChildren().clear();
        content.getStyleClass().setAll("workspace");
        content.setPadding(new Insets(18, 26, 16, 26));

        HBox topBar = new HBox(12);
        topBar.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("🤖 Trợ lý Học tập AI · StudyBot");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: 800; -fx-text-fill: -ink;");

        Label badge = new Label("Gemini AI · KaTeX Math");
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

        HBox promptChips = new HBox(8);
        promptChips.setAlignment(Pos.CENTER_LEFT);
        promptChips.setPadding(new Insets(4, 0, 4, 0));

        String[] samplePrompts = {
            "📐 Giải bài tập: Tính khối lượng este thu được khi đun 6g CH3COOH với 4.6g C2H5OH",
            "📝 Tóm tắt lý thuyết trọng tâm chương Este - Lipit Hóa 12",
            "🇬🇧 Sửa lỗi ngữ pháp và viết đoạn văn Tiếng Anh về sở thích",
            "🎯 Cho tôi 3 câu hỏi trắc nghiệm Toán giải tích ôn thi THPT"
        };

        WebView chatWebView = new WebView();
        VBox.setVgrow(chatWebView, Priority.ALWAYS);
        chatWebView.setMinHeight(200);
        chatWebView.setStyle("-fx-border-color: #e2e8f0; -fx-border-radius: 14; -fx-background-radius: 14;");

        WebEngine engine = chatWebView.getEngine();
        boolean[] pageLoaded = { false };
        List<Runnable> pendingScripts = new ArrayList<>();

        Consumer<String> runJs = script -> {
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

        Consumer<String> appendUserBubble = textMsg -> {
            String js = "addUserMessage(" + escapeJsForWeb(textMsg) + ", " + escapeJsForWeb(userInitials) + ");";
            runJs.accept(js);
        };

        Consumer<String> appendBotBubble = replyMsg -> {
            String js = "addBotMessage(" + escapeJsForWeb(replyMsg) + ");";
            runJs.accept(js);
        };

        Consumer<Boolean> setLoading = loading -> {
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

        var chatHtmlUrl = getClass().getResource("/katex/ai_chat.html");
        if (chatHtmlUrl != null) {
            engine.load(chatHtmlUrl.toExternalForm());
        }

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
            if (ke.getCode() == KeyCode.ENTER) {
                if (!ke.isShiftDown()) {
                    ke.consume();
                    doSend.run();
                }
            }
        });

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
}
