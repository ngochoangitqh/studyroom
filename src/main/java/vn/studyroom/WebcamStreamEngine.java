package vn.studyroom;

import javafx.application.Platform;
import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Real-time UDP Video Streaming Engine for Peer-to-Peer Classroom Webcams.
 * Broadcasts local webcam JPEG frames to classroom peers and dispatches
 * incoming peer frames to their corresponding member video tiles.
 */
public final class WebcamStreamEngine implements AutoCloseable {
    private static final WebcamStreamEngine INSTANCE = new WebcamStreamEngine();
    private static final byte[] MAGIC = new byte[] { 'W', 'C', 'A', 'M' };
    private static final int MAX_PACKET_SIZE = 65507;

    public static WebcamStreamEngine getInstance() {
        return INSTANCE;
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private DatagramSocket udpSocket;
    private final Set<InetSocketAddress> peers = new CopyOnWriteArraySet<>();
    private final Map<String, Consumer<Image>> memberCallbacks = new ConcurrentHashMap<>();
    private final Map<String, Long> lastFrameTime = new ConcurrentHashMap<>();

    private WebcamStreamEngine() { }

    public synchronized int start(int preferredPort) {
        if (running.get() && udpSocket != null && !udpSocket.isClosed()) {
            return udpSocket.getLocalPort();
        }
        stop();
        running.set(true);

        int port = preferredPort;
        while (udpSocket == null && port < preferredPort + 50) {
            try {
                udpSocket = new DatagramSocket(port);
            } catch (SocketException e) {
                port++;
            }
        }
        if (udpSocket == null) {
            try {
                udpSocket = new DatagramSocket();
                port = udpSocket.getLocalPort();
            } catch (SocketException e) {
                port = 0;
            }
        }

        if (udpSocket != null) {
            try {
                udpSocket.setReceiveBufferSize(256 * 1024);
                udpSocket.setSendBufferSize(256 * 1024);
            } catch (SocketException ignored) { }
            Thread.ofVirtual().start(this::receiveLoop);
        }

        return port;
    }

    public void addPeer(String host, int port) {
        if (host != null && !host.isBlank() && port > 0) {
            try {
                peers.add(new InetSocketAddress(InetAddress.getByName(host), port));
            } catch (UnknownHostException ignored) { }
        }
    }

    public void clearPeers() {
        peers.clear();
    }

    public void registerMemberCallback(String username, Consumer<Image> callback) {
        if (username != null && callback != null) {
            memberCallbacks.put(username, callback);
        }
    }

    public void unregisterMemberCallback(String username) {
        if (username != null) {
            memberCallbacks.remove(username);
            lastFrameTime.remove(username);
        }
    }

    public boolean hasRecentVideo(String username) {
        Long t = lastFrameTime.get(username);
        return t != null && (System.currentTimeMillis() - t < 3000);
    }

    public void broadcastFrame(String username, byte[] jpegData) {
        if (!running.get() || udpSocket == null || udpSocket.isClosed() || peers.isEmpty() || jpegData == null || jpegData.length == 0) {
            return;
        }

        byte[] userBytes = username.getBytes(StandardCharsets.UTF_8);
        if (userBytes.length > 255) return;

        // Packet format: [MAGIC: 4 bytes] [user_len: 1 byte] [user: N bytes] [timestamp: 8 bytes] [jpeg_data]
        int totalLen = 4 + 1 + userBytes.length + 8 + jpegData.length;
        if (totalLen > MAX_PACKET_SIZE) return;

        ByteBuffer bb = ByteBuffer.allocate(totalLen);
        bb.put(MAGIC);
        bb.put((byte) userBytes.length);
        bb.put(userBytes);
        bb.putLong(System.currentTimeMillis());
        bb.put(jpegData);
        byte[] packetBytes = bb.array();

        for (InetSocketAddress peer : peers) {
            try {
                DatagramPacket packet = new DatagramPacket(packetBytes, packetBytes.length, peer);
                udpSocket.send(packet);
            } catch (IOException ignored) { }
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[MAX_PACKET_SIZE];
        while (running.get() && udpSocket != null && !udpSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);

                // Auto-learn peer for symmetric UDP NAT traversal
                peers.add(new InetSocketAddress(packet.getAddress(), packet.getPort()));

                int len = packet.getLength();
                if (len < 13) continue; // Minimum header size

                byte[] data = packet.getData();
                int offset = packet.getOffset();

                // Validate MAGIC
                if (data[offset] != MAGIC[0] || data[offset + 1] != MAGIC[1] ||
                    data[offset + 2] != MAGIC[2] || data[offset + 3] != MAGIC[3]) {
                    continue;
                }

                int userLen = data[offset + 4] & 0xFF;
                if (offset + 5 + userLen + 8 > offset + len) continue;

                String senderUsername = new String(data, offset + 5, userLen, StandardCharsets.UTF_8);
                int jpegOffset = offset + 5 + userLen + 8;
                int jpegLen = len - (5 + userLen + 8);
                if (jpegLen <= 0) continue;

                lastFrameTime.put(senderUsername, System.currentTimeMillis());

                Consumer<Image> callback = memberCallbacks.get(senderUsername);
                if (callback != null) {
                    try {
                        ByteArrayInputStream bais = new ByteArrayInputStream(data, jpegOffset, jpegLen);
                        Image img = new Image(bais);
                        if (!img.isError() && img.getWidth() > 0) {
                            Platform.runLater(() -> callback.accept(img));
                        }
                    } catch (Exception ignored) { }
                }
            } catch (IOException e) {
                break;
            }
        }
    }

    public synchronized void stop() {
        running.set(false);
        peers.clear();
        memberCallbacks.clear();
        lastFrameTime.clear();
        if (udpSocket != null) {
            try { udpSocket.close(); } catch (Exception ignored) { }
            udpSocket = null;
        }
    }

    @Override
    public void close() {
        stop();
    }
}
