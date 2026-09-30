package vn.studyroom;

import javax.sound.sampled.*;
import java.io.IOException;
import java.net.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-time VoIP Audio Engine using javax.sound.sampled and UDP datagram packets.
 * Captures microphone audio, packets it into 20ms PCM frames, and plays incoming streams.
 */
public final class VoiceEngine implements AutoCloseable {
    private static final AudioFormat FORMAT = new AudioFormat(16000.0f, 16, 1, true, false);
    private static final int FRAME_SIZE = 640; // 20ms of 16kHz 16-bit mono audio

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean muted = new AtomicBoolean(false);
    private final AtomicBoolean deafened = new AtomicBoolean(false);

    private DatagramSocket udpSocket;
    private TargetDataLine micLine;
    private SourceDataLine speakerLine;
    private final List<InetSocketAddress> peers = new CopyOnWriteArrayList<>();

    public int start(int preferredPort) {
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

        // Initialize audio speaker
        try {
            DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, FORMAT);
            if (AudioSystem.isLineSupported(speakerInfo)) {
                speakerLine = (SourceDataLine) AudioSystem.getLine(speakerInfo);
                speakerLine.open(FORMAT);
                speakerLine.start();
            }
        } catch (Exception ignored) { }

        // Initialize microphone
        try {
            DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (AudioSystem.isLineSupported(micInfo)) {
                micLine = (TargetDataLine) AudioSystem.getLine(micInfo);
                micLine.open(FORMAT);
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

        return port;
    }

    public void addPeer(String host, int port) {
        if (host != null && port > 0) {
            try {
                peers.add(new InetSocketAddress(InetAddress.getByName(host), port));
            } catch (UnknownHostException ignored) { }
        }
    }

    public void setMuted(boolean isMuted) {
        muted.set(isMuted);
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
            if (read > 0 && !muted.get() && !peers.isEmpty() && udpSocket != null && !udpSocket.isClosed()) {
                for (InetSocketAddress peer : peers) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, read, peer);
                        udpSocket.send(packet);
                    } catch (IOException ignored) { }
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
                if (!deafened.get() && speakerLine != null && speakerLine.isOpen()) {
                    speakerLine.write(packet.getData(), packet.getOffset(), packet.getLength());
                }
            } catch (IOException e) {
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
