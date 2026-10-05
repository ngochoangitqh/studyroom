package vn.studyroom;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Downloads and extracts the actual high-quality audio stream from YouTube links or search queries
 * using the embedded yt-dlp binary. Caches files in .cache/audio/{videoId}.ext.
 */
public class YoutubeAudioService {

    // Use absolute path based on user.dir so it works regardless of working directory
    private static final File CACHE_DIR = new File(
        System.getProperty("user.dir"), ".cache/audio"
    );

    // Absolute path to yt-dlp binary
    private static final File YTDLP_FILE = new File(
        System.getProperty("user.dir"), ".tools/yt-dlp.exe"
    );

    public record DownloadedTrack(
        String videoId,
        String title,
        String artist,
        int durationSeconds,
        File audioFile,
        String thumbnailUrl
    ) {}

    public static DownloadedTrack downloadAudio(String input) {
        if (input == null || input.isBlank()) return null;
        String trimmed = input.trim();

        CACHE_DIR.mkdirs();

        String bin = YTDLP_FILE.exists() ? YTDLP_FILE.getAbsolutePath() : "yt-dlp";
        System.out.println("[YTAudio] Using yt-dlp: " + bin);
        System.out.println("[YTAudio] Cache dir: " + CACHE_DIR.getAbsolutePath());

        // If user provided a direct video ID or YouTube URL, pass directly; else search
        String target = trimmed;
        if (!trimmed.contains("://") && !trimmed.contains(".")) {
            target = "ytsearch1:" + trimmed;
        }

        // Output template using absolute cache path
        String outputTemplate = CACHE_DIR.getAbsolutePath() + File.separator + "%(id)s.%(ext)s";

        try {
            // Step 1: Get video metadata with --print (no download yet)
            ProcessBuilder pbInfo = new ProcessBuilder(
                bin,
                "--no-playlist",
                "--print", "%(id)s|||%(title)s|||%(uploader)s|||%(duration)s",
                "--no-download",
                target
            );
            pbInfo.redirectErrorStream(true);

            String videoId = null;
            String title = null;
            String uploader = null;
            int duration = 210;

            Process procInfo = pbInfo.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(procInfo.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[YTAudio] INFO: " + line);
                    if (line.contains("|||")) {
                        String[] parts = line.split("\\|\\|\\|", 4);
                        if (parts.length >= 2 && !parts[0].isBlank()) {
                            videoId = parts[0].trim();
                            title = parts[1].trim();
                            if (parts.length >= 3 && !parts[2].isBlank()) {
                                uploader = parts[2].trim();
                            }
                            if (parts.length >= 4 && !parts[3].isBlank()) {
                                try {
                                    duration = (int) Math.round(Double.parseDouble(parts[3].trim()));
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }
            procInfo.waitFor(30, TimeUnit.SECONDS);

            // Fallback: extract video ID from URL
            if (videoId == null) {
                videoId = YoutubePlayerBridge.extractVideoId(trimmed);
            }

            if (videoId == null) {
                System.err.println("[YTAudio] Could not resolve video ID for: " + trimmed);
                return null;
            }

            System.out.println("[YTAudio] Resolved videoId: " + videoId);

            // Check cache first — avoid re-downloading
            final String finalId = videoId;
            File[] cached = CACHE_DIR.listFiles((dir, name) -> name.startsWith(finalId + "."));
            if (cached != null && cached.length > 0 && cached[0].length() > 1024) {
                System.out.println("[YTAudio] Cache hit: " + cached[0].getAbsolutePath());
                String t = (title != null && !title.isBlank()) ? title : "YouTube · " + videoId;
                String a = (uploader != null && !uploader.isBlank()) ? uploader : "YouTube";
                String thumb = "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
                return new DownloadedTrack(videoId, t, a, duration, cached[0], thumb);
            }

            // Step 2: Download the audio
            System.out.println("[YTAudio] Downloading audio for: " + videoId);
            ProcessBuilder pbDown = new ProcessBuilder(
                bin,
                "--no-playlist",
                "-f", "140/ba[ext=m4a]/ba[ext=webm]/ba[ext=opus]/ba/bestaudio/best",
                "--no-part",
                "-o", outputTemplate,
                target
            );
            pbDown.redirectErrorStream(true);
            pbDown.directory(CACHE_DIR.getParentFile().getParentFile()); // set working dir to project root

            Process procDown = pbDown.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(procDown.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[YTAudio] DL: " + line);
                }
            }

            boolean finished = procDown.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                procDown.destroyForcibly();
                System.err.println("[YTAudio] Download timed out for: " + videoId);
                return null;
            }

            int exitCode = procDown.exitValue();
            System.out.println("[YTAudio] yt-dlp exited with code: " + exitCode);

            // Find the downloaded file
            File[] allFiles = CACHE_DIR.listFiles();
            if (allFiles != null) {
                System.out.println("[YTAudio] Files in cache dir after download:");
                for (File f : allFiles) {
                    System.out.println("  " + f.getName() + " size=" + f.length());
                }
            }

            File audioFile = null;
            File[] matches = CACHE_DIR.listFiles((dir, name) -> name.startsWith(finalId + "."));
            if (matches != null && matches.length > 0) {
                // Pick the largest file (in case of multiple matches)
                audioFile = matches[0];
                for (File m : matches) {
                    if (m.length() > audioFile.length()) audioFile = m;
                }
            }

            if (audioFile == null || !audioFile.exists() || audioFile.length() < 1024) {
                System.err.println("[YTAudio] Audio file not found or too small for: " + videoId);
                return null;
            }

            if (title == null || title.isBlank()) title = "YouTube · " + videoId;
            if (uploader == null || uploader.isBlank()) uploader = "YouTube";

            String thumbUrl = "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
            System.out.println("[YTAudio] ✓ Ready: " + title + " → " + audioFile.getAbsolutePath());
            return new DownloadedTrack(videoId, title, uploader, duration, audioFile, thumbUrl);

        } catch (Exception e) {
            System.err.println("[YTAudio] Failed: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }
}
