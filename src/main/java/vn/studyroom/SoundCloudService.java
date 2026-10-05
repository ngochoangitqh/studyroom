package vn.studyroom;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service to resolve track & playlist metadata from SoundCloud using oEmbed API
 * and provide curated SoundCloud study playlists.
 */
public final class SoundCloudService {
    private static final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public record SoundCloudPreset(
        String name,
        String category,
        String soundcloudUrl,
        String defaultThumbnail,
        String description
    ) {}

    public static List<SoundCloudPreset> getCuratedPresets() {
        return List.of(
            new SoundCloudPreset(
                "Chillhop Essentials", "🎧 Lofi",
                "https://soundcloud.com/chillhopdotcom/sets/chillhop-essentials-summer-2023",
                "https://i1.sndcdn.com/artworks-ffArlDGZ03CyjwhK-NhkZZQ-t500x500.jpg",
                "Tuyển tập 28 bản Lofi Chillhop mượt mà từ Chillhop Music."
            ),
            new SoundCloudPreset(
                "Lofi Hip Hop Study Beats", "🎧 Lofi",
                "https://soundcloud.com/lofigirl-music/sets/lofi-hip-hop-beats-to-relax-study-to",
                "https://i1.sndcdn.com/artworks-000572886359-n56w57-t500x500.jpg",
                "Những giai điệu kinh điển từ kênh Lofi Girl giúp tập trung học."
            ),
            new SoundCloudPreset(
                "Starbucks Coffeehouse Acoustic", "☕ Cafe",
                "https://soundcloud.com/starbuckscoffee/sets/starbucks-coffeehouse-acoustic",
                "https://i1.sndcdn.com/artworks-000109968435-0814u1-t500x500.jpg",
                "Không gian quán cafe ấm cúng với tiếng đàn guitar mộc mạc."
            ),
            new SoundCloudPreset(
                "Sungha Jung Acoustic Fingerstyle", "☕ Cafe",
                "https://soundcloud.com/sungha-jung/sets/acoustic-fingerstyle",
                "https://i1.sndcdn.com/artworks-000085442111-2m5t90-t500x500.jpg",
                "Guitar fingerstyle acoustic đỉnh cao của Sungha Jung."
            ),
            new SoundCloudPreset(
                "Yiruma Best Piano Collection", "🎹 Piano",
                "https://soundcloud.com/yiruma-official/sets/the-best-reminiscent-10th",
                "https://i1.sndcdn.com/artworks-000034633857-8e69r5-t500x500.jpg",
                "River Flows in You, Kiss the Rain và những bản piano bất hủ."
            ),
            new SoundCloudPreset(
                "Gentle Rain & Thunder Sleep/Study", "🌧️ Nature",
                "https://soundcloud.com/nature-sounds-nature-music/sets/gentle-rain-for-sleeping-study",
                "https://i1.sndcdn.com/artworks-000219488349-f9h5c3-t500x500.jpg",
                "Âm thanh tiếng mưa rơi êm dịu trên mái hiên giúp thư giãn."
            ),
            new SoundCloudPreset(
                "Monstercat Silk Chillout", "⚡ Chillout",
                "https://soundcloud.com/monstercat/sets/monstercat-silk-chillout",
                "https://i1.sndcdn.com/artworks-f3GgT5471dY2vG8G-7d4V6A-t500x500.jpg",
                "Nhịp điệu Silk Chillout sâu lắng kích thích tư duy làm việc."
            ),
            new SoundCloudPreset(
                "Nhạc Việt Lofi & Chill Đêm", "🇻🇳 V-Pop",
                "https://soundcloud.com/vietnam-indie-music/sets/nhac-viet-chill-dem-khuya",
                "https://i1.sndcdn.com/artworks-000654321789-abc123-t500x500.jpg",
                "Tuyển tập indie và lofi Việt Nam giai điệu êm ái, thư giãn."
            )
        );
    }

    /**
     * Resolves metadata for any SoundCloud track, album or playlist URL.
     */
    public static SoundCloudTrackInfo resolveUrl(String soundcloudUrl) {
        if (soundcloudUrl == null || soundcloudUrl.isBlank()) return null;
        String trimmed = soundcloudUrl.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://" + trimmed;
        }

        try {
            String encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8);
            String apiUrl = "https://soundcloud.com/oembed?format=json&url=" + encoded;

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
                String authorName = extractJsonField(json, "author_name");
                String authorUrl = extractJsonField(json, "author_url");
                String thumb = extractJsonField(json, "thumbnail_url");
                String html = extractJsonField(json, "html");

                String widgetSrc = "";
                if (html != null && html.contains("src=\"")) {
                    int s = html.indexOf("src=\"") + 5;
                    int e = html.indexOf("\"", s);
                    if (e > s) {
                        widgetSrc = html.substring(s, e);
                    }
                }

                if (widgetSrc.isEmpty()) {
                    widgetSrc = "https://w.soundcloud.com/player/?url=" + encoded + "&auto_play=true&show_artwork=true&color=%23ff5500";
                } else if (!widgetSrc.contains("auto_play=")) {
                    widgetSrc += "&auto_play=true";
                }

                boolean isPlaylist = trimmed.contains("/sets/") || (html != null && html.contains("/playlists/"));
                if (title == null || title.isBlank()) title = "SoundCloud Stream";
                if (authorName == null || authorName.isBlank()) authorName = "SoundCloud Creator";

                return new SoundCloudTrackInfo(title, authorName, authorUrl, thumb, widgetSrc, trimmed, isPlaylist);
            }
        } catch (Exception ex) {
            System.err.println("Could not resolve SoundCloud URL: " + ex.getMessage());
        }

        // Fallback info if oEmbed network fails
        String fallbackSrc = "https://w.soundcloud.com/player/?url=" + URLEncoder.encode(trimmed, StandardCharsets.UTF_8) + "&auto_play=true&show_artwork=true&color=%23ff5500";
        return new SoundCloudTrackInfo("SoundCloud Track", "SoundCloud", "", null, fallbackSrc, trimmed, trimmed.contains("/sets/"));
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
