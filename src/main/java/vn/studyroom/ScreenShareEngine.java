package vn.studyroom;

import javafx.application.Platform;
import javafx.scene.image.Image;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Screen Sharing Engine for live desktop presentation.
 * Captures the host screen at ~10 FPS, encodes JPEG, and streams to connected classroom peers.
 */
public final class ScreenShareEngine {
    private static final ScreenShareEngine INSTANCE = new ScreenShareEngine();

    public static ScreenShareEngine getInstance() {
        return INSTANCE;
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean isHost = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private final List<Socket> clientSockets = new CopyOnWriteArrayList<>();
    private Socket receiverSocket;
    private volatile Consumer<Image> localCallback;
    private volatile Consumer<Image> clientCallback;

    private ScreenShareEngine() { }

    public synchronized void startHost(int port, Consumer<Image> localFrameCallback) {
        this.localCallback = localFrameCallback;
        if (running.get() && isHost.get()) {
            return; // Already running as host, callback updated
        }

        stop();
        running.set(true);
        isHost.set(true);

        // Start ServerSocket for students to connect
        Thread.ofVirtual().start(() -> {
            try {
                ServerSocket server = new ServerSocket(port);
                serverSocket = server;
                while (running.get() && !server.isClosed()) {
                    Socket client = server.accept();
                    client.setTcpNoDelay(true);
                    clientSockets.add(client);
                }
            } catch (IOException ignored) { }
        });

        // Start Host Screen Capture loop
        Thread.ofVirtual().start(() -> {
            try {
                Robot robot = new Robot();
                Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
                Rectangle screenRect = new Rectangle(screenSize);

                int targetW = 1280;
                int targetH = (int) (screenSize.getHeight() * targetW / screenSize.getWidth());
                BufferedImage scaledBuffer = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);

                Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
                if (!writers.hasNext()) return;
                ImageWriter writer = writers.next();
                ImageWriteParam writeParam = writer.getDefaultWriteParam();
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                writeParam.setCompressionQuality(0.65f);

                while (running.get() && isHost.get()) {
                    long start = System.currentTimeMillis();
                    BufferedImage capture = robot.createScreenCapture(screenRect);

                    Graphics2D g = scaledBuffer.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(capture, 0, 0, targetW, targetH, null);
                    g.dispose();

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (MemoryCacheImageOutputStream mcios = new MemoryCacheImageOutputStream(baos)) {
                        writer.setOutput(mcios);
                        writer.write(null, new IIOImage(scaledBuffer, null, null), writeParam);
                    }
                    byte[] jpegData = baos.toByteArray();

                    // Host local preview
                    Consumer<Image> cb = localCallback;
                    if (cb != null) {
                        Image fxImg = new Image(new ByteArrayInputStream(jpegData));
                        Platform.runLater(() -> cb.accept(fxImg));
                    }

                    // Send to student peers
                    for (Socket s : clientSockets) {
                        if (s.isClosed()) {
                            clientSockets.remove(s);
                            continue;
                        }
                        try {
                            DataOutputStream dos = new DataOutputStream(s.getOutputStream());
                            dos.writeInt(jpegData.length);
                            dos.write(jpegData);
                            dos.flush();
                        } catch (IOException e) {
                            try { s.close(); } catch (IOException ignored) {}
                            clientSockets.remove(s);
                        }
                    }

                    long elapsed = System.currentTimeMillis() - start;
                    long sleepTime = 100 - elapsed;
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime);
                    }
                }
            } catch (Exception e) {
                // Screen capture stopped or unsupported
            }
        });
    }

    public synchronized void startClient(String hostIp, int port, Consumer<Image> frameCallback) {
        this.clientCallback = frameCallback;
        if (running.get() && !isHost.get()) {
            return; // Already running as client, callback updated
        }

        stop();
        running.set(true);
        isHost.set(false);

        Thread.ofVirtual().start(() -> {
            int retries = 0;
            while (running.get() && !isHost.get() && retries < 25) {
                try {
                    Socket socket = new Socket();
                    receiverSocket = socket;
                    socket.connect(new InetSocketAddress(hostIp, port), 4000);
                    socket.setTcpNoDelay(true);

                    DataInputStream dis = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                    while (running.get() && !socket.isClosed()) {
                        int len = dis.readInt();
                        if (len <= 0 || len > 10_000_000) break;
                        byte[] data = new byte[len];
                        dis.readFully(data);

                        Consumer<Image> cb = clientCallback;
                        if (cb != null) {
                            Image fxImg = new Image(new ByteArrayInputStream(data));
                            Platform.runLater(() -> cb.accept(fxImg));
                        }
                    }
                } catch (Exception e) {
                    try { Thread.sleep(1200); } catch (InterruptedException ignored) { break; }
                    retries++;
                }
            }
        });
    }

    public synchronized void stop() {
        running.set(false);
        isHost.set(false);

        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) { }
            serverSocket = null;
        }

        for (Socket s : clientSockets) {
            try { s.close(); } catch (Exception ignored) { }
        }
        clientSockets.clear();

        if (receiverSocket != null) {
            try { receiverSocket.close(); } catch (Exception ignored) { }
            receiverSocket = null;
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
