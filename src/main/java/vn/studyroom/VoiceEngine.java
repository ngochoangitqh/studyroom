package vn.studyroom;

import javax.sound.sampled.*;
import java.io.IOException;
import java.net.*;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Real-time VoIP Audio Engine using javax.sound.sampled and UDP datagram packets.
 * Features noise gate filtering, jitter buffering, and duplicate peer elimination
 * to provide crisp, noise-free voice communications.
 */
public final class VoiceEngine implements AutoCloseable {
    private static final AudioFormat FORMAT = new AudioFormat(16000.0f, 16, 1, true, false);
    private static final int FRAME_SIZE = 640; // 20ms of 16kHz 16-bit mono audio
    private static final double NOISE_THRESHOLD = 28.0; // Highly sensitive noise gate for crisp speech pickup

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean muted = new AtomicBoolean(false);
    private final AtomicBoolean deafened = new AtomicBoolean(false);

    private DatagramSocket udpSocket;
    private TargetDataLine micLine;
    private SourceDataLine speakerLine;
    private final Set<InetSocketAddress> peers = new CopyOnWriteArraySet<>();

    private volatile Consumer<Boolean> localSpeakingCallback;
    private volatile BiConsumer<String, Integer> remoteSpeakingCallback;
    private volatile boolean localSpeakingState = false;
    private volatile long lastLocalSpeechTime = 0;

    public void setLocalSpeakingCallback(Consumer<Boolean> callback) {
        this.localSpeakingCallback = callback;
    }

    public void setRemoteSpeakingCallback(BiConsumer<String, Integer> callback) {
        this.remoteSpeakingCallback = callback;
    }

    public void clearPeers() {
        peers.clear();
    }

    public int start(int preferredPort) {
        if (running.get() && udpSocket != null && !udpSocket.isClosed()) {
            return udpSocket.getLocalPort();
        }
        stop();
        running.set(true);

        // Bind UDP socket
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
                udpSocket = new DatagramSocket(); // pick random available port
                port = udpSocket.getLocalPort();
            } catch (SocketException e) {
                port = 0;
            }
        }

        // Initialize audio speaker with 200ms buffer to prevent underrun clicking
        try {
            DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, FORMAT);
            if (AudioSystem.isLineSupported(speakerInfo)) {
                speakerLine = (SourceDataLine) AudioSystem.getLine(speakerInfo);
                speakerLine.open(FORMAT, FRAME_SIZE * 10);
                speakerLine.start();
            }
        } catch (Exception e) {
            // Fallback: try finding any playback mixer that supports SourceDataLine
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (mi.getDescription().contains("Playback") || mi.getName().contains("Speakers") || mi.getName().contains("Loa")) {
                    try {
                        Mixer m = AudioSystem.getMixer(mi);
                        DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, FORMAT);
                        if (m.isLineSupported(speakerInfo)) {
                            speakerLine = (SourceDataLine) m.getLine(speakerInfo);
                            speakerLine.open(FORMAT, FRAME_SIZE * 10);
                            speakerLine.start();
                            break;
                        }
                    } catch (Exception ignored) { }
                }
            }
        }

        // Initialize microphone with 200ms buffer
        try {
            DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (AudioSystem.isLineSupported(micInfo)) {
                micLine = (TargetDataLine) AudioSystem.getLine(micInfo);
                micLine.open(FORMAT, FRAME_SIZE * 10);
                micLine.start();
            }
        } catch (Exception ignored) { }

        // Start UDP Receiver thread
        if (udpSocket != null) {
            Thread.ofVirtual().start(this::receiveLoop);
        }

        // Start Mic Sender thread
        if (micLine != null && udpSocket != null) {
            Thread.ofVirtual().start(this::sendLoop);
        }

        // Start Keepalive Ping thread to punch NAT and open stateful firewalls
        if (udpSocket != null) {
            Thread.ofVirtual().start(this::keepaliveLoop);
        }

        return port;
    }

    public static String getLocalIp() {
        // 1. Try socket routing towards 8.8.8.8
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 10002);
            String ip = s.getLocalAddress().getHostAddress();
            if (ip != null && !ip.startsWith("127.") && !ip.equals("0.0.0.0")) {
                return ip;
            }
        } catch (Exception ignored) { }

        // 2. Iterate network interfaces for site-local LAN IP (192.168.x.x, 10.x.x.x, 172.x.x.x)
        try {
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                java.util.Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String host = addr.getHostAddress();
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host;
                        }
                    }
                }
            }
        } catch (Exception ignored) { }

        // 3. Fallback
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ex) {
            return "127.0.0.1";
        }
    }

    public void addPeer(String host, int port) {
        if (host != null && !host.isBlank() && port > 0) {
            try {
                peers.add(new InetSocketAddress(InetAddress.getByName(host), port));
            } catch (UnknownHostException ignored) { }
        }
    }

    public void setMuted(boolean isMuted) {
        muted.set(isMuted);
        if (isMuted && localSpeakingState) {
            localSpeakingState = false;
            Consumer<Boolean> cb = localSpeakingCallback;
            if (cb != null) cb.accept(false);
        }
    }

    public boolean isMuted() {
        return muted.get();
    }

    public void setDeafened(boolean isDeafened) {
        deafened.set(isDeafened);
    }

    public boolean isDeafened() {
        return deafened.get();
    }

    private void sendLoop() {
        byte[] buffer = new byte[FRAME_SIZE];
        while (running.get() && micLine != null && !micLine.isOpen()) {
            try { Thread.sleep(50); } catch (InterruptedException e) { return; }
        }

        while (running.get() && micLine != null && micLine.isOpen()) {
            int read = micLine.read(buffer, 0, buffer.length);
            if (read > 0 && !muted.get()) {
                // Apply 1.8x clean software mic gain for loud & clear speech pickup
                for (int i = 0; i < read - 1; i += 2) {
                    short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
                    int boosted = (int) (sample * 1.8);
                    if (boosted > 32767) boosted = 32767;
                    else if (boosted < -32768) boosted = -32768;
                    buffer[i] = (byte) (boosted & 0xFF);
                    buffer[i + 1] = (byte) ((boosted >> 8) & 0xFF);
                }

                // Calculate RMS level of voice
                long sum = 0;
                for (int i = 0; i < read - 1; i += 2) {
                    short val = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
                    sum += (long) val * val;
                }
                double rms = Math.sqrt((double) sum / (read / 2.0));

                // Voice Activity Detection with sensitive threshold
                long now = System.currentTimeMillis();
                boolean speaking = rms > 45.0;
                if (speaking) {
                    lastLocalSpeechTime = now;
                    if (!localSpeakingState) {
                        localSpeakingState = true;
                        Consumer<Boolean> cb = localSpeakingCallback;
                        if (cb != null) cb.accept(true);
                    }
                } else if (localSpeakingState && (now - lastLocalSpeechTime > 350)) {
                    localSpeakingState = false;
                    Consumer<Boolean> cb = localSpeakingCallback;
                    if (cb != null) cb.accept(false);
                }

                // If above noise threshold and peers exist, broadcast UDP packet
                if (rms >= NOISE_THRESHOLD && !peers.isEmpty() && udpSocket != null && !udpSocket.isClosed()) {
                    for (InetSocketAddress peer : peers) {
                        try {
                            DatagramPacket packet = new DatagramPacket(buffer, read, peer);
                            udpSocket.send(packet);
                        } catch (IOException ignored) { }
                    }
                }
            }
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[FRAME_SIZE * 2];
        while (running.get() && udpSocket != null && !udpSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);

                byte[] data = packet.getData();
                int offset = packet.getOffset();
                int length = packet.getLength();

                // Auto-learn peer for symmetric UDP return & NAT traversal
                peers.add(new InetSocketAddress(packet.getAddress(), packet.getPort()));

                // Ignore PING packet (keepalive only)
                if (length == 4 && data[offset] == 'P' && data[offset + 1] == 'I' && data[offset + 2] == 'N' && data[offset + 3] == 'G') {
                    continue;
                }

                // Notify voice activity from peer
                String senderIp = packet.getAddress().getHostAddress();
                int senderPort = packet.getPort();
                BiConsumer<String, Integer> rcb = remoteSpeakingCallback;
                if (rcb != null) {
                    rcb.accept(senderIp, senderPort);
                }

                if (!deafened.get() && speakerLine != null && speakerLine.isOpen()) {
                    speakerLine.write(data, offset, length);
                }
            } catch (IOException e) {
                break;
            }
        }
    }

    private void keepaliveLoop() {
        byte[] pingBytes = "PING".getBytes();
        while (running.get() && udpSocket != null && !udpSocket.isClosed()) {
            try {
                Thread.sleep(2000);
                if (!peers.isEmpty() && udpSocket != null && !udpSocket.isClosed()) {
                    for (InetSocketAddress peer : peers) {
                        try {
                            DatagramPacket packet = new DatagramPacket(pingBytes, pingBytes.length, peer);
                            udpSocket.send(packet);
                        } catch (IOException ignored) { }
                    }
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    public void stop() {
        running.set(false);
        peers.clear();

        if (micLine != null) {
            try { micLine.stop(); micLine.close(); } catch (Exception ignored) { }
            micLine = null;
        }
        if (speakerLine != null) {
            try { speakerLine.stop(); speakerLine.close(); } catch (Exception ignored) { }
            speakerLine = null;
        }
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
