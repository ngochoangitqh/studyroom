package vn.studyroom;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * SoundCloud-inspired interactive audio waveform visualizer.
 * Supports dynamic bar animation and interactive click-to-seek.
 */
public class SoundCloudWaveform extends StackPane {
    private static final int BAR_COUNT = 45;
    private final Region[] bars = new Region[BAR_COUNT];
    private final double[] defaultHeights = new double[BAR_COUNT];
    private double currentProgressRatio = 0.0;
    private final MusicPlayerService playerService;
    private final Tooltip timeTooltip = new Tooltip();

    public SoundCloudWaveform(MusicPlayerService playerService) {
        this.playerService = playerService;
        setAlignment(Pos.CENTER);
        setPrefHeight(64);
        setMinHeight(50);
        setMaxHeight(80);
        setStyle("-fx-background-color: rgba(24, 24, 27, 0.6); -fx-background-radius: 12; -fx-padding: 8 16;");
        setCursor(Cursor.HAND);

        HBox barContainer = new HBox(3);
        barContainer.setAlignment(Pos.CENTER);

        // Precompute natural waveform peaks
        for (int i = 0; i < BAR_COUNT; i++) {
            double x = (double) i / BAR_COUNT;
            // Harmonic wave shape
            double h = 0.2 + 0.6 * Math.abs(Math.sin(x * Math.PI * 3.5)) * Math.sin(x * Math.PI);
            defaultHeights[i] = Math.max(0.15, Math.min(1.0, h));

            Region bar = new Region();
            bar.setMinWidth(4);
            bar.setMaxWidth(6);
            bar.setPrefWidth(5);
            bar.setPrefHeight(defaultHeights[i] * 46);
            bar.setStyle("-fx-background-radius: 3; -fx-background-color: #3f3f46;");
            HBox.setHgrow(bar, javafx.scene.layout.Priority.ALWAYS);

            bars[i] = bar;
            barContainer.getChildren().add(bar);
        }

        getChildren().add(barContainer);

        // Click to seek
        setOnMouseClicked(e -> {
            double ratio = Math.max(0.0, Math.min(1.0, e.getX() / getWidth()));
            playerService.seek(ratio);
        });

        // Hover tooltip
        Tooltip.install(this, timeTooltip);
        setOnMouseMoved(e -> {
            MusicTrack t = playerService.getCurrentTrack();
            if (t != null) {
                double ratio = Math.max(0.0, Math.min(1.0, e.getX() / getWidth()));
                int sec = (int) (ratio * t.durationSeconds());
                timeTooltip.setText(String.format("%02d:%02d", sec / 60, sec % 60));
            }
        });

        // Listen for visualizer updates
        playerService.addVisualizerListener(this::updateVisualizer);
        playerService.addTimeUpdateListener(this::updateProgress);
    }

    public void updateProgress(double currentSec) {
        MusicTrack track = playerService.getCurrentTrack();
        if (track == null || track.durationSeconds() <= 0) return;
        this.currentProgressRatio = Math.max(0.0, Math.min(1.0, currentSec / track.durationSeconds()));
        refreshBarColors();
    }

    private void updateVisualizer(float[] bands) {
        boolean playing = playerService.isPlaying();
        for (int i = 0; i < BAR_COUNT; i++) {
            int bandIdx = i % bands.length;
            double targetH;
            if (playing) {
                targetH = (defaultHeights[i] * 0.4 + bands[bandIdx] * 0.6) * 48;
            } else {
                targetH = defaultHeights[i] * 38;
            }
            bars[i].setPrefHeight(Math.max(6, targetH));
        }
        refreshBarColors();
    }

    private void refreshBarColors() {
        int activeCutoff = (int) (currentProgressRatio * BAR_COUNT);
        for (int i = 0; i < BAR_COUNT; i++) {
            if (i <= activeCutoff) {
                // SoundCloud vibrant orange to yellow gradient
                bars[i].setStyle("-fx-background-radius: 3; -fx-background-color: linear-gradient(to top, #ff5500, #ffaa00);");
            } else {
                // Unplayed track waveform
                bars[i].setStyle("-fx-background-radius: 3; -fx-background-color: #3f3f46;");
            }
        }
    }
}
