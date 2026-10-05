package vn.studyroom;

/**
 * Metadata resolved from SoundCloud oEmbed API.
 */
public record SoundCloudTrackInfo(
    String title,
    String authorName,
    String authorUrl,
    String thumbnailUrl,
    String widgetSrc,
    String soundcloudUrl,
    boolean isPlaylist
) {}
