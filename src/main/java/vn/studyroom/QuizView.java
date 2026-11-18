package vn.studyroom;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Modular UI component for Course Quizzes, Timed Assessments, Automatic Grading, and Score Leaderboards.
 */
public final class QuizView {
    private final QuizRepository quizRepo;
    private final User user;
    private final Consumer<String> toastCallback;
    private final BiFunction<String, String, StackPane> avatarProvider;

    public QuizView(QuizRepository quizRepo, User user, Consumer<String> toastCallback, BiFunction<String, String, StackPane> avatarProvider) {
        this.quizRepo = quizRepo;
        this.user = user;
        this.toastCallback = toastCallback;
        this.avatarProvider = avatarProvider;
    }

    private void toast(String msg) {
        if (toastCallback != null) toastCallback.accept(msg);
    }

    public void renderQuizzesTab(CourseRepository.Course course, VBox container) {
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
            emptyMsg.setTextAlignment(TextAlignment.CENTER);
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

        Consumer<QuestionItem> attachDelete = item -> {
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

        HBox topBar = new HBox(12);
        topBar.setAlignment(Pos.CENTER_LEFT);

        VBox titleBox = new VBox(2);
        HBox.setHgrow(titleBox, Priority.ALWAYS);
        Label titleLbl = new Label(quiz.title());
        titleLbl.setStyle("-fx-font-size: 17px; -fx-font-weight: 800; -fx-text-fill: #111827;");
        Label subLbl = new Label("Số câu: " + questions.size() + " câu  ·  Thời gian: " + quiz.durationMinutes() + " phút");
        subLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #6b7280;");
        titleBox.getChildren().addAll(titleLbl, subLbl);

        final int[] secondsLeft = {quiz.durationMinutes() * 60};
        Label timerLbl = new Label(String.format("⏱ %02d:%02d", secondsLeft[0] / 60, secondsLeft[0] % 60));
        timerLbl.setStyle("-fx-font-size: 15px; -fx-font-weight: 800; -fx-background-color: #fee2e2; -fx-text-fill: #dc2626; -fx-padding: 6 14; -fx-background-radius: 20;");

        topBar.getChildren().addAll(titleBox, timerLbl);

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

                StackPane avt = avatarProvider != null ? avatarProvider.apply(s.username(), s.displayName()) : new StackPane();

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
}
