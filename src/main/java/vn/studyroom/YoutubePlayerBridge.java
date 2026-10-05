package vn.studyroom;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embeds a real YouTube player using JavaFX WebView served through a local HTTP server origin.
 *
 * Serving the embed iframe from http://127.0.0.1:{port} fixes:
 *  - YouTube Error 153 (Video player configuration error caused by missing/invalid origin & referrer)
 *  - Embed cross-origin iframe security restrictions
 *
 * Supports:
 *  - Direct links: youtube.com/watch?v=..., youtu.be/..., shorts/..., embed/...
 *  - Playlists: youtube.com/playlist?list=...
 *  - Direct 11-char Video ID
 *  - Free-text search: "sơn tùng mtp", "lofi chill study", etc.
 */
public class YoutubePlayerBridge {

    private static HttpServer server;
    private static int serverPort = 0;

    private static synchronized void ensureServerStarted() {
        if (server != null) return;
        try {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 20153), 0);
            } catch (Exception e) {
                // If 20153 is occupied, bind to any ephemeral available port
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            }
            serverPort = server.getAddress().getPort();
            server.createContext("/player", new PlayerHttpHandler());
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "yt-http-worker");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            System.out.println("[YTBridge] Local HTTP embed server started at http://127.0.0.1:" + serverPort);
        } catch (IOException e) {
            System.err.println("[YTBridge] Failed to start local HTTP server: " + e.getMessage());
        }
    }

    private final WebView webView;
    private final WebEngine webEngine;
    private String currentVideoId = null;
    private String currentPlaylistId = null;

    public YoutubePlayerBridge() {
        ensureServerStarted();

        webView = new WebView();
        webEngine = webView.getEngine();
        webEngine.setJavaScriptEnabled(true);

        // Modern Chrome User-Agent
        webEngine.setUserAgent(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
        );

        // Debug logging
        webEngine.setOnError(e -> System.err.println("[YTBridge] Error: " + e.getMessage()));
        webEngine.getLoadWorker().exceptionProperty().addListener((obs, old, ex) -> {
            if (ex != null) System.err.println("[YTBridge] Exception: " + ex.getMessage());
        });
        webEngine.getLoadWorker().stateProperty().addListener((obs, old, state) ->
            System.out.println("[YTBridge] Load state: " + state));

        // Start minimal
        webView.setMinSize(1, 1);
        webView.setPrefSize(1, 1);
        webView.setMaxSize(1, 1);
        webView.setOpacity(0.01);
    }

    public WebView getWebView() {
        return webView;
    }

    /**
     * Extract YouTube video ID from various URL formats.
     */
    public static String extractVideoId(String url) {
        if (url == null || url.isBlank()) return null;
        String trimmed = url.trim();

        // Direct 11-character video ID
        if (trimmed.matches("^[A-Za-z0-9_\\-]{11}$")) {
            return trimmed;
        }

        // youtu.be/XXXX
        Matcher m1 = Pattern.compile("youtu\\.be/([A-Za-z0-9_\\-]{11})").matcher(trimmed);
        if (m1.find()) return m1.group(1);

        // [?&]v=XXXX
        Matcher m2 = Pattern.compile("[?&]v=([A-Za-z0-9_\\-]{11})").matcher(trimmed);
        if (m2.find()) return m2.group(1);

        // /(embed|shorts|v)/XXXX
        Matcher m3 = Pattern.compile("/(?:embed|shorts|v)/([A-Za-z0-9_\\-]{11})").matcher(trimmed);
        if (m3.find()) return m3.group(1);

        // /live/XXXX
        Matcher m4 = Pattern.compile("/live/([A-Za-z0-9_\\-]{11})").matcher(trimmed);
        if (m4.find()) return m4.group(1);

        return null;
    }

    /**
     * Extract YouTube playlist ID from URL.
     */
    public static String extractPlaylistId(String url) {
        if (url == null || url.isBlank()) return null;
        Matcher m = Pattern.compile("[?&]list=([A-Za-z0-9_\\-]+)").matcher(url.trim());
        if (m.find()) return m.group(1);
        return null;
    }

    public static boolean isYoutubeUrl(String url) {
        if (url == null) return false;
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase();
        return lower.contains("youtube.com") || lower.contains("youtu.be") || trimmed.matches("^[A-Za-z0-9_\\-]{11}$");
    }

    /**
     * Search YouTube for a query and return top video ID and estimated title.
     */
    public static SearchResult searchVideo(String query) {
        if (query == null || query.isBlank()) return null;
        try {
            String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://www.youtube.com/results?search_query=" + encoded))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
                .timeout(Duration.ofSeconds(6))
                .GET()
                .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                Matcher mId = Pattern.compile("\"videoId\":\"([a-zA-Z0-9_\\-]{11})\"").matcher(body);
                if (mId.find()) {
                    String vid = mId.group(1);
                    return new SearchResult(vid, query.trim());
                }
            }
        } catch (Exception e) {
            System.err.println("[YTBridge] Search failed: " + e.getMessage());
        }
        return null;
    }

    public record SearchResult(String videoId, String title) {}

    /**
     * Load and play a YouTube video or playlist by URL or free-text search.
     * Serves through local HTTP server to prevent Error 153.
     */
    public YoutubeTrackInfo loadUrl(String input) {
        if (input == null || input.isBlank()) return null;
        String trimmed = input.trim();

        String videoId = extractVideoId(trimmed);
        String playlistId = extractPlaylistId(trimmed);
        String trackTitle = null;

        // If not a direct YouTube URL, try searching YouTube for the keyword
        if (videoId == null && playlistId == null && !trimmed.contains(".")) {
            System.out.println("[YTBridge] Searching YouTube for query: " + trimmed);
            SearchResult sr = searchVideo(trimmed);
            if (sr != null) {
                videoId = sr.videoId();
                trackTitle = sr.title();
            }
        }

        if (videoId == null && playlistId == null) {
            System.err.println("[YTBridge] Cannot parse or find video for: " + trimmed);
            return null;
        }

        currentVideoId = videoId;
        currentPlaylistId = playlistId;

        ensureServerStarted();

        // Construct local player URL served from our HTTP server
        StringBuilder localUrl = new StringBuilder("http://127.0.0.1:")
                .append(serverPort)
                .append("/player?");

        if (videoId != null) {
            localUrl.append("v=").append(videoId);
            if (playlistId != null) {
                localUrl.append("&list=").append(playlistId);
            }
        } else {
            localUrl.append("list=").append(playlistId);
        }

        final String finalUrl = localUrl.toString();
        System.out.println("[YTBridge] Loading via local HTTP server: " + finalUrl);
        Platform.runLater(() -> webEngine.load(finalUrl));

        if (trackTitle == null) {
            trackTitle = videoId != null ? "YouTube · " + videoId : "YouTube Playlist";
        }

        return new YoutubeTrackInfo(trackTitle, videoId, playlistId, trimmed);
    }

    public void pause() {
        Platform.runLater(() -> {
            try {
                webEngine.executeScript(
                    "var p = document.getElementById('ytplayer'); " +
                    "if (p && p.contentWindow) { " +
                    "  p.contentWindow.postMessage('{\"event\":\"command\",\"func\":\"pauseVideo\",\"args\":\"\"}', '*'); " +
                    "}"
                );
            } catch (Exception ignored) {}
        });
    }

    public void resume() {
        Platform.runLater(() -> {
            try {
                webEngine.executeScript(
                    "var p = document.getElementById('ytplayer'); " +
                    "if (p && p.contentWindow) { " +
                    "  p.contentWindow.postMessage('{\"event\":\"command\",\"func\":\"playVideo\",\"args\":\"\"}', '*'); " +
                    "}"
                );
            } catch (Exception ignored) {}
        });
    }

    public void stop() {
        Platform.runLater(() -> {
            try {
                webEngine.load("about:blank");
            } catch (Exception ignored) {}
        });
        currentVideoId = null;
        currentPlaylistId = null;
    }

    public String getCurrentVideoId() { return currentVideoId; }
    public String getCurrentPlaylistId() { return currentPlaylistId; }

    public record YoutubeTrackInfo(
        String title,
        String videoId,
        String playlistId,
        String originalUrl
    ) {
        public boolean isPlaylist() { return playlistId != null && videoId == null; }
    }

    // =========================================================================
    // LOCAL HTTP SERVER HANDLER FOR EMBEDDING YOUTUBE
    // =========================================================================
    private static class PlayerHttpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                URI uri = exchange.getRequestURI();
                Map<String, String> params = parseQuery(uri.getRawQuery());

                String videoId = params.get("v");
                String playlistId = params.get("list");

                String embedUrl;
                if (videoId != null && !videoId.isBlank()) {
                    embedUrl = "https://www.youtube-nocookie.com/embed/" + videoId +
                            "?autoplay=1&enablejsapi=1&rel=0&playsinline=1&origin=http://127.0.0.1:" + serverPort;
                    if (playlistId != null && !playlistId.isBlank()) {
                        embedUrl += "&list=" + playlistId;
                    }
                } else if (playlistId != null && !playlistId.isBlank()) {
                    embedUrl = "https://www.youtube-nocookie.com/embed/videoseries?list=" + playlistId +
                            "&autoplay=1&enablejsapi=1&rel=0&playsinline=1&origin=http://127.0.0.1:" + serverPort;
                } else {
                    embedUrl = "about:blank";
                }

                String html = """
                    <!DOCTYPE html>
                    <html lang="vi">
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1.0">
                      <meta name="referrer" content="strict-origin-when-cross-origin">
                      <title>YouTube Player</title>
                      <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        html, body {
                          width: 100%;
                          height: 100%;
                          background: #000;
                          overflow: hidden;
                          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                        }
                        #wrapper {
                          width: 100%;
                          height: 100%;
                          position: relative;
                        }
                        iframe {
                          width: 100%;
                          height: 100%;
                          border: none;
                          display: block;
                        }
                      </style>
                    </head>
                    <body>
                      <div id="wrapper">
                        <iframe
                          id="ytplayer"
                          src="%s"
                          referrerpolicy="strict-origin-when-cross-origin"
                          allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
                          allowfullscreen>
                        </iframe>
                      </div>
                    </body>
                    </html>
                    """.formatted(embedUrl);

                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception e) {
                System.err.println("[YTBridge] HTTP handler error: " + e.getMessage());
                exchange.sendResponseHeaders(500, -1);
            }
        }

        private Map<String, String> parseQuery(String rawQuery) {
            Map<String, String> map = new HashMap<>();
            if (rawQuery == null || rawQuery.isBlank()) return map;
            String[] pairs = rawQuery.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                if (idx > 0) {
                    String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                    String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                    map.put(key, val);
                }
            }
            return map;
        }
    }
}
