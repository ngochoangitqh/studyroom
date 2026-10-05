package vn.studyroom;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service to resolve Spotify metadata via oEmbed API and provide curated Spotify playlists.
 */
public final class SpotifyService {
    private static final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public record SpotifyTrackInfo(
        String title,
        String artist,
        String thumbnailUrl,
        String iframeUrl,
        String spotifyUrl,
        boolean isPlaylist
    ) {}

    public record SpotifyPreset(
        String name,
        String category,
        String spotifyUrl,
        String defaultThumbnail,
        String description
    ) {}

    public static List<SpotifyPreset> getCuratedPresets() {
        return List.of(
            new SpotifyPreset(
                "Chill Lofi Study Beats", "🎧 Lofi",
                "https://open.spotify.com/playlist/37i9dQZF1DX8Uebhn9wzrS",
                "https://i.scdn.co/image/ab67706f000000021498ddc160581579f9b55979",
                "Giai điệu lofi nhẹ nhàng từ Spotify giúp bạn tập trung học bài và làm việc."
            ),
            new SpotifyPreset(
                "Deep Focus", "🧠 Tập trung",
                "https://open.spotify.com/playlist/37i9dQZF1DWZeKCadgRdKQ",
                "https://i.scdn.co/image/ab67706f000000025551996f500d9f805a57e0d8",
                "Nhạc ambient êm dịu, loại bỏ tạp âm xung quanh cho trạng thái dòng chảy."
            ),
            new SpotifyPreset(
                "Peaceful Piano", "🎹 Piano",
                "https://open.spotify.com/playlist/37i9dQZF1DX4sWSpwq3LiO",
                "https://i.scdn.co/image/ab67706f00000002d073e656e546e43bc387ad79",
                "Những phím đàn piano mộc thư giãn và sâu lắng."
            ),
            new SpotifyPreset(
                "Coffee Table Jazz", "☕ Quán Cafe",
                "https://open.spotify.com/playlist/37i9dQZF1DX65BNHJha8KG",
                "https://i.scdn.co/image/ab67706f00000002c918a5be8933b9b9a6d09c25",
                "Jazz mộc ấm áp như đang ngồi học tại một quán cafe yên tĩnh."
            ),
            new SpotifyPreset(
                "Nhạc Việt Chill & Acoustic", "🇻🇳 V-Pop",
                "https://open.spotify.com/playlist/37i9dQZF1DX4g8Gs5vgUm9",
                "https://i.scdn.co/image/ab67706f00000002d257262078bfdcab1d92cfb9",
                "Tuyển tập giai điệu Indie và Acoustic Việt Nam thư giãn tâm hồn."
            ),
            new SpotifyPreset(
                "Coding Mode & Synthwave", "💻 Lập trình",
                "https://open.spotify.com/playlist/37i9dQZF1DX5trt9i14X7j",
                "https://i.scdn.co/image/ab67706f000000029249b3c997239ad41a5270f1",
                "Nhịp điệu điện tử Retro & Cyberpunk kích thích tư duy logic khi viết code."
            ),
            new SpotifyPreset(
                "Rain Sounds for Sleep & Study", "🌧️ Mưa rào",
                "https://open.spotify.com/playlist/37i9dQZF1DX8ymr6UES72F",
                "https://i.scdn.co/image/ab67706f00000002f5a560f845d064cf4f88e734",
                "Tiếng mưa tự nhiên trắng giúp giảm âu lo và tập trung ôn thi."
            )
        );
    }

    public static SpotifyTrackInfo resolveUrl(String spotifyUrl) {
        if (spotifyUrl == null || spotifyUrl.isBlank()) return null;
        String trimmed = spotifyUrl.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://" + trimmed;
        }

        try {
            String encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8);
            String apiUrl = "https://open.spotify.com/oembed?url=" + encoded;

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .timeout(Duration.ofSeconds(6))
                .GET()
                .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                String json = resp.body();
                String title = extractJsonField(json, "title");
                String thumb = extractJsonField(json, "thumbnail_url");
                String iframeUrl = extractJsonField(json, "iframe_url");
                String html = extractJsonField(json, "html");

                if (iframeUrl == null || iframeUrl.isBlank()) {
                    if (html != null && html.contains("src=\"")) {
                        int s = html.indexOf("src=\"") + 5;
                        int e = html.indexOf("\"", s);
                        if (e > s) iframeUrl = html.substring(s, e);
                    }
                }

                if (iframeUrl == null || iframeUrl.isBlank()) {
                    iframeUrl = toEmbedUrl(trimmed);
                } else if (!iframeUrl.contains("theme=")) {
                    iframeUrl += (iframeUrl.contains("?") ? "&" : "?") + "theme=0";
                }

                boolean isPlaylist = trimmed.contains("/playlist/") || trimmed.contains("/album/");
                String artist = "Spotify Music";
                if (title != null && title.contains(" by ")) {
                    String[] parts = title.split(" by ", 2);
                    title = parts[0];
                    artist = parts[1];
                }

                return new SpotifyTrackInfo(title != null ? title : "Spotify Track", artist, thumb, iframeUrl, trimmed, isPlaylist);
            }
        } catch (Exception ex) {
            System.err.println("Could not resolve Spotify URL: " + ex.getMessage());
        }

        // Fallback
        String fallbackIframe = toEmbedUrl(trimmed);
        return new SpotifyTrackInfo("Spotify Stream", "Spotify", null, fallbackIframe, trimmed, trimmed.contains("/playlist/"));
    }

    public static String toEmbedUrl(String spotifyUrl) {
        String clean = spotifyUrl.replace("open.spotify.com/", "open.spotify.com/embed/");
        if (!clean.contains("?")) clean += "?theme=0";
        else clean += "&theme=0";
        return clean;
    }

    private static String extractJsonField(String json, String field) {
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (m.find()) {
            return unescapeJson(m.group(1));
        }
        return null;
    }

    private static String unescapeJson(String input) {
        if (input == null) return null;
        return input.replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\/", "/")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replaceAll("\\\\u([0-9a-fA-F]{4})", "");
    }
}
