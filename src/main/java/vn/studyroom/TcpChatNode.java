package vn.studyroom;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Peer-mesh transport: each desktop listens locally and can connect to known peers.
 * Payloads are newline-delimited, base64-encoded UTF-8. TLS/authentication must be
 * added before exposing this to an untrusted network. */
public final class TcpChatNode implements AutoCloseable {
    private final List<Socket> peers = new CopyOnWriteArrayList<>();
    private final Consumer<ChatPacket> receiver;
    private ServerSocket server;

    public TcpChatNode(Consumer<ChatPacket> receiver) { this.receiver = receiver; }

    public void start(int port) throws IOException {
        server = new ServerSocket(port);
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) try { attach(server.accept()); } catch (IOException ignored) { break; }
        });
    }
    public void connect(String host, int port) throws IOException { attach(new Socket(host, port)); }
    public void broadcast(String sender, String body) {
        String line = "CHAT|" + Base64.getEncoder().encodeToString(sender.getBytes(StandardCharsets.UTF_8)) + "|" + Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        for (Socket peer : peers) try {
            new PrintWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8), true).println(line);
        } catch (IOException e) { peers.remove(peer); closeQuietly(peer); }
    }
    private void attach(Socket socket) throws IOException {
        peers.add(socket);
        Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    String[] p = line.split("\\|", 3);
                    if (p.length == 3 && "CHAT".equals(p[0])) receiver.accept(new ChatPacket(decode(p[1]), decode(p[2])));
                }
            } catch (IOException ignored) { } finally { peers.remove(socket); closeQuietly(socket); }
        });
    }
    private static String decode(String input) { return new String(Base64.getDecoder().decode(input), StandardCharsets.UTF_8); }
    private static void closeQuietly(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }
    @Override public void close() { if (server != null) try { server.close(); } catch (IOException ignored) { } peers.forEach(TcpChatNode::closeQuietly); }
    public record ChatPacket(String sender, String body) { }
}
