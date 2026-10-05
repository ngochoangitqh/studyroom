package vn.studyroom;

import java.util.ArrayList;
import java.util.List;

/**
 * Representation of a shared listening room with active participants, Spotify playlist link, and live room chat.
 */
public class MusicRoom {
    private final String id;
    private String name;
    private String description;
    private final String bannerGradient;
    private final String accentColor;
    private String ownerUsername;
    private String ownerDisplayName;
    private boolean userCreated;
    private String spotifyUrl;
    private String coverImageUrl;

    private final List<MusicTrack> playlist = new ArrayList<>();
    private final List<String> listeners = new ArrayList<>();
    private final List<MusicRoomMessage> chatMessages = new ArrayList<>();

    public record MusicRoomMessage(String sender, String text, String time, boolean isReaction) {}

    public MusicRoom(String id, String name, String description, String bannerGradient, String accentColor, List<MusicTrack> tracks, List<String> initialListeners) {
        this(id, name, description, bannerGradient, accentColor, "system", "Studyroom Music", false, null, null, tracks, initialListeners);
    }

    public MusicRoom(String id, String name, String description, String bannerGradient, String accentColor, 
                     String ownerUsername, String ownerDisplayName, boolean userCreated, String spotifyUrl, String coverImageUrl,
                     List<MusicTrack> tracks, List<String> initialListeners) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.bannerGradient = bannerGradient;
        this.accentColor = accentColor;
        this.ownerUsername = ownerUsername;
        this.ownerDisplayName = ownerDisplayName;
        this.userCreated = userCreated;
        this.spotifyUrl = spotifyUrl;
        this.coverImageUrl = coverImageUrl;
        if (tracks != null) this.playlist.addAll(tracks);
        if (initialListeners != null) this.listeners.addAll(initialListeners);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String desc) { this.description = desc; }
    public String getBannerGradient() { return bannerGradient; }
    public String getAccentColor() { return accentColor; }
    public String getOwnerUsername() { return ownerUsername; }
    public String getOwnerDisplayName() { return ownerDisplayName != null ? ownerDisplayName : "Studyroom"; }
    public boolean isUserCreated() { return userCreated; }
    public String getSpotifyUrl() { return spotifyUrl; }
    public void setSpotifyUrl(String url) { this.spotifyUrl = url; }
    public String getCoverImageUrl() { return coverImageUrl; }
    public void setCoverImageUrl(String url) { this.coverImageUrl = url; }

    public List<MusicTrack> getPlaylist() { return playlist; }
    public List<String> getListeners() { return listeners; }
    public List<MusicRoomMessage> getChatMessages() { return chatMessages; }

    public void addListener(String username) {
        if (!listeners.contains(username)) {
            listeners.add(username);
        }
    }

    public void removeListener(String username) {
        listeners.remove(username);
    }

    public void addChatMessage(String sender, String text, boolean isReaction) {
        String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        chatMessages.add(new MusicRoomMessage(sender, text, time, isReaction));
        if (chatMessages.size() > 50) {
            chatMessages.remove(0);
        }
    }
}
