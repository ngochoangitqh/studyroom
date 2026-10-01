package vn.studyroom;

import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamResolution;
import javafx.application.Platform;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Real-time Webcam capture engine using Webcam Capture library.
 * Captures from local webcam (e.g. ACER HD User Facing) and provides frames to JavaFX.
 */
public final class CameraEngine {
    private static final CameraEngine INSTANCE = new CameraEngine();

    public static CameraEngine getInstance() {
        return INSTANCE;
    }

    private Webcam webcam;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Consumer<Image> currentCallback;
    private volatile Consumer<byte[]> rawFrameCallback;

    private CameraEngine() { }

    public void setRawFrameCallback(Consumer<byte[]> callback) {
        this.rawFrameCallback = callback;
    }

    public synchronized void start(Consumer<Image> frameCallback) {
        this.currentCallback = frameCallback;
        if (running.get()) return;
        running.set(true);

        Thread.ofVirtual().start(() -> {
            try {
                webcam = Webcam.getDefault();
                if (webcam != null) {
                    Dimension size = WebcamResolution.QVGA.getSize(); // 320x240 for crisp fast tile
                    webcam.setViewSize(size);
                    webcam.open(true);

                    while (running.get() && webcam.isOpen()) {
                        BufferedImage bi = webcam.getImage();
                        if (bi != null) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ImageIO.write(bi, "jpg", baos);
                            byte[] rawBytes = baos.toByteArray();

                            Consumer<byte[]> rcb = rawFrameCallback;
                            if (rcb != null) {
                                rcb.accept(rawBytes);
                            }

                            Image fxImg = new Image(new ByteArrayInputStream(rawBytes));
                            Consumer<Image> cb = currentCallback;
                            if (cb != null) {
                                Platform.runLater(() -> cb.accept(fxImg));
                            }
                        }
                        Thread.sleep(66); // ~15 FPS
                    }
                }
            } catch (Exception e) {
                // Webcam busy or unsupported
            } finally {
                closeWebcam();
            }
        });
    }

    public synchronized void stop() {
        running.set(false);
        closeWebcam();
    }

    private void closeWebcam() {
        if (webcam != null) {
            try { webcam.close(); } catch (Exception ignored) { }
            webcam = null;
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
