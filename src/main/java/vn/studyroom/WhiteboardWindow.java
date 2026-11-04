package vn.studyroom;

import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Modern Interactive Whiteboard & Screen Annotation Overlay for Teaching & Lecturing.
 * - Screen Annotation: Transparent overlay allowing direct drawing over slides, docs, or desktop.
 * - Whiteboard Mode: Clean white canvas for formulas and explanations.
 * - Chalkboard Mode: Classic dark-green classroom board.
 * - Full suite of teaching tools: Pen, Highlighter, Arrow, Rectangle, Circle, Line, Text, Eraser, Undo/Redo, Export PNG.
 */
public final class WhiteboardWindow {
    private static final WhiteboardWindow INSTANCE = new WhiteboardWindow();

    public static WhiteboardWindow getInstance() {
        return INSTANCE;
    }

    public enum Mode {
        SCREEN_OVERLAY, // Transparent overlay on screen
        WHITEBOARD,     // White background
        CHALKBOARD      // Dark green blackboard
    }

    public enum Tool {
        PEN,
        HIGHLIGHTER,
        ARROW,
        RECTANGLE,
        CIRCLE,
        LINE,
        TEXT,
        ERASER
    }

    // Abstract action for Undo/Redo
    private interface DrawAction {
        void draw(GraphicsContext gc, Mode mode);
    }

    private static class FreehandAction implements DrawAction {
        final List<Point2D> points;
        final Color color;
        final double width;
        final boolean isHighlighter;

        FreehandAction(List<Point2D> points, Color color, double width, boolean isHighlighter) {
            this.points = new ArrayList<>(points);
            this.color = color;
            this.width = width;
            this.isHighlighter = isHighlighter;
        }

        @Override
        public void draw(GraphicsContext gc, Mode mode) {
            if (points.size() < 2) return;
            gc.save();
            if (isHighlighter) {
                gc.setStroke(Color.color(color.getRed(), color.getGreen(), color.getBlue(), 0.38));
                gc.setLineWidth(width * 2.2);
            } else {
                gc.setStroke(color);
                gc.setLineWidth(width);
            }
            gc.setLineCap(StrokeLineCap.ROUND);
            gc.setLineJoin(StrokeLineJoin.ROUND);
            gc.beginPath();
            gc.moveTo(points.get(0).getX(), points.get(0).getY());
            for (int i = 1; i < points.size(); i++) {
                gc.lineTo(points.get(i).getX(), points.get(i).getY());
            }
            gc.stroke();
            gc.restore();
        }
    }

    private static class EraserAction implements DrawAction {
        final List<Point2D> points;
        final double width;

        EraserAction(List<Point2D> points, double width) {
            this.points = new ArrayList<>(points);
            this.width = width;
        }

        @Override
        public void draw(GraphicsContext gc, Mode mode) {
            if (points.isEmpty()) return;
            gc.save();
            double size = Math.max(20, width * 3.5);
            if (mode == Mode.SCREEN_OVERLAY) {
                for (Point2D p : points) {
                    gc.clearRect(p.getX() - size / 2, p.getY() - size / 2, size, size);
                }
            } else {
                Color bg = (mode == Mode.WHITEBOARD) ? Color.WHITE : Color.web("#173826");
                gc.setStroke(bg);
                gc.setLineWidth(size);
                gc.setLineCap(StrokeLineCap.ROUND);
                gc.setLineJoin(StrokeLineJoin.ROUND);
                gc.beginPath();
                gc.moveTo(points.get(0).getX(), points.get(0).getY());
                for (int i = 1; i < points.size(); i++) {
                    gc.lineTo(points.get(i).getX(), points.get(i).getY());
                }
                gc.stroke();
            }
            gc.restore();
        }
    }

    private static class ShapeAction implements DrawAction {
        final Tool tool;
        final double x1, y1, x2, y2;
        final Color color;
        final double width;

        ShapeAction(Tool tool, double x1, double y1, double x2, double y2, Color color, double width) {
            this.tool = tool;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.color = color;
            this.width = width;
        }

        @Override
        public void draw(GraphicsContext gc, Mode mode) {
            gc.save();
            gc.setStroke(color);
            gc.setFill(color);
            gc.setLineWidth(width);
            gc.setLineCap(StrokeLineCap.ROUND);
            gc.setLineJoin(StrokeLineJoin.ROUND);

            double minX = Math.min(x1, x2);
            double minY = Math.min(y1, y2);
            double w = Math.abs(x2 - x1);
            double h = Math.abs(y2 - y1);

            switch (tool) {
                case LINE -> gc.strokeLine(x1, y1, x2, y2);
                case RECTANGLE -> gc.strokeRoundRect(minX, minY, w, h, 8, 8);
                case CIRCLE -> gc.strokeOval(minX, minY, w, h);
                case ARROW -> drawArrow(gc, x1, y1, x2, y2, width);
                default -> {}
            }
            gc.restore();
        }

        private static void drawArrow(GraphicsContext gc, double fromX, double fromY, double toX, double toY, double strokeW) {
            gc.strokeLine(fromX, fromY, toX, toY);
            double angle = Math.atan2(toY - fromY, toX - fromX);
            double headLen = Math.max(16, strokeW * 3.0);
            double arrowAngle = Math.PI / 6.0; // 30 degrees

            double xA = toX - headLen * Math.cos(angle - arrowAngle);
            double yA = toY - headLen * Math.sin(angle - arrowAngle);
            double xB = toX - headLen * Math.cos(angle + arrowAngle);
            double yB = toY - headLen * Math.sin(angle + arrowAngle);

            gc.fillPolygon(new double[]{toX, xA, xB}, new double[]{toY, yA, yB}, 3);
        }
    }

    private static class TextAction implements DrawAction {
        final double x, y;
        final String text;
        final Color color;
        final double fontSize;

        TextAction(double x, double y, String text, Color color, double fontSize) {
            this.x = x;
            this.y = y;
            this.text = text;
            this.color = color;
            this.fontSize = fontSize;
        }

        @Override
        public void draw(GraphicsContext gc, Mode mode) {
            gc.save();
            gc.setFill(color);
            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, fontSize));
            gc.fillText(text, x, y);
            gc.restore();
        }
    }

    private Stage stage;
    private Pane rootPane;
    private Canvas canvas;
    private GraphicsContext gc;

    private Mode currentMode = Mode.SCREEN_OVERLAY;
    private Tool currentTool = Tool.PEN;
    private Color currentColor = Color.web("#ef4444"); // Default red for teacher pen
    private double currentWidth = 4.0;
    private boolean isMousePassthrough = false;

    private final List<DrawAction> undoStack = new ArrayList<>();
    private final List<DrawAction> redoStack = new ArrayList<>();

    // Dragging state
    private final List<Point2D> currentStrokePoints = new ArrayList<>();
    private double dragStartX, dragStartY;
    private boolean isDragging = false;

    // Floating Toolbar
    private VBox toolbarCard;
    private HBox mainToolRow;
    private HBox colorAndSizeRow;
    private ToggleButton passthroughBtn;
    private boolean isToolbarCollapsed = false;

    private double toolbarDragOffsetX, toolbarDragOffsetY;

    private WhiteboardWindow() {
    }

    public synchronized void show() {
        if (stage != null && stage.isShowing()) {
            stage.toFront();
            return;
        }

        buildStage();
        stage.show();
        stage.toFront();
    }

    public synchronized void close() {
        if (stage != null) {
            stage.close();
            stage = null;
        }
    }

    public boolean isShowing() {
        return stage != null && stage.isShowing();
    }

    private void buildStage() {
        stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setTitle("Studyroom - Bảng Trắng & Giảng Bài Trực Tuyến");

        Rectangle2D bounds = Screen.getPrimary().getBounds();
        double screenW = bounds.getWidth();
        double screenH = bounds.getHeight();

        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(screenW);
        stage.setHeight(screenH);

        canvas = new Canvas(screenW, screenH);
        gc = canvas.getGraphicsContext2D();

        rootPane = new Pane();
        applyModeBackground();

        setupCanvasEvents();

        toolbarCard = buildFloatingToolbar();
        // Position toolbar at top center
        toolbarCard.setLayoutX(Math.max(20, (screenW - 820) / 2.0));
        toolbarCard.setLayoutY(24);

        rootPane.getChildren().addAll(canvas, toolbarCard);

        Scene scene = new Scene(rootPane, screenW, screenH);
        scene.setFill(Color.TRANSPARENT);

        // Global hotkeys
        scene.setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.Z) {
                undo();
            } else if (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.Y) {
                redo();
            } else if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                close();
            }
        });

        stage.setScene(scene);
    }

    private void applyModeBackground() {
        if (rootPane == null) return;
        switch (currentMode) {
            case SCREEN_OVERLAY -> rootPane.setStyle("-fx-background-color: rgba(0, 0, 0, 0.01);");
            case WHITEBOARD -> rootPane.setStyle("-fx-background-color: #ffffff;");
            case CHALKBOARD -> rootPane.setStyle("-fx-background-color: #173826;");
        }
        redrawAll();
    }

    private void redrawAll() {
        if (gc == null || canvas == null) return;
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        gc.clearRect(0, 0, w, h);

        if (currentMode == Mode.WHITEBOARD) {
            gc.setFill(Color.WHITE);
            gc.fillRect(0, 0, w, h);
            // Draw subtle grid lines
            gc.setStroke(Color.web("#f1f5f9"));
            gc.setLineWidth(1);
            for (double x = 40; x < w; x += 40) {
                gc.strokeLine(x, 0, x, h);
            }
            for (double y = 40; y < h; y += 40) {
                gc.strokeLine(0, y, w, y);
            }
        } else if (currentMode == Mode.CHALKBOARD) {
            gc.setFill(Color.web("#173826"));
            gc.fillRect(0, 0, w, h);
            // Subtle chalk grid
            gc.setStroke(Color.web("rgba(255, 255, 255, 0.05)"));
            gc.setLineWidth(1);
            for (double x = 40; x < w; x += 40) {
                gc.strokeLine(x, 0, x, h);
            }
            for (double y = 40; y < h; y += 40) {
                gc.strokeLine(0, y, w, y);
            }
        }

        for (DrawAction action : undoStack) {
            action.draw(gc, currentMode);
        }
    }

    private void setupCanvasEvents() {
        canvas.setCursor(Cursor.CROSSHAIR);

        canvas.setOnMousePressed(e -> {
            if (isMousePassthrough) return;
            if (e.getButton() != MouseButton.PRIMARY) return;
            dragStartX = e.getX();
            dragStartY = e.getY();
            isDragging = true;

            if (currentTool == Tool.PEN || currentTool == Tool.HIGHLIGHTER || currentTool == Tool.ERASER) {
                currentStrokePoints.clear();
                currentStrokePoints.add(new Point2D(dragStartX, dragStartY));
            } else if (currentTool == Tool.TEXT) {
                promptTextInput(e.getX(), e.getY());
                isDragging = false;
            }
        });

        canvas.setOnMouseDragged(e -> {
            if (isMousePassthrough || !isDragging) return;
            double curX = e.getX();
            double curY = e.getY();

            if (currentTool == Tool.PEN || currentTool == Tool.HIGHLIGHTER || currentTool == Tool.ERASER) {
                currentStrokePoints.add(new Point2D(curX, curY));

                // Progressive render for responsiveness
                int sz = currentStrokePoints.size();
                if (sz >= 2) {
                    Point2D p1 = currentStrokePoints.get(sz - 2);
                    Point2D p2 = currentStrokePoints.get(sz - 1);
                    gc.save();
                    if (currentTool == Tool.ERASER) {
                        double erSize = Math.max(20, currentWidth * 3.5);
                        if (currentMode == Mode.SCREEN_OVERLAY) {
                            gc.clearRect(curX - erSize / 2, curY - erSize / 2, erSize, erSize);
                        } else {
                            Color bg = (currentMode == Mode.WHITEBOARD) ? Color.WHITE : Color.web("#173826");
                            gc.setStroke(bg);
                            gc.setLineWidth(erSize);
                            gc.setLineCap(StrokeLineCap.ROUND);
                            gc.strokeLine(p1.getX(), p1.getY(), p2.getX(), p2.getY());
                        }
                    } else if (currentTool == Tool.HIGHLIGHTER) {
                        gc.setStroke(Color.color(currentColor.getRed(), currentColor.getGreen(), currentColor.getBlue(), 0.38));
                        gc.setLineWidth(currentWidth * 2.2);
                        gc.setLineCap(StrokeLineCap.ROUND);
                        gc.strokeLine(p1.getX(), p1.getY(), p2.getX(), p2.getY());
                    } else {
                        gc.setStroke(currentColor);
                        gc.setLineWidth(currentWidth);
                        gc.setLineCap(StrokeLineCap.ROUND);
                        gc.strokeLine(p1.getX(), p1.getY(), p2.getX(), p2.getY());
                    }
                    gc.restore();
                }
            } else {
                // Shape preview: redraw canvas then draw preview
                redrawAll();
                ShapeAction preview = new ShapeAction(currentTool, dragStartX, dragStartY, curX, curY, currentColor, currentWidth);
                preview.draw(gc, currentMode);
            }
        });

        canvas.setOnMouseReleased(e -> {
            if (isMousePassthrough || !isDragging) return;
            isDragging = false;
            double endX = e.getX();
            double endY = e.getY();

            DrawAction finishedAction = null;
            if (currentTool == Tool.PEN || currentTool == Tool.HIGHLIGHTER) {
                if (currentStrokePoints.size() >= 2) {
                    finishedAction = new FreehandAction(currentStrokePoints, currentColor, currentWidth, currentTool == Tool.HIGHLIGHTER);
                }
                currentStrokePoints.clear();
            } else if (currentTool == Tool.ERASER) {
                if (!currentStrokePoints.isEmpty()) {
                    finishedAction = new EraserAction(currentStrokePoints, currentWidth);
                }
                currentStrokePoints.clear();
            } else if (currentTool == Tool.LINE || currentTool == Tool.RECTANGLE || currentTool == Tool.CIRCLE || currentTool == Tool.ARROW) {
                if (Math.abs(endX - dragStartX) > 2 || Math.abs(endY - dragStartY) > 2) {
                    finishedAction = new ShapeAction(currentTool, dragStartX, dragStartY, endX, endY, currentColor, currentWidth);
                }
            }

            if (finishedAction != null) {
                undoStack.add(finishedAction);
                redoStack.clear();
            }
            redrawAll();
        });
    }

    private void promptTextInput(double x, double y) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Chèn chữ ghi chú bài giảng");
        dialog.setHeaderText("Nhập nội dung ghi chú / công thức bài giảng:");
        dialog.setContentText("Nội dung:");
        if (stage != null) dialog.initOwner(stage);

        dialog.showAndWait().ifPresent(text -> {
            if (!text.isBlank()) {
                double fontSize = Math.max(16, currentWidth * 4.0);
                DrawAction action = new TextAction(x, y, text.trim(), currentColor, fontSize);
                undoStack.add(action);
                redoStack.clear();
                redrawAll();
            }
        });
    }

    private void undo() {
        if (!undoStack.isEmpty()) {
            DrawAction popped = undoStack.remove(undoStack.size() - 1);
            redoStack.add(popped);
            redrawAll();
        }
    }

    private void redo() {
        if (!redoStack.isEmpty()) {
            DrawAction action = redoStack.remove(redoStack.size() - 1);
            undoStack.add(action);
            redrawAll();
        }
    }

    private void clearCanvas() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Xóa bảng");
        confirm.setHeaderText("Xóa toàn bộ nét vẽ trên bảng?");
        confirm.setContentText("Bạn có chắc chắn muốn xóa hết tất cả nội dung hiện tại không?");
        if (stage != null) confirm.initOwner(stage);

        confirm.showAndWait().ifPresent(res -> {
            if (res == ButtonType.OK) {
                undoStack.clear();
                redoStack.clear();
                redrawAll();
            }
        });
    }

    private void exportImage() {
        try {
            int w = (int) canvas.getWidth();
            int h = (int) canvas.getHeight();
            WritableImage fxImg = new WritableImage(w, h);
            canvas.snapshot(null, fxImg);

            PixelReader reader = fxImg.getPixelReader();
            BufferedImage bImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    bImg.setRGB(x, y, reader.getArgb(x, y));
                }
            }

            FileChooser chooser = new FileChooser();
            chooser.setTitle("Lưu ảnh bài giảng");
            String defaultName = "bai_giang_" + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now()) + ".png";
            chooser.setInitialFileName(defaultName);
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Hình ảnh PNG (*.png)", "*.png"));

            File dest = chooser.showSaveDialog(stage);
            if (dest != null) {
                ImageIO.write(bImg, "png", dest);
                Alert done = new Alert(Alert.AlertType.INFORMATION);
                done.setTitle("Đã lưu bài giảng");
                done.setHeaderText(null);
                done.setContentText("Hình ảnh bài giảng đã được lưu thành công tại:\n" + dest.getAbsolutePath());
                if (stage != null) done.initOwner(stage);
                done.showAndWait();
            }
        } catch (Exception ex) {
            Alert err = new Alert(Alert.AlertType.ERROR);
            err.setTitle("Lỗi lưu ảnh");
            err.setHeaderText("Không thể lưu ảnh bài giảng");
            err.setContentText(ex.getMessage());
            if (stage != null) err.initOwner(stage);
            err.showAndWait();
        }
    }

    // ── Floating Toolbar ──────────────────────────────────────────────────────────
    private VBox buildFloatingToolbar() {
        VBox card = new VBox(8);
        card.setStyle(
            "-fx-background-color: rgba(30, 32, 48, 0.95); " +
            "-fx-background-radius: 16; " +
            "-fx-padding: 8 14; " +
            "-fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.5), 18, 0, 0, 4); " +
            "-fx-border-color: rgba(255, 255, 255, 0.15); " +
            "-fx-border-radius: 16;"
        );
        card.setMaxWidth(820);

        // Header / Drag handle
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-cursor: move; -fx-padding: 2 4 4 4;");

        Label dragIcon = new Label("✥");
        dragIcon.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 14px; -fx-font-weight: bold;");

        Label title = new Label("Studyroom Whiteboard · Bảng Giảng Bài");
        title.setStyle("-fx-text-fill: #f8fafc; -fx-font-weight: 800; -fx-font-size: 12.5px;");

        Region hgap = new Region();
        HBox.setHgrow(hgap, Priority.ALWAYS);

        // Mode Toggles: Screen Draw / Whiteboard / Chalkboard
        ToggleGroup modeGroup = new ToggleGroup();
        ToggleButton screenModeBtn = new ToggleButton("🖥️ Vẽ lên màn hình");
        screenModeBtn.setToggleGroup(modeGroup);
        screenModeBtn.setSelected(true);
        styleModeBtn(screenModeBtn);

        ToggleButton whiteModeBtn = new ToggleButton("⬜ Bảng trắng");
        whiteModeBtn.setToggleGroup(modeGroup);
        styleModeBtn(whiteModeBtn);

        ToggleButton chalkModeBtn = new ToggleButton("🟩 Bảng phấn");
        chalkModeBtn.setToggleGroup(modeGroup);
        styleModeBtn(chalkModeBtn);

        modeGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == screenModeBtn) {
                currentMode = Mode.SCREEN_OVERLAY;
                passthroughBtn.setDisable(false);
            } else if (newVal == whiteModeBtn) {
                currentMode = Mode.WHITEBOARD;
                passthroughBtn.setDisable(true);
                setMousePassthrough(false);
            } else if (newVal == chalkModeBtn) {
                currentMode = Mode.CHALKBOARD;
                passthroughBtn.setDisable(true);
                setMousePassthrough(false);
            }
            applyModeBackground();
        });

        // Mouse Passthrough Button (for clicking PowerPoint/Desktop underneath)
        passthroughBtn = new ToggleButton("🖱️ Thao tác chuột");
        passthroughBtn.setTooltip(new Tooltip("Tạm dừng vẽ để nhấp chuột chuyển slide / lướt web bên dưới"));
        passthroughBtn.setStyle(
            "-fx-background-color: rgba(255, 255, 255, 0.08); -fx-text-fill: #cbd5e1; -fx-font-size: 11px; -fx-font-weight: 700; -fx-padding: 3 8; -fx-background-radius: 8; -fx-cursor: hand;"
        );
        passthroughBtn.selectedProperty().addListener((obs, old, selected) -> {
            setMousePassthrough(selected);
        });

        Button minBtn = new Button("🗕");
        minBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #94a3b8; -fx-font-size: 13px; -fx-cursor: hand;");
        minBtn.setTooltip(new Tooltip("Thu nhỏ thanh công cụ"));
        minBtn.setOnAction(e -> toggleCollapseToolbar());

        Button closeBtn = new Button("✕");
        closeBtn.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold; -fx-background-radius: 12; -fx-padding: 3 8; -fx-cursor: hand;");
        closeBtn.setTooltip(new Tooltip("Đóng bảng trắng (Esc)"));
        closeBtn.setOnAction(e -> close());

        header.getChildren().addAll(dragIcon, title, hgap, screenModeBtn, whiteModeBtn, chalkModeBtn, passthroughBtn, minBtn, closeBtn);

        // Make toolbar draggable
        header.setOnMousePressed(e -> {
            toolbarDragOffsetX = e.getSceneX() - card.getLayoutX();
            toolbarDragOffsetY = e.getSceneY() - card.getLayoutY();
        });
        header.setOnMouseDragged(e -> {
            card.setLayoutX(e.getSceneX() - toolbarDragOffsetX);
            card.setLayoutY(e.getSceneY() - toolbarDragOffsetY);
        });

        // Main Tools Row
        mainToolRow = new HBox(6);
        mainToolRow.setAlignment(Pos.CENTER_LEFT);

        ToggleGroup toolGroup = new ToggleGroup();
        ToggleButton penBtn = createToolBtn("✏️ Bút vẽ", Tool.PEN, toolGroup, true);
        ToggleButton highBtn = createToolBtn("🖍️ Dạ quang", Tool.HIGHLIGHTER, toolGroup, false);
        ToggleButton arrowBtn = createToolBtn("➔ Mũi tên", Tool.ARROW, toolGroup, false);
        ToggleButton rectBtn = createToolBtn("▭ Khung chữ nhật", Tool.RECTANGLE, toolGroup, false);
        ToggleButton circleBtn = createToolBtn("◯ Hình tròn", Tool.CIRCLE, toolGroup, false);
        ToggleButton lineBtn = createToolBtn("╱ Thước kẻ", Tool.LINE, toolGroup, false);
        ToggleButton textBtn = createToolBtn("🔤 Chữ", Tool.TEXT, toolGroup, false);
        ToggleButton eraserBtn = createToolBtn("🧽 Tẩy", Tool.ERASER, toolGroup, false);

        Separator sep1 = new Separator(javafx.geometry.Orientation.VERTICAL);
        sep1.setStyle("-fx-opacity: 0.3;");

        Button undoBtn = createActionBtn("↩ Hoàn tác", this::undo, "Ctrl+Z");
        Button redoBtn = createActionBtn("↪ Làm lại", this::redo, "Ctrl+Y");
        Button clearBtn = createActionBtn("🗑️ Xóa hết", this::clearCanvas, "Xóa sạch nét vẽ");
        Button saveBtn = createActionBtn("💾 Lưu ảnh", this::exportImage, "Lưu file PNG bài giảng");

        mainToolRow.getChildren().addAll(
            penBtn, highBtn, arrowBtn, rectBtn, circleBtn, lineBtn, textBtn, eraserBtn,
            sep1, undoBtn, redoBtn, clearBtn, saveBtn
        );

        // Colors & Stroke Width Row
        colorAndSizeRow = new HBox(8);
        colorAndSizeRow.setAlignment(Pos.CENTER_LEFT);

        Label colorLbl = new Label("Màu sắc:");
        colorLbl.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11.5px; -fx-font-weight: 600;");

        HBox colorPalettes = new HBox(6);
        colorPalettes.setAlignment(Pos.CENTER_LEFT);

        String[] presetColors = {"#ef4444", "#f59e0b", "#10b981", "#3b82f6", "#8b5cf6", "#ec4899", "#ffffff", "#1e293b"};
        for (String cHex : presetColors) {
            Button cBtn = new Button();
            cBtn.setStyle("-fx-background-color: " + cHex + "; -fx-min-width: 22px; -fx-min-height: 22px; -fx-max-width: 22px; -fx-max-height: 22px; -fx-background-radius: 50%; -fx-border-color: rgba(255,255,255,0.4); -fx-border-radius: 50%; -fx-cursor: hand;");
            cBtn.setOnAction(e -> {
                currentColor = Color.web(cHex);
            });
            colorPalettes.getChildren().add(cBtn);
        }

        Separator sep2 = new Separator(javafx.geometry.Orientation.VERTICAL);
        sep2.setStyle("-fx-opacity: 0.3;");

        Label sizeLbl = new Label("Nét bút:");
        sizeLbl.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11.5px; -fx-font-weight: 600;");

        ToggleGroup sizeGroup = new ToggleGroup();
        RadioButton s1 = createSizeRadio("Mảnh (2px)", 2.0, sizeGroup, false);
        RadioButton s2 = createSizeRadio("Vừa (4px)", 4.0, sizeGroup, true);
        RadioButton s3 = createSizeRadio("Dày (8px)", 8.0, sizeGroup, false);
        RadioButton s4 = createSizeRadio("Lớn (14px)", 14.0, sizeGroup, false);

        colorAndSizeRow.getChildren().addAll(colorLbl, colorPalettes, sep2, sizeLbl, s1, s2, s3, s4);

        card.getChildren().addAll(header, mainToolRow, colorAndSizeRow);
        return card;
    }

    private void setMousePassthrough(boolean passthrough) {
        this.isMousePassthrough = passthrough;
        if (canvas != null) {
            canvas.setMouseTransparent(passthrough);
            canvas.setCursor(passthrough ? Cursor.DEFAULT : Cursor.CROSSHAIR);
        }
        if (passthroughBtn != null) {
            if (passthrough) {
                passthroughBtn.setStyle(
                    "-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: 800; -fx-padding: 3 8; -fx-background-radius: 8; -fx-cursor: hand;"
                );
                passthroughBtn.setText("🖱️ Đang bấm màn hình");
            } else {
                passthroughBtn.setStyle(
                    "-fx-background-color: rgba(255, 255, 255, 0.08); -fx-text-fill: #cbd5e1; -fx-font-size: 11px; -fx-font-weight: 700; -fx-padding: 3 8; -fx-background-radius: 8; -fx-cursor: hand;"
                );
                passthroughBtn.setText("🖱️ Thao tác chuột");
            }
        }
    }

    private void toggleCollapseToolbar() {
        isToolbarCollapsed = !isToolbarCollapsed;
        mainToolRow.setVisible(!isToolbarCollapsed);
        mainToolRow.setManaged(!isToolbarCollapsed);
        colorAndSizeRow.setVisible(!isToolbarCollapsed);
        colorAndSizeRow.setManaged(!isToolbarCollapsed);
    }

    private void styleModeBtn(ToggleButton btn) {
        btn.setStyle(
            "-fx-background-color: rgba(255, 255, 255, 0.08); " +
            "-fx-text-fill: #cbd5e1; " +
            "-fx-font-size: 11px; " +
            "-fx-font-weight: 700; " +
            "-fx-padding: 3 8; " +
            "-fx-background-radius: 8; " +
            "-fx-cursor: hand;"
        );
        btn.selectedProperty().addListener((obs, old, selected) -> {
            if (selected) {
                btn.setStyle(
                    "-fx-background-color: #6366f1; " +
                    "-fx-text-fill: white; " +
                    "-fx-font-size: 11px; " +
                    "-fx-font-weight: 700; " +
                    "-fx-padding: 3 8; " +
                    "-fx-background-radius: 8; " +
                    "-fx-cursor: hand;"
                );
            } else {
                btn.setStyle(
                    "-fx-background-color: rgba(255, 255, 255, 0.08); " +
                    "-fx-text-fill: #cbd5e1; " +
                    "-fx-font-size: 11px; " +
                    "-fx-font-weight: 700; " +
                    "-fx-padding: 3 8; " +
                    "-fx-background-radius: 8; " +
                    "-fx-cursor: hand;"
                );
            }
        });
    }

    private ToggleButton createToolBtn(String label, Tool tool, ToggleGroup group, boolean defSelected) {
        ToggleButton btn = new ToggleButton(label);
        btn.setToggleGroup(group);
        btn.setSelected(defSelected);
        applyToolBtnStyle(btn, defSelected);

        btn.selectedProperty().addListener((obs, old, selected) -> {
            applyToolBtnStyle(btn, selected);
            if (selected) {
                currentTool = tool;
                if (isMousePassthrough) {
                    setMousePassthrough(false);
                    if (passthroughBtn != null) passthroughBtn.setSelected(false);
                }
            }
        });
        return btn;
    }

    private void applyToolBtnStyle(ToggleButton btn, boolean selected) {
        if (selected) {
            btn.setStyle(
                "-fx-background-color: #4f46e5; " +
                "-fx-text-fill: white; " +
                "-fx-font-weight: 700; " +
                "-fx-font-size: 11.5px; " +
                "-fx-background-radius: 8; " +
                "-fx-padding: 5 10; " +
                "-fx-cursor: hand;"
            );
        } else {
            btn.setStyle(
                "-fx-background-color: rgba(255, 255, 255, 0.06); " +
                "-fx-text-fill: #e2e8f0; " +
                "-fx-font-weight: 600; " +
                "-fx-font-size: 11.5px; " +
                "-fx-background-radius: 8; " +
                "-fx-padding: 5 10; " +
                "-fx-cursor: hand;"
            );
        }
    }

    private Button createActionBtn(String label, Runnable action, String tooltipText) {
        Button b = new Button(label);
        b.setStyle(
            "-fx-background-color: rgba(255, 255, 255, 0.08); " +
            "-fx-text-fill: #f1f5f9; " +
            "-fx-font-weight: 600; " +
            "-fx-font-size: 11.5px; " +
            "-fx-background-radius: 8; " +
            "-fx-padding: 5 9; " +
            "-fx-cursor: hand;"
        );
        b.setTooltip(new Tooltip(tooltipText));
        b.setOnAction(e -> action.run());
        return b;
    }

    private RadioButton createSizeRadio(String label, double size, ToggleGroup group, boolean defSelected) {
        RadioButton rb = new RadioButton(label);
        rb.setToggleGroup(group);
        rb.setSelected(defSelected);
        rb.setStyle("-fx-text-fill: #e2e8f0; -fx-font-size: 11px; -fx-cursor: hand;");
        rb.selectedProperty().addListener((obs, old, selected) -> {
            if (selected) {
                currentWidth = size;
            }
        });
        return rb;
    }
}
