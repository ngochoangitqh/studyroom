package vn.studyroom;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import netscape.javascript.JSObject;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Modular UI component for StudyBot AI Assistant (Gemini Vision Multimodal + Offline KaTeX math rendering).
 * Supports screen snipping homework problems and uploading exercise images for step-by-step solutions.
 */
public final class AiAssistantView {
    private final GeminiAiService aiService;
    private final User user;
    private final String userInitials;
    private final Consumer<String> toastCallback;

    private byte[] attachedImageBytes = null;
    private String attachedMimeType = null;
    private HBox attachmentPreviewBar;
    private ImageView attachmentThumbView;
    private Label attachmentNameLbl;

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

        Label badge = new Label("Gemini Vision · Giải bài qua Ảnh & KaTeX");
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

        Label subtitle = new Label("Giải bài tập bằng văn bản hoặc chụp vùng màn hình đề bài — Hiển thị công thức Toán, Lý, Hóa chuẩn xác");
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
                    appendBotBubble.accept("👋 Chào " + user.displayName() + "! Mình là StudyBot - Trợ lý AI học tập thông minh trong Studyroom.\n\nMình có thể hỗ trợ bạn:\n✔ **Chụp màn hình đề bài (📸)** hoặc tải ảnh bài tập để giải tự động từng bước\n✔ Hướng dẫn giải chi tiết Toán, Lý, Hóa, Sinh, Anh, Văn...\n✔ Hiển thị công thức Toán học, Vật lý, Hóa học bằng KaTeX chuẩn xác\n✔ Tóm tắt kiến thức bài học và tạo câu hỏi tự kiểm tra\n\nHãy bấm nút **📸 Chụp đề bài** hoặc gõ câu hỏi bên dưới nhé!");
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

        // Input Container with Image Attachment Preview
        VBox inputContainer = new VBox(8);
        inputContainer.setMinHeight(Region.USE_PREF_SIZE);
        inputContainer.setStyle("-fx-background-color: white; -fx-background-radius: 16; -fx-padding: 12 16; -fx-border-color: #e2e8f0; -fx-border-radius: 16; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 6, 0, 0, 1);");

        // Attachment preview bar (hidden until user snips or selects an image)
        attachmentPreviewBar = new HBox(10);
        attachmentPreviewBar.setAlignment(Pos.CENTER_LEFT);
        attachmentPreviewBar.setPadding(new Insets(6, 10, 6, 10));
        attachmentPreviewBar.setStyle("-fx-background-color: #eef2ff; -fx-border-color: #c7d2fe; -fx-border-radius: 10; -fx-background-radius: 10;");
        attachmentPreviewBar.setVisible(false);
        attachmentPreviewBar.setManaged(false);

        attachmentThumbView = new ImageView();
        attachmentThumbView.setFitHeight(48);
        attachmentThumbView.setPreserveRatio(true);

        VBox attachMeta = new VBox(2);
        attachmentNameLbl = new Label("📷 Ảnh bài tập đính kèm");
        attachmentNameLbl.setStyle("-fx-font-weight: 700; -fx-font-size: 12px; -fx-text-fill: #3730a3;");
        Label attachSub = new Label("AI sẽ đọc đề bài trong ảnh này và giải chi tiết từng bước");
        attachSub.setStyle("-fx-font-size: 11px; -fx-text-fill: #6366f1;");
        attachMeta.getChildren().addAll(attachmentNameLbl, attachSub);
        HBox.setHgrow(attachMeta, Priority.ALWAYS);

        Button removeAttachBtn = new Button("✕ Bỏ ảnh");
        removeAttachBtn.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #dc2626; -fx-font-weight: 700; -fx-font-size: 11px; -fx-background-radius: 8; -fx-cursor: hand;");
        removeAttachBtn.setOnAction(e -> clearAttachment());

        attachmentPreviewBar.getChildren().addAll(attachmentThumbView, attachMeta, removeAttachBtn);

        TextArea promptInput = new TextArea();
        promptInput.setPromptText("Nhập câu hỏi hoặc đính kèm ảnh đề bài... (Enter để gửi, Shift+Enter xuống dòng)");
        promptInput.setPrefRowCount(2);
        promptInput.setMaxHeight(90);
        promptInput.setWrapText(true);
        promptInput.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-font-size: 13.5px; -fx-text-fill: #111827;");

        HBox inputBottomRow = new HBox(8);
        inputBottomRow.setAlignment(Pos.CENTER_LEFT);

        Button snipScreenBtn = new Button("📸 Chụp vùng màn hình đề bài");
        snipScreenBtn.setStyle("-fx-background-color: #eef2ff; -fx-text-fill: #4338ca; -fx-border-color: #c7d2fe; -fx-border-radius: 16; -fx-background-radius: 16; -fx-font-weight: 700; -fx-font-size: 12px; -fx-padding: 6 12; -fx-cursor: hand;");
        snipScreenBtn.setOnAction(e -> startScreenSnip(content, promptInput));

        Button uploadImgBtn = new Button("🖼️ Chọn ảnh bài tập");
        uploadImgBtn.setStyle("-fx-background-color: #f8fafc; -fx-text-fill: #334155; -fx-border-color: #cbd5e1; -fx-border-radius: 16; -fx-background-radius: 16; -fx-font-weight: 700; -fx-font-size: 12px; -fx-padding: 6 12; -fx-cursor: hand;");
        uploadImgBtn.setOnAction(e -> chooseImageFile(content, promptInput));

        Label hintLbl = new Label("💡 Enter để gửi");
        hintLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #9ca3af;");
        Region inputGap = new Region();
        HBox.setHgrow(inputGap, Priority.ALWAYS);

        Button sendBtn = new Button("➤ Gửi câu hỏi");
        sendBtn.setStyle("-fx-background-color: #4f46e5; -fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 13px; -fx-background-radius: 20; -fx-padding: 7 20; -fx-cursor: hand;");

        inputBottomRow.getChildren().addAll(snipScreenBtn, uploadImgBtn, hintLbl, inputGap, sendBtn);
        inputContainer.getChildren().addAll(attachmentPreviewBar, promptInput, inputBottomRow);

        Runnable doSend = () -> {
            try {
                String textMsg = promptInput.getText().trim();
                boolean hasImg = attachedImageBytes != null && attachedImageBytes.length > 0;
                if (textMsg.isBlank() && !hasImg) return;

                String displayPrompt = textMsg.isBlank()
                    ? "Hãy đọc đề bài trong ảnh đính kèm và giải chi tiết từng bước."
                    : textMsg;

                byte[] imgBytesToSend = attachedImageBytes;
                String mimeToSend = attachedMimeType;

                promptInput.clear();
                clearAttachment();

                appendUserBubble.accept(hasImg ? ("📷 [Ảnh đề bài đính kèm]\n" + displayPrompt) : displayPrompt);

                setLoading.accept(true);
                sendBtn.setDisable(true);
                promptInput.setDisable(true);

                aiService.askGeminiWithImageAsync(user.username(), displayPrompt, imgBytesToSend, mimeToSend, reply -> {
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

    private void attachImage(byte[] bytes, String mimeType, String labelText) {
        this.attachedImageBytes = bytes;
        this.attachedMimeType = mimeType;
        attachmentThumbView.setImage(new Image(new ByteArrayInputStream(bytes)));
        attachmentNameLbl.setText("📷 " + labelText);
        attachmentPreviewBar.setVisible(true);
        attachmentPreviewBar.setManaged(true);
    }

    private void clearAttachment() {
        this.attachedImageBytes = null;
        this.attachedMimeType = null;
        if (attachmentPreviewBar != null) {
            attachmentPreviewBar.setVisible(false);
            attachmentPreviewBar.setManaged(false);
        }
    }

    private void chooseImageFile(VBox content, TextArea promptInput) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Chọn hình ảnh đề bài / bài tập");
        fc.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Hình ảnh (*.png, *.jpg, *.jpeg)", "*.png", "*.jpg", "*.jpeg")
        );
        Stage owner = content.getScene() != null && content.getScene().getWindow() instanceof Stage s ? s : null;
        File file = fc.showOpenDialog(owner);
        if (file != null) {
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                String name = file.getName().toLowerCase();
                String mime = (name.endsWith(".jpg") || name.endsWith(".jpeg")) ? "image/jpeg" : "image/png";
                attachImage(bytes, mime, file.getName());
                if (promptInput.getText().isBlank()) {
                    promptInput.setText("Hãy đọc đề bài trong hình ảnh này và giải chi tiết từng bước.");
                }
                promptInput.requestFocus();
                toast("Đã đính kèm ảnh: " + file.getName());
            } catch (Exception ex) {
                toast("Không thể đọc ảnh: " + ex.getMessage());
            }
        }
    }

    private void startScreenSnip(VBox content, TextArea promptInput) {
        Stage mainStage = content.getScene() != null && content.getScene().getWindow() instanceof Stage s ? s : null;
        if (mainStage != null) {
            mainStage.setIconified(true);
        }

        // Wait 320ms for main window to minimize before showing snipping overlay
        PauseTransition delay = new PauseTransition(Duration.millis(320));
        delay.setOnFinished(ev -> {
            Rectangle2D bounds = Screen.getPrimary().getBounds();
            Stage snipStage = new Stage(StageStyle.TRANSPARENT);
            snipStage.setAlwaysOnTop(true);
            snipStage.setX(bounds.getMinX());
            snipStage.setY(bounds.getMinY());
            snipStage.setWidth(bounds.getWidth());
            snipStage.setHeight(bounds.getHeight());

            Canvas canvas = new Canvas(bounds.getWidth(), bounds.getHeight());
            GraphicsContext gc = canvas.getGraphicsContext2D();

            final double[] startX = { 0 };
            final double[] startY = { 0 };
            final double[] curX = { 0 };
            final double[] curY = { 0 };
            final boolean[] dragging = { false };

            Runnable redraw = () -> {
                gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
                gc.setFill(Color.rgb(15, 23, 42, 0.42));
                gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

                gc.setFill(Color.WHITE);
                gc.fillText("📸 Kéo chuột quét vùng đề bài cần AI giải (Nhấn ESC để hủy)", 28, 36);

                if (dragging[0]) {
                    double x = Math.min(startX[0], curX[0]);
                    double y = Math.min(startY[0], curY[0]);
                    double w = Math.abs(curX[0] - startX[0]);
                    double h = Math.abs(curY[0] - startY[0]);
                    gc.clearRect(x, y, w, h);
                    gc.setStroke(Color.web("#6366f1"));
                    gc.setLineWidth(2.5);
                    gc.strokeRect(x, y, w, h);
                }
            };

            redraw.run();

            canvas.setCursor(Cursor.CROSSHAIR);
            canvas.setOnMousePressed(me -> {
                startX[0] = me.getScreenX();
                startY[0] = me.getScreenY();
                curX[0] = startX[0];
                curY[0] = startY[0];
                dragging[0] = true;
            });

            canvas.setOnMouseDragged(me -> {
                curX[0] = me.getScreenX();
                curY[0] = me.getScreenY();
                redraw.run();
            });

            canvas.setOnMouseReleased(me -> {
                dragging[0] = false;
                double x = Math.min(startX[0], me.getScreenX());
                double y = Math.min(startY[0], me.getScreenY());
                double w = Math.abs(me.getScreenX() - startX[0]);
                double h = Math.abs(me.getScreenY() - startY[0]);

                snipStage.close();

                if (w > 15 && h > 15) {
                    PauseTransition capDelay = new PauseTransition(Duration.millis(120));
                    capDelay.setOnFinished(ce -> {
                        try {
                            Robot robot = new Robot();
                            BufferedImage captured = robot.createScreenCapture(new Rectangle((int) x, (int) y, (int) w, (int) h));
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(captured, "png", baos);
                            byte[] pngBytes = baos.toByteArray();

                            if (mainStage != null) {
                                mainStage.setIconified(false);
                                mainStage.toFront();
                            }
                            attachImage(pngBytes, "image/png", "Ảnh chụp vùng màn hình (" + (int) w + "x" + (int) h + ")");
                            if (promptInput.getText().isBlank()) {
                                promptInput.setText("Hãy đọc đề bài trong ảnh chụp màn hình này và giải chi tiết từng bước.");
                            }
                            promptInput.requestFocus();
                            toast("📸 Đã chụp vùng đề bài! Nhấn Gửi để AI giải ngay.");
                        } catch (Exception ex) {
                            if (mainStage != null) mainStage.setIconified(false);
                            toast("Lỗi chụp màn hình: " + ex.getMessage());
                        }
                    });
                    capDelay.play();
                } else {
                    if (mainStage != null) {
                        mainStage.setIconified(false);
                        mainStage.toFront();
                    }
                }
            });

            StackPane root = new StackPane(canvas);
            root.setStyle("-fx-background-color: transparent;");
            Scene scene = new Scene(root, bounds.getWidth(), bounds.getHeight(), Color.TRANSPARENT);
            scene.setOnKeyPressed(ke -> {
                if (ke.getCode() == KeyCode.ESCAPE) {
                    snipStage.close();
                    if (mainStage != null) {
                        mainStage.setIconified(false);
                        mainStage.toFront();
                    }
                }
            });

            snipStage.setScene(scene);
            snipStage.show();
            snipStage.requestFocus();
        });
        delay.play();
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
