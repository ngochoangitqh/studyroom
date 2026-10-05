package vn.studyroom;

/**
 * Representation of a music track in the study music rooms.
 * Supports synthesized ambient tracks, custom WAV uploads, SoundCloud streams, and Spotify embeds.
 */
public record MusicTrack(
    String id,
    String title,
    String artist,
    String album,
    int durationSeconds,
    String soundType,
    String coverGradient,
    String genre,
    byte[] customAudioData,
    String soundcloudUrl,
    String spotifyUrl,
    String thumbnailUrl,
    String widgetSrc
) {
    public MusicTrack(String id, String title, String artist, String album, int durationSeconds, String soundType, String coverGradient, String genre) {
        this(id, title, artist, album, durationSeconds, soundType, coverGradient, genre, null, null, null, null, null);
    }

    public MusicTrack(String id, String title, String artist, String album, int durationSeconds, String soundType, String coverGradient, String genre, byte[] customAudioData) {
        this(id, title, artist, album, durationSeconds, soundType, coverGradient, genre, customAudioData, null, null, null, null);
    }

    public static String detectSoundType(String title, String artist, String genre) {
        String text = ((title != null ? title : "") + " " + (artist != null ? artist : "") + " " + (genre != null ? genre : "")).toLowerCase();
        if (text.contains("piano") || text.contains("classical") || text.contains("nocturne") || text.contains("yiruma")) return "PIANO";
        if (text.contains("guitar") || text.contains("acoustic") || text.contains("cafe") || text.contains("coffee") || text.contains("jazz")) return "CAFE";
        if (text.contains("rain") || text.contains("storm") || text.contains("nature") || text.contains("water")) return "RAIN";
        if (text.contains("focus") || text.contains("binaural") || text.contains("alpha") || text.contains("wave") || text.contains("sleep")) return "BINAURAL";
        if (text.contains("code") || text.contains("coding") || text.contains("synth") || text.contains("cyber") || text.contains("retro")) return "SYNTHWAVE";
        if (text.contains("viet") || text.contains("v-pop") || text.contains("indie") || text.contains("ballad") || text.contains("buon")) return "VPOP";
        return "LOFI";
    }

    public static MusicTrack fromSoundCloud(SoundCloudTrackInfo info) {
        String id = "sc-" + Math.abs(info.soundcloudUrl().hashCode());
        String genre = info.isPlaylist() ? "SoundCloud Playlist" : "SoundCloud Track";
        String gradient = "linear-gradient(to bottom right, #ea580c, #f97316)";
        String soundType = detectSoundType(info.title(), info.authorName(), genre);
        return new MusicTrack(
            id,
            info.title(),
            info.authorName(),
            info.isPlaylist() ? "SoundCloud Album" : "SoundCloud Single",
            240,
            soundType,
            gradient,
            genre,
            null,
            info.soundcloudUrl(),
            null,
            info.thumbnailUrl(),
            info.widgetSrc()
        );
    }

    public static MusicTrack fromSpotify(SpotifyService.SpotifyTrackInfo info) {
        String id = "sp-" + Math.abs(info.spotifyUrl().hashCode());
        String genre = info.isPlaylist() ? "Spotify Playlist" : "Spotify Track";
        String gradient = "linear-gradient(to bottom right, #1db954, #1ed760)";
        String soundType = detectSoundType(info.title(), info.artist(), genre);
        return new MusicTrack(
            id,
            info.title(),
            info.artist(),
            info.isPlaylist() ? "Spotify Playlist" : "Spotify Single",
            210,
            soundType,
            gradient,
            genre,
            null,
            null,
            info.spotifyUrl(),
            info.thumbnailUrl(),
            info.iframeUrl()
        );
    }

    public static MusicTrack fromYoutube(String videoId, String title, String artist, String originalUrl) {
        String id = "yt-" + (videoId != null ? videoId : String.valueOf(System.currentTimeMillis()));
        String genre = "YouTube Audio";
        String gradient = "linear-gradient(to bottom right, #f43f5e, #fb7185)";
        String soundType = detectSoundType(title, artist, genre);
        String thumb = (videoId != null && !videoId.isBlank())
            ? "https://img.youtube.com/vi/" + videoId + "/maxresdefault.jpg"
            : null;
        return new MusicTrack(
            id,
            title != null ? title : "YouTube Track",
            artist != null ? artist : "YouTube",
            "YouTube Audio",
            225,
            soundType,
            gradient,
            genre,
            null,
            null,
            null,
            thumb,
            originalUrl
        );
    }

    public boolean isYoutube() {
        return (id != null && id.startsWith("yt-"))
            || "YouTube Audio".equalsIgnoreCase(genre)
            || (widgetSrc != null && (widgetSrc.contains("youtube.com") || widgetSrc.contains("youtu.be")));
    }

    public boolean isSoundCloud() {
        return soundcloudUrl != null && !soundcloudUrl.isBlank();
    }

    public boolean isSpotify() {
        return spotifyUrl != null && !spotifyUrl.isBlank();
    }

    public boolean isOnlineStream() {
        return isSoundCloud() || isSpotify();
    }

    public String formattedDuration() {
        int m = durationSeconds / 60;
        int s = durationSeconds % 60;
        return String.format("%02d:%02d", m, s);
    }
}
