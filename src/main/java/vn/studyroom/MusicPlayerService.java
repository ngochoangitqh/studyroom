package vn.studyroom;

import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Background audio player and shared listening engine.
 * Supports Spotify streams & playlists, user room creation, room invitations,
 * synthesized ambient tracks, custom WAV uploads, and SoundCloud streams.
 */
public final class MusicPlayerService {
    private static MusicPlayerService instance;

    public static synchronized MusicPlayerService getInstance() {
        if (instance == null) {
            instance = new MusicPlayerService();
        }
        return instance;
    }

    private final List<MusicRoom> rooms = new ArrayList<>();
    private MusicRoom currentRoom;
    private int currentTrackIndex = 0;
    private volatile boolean isPlaying = false;
    private volatile boolean isMuted = false;
    private volatile double volume = 0.85;
    private volatile double currentPositionSeconds = 0.0;
    private boolean isShuffle = false;
    private boolean isRepeat = false;

    // Real Audio File MediaPlayer
    private MediaPlayer activeMediaPlayer;

    // Amplitude bands for visualizer (36 frequency bands)
    private final float[] visualizerBars = new float[36];
    private final Random random = new Random();

    // Event listeners
    private final List<Consumer<MusicTrack>> trackChangeListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Boolean>> playStateListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Double>> timeUpdateListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<float[]>> visualizerListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<MusicRoom>> roomChangeListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> roomUpdateListeners = new CopyOnWriteArrayList<>();

    private TcpChatNode peerNode;
    private String currentUsername = "Bạn";

    // Spotify Audio Bridge
    private final SpotifyAudioBridge spotifyBridge = new SpotifyAudioBridge();

    // YouTube Player Bridge (real audio from YouTube links)
    private final YoutubePlayerBridge youtubeBridge = new YoutubePlayerBridge();
    private YoutubePlayerBridge.YoutubeTrackInfo currentYoutubeTrack = null;

    // Audio Output for Synthesized / Local Sounds
    private SourceDataLine audioLine;
    private Thread audioThread;
    private volatile boolean isRunning = true;

    private MusicPlayerService() {
        initDefaultRooms();
        currentRoom = rooms.getFirst();
        startAudioEngine();
    }

    public SpotifyAudioBridge getSpotifyBridge() {
        return spotifyBridge;
    }

    public YoutubePlayerBridge getYoutubeBridge() {
        return youtubeBridge;
    }

    private MusicPresenceRepository musicPresenceRepository;
    private volatile boolean isApplyingRemoteSync = false;

    public void setMusicPresenceRepository(MusicPresenceRepository repo) {
        this.musicPresenceRepository = repo;
    }

    public MusicPresenceRepository getMusicPresenceRepository() {
        return musicPresenceRepository;
    }

    public void setCurrentUsername(String username) {
        if (username != null && !username.isBlank()) {
            this.currentUsername = username;
        }
    }

    public String getCurrentUsername() {
        return currentUsername;
    }

    public void setApplyingRemoteSync(boolean val) {
        this.isApplyingRemoteSync = val;
    }

    public boolean isApplyingRemoteSync() {
        return isApplyingRemoteSync;
    }

    public void syncRoomStateToDatabase() {
        if (isApplyingRemoteSync || musicPresenceRepository == null || currentRoom == null) return;
        MusicTrack t = getCurrentTrack();
        String query = "";
        if (currentYoutubeTrack != null && t != null && t.id().equals("yt-" + currentYoutubeTrack.videoId())) {
            query = currentYoutubeTrack.originalUrl();
        } else if (t != null && t.widgetSrc() != null) {
            query = t.widgetSrc();
        }
        final String finalQuery = query;
        final MusicTrack finalT = t;
        final boolean finalPlaying = isPlaying;
        final double finalPos = currentPositionSeconds;
        final String rId = currentRoom.getId();
        final String rName = currentRoom.getName();
        final String uName = currentUsername;

        Thread.ofVirtual().start(() -> {
            musicPresenceRepository.updateRoomSyncState(
                rId, rName, finalT, finalPlaying, finalPos, uName, finalQuery
            );
        });
    }

    public void applyRemotePlay(double positionSec) {
        isApplyingRemoteSync = true;
        try {
            if (activeMediaPlayer != null && positionSec >= 0) {
                Platform.runLater(() -> activeMediaPlayer.seek(javafx.util.Duration.seconds(positionSec)));
            }
            this.currentPositionSeconds = Math.max(0, positionSec);
            if (!isPlaying) {
                this.isPlaying = true;
                if (activeMediaPlayer != null) {
                    Platform.runLater(() -> activeMediaPlayer.play());
                } else {
                    syncTrackPlayback();
                }
                notifyPlayStateChanged();
            }
        } finally {
            isApplyingRemoteSync = false;
        }
    }

    public void applyRemotePause() {
        isApplyingRemoteSync = true;
        try {
            if (isPlaying) {
                this.isPlaying = false;
                if (activeMediaPlayer != null) {
                    Platform.runLater(() -> activeMediaPlayer.pause());
                }
                notifyPlayStateChanged();
            }
        } finally {
            isApplyingRemoteSync = false;
        }
    }

    public void applyRemoteSeek(double positionSec) {
        isApplyingRemoteSync = true;
        try {
            this.currentPositionSeconds = Math.max(0, positionSec);
            if (activeMediaPlayer != null) {
                Platform.runLater(() -> activeMediaPlayer.seek(javafx.util.Duration.seconds(positionSec)));
            }
            notifyTimeUpdated();
        } finally {
            isApplyingRemoteSync = false;
        }
    }

    public void applyRemoteTrack(String trackId, boolean targetPlaying, double positionSec) {
        if (currentRoom == null) return;
        int targetIdx = -1;
        for (int i = 0; i < currentRoom.getPlaylist().size(); i++) {
            if (currentRoom.getPlaylist().get(i).id().equals(trackId)) {
                targetIdx = i;
                break;
            }
        }
        if (targetIdx != -1) {
            isApplyingRemoteSync = true;
            try {
                this.currentTrackIndex = targetIdx;
                this.currentPositionSeconds = Math.max(0, positionSec);
                this.isPlaying = targetPlaying;
                notifyTrackChanged();
                syncTrackPlayback();
                if (targetPlaying && activeMediaPlayer != null && positionSec > 0) {
                    Platform.runLater(() -> activeMediaPlayer.seek(javafx.util.Duration.seconds(positionSec)));
                }
                notifyPlayStateChanged();
            } finally {
                isApplyingRemoteSync = false;
            }
        }
    }

    /**
     * Load and add a YouTube URL or search query to the playlist.
     * Downloads real audio file via YoutubeAudioService, saves to persistent user playlist,
     * and auto-plays if playlist was previously empty or paused.
     */
    public YoutubePlayerBridge.YoutubeTrackInfo loadYoutubeUrl(String input) {
        if (input == null || input.isBlank()) return null;
        YoutubeAudioService.DownloadedTrack dt = YoutubeAudioService.downloadAudio(input);
        if (dt != null && currentRoom != null) {
            MusicTrack ytTrack = new MusicTrack(
                "yt-" + dt.videoId(),
                dt.title(),
                dt.artist(),
                "YouTube Audio",
                dt.durationSeconds(),
                "YOUTUBE",
                "linear-gradient(to bottom right, #f43f5e, #fb7185)",
                "YouTube Audio",
                null,
                null,
                null,
                dt.thumbnailUrl(),
                dt.audioFile().getAbsolutePath()
            );

            boolean wasEmpty = currentRoom.getPlaylist().isEmpty();
            boolean needPlay = wasEmpty || !isPlaying;

            currentRoom.getPlaylist().add(ytTrack);
            saveUserPlaylist();

            currentYoutubeTrack = new YoutubePlayerBridge.YoutubeTrackInfo(
                dt.title(), dt.videoId(), null, input
            );

            if (musicPresenceRepository != null) {
                final String rId = currentRoom.getId();
                final String uName = currentUsername;
                final String inQuery = input;
                Thread.ofVirtual().start(() -> {
                    musicPresenceRepository.addTrackToRoomPlaylist(rId, ytTrack, uName, inQuery);
                });
            }

            if (needPlay) {
                currentTrackIndex = currentRoom.getPlaylist().size() - 1;
                currentPositionSeconds = 0.0;
                isPlaying = true;
                syncTrackPlayback();
                notifyTrackChanged();
                notifyPlayStateChanged();
                syncRoomStateToDatabase();
            } else {
                notifyTrackChanged();
            }
            notifyRoomUpdated();
            return currentYoutubeTrack;
        }
        return null;
    }

    public void removeTrack(int index) {
        if (currentRoom == null || index < 0 || index >= currentRoom.getPlaylist().size()) return;
        MusicTrack removedTrk = currentRoom.getPlaylist().get(index);
        if (musicPresenceRepository != null) {
            final String rId = currentRoom.getId();
            final String trkId = removedTrk.id();
            Thread.ofVirtual().start(() -> musicPresenceRepository.removeTrackFromRoomPlaylist(rId, trkId));
        }
        boolean removingActive = (index == currentTrackIndex);
        currentRoom.getPlaylist().remove(index);
        saveUserPlaylist();

        if (currentRoom.getPlaylist().isEmpty()) {
            currentTrackIndex = 0;
            currentPositionSeconds = 0.0;
            pause();
            if (activeMediaPlayer != null) {
                Platform.runLater(() -> {
                    if (activeMediaPlayer != null) {
                        activeMediaPlayer.stop();
                        activeMediaPlayer.dispose();
                        activeMediaPlayer = null;
                    }
                });
            }
            notifyTrackChanged();
            notifyRoomUpdated();
            return;
        }

        if (removingActive) {
            if (currentTrackIndex >= currentRoom.getPlaylist().size()) {
                currentTrackIndex = 0;
            }
            currentPositionSeconds = 0.0;
            notifyTrackChanged();
            if (isPlaying) {
                syncTrackPlayback();
            }
        } else if (index < currentTrackIndex) {
            currentTrackIndex--;
            notifyTrackChanged();
        }
        notifyRoomUpdated();
    }

    public void clearUserPlaylist() {
        if (currentRoom == null) return;
        if (musicPresenceRepository != null) {
            final String rId = currentRoom.getId();
            Thread.ofVirtual().start(() -> musicPresenceRepository.clearRoomPlaylist(rId));
        }
        currentRoom.getPlaylist().clear();
        currentTrackIndex = 0;
        currentPositionSeconds = 0.0;
        pause();
        if (activeMediaPlayer != null) {
            Platform.runLater(() -> {
                if (activeMediaPlayer != null) {
                    activeMediaPlayer.stop();
                    activeMediaPlayer.dispose();
                    activeMediaPlayer = null;
                }
            });
        }
        saveUserPlaylist();
        notifyTrackChanged();
        notifyRoomUpdated();
    }

    public synchronized void saveUserPlaylist() {
        if (currentRoom == null) return;
        try {
            File cacheDir = new File(System.getProperty("user.dir"), ".cache");
            cacheDir.mkdirs();
            File file = new File(cacheDir, "my_playlist.json");
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            List<MusicTrack> list = currentRoom.getPlaylist();
            for (int i = 0; i < list.size(); i++) {
                MusicTrack t = list.get(i);
                sb.append("  {\n");
                sb.append("    \"id\": \"").append(escapeJson(t.id())).append("\",\n");
                sb.append("    \"title\": \"").append(escapeJson(t.title())).append("\",\n");
                sb.append("    \"artist\": \"").append(escapeJson(t.artist())).append("\",\n");
                sb.append("    \"durationSeconds\": ").append(t.durationSeconds()).append(",\n");
                sb.append("    \"thumbnailUrl\": \"").append(escapeJson(t.thumbnailUrl() != null ? t.thumbnailUrl() : "")).append("\",\n");
                sb.append("    \"audioPath\": \"").append(escapeJson(t.widgetSrc() != null ? t.widgetSrc() : "")).append("\"\n");
                sb.append("  }").append(i < list.size() - 1 ? ",\n" : "\n");
            }
            sb.append("]\n");
            java.nio.file.Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
            System.out.println("[MusicPlayer] Saved " + list.size() + " tracks to " + file.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[MusicPlayer] Failed to save playlist: " + e.getMessage());
        }
    }

    public synchronized List<MusicTrack> loadUserPlaylist() {
        List<MusicTrack> result = new ArrayList<>();
        try {
            File file = new File(System.getProperty("user.dir"), ".cache/my_playlist.json");
            if (!file.exists() || file.length() == 0) return result;
            String content = java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8);
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\{[^{}]*\\}", java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(content);
            while (m.find()) {
                String obj = m.group();
                String id = extractJsonField(obj, "id");
                String title = extractJsonField(obj, "title");
                String artist = extractJsonField(obj, "artist");
                String durStr = extractJsonField(obj, "durationSeconds");
                String thumb = extractJsonField(obj, "thumbnailUrl");
                String audio = extractJsonField(obj, "audioPath");

                int dur = 210;
                if (durStr != null && !durStr.isBlank()) {
                    try { dur = Integer.parseInt(durStr.trim()); } catch (Exception ignored) {}
                }

                if (audio == null || audio.isBlank() || !new File(audio).exists()) {
                    if (id != null && id.startsWith("yt-")) {
                        String vId = id.substring(3);
                        File audioDir = new File(System.getProperty("user.dir"), ".cache/audio");
                        File[] matches = audioDir.listFiles((dir, name) -> name.startsWith(vId + "."));
                        if (matches != null && matches.length > 0) {
                            audio = matches[0].getAbsolutePath();
                        }
                    }
                }

                if (title != null && !title.isBlank()) {
                    MusicTrack track = new MusicTrack(
                        id != null ? id : ("yt-" + System.currentTimeMillis()),
                        title,
                        artist != null ? artist : "YouTube",
                        "YouTube Audio",
                        dur,
                        "YOUTUBE",
                        "linear-gradient(to bottom right, #f43f5e, #fb7185)",
                        "YouTube Audio",
                        null,
                        null,
                        null,
                        thumb,
                        audio
                    );
                    result.add(track);
                }
            }
            System.out.println("[MusicPlayer] Loaded " + result.size() + " tracks from " + file.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[MusicPlayer] Failed to load playlist: " + e.getMessage());
        }
        return result;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String extractJsonField(String json, String field) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*(?:\"([^\"]*)\"|([0-9]+))");
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            String s = m.group(1);
            if (s != null) {
                return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\t", "\t");
            }
            return m.group(2);
        }
        return null;
    }

    public YoutubePlayerBridge.YoutubeTrackInfo getCurrentYoutubeTrack() {
        return currentYoutubeTrack;
    }

    public void stopYoutube() {
        youtubeBridge.stop();
        currentYoutubeTrack = null;
        notifyRoomUpdated();
    }

    public void setPeerNode(TcpChatNode node, String username) {
        this.peerNode = node;
        this.currentUsername = username;
        if (currentRoom != null) {
            currentRoom.addListener(username);
        }
    }

    private void initDefaultRooms() {
        // Room 1: Playlist của tôi (User's custom playlist saved locally)
        List<MusicTrack> savedTracks = new ArrayList<>(loadUserPlaylist());
        MusicRoom r1 = new MusicRoom("room_dem_khuya", "Playlist của tôi",
            "Danh sách nhạc do bạn tự thêm và lưu trữ 🎧",
            "linear-gradient(to bottom, #1e1b4b 0%, #0f172a 60%, #121212 100%)", "#6366f1",
            "system", "StudyTogether", false, null,
            "https://images.unsplash.com/photo-1518495973542-4542c06a5843?w=600&q=80",
            savedTracks, List.of("Bạn", "Lan", "Minh"));

        // Room 2: Spotify Deep Focus (Official Spotify)
        List<MusicTrack> spFocus = new ArrayList<>(List.of(
            MusicTrack.fromSpotify(new SpotifyService.SpotifyTrackInfo(
                "Deep Focus & Ambient Flow", "Spotify Focus Curators",
                "https://i.scdn.co/image/ab67706f000000025551996f500d9f805a57e0d8",
                SpotifyService.toEmbedUrl("https://open.spotify.com/playlist/37i9dQZF1DWZeKCadgRdKQ"),
                "https://open.spotify.com/playlist/37i9dQZF1DWZeKCadgRdKQ", true
            )),
            new MusicTrack("focus-1", "Binaural Alpha Waves 432Hz", "Delta Labs", "Brain Entrainment", 280, "BINAURAL", "linear-gradient(to bottom right, #1e3a8a, #3b82f6)", "Focus Wave"),
            new MusicTrack("focus-2", "Flow State Resonance", "Mind Harmony", "Deep Study Zone", 260, "BINAURAL", "linear-gradient(to bottom right, #172554, #2563eb)", "Alpha Waves"),
            new MusicTrack("focus-3", "Gamma Waves for Memory", "Neuro Beats", "Cognitive Power", 240, "BINAURAL", "linear-gradient(to bottom right, #1d4ed8, #60a5fa)", "Focus Music")
        ));
        MusicRoom r2 = new MusicRoom("room_sp_focus", "🧠 Spotify Deep Focus",
            "Âm nhạc ambient không lời giúp loại bỏ tạp âm, đưa não bộ vào trạng thái tập trung sâu.",
            "linear-gradient(to bottom, #1e3a8a 0%, #0f172a 60%, #121212 100%)", "#3b82f6",
            "spotify", "Spotify Official", false, "https://open.spotify.com/playlist/37i9dQZF1DWZeKCadgRdKQ",
            "https://i.scdn.co/image/ab67706f000000025551996f500d9f805a57e0d8", spFocus, List.of("Huy", "Thảo", "Hải Đăng"));

        // Room 3: Spotify Peaceful Piano
        List<MusicTrack> spPiano = new ArrayList<>(List.of(
            MusicTrack.fromSpotify(new SpotifyService.SpotifyTrackInfo(
                "Peaceful Piano Melodies", "Spotify Classical",
                "https://i.scdn.co/image/ab67706f00000002d073e656e546e43bc387ad79",
                SpotifyService.toEmbedUrl("https://open.spotify.com/playlist/37i9dQZF1DX4sWSpwq3LiO"),
                "https://open.spotify.com/playlist/37i9dQZF1DX4sWSpwq3LiO", true
            )),
            new MusicTrack("piano-1", "Nocturne in C-Sharp", "Midnight Pianist", "Silent Night Solitude", 230, "PIANO", "linear-gradient(to bottom right, #581c87, #9333ea)", "Classical Piano"),
            new MusicTrack("piano-2", "Moonlight Sonata Variations", "Luna Harmonies", "Moonlit Keys", 255, "PIANO", "linear-gradient(to bottom right, #4c1d95, #7c3aed)", "Neoclassical"),
            new MusicTrack("piano-3", "River Flows in Your Heart", "Yiruma Inspired", "Echoes of Rain", 210, "PIANO", "linear-gradient(to bottom right, #3b0764, #a855f7)", "Ambient Piano"),
            new MusicTrack("piano-4", "Melancholy Autumn Keys", "Soloist Cafe", "Autumn Leaves", 220, "PIANO", "linear-gradient(to bottom right, #2e1065, #c084fc)", "Piano Solo")
        ));
        MusicRoom r3 = new MusicRoom("room_sp_piano", "🎹 Spotify Peaceful Piano",
            "Những phím đàn piano mộc thư giãn và tinh tế cho những buổi tối ôn tập yên ả.",
            "linear-gradient(to bottom, #4c1d95 0%, #2e1065 60%, #121212 100%)", "#a855f7",
            "spotify", "Spotify Official", false, "https://open.spotify.com/playlist/37i9dQZF1DX4sWSpwq3LiO",
            "https://i.scdn.co/image/ab67706f00000002d073e656e546e43bc387ad79", spPiano, List.of("Quỳnh", "Bảo", "Duy"));

        // Room 4: Spotify Coffee Table Jazz & Acoustic
        List<MusicTrack> spJazz = new ArrayList<>(List.of(
            MusicTrack.fromSpotify(new SpotifyService.SpotifyTrackInfo(
                "Coffee Table Jazz & Acoustic", "Spotify Jazz & Cafe",
                "https://i.scdn.co/image/ab67706f00000002c918a5be8933b9b9a6d09c25",
                SpotifyService.toEmbedUrl("https://open.spotify.com/playlist/37i9dQZF1DX65BNHJha8KG"),
                "https://open.spotify.com/playlist/37i9dQZF1DX65BNHJha8KG", true
            )),
            new MusicTrack("cafe-1", "Morning Espresso & Acoustic", "Morning Brew", "Cozy Coffeehouse", 185, "CAFE", "linear-gradient(to bottom right, #78350f, #b45309)", "Acoustic"),
            new MusicTrack("cafe-2", "Cozy Corner Guitar Fingerstyle", "Wood & Strings", "Autumn Sunlight", 220, "CAFE", "linear-gradient(to bottom right, #713f12, #ca8a04)", "Fingerstyle"),
            new MusicTrack("cafe-3", "Sunday Bookshop Jazz", "Gentle Breeze", "Vintage Books", 195, "CAFE", "linear-gradient(to bottom right, #451a03, #d97706)", "Acoustic Jazz")
        ));
        MusicRoom r4 = new MusicRoom("room_sp_jazz", "☕ Spotify Coffee Table Jazz",
            "Jazz mộc ấm áp như đang ngồi học tại một quán cafe yên tĩnh buổi sáng.",
            "linear-gradient(to bottom, #451a03 0%, #291203 60%, #121212 100%)", "#f59e0b",
            "spotify", "Spotify Official", false, "https://open.spotify.com/playlist/37i9dQZF1DX65BNHJha8KG",
            "https://i.scdn.co/image/ab67706f00000002c918a5be8933b9b9a6d09c25", spJazz, List.of("Huy", "Thảo", "Hải Đăng"));

        // Room 5: Spotify Nhạc Việt Chill & Acoustic
        List<MusicTrack> spVpop = new ArrayList<>(List.of(
            MusicTrack.fromSpotify(new SpotifyService.SpotifyTrackInfo(
                "Nhạc Việt Chill & Acoustic", "Spotify Vietnam",
                "https://i.scdn.co/image/ab67706f00000002d257262078bfdcab1d92cfb9",
                SpotifyService.toEmbedUrl("https://open.spotify.com/playlist/37i9dQZF1DX4g8Gs5vgUm9"),
                "https://open.spotify.com/playlist/37i9dQZF1DX4g8Gs5vgUm9", true
            )),
            new MusicTrack("vpop-1", "Giai Điệu Chiều Hoàng Hôn", "Indie Việt", "Góc Phố Mùa Thu", 215, "VPOP", "linear-gradient(to bottom right, #b45309, #f59e0b)", "Indie Chill"),
            new MusicTrack("vpop-2", "Cơn Mưa Rào Tháng Sáu", "Acoustic Ballad", "Kỷ Niệm Đã Xa", 240, "VPOP", "linear-gradient(to bottom right, #9a3412, #ea580c)", "V-Pop Ballad"),
            new MusicTrack("vpop-3", "Phố Thị Về Đêm (Chillhop)", "Hà Nội Lofi", "Đêm Yên Bình", 205, "VPOP", "linear-gradient(to bottom right, #7c2d12, #fb923c)", "Acoustic Lofi")
        ));
        MusicRoom r5 = new MusicRoom("room_sp_vpop", "🇻🇳 Spotify Nhạc Việt Chill",
            "Tuyển tập giai điệu Indie và Acoustic Việt Nam mộc mạc, thư giãn cho tâm hồn.",
            "linear-gradient(to bottom, #78350f 0%, #451a03 60%, #121212 100%)", "#f59e0b",
            "spotify", "Spotify Official", false, "https://open.spotify.com/playlist/37i9dQZF1DX4g8Gs5vgUm9",
            "https://i.scdn.co/image/ab67706f00000002d257262078bfdcab1d92cfb9", spVpop, List.of("Tuấn", "Ngọc", "Thảo"));

        // Room 6: Spotify Coding Mode & Synthwave
        List<MusicTrack> spCode = new ArrayList<>(List.of(
            MusicTrack.fromSpotify(new SpotifyService.SpotifyTrackInfo(
                "Coding Mode & Synthwave", "Spotify Tech & Beats",
                "https://i.scdn.co/image/ab67706f000000029249b3c997239ad41a5270f1",
                SpotifyService.toEmbedUrl("https://open.spotify.com/playlist/37i9dQZF1DX5trt9i14X7j"),
                "https://open.spotify.com/playlist/37i9dQZF1DX5trt9i14X7j", true
            )),
            new MusicTrack("synth-1", "Neon Cyber Highway", "RetroWave 84", "Outrun Night", 225, "SYNTHWAVE", "linear-gradient(to bottom right, #831843, #db2777)", "Synthwave"),
            new MusicTrack("synth-2", "Cybernetic Algorithm Core", "Glitch Matrix", "Cyberpunk Flow", 205, "SYNTHWAVE", "linear-gradient(to bottom right, #4c0519, #be123c)", "Darksynth"),
            new MusicTrack("synth-3", "Midnight Terminal Coding", "Byte Hacker", "Syntax Highlighting", 235, "SYNTHWAVE", "linear-gradient(to bottom right, #701a75, #f43f5e)", "Retrowave")
        ));
        MusicRoom r6 = new MusicRoom("room_sp_code", "⚡ Spotify Coding Mode",
            "Nhịp điệu điện tử Retro & Cyberpunk kích thích tư duy logic khi viết code giải đề.",
            "linear-gradient(to bottom, #831843 0%, #4c0519 60%, #121212 100%)", "#f43f5e",
            "spotify", "Spotify Official", false, "https://open.spotify.com/playlist/37i9dQZF1DX5trt9i14X7j",
            "https://i.scdn.co/image/ab67706f000000029249b3c997239ad41a5270f1", spCode, List.of("Khánh", "Trực"));

        // Room 7: Spotify Rain Sounds & Deep Calm
        List<MusicTrack> spRain = new ArrayList<>(List.of(
            MusicTrack.fromSpotify(new SpotifyService.SpotifyTrackInfo(
                "Rain Sounds for Sleep & Study", "Spotify Nature Sounds",
                "https://i.scdn.co/image/ab67706f00000002f5a560f845d064cf4f88e734",
                SpotifyService.toEmbedUrl("https://open.spotify.com/playlist/37i9dQZF1DX8ymr6UES72F"),
                "https://open.spotify.com/playlist/37i9dQZF1DX8ymr6UES72F", true
            )),
            new MusicTrack("rain-1", "Rain on Roof & Distant Thunder", "Nature & Mind", "White Noise Calm", 300, "RAIN", "linear-gradient(to bottom right, #111827, #1f2937)", "Nature Sound"),
            new MusicTrack("rain-2", "Cozy Window Rain Storm", "Sleep Sanctuary", "Rain Therapy", 280, "RAIN", "linear-gradient(to bottom right, #0f172a, #334155)", "White Noise")
        ));
        MusicRoom r7 = new MusicRoom("room_sp_rain", "🌧️ Spotify Rain Sounds",
            "Tiếng mưa tự nhiên trắng giúp giảm âu lo và tập trung ôn thi trong tĩnh lặng.",
            "linear-gradient(to bottom, #111827 0%, #030712 60%, #121212 100%)", "#6b7280",
            "spotify", "Spotify Official", false, "https://open.spotify.com/playlist/37i9dQZF1DX8ymr6UES72F",
            "https://i.scdn.co/image/ab67706f00000002f5a560f845d064cf4f88e734", spRain, List.of("Ngọc", "Tuấn"));

        rooms.addAll(List.of(r1, r2, r3, r4, r5, r6, r7));
    }

    public List<MusicRoom> getRooms() { return rooms; }
    public MusicRoom getCurrentRoom() { return currentRoom; }
    public MusicTrack getCurrentTrack() {
        if (currentRoom == null || currentRoom.getPlaylist().isEmpty()) return null;
        if (currentTrackIndex < 0 || currentTrackIndex >= currentRoom.getPlaylist().size()) {
            currentTrackIndex = 0;
        }
        return currentRoom.getPlaylist().get(currentTrackIndex);
    }
    public int getCurrentTrackIndex() { return currentTrackIndex; }
    public boolean isPlaying() { return isPlaying; }
    public boolean isMuted() { return isMuted; }
    public double getVolume() { return volume; }
    public double getCurrentPositionSeconds() { return currentPositionSeconds; }
    public boolean isShuffle() { return isShuffle; }
    public boolean isRepeat() { return isRepeat; }
    public float[] getVisualizerBars() { return visualizerBars; }

    public void addTrackChangeListener(Consumer<MusicTrack> l) { trackChangeListeners.add(l); }
    public void addPlayStateListener(Consumer<Boolean> l) { playStateListeners.add(l); }
    public void addTimeUpdateListener(Consumer<Double> l) { timeUpdateListeners.add(l); }
    public void addVisualizerListener(Consumer<float[]> l) { visualizerListeners.add(l); }
    public void addRoomChangeListener(Consumer<MusicRoom> l) { roomChangeListeners.add(l); }
    public void addRoomUpdateListener(Runnable l) { roomUpdateListeners.add(l); }

    public MusicRoom createUserRoom(String roomName, String description, String themeGradient, String accentColor, String initialSpotifyUrl, String creatorUsername, String creatorDisplayName) {
        String id = "room_user_" + System.currentTimeMillis();
        List<MusicTrack> initialTracks = new ArrayList<>();

        String spUrl = (initialSpotifyUrl != null && !initialSpotifyUrl.isBlank()) ? initialSpotifyUrl.trim() : "https://open.spotify.com/playlist/37i9dQZF1DX8Uebhn9wzrS";
        SpotifyService.SpotifyTrackInfo info = SpotifyService.resolveUrl(spUrl);
        String cover = null;
        if (info != null) {
            initialTracks.add(MusicTrack.fromSpotify(info));
            cover = info.thumbnailUrl();
        }

        // Add 3 beautiful study tracks to the user's room
        initialTracks.add(new MusicTrack("u-trk-1", "Chill Study Vibes", creatorDisplayName, "Study Sessions", 215, "LOFI", "linear-gradient(to bottom right, #1db954, #10b981)", "Lofi"));
        initialTracks.add(new MusicTrack("u-trk-2", "Late Night Focus Piano", creatorDisplayName, "Night Study", 230, "PIANO", "linear-gradient(to bottom right, #6366f1, #a855f7)", "Piano"));
        initialTracks.add(new MusicTrack("u-trk-3", "Acoustic Coffee Break", creatorDisplayName, "Coffee Chill", 195, "CAFE", "linear-gradient(to bottom right, #f59e0b, #d97706)", "Acoustic"));

        MusicRoom newRoom = new MusicRoom(
            id, roomName, description,
            themeGradient != null ? themeGradient : "linear-gradient(to bottom, #1e1b4b 0%, #0f172a 60%, #121212 100%)",
            accentColor != null ? accentColor : "#1db954",
            creatorUsername, creatorDisplayName, true, spUrl, cover,
            initialTracks, List.of(creatorDisplayName)
        );

        rooms.add(newRoom);
        switchRoom(id);
        notifyRoomUpdated();

        broadcastSync("CREATE_ROOM", id + ":::" + roomName + ":::" + description + ":::" + creatorUsername + ":::" + creatorDisplayName + ":::" + spUrl);
        return newRoom;
    }

    public void switchRoom(String roomId) {
        switchRoom(roomId, null);
    }

    public void switchRoom(String roomId, String roomName) {
        for (MusicRoom r : rooms) {
            if (r.getId().equals(roomId)) {
                if (currentRoom != null) {
                    currentRoom.removeListener(currentUsername);
                }
                currentRoom = r;
                currentRoom.addListener(currentUsername);
                currentTrackIndex = 0;
                currentPositionSeconds = 0.0;
                notifyRoomChanged();
                notifyTrackChanged();
                broadcastSync("ROOM_CHANGE", roomId);
                if (isPlaying) {
                    syncTrackPlayback();
                }
                syncInitialRoomFromDatabase(roomId);
                return;
            }
        }
        // If not found in local list, create dynamically and join
        String name = (roomName != null && !roomName.isBlank()) ? roomName : "Phòng nghe nhạc của bạn bè";
        MusicRoom newRoom = new MusicRoom(roomId, name,
            "Cùng nghe nhạc & tập trung học tập 🎧",
            "linear-gradient(to bottom, #1e1b4b 0%, #0f172a 60%, #121212 100%)", "#6366f1",
            "friend", name, true, null,
            "https://images.unsplash.com/photo-1518495973542-4542c06a5843?w=600&q=80",
            new ArrayList<>(loadUserPlaylist()), new ArrayList<>(List.of(currentUsername)));
        rooms.add(newRoom);
        switchRoom(roomId, name);
    }

    private void syncInitialRoomFromDatabase(String roomId) {
        if (musicPresenceRepository == null) return;
        Thread.ofVirtual().start(() -> {
            try {
                var dbPlaylist = musicPresenceRepository.getRoomPlaylist(roomId);
                var state = musicPresenceRepository.getRoomSyncState(roomId);
                Platform.runLater(() -> {
                    if (currentRoom != null && currentRoom.getId().equals(roomId)) {
                        if (dbPlaylist != null && !dbPlaylist.isEmpty()) {
                            for (var item : dbPlaylist) {
                                boolean exists = currentRoom.getPlaylist().stream().anyMatch(t -> t.id().equals(item.trackId()));
                                if (!exists) {
                                    currentRoom.getPlaylist().add(new MusicTrack(
                                        item.trackId(), item.title(), item.artist(), "YouTube Audio",
                                        item.durationSeconds(), "YOUTUBE",
                                        "linear-gradient(to bottom right, #f43f5e, #fb7185)",
                                        "YouTube Audio", null, null, null, item.thumbnailUrl(), item.audioPath()
                                    ));
                                }
                            }
                            notifyRoomUpdated();
                        }
                        if (state != null && state.currentTrackId() != null && !state.currentTrackId().isBlank()) {
                            long elapsed = (System.currentTimeMillis() / 1000) - state.updatedAtEpoch();
                            double targetPos = state.positionSeconds() + (state.isPlaying() ? Math.max(0, elapsed) : 0);
                            applyRemoteTrack(state.currentTrackId(), state.isPlaying(), targetPos);
                        }
                    }
                });
            } catch (Exception ignored) { }
        });
    }

    public void play() {
        if (!isPlaying) {
            isPlaying = true;
            if (activeMediaPlayer != null) {
                Platform.runLater(() -> activeMediaPlayer.play());
            } else {
                syncTrackPlayback();
            }
            notifyPlayStateChanged();
            broadcastSync("PLAY", currentTrackIndex + ":" + (int)currentPositionSeconds);
            syncRoomStateToDatabase();
        }
    }

    public void pause() {
        if (isPlaying) {
            isPlaying = false;
            if (activeMediaPlayer != null) {
                Platform.runLater(() -> activeMediaPlayer.pause());
            }
            notifyPlayStateChanged();
            broadcastSync("PAUSE", currentTrackIndex + ":" + (int)currentPositionSeconds);
            syncRoomStateToDatabase();
        }
    }

    public void togglePlayPause() {
        if (isPlaying) pause();
        else play();
    }

    private void syncTrackPlayback() {
        MusicTrack t = getCurrentTrack();
        if (t == null) return;

        File audioFile = null;
        if (t.widgetSrc() != null && !t.widgetSrc().isBlank()) {
            File f = new File(t.widgetSrc());
            if (f.exists() && f.isFile()) {
                audioFile = f;
            }
        }

        // Fallback: search in .cache/audio/ for matching video ID
        if (audioFile == null && t.id() != null && t.id().startsWith("yt-")) {
            String vId = t.id().substring(3);
            File audioDir = new File(System.getProperty("user.dir"), ".cache/audio");
            File[] matches = audioDir.listFiles((dir, name) -> name.startsWith(vId + "."));
            if (matches != null && matches.length > 0) {
                audioFile = matches[0];
            }
        }

        if (audioFile != null) {
            final File finalAudio = audioFile;
            System.out.println("[MusicPlayer] Playing audio file: " + finalAudio.getAbsolutePath() + " (" + t.title() + ")");
            Platform.runLater(() -> {
                try {
                    if (activeMediaPlayer != null) {
                        activeMediaPlayer.stop();
                        activeMediaPlayer.dispose();
                        activeMediaPlayer = null;
                    }
                    Media media = new Media(finalAudio.toURI().toString());
                    activeMediaPlayer = new MediaPlayer(media);
                    activeMediaPlayer.setVolume(isMuted ? 0.0 : volume);

                    activeMediaPlayer.currentTimeProperty().addListener((obs, oldT, newT) -> {
                        if (newT != null) {
                            currentPositionSeconds = newT.toSeconds();
                            notifyTimeUpdated();
                        }
                    });

                    activeMediaPlayer.setOnEndOfMedia(() -> {
                        System.out.println("[MusicPlayer] End of song reached: " + t.title() + ". Auto-switching to next track...");
                        if (isRepeat) {
                            activeMediaPlayer.seek(javafx.util.Duration.ZERO);
                            activeMediaPlayer.play();
                        } else {
                            next();
                        }
                    });

                    activeMediaPlayer.setOnError(() -> {
                        System.err.println("[MusicPlayer] MediaPlayer error: " + activeMediaPlayer.getError());
                        // Try skipping to next track on failure
                        Platform.runLater(this::next);
                    });

                    if (isPlaying) {
                        activeMediaPlayer.play();
                    }
                } catch (Exception ex) {
                    System.err.println("[MusicPlayer] Failed to initialize MediaPlayer: " + ex.getMessage());
                }
            });
        } else {
            // Ambient synthesizer or Spotify stream
            Platform.runLater(() -> {
                if (activeMediaPlayer != null) {
                    activeMediaPlayer.stop();
                    activeMediaPlayer.dispose();
                    activeMediaPlayer = null;
                }
            });
            if (t.isSpotify() && t.widgetSrc() != null) {
                spotifyBridge.loadSpotifyEmbed(t.widgetSrc());
            }
        }
    }

    public void next() {
        if (currentRoom == null || currentRoom.getPlaylist().isEmpty()) return;
        if (isShuffle) {
            if (currentRoom.getPlaylist().size() > 1) {
                int nextIdx;
                do {
                    nextIdx = random.nextInt(currentRoom.getPlaylist().size());
                } while (nextIdx == currentTrackIndex);
                currentTrackIndex = nextIdx;
            }
        } else {
            currentTrackIndex = (currentTrackIndex + 1) % currentRoom.getPlaylist().size();
        }
        currentPositionSeconds = 0.0;
        isPlaying = true;
        notifyTrackChanged();
        syncTrackPlayback();
        notifyPlayStateChanged();
        broadcastSync("NEXT", String.valueOf(currentTrackIndex));
        syncRoomStateToDatabase();
    }

    public void prev() {
        if (currentRoom == null || currentRoom.getPlaylist().isEmpty()) return;
        if (currentPositionSeconds > 4.0) {
            currentPositionSeconds = 0.0;
        } else {
            currentTrackIndex = (currentTrackIndex - 1 + currentRoom.getPlaylist().size()) % currentRoom.getPlaylist().size();
            currentPositionSeconds = 0.0;
        }
        notifyTrackChanged();
        if (isPlaying) {
            syncTrackPlayback();
            broadcastSync("PREV", String.valueOf(currentTrackIndex));
            syncRoomStateToDatabase();
        }
    }

    public void selectTrack(int index) {
        if (currentRoom == null || index < 0 || index >= currentRoom.getPlaylist().size()) return;
        currentTrackIndex = index;
        currentPositionSeconds = 0.0;
        notifyTrackChanged();
        syncTrackPlayback();
        if (!isPlaying) {
            isPlaying = true;
            notifyPlayStateChanged();
        }
        broadcastSync("PLAY", currentTrackIndex + ":0");
        syncRoomStateToDatabase();
    }

    public void seek(double progressRatio) {
        MusicTrack track = getCurrentTrack();
        if (track == null) return;
        double targetSec = Math.max(0, Math.min(track.durationSeconds(), progressRatio * track.durationSeconds()));
        currentPositionSeconds = targetSec;
        if (activeMediaPlayer != null) {
            Platform.runLater(() -> activeMediaPlayer.seek(javafx.util.Duration.seconds(targetSec)));
        }
        notifyTimeUpdated();
        if (isPlaying) {
            broadcastSync("SEEK", String.valueOf((int)targetSec));
            syncRoomStateToDatabase();
        }
    }

    public void setVolume(double val) {
        this.volume = Math.max(0.0, Math.min(1.0, val));
        if (this.volume > 0) {
            this.isMuted = false;
        }
        if (activeMediaPlayer != null) {
            Platform.runLater(() -> activeMediaPlayer.setVolume(isMuted ? 0.0 : volume));
        }
    }

    public void toggleMute() {
        this.isMuted = !this.isMuted;
        if (activeMediaPlayer != null) {
            Platform.runLater(() -> activeMediaPlayer.setVolume(isMuted ? 0.0 : volume));
        }
    }

    public void toggleShuffle() {
        this.isShuffle = !this.isShuffle;
        notifyRoomUpdated();
    }

    public void toggleRepeat() {
        this.isRepeat = !this.isRepeat;
        notifyRoomUpdated();
    }

    public void addSpotifyTrack(SpotifyService.SpotifyTrackInfo info) {
        if (currentRoom == null || info == null) return;
        MusicTrack spTrack = MusicTrack.fromSpotify(info);
        currentRoom.getPlaylist().add(spTrack);
        notifyRoomUpdated();
        selectTrack(currentRoom.getPlaylist().size() - 1);
        broadcastSync("ADD_SP", info.spotifyUrl());
    }

    public void sendReaction(String emoji) {
        if (currentRoom != null) {
            currentRoom.addChatMessage(currentUsername, emoji, true);
            notifyRoomUpdated();
            broadcastSync("REACTION", emoji);
        }
    }

    public void sendRoomChat(String text) {
        if (currentRoom != null && text != null && !text.isBlank()) {
            currentRoom.addChatMessage(currentUsername, text.trim(), false);
            notifyRoomUpdated();
            broadcastSync("CHAT", text.trim());
        }
    }

    private void broadcastSync(String action, String payload) {
        if (peerNode != null && currentRoom != null) {
            String msg = "MUSIC_SYNC|" + currentRoom.getId() + "|" + action + "|" + payload + "|" + currentUsername;
            peerNode.broadcast(currentUsername, msg);
        }
    }

    public void handlePeerSync(String sender, String body) {
        if (sender.equals(currentUsername)) return;
        try {
            String[] parts = body.split("\\|", 5);
            if (parts.length < 5) return;
            String roomId = parts[1];
            String action = parts[2];
            String payload = parts[3];
            String originUser = parts[4];

            Platform.runLater(() -> {
                if ("CREATE_ROOM".equals(action)) {
                    String[] d = payload.split(":::", 6);
                    if (d.length >= 5) {
                        String nId = d[0];
                        String nName = d[1];
                        String nDesc = d[2];
                        String nOwner = d[3];
                        String nOwnerDisp = d[4];
                        String nSpUrl = (d.length >= 6) ? d[5] : "";
                        boolean exists = rooms.stream().anyMatch(r -> r.getId().equals(nId));
                        if (!exists) {
                            List<MusicTrack> trks = new ArrayList<>();
                            if (!nSpUrl.isBlank()) {
                                SpotifyService.SpotifyTrackInfo info = SpotifyService.resolveUrl(nSpUrl);
                                if (info != null) trks.add(MusicTrack.fromSpotify(info));
                            }
                            trks.add(new MusicTrack("peer-trk-1", "Chill Study Beats", nOwnerDisp, "Study Sessions", 215, "LOFI", "linear-gradient(to bottom right, #1db954, #10b981)", "Lofi"));
                            MusicRoom peerRoom = new MusicRoom(nId, nName, nDesc, "linear-gradient(to bottom, #1e1b4b 0%, #0f172a 60%, #121212 100%)",
                                "#1db954", nOwner, nOwnerDisp, true, nSpUrl, null, trks, List.of(nOwnerDisp));
                            rooms.add(peerRoom);
                            notifyRoomUpdated();
                        }
                    }
                    return;
                }
                if ("REACTION".equals(action)) {
                    for (MusicRoom r : rooms) {
                        if (r.getId().equals(roomId)) {
                            r.addChatMessage(originUser, payload, true);
                            notifyRoomUpdated();
                            break;
                        }
                    }
                    return;
                }
                if ("CHAT".equals(action)) {
                    for (MusicRoom r : rooms) {
                        if (r.getId().equals(roomId)) {
                            r.addChatMessage(originUser, payload, false);
                            notifyRoomUpdated();
                            break;
                        }
                    }
                    return;
                }
                if ("ADD_SP".equals(action)) {
                    Thread.ofVirtual().start(() -> {
                        SpotifyService.SpotifyTrackInfo info = SpotifyService.resolveUrl(payload);
                        if (info != null) {
                            Platform.runLater(() -> {
                                for (MusicRoom r : rooms) {
                                    if (r.getId().equals(roomId)) {
                                        r.getPlaylist().add(MusicTrack.fromSpotify(info));
                                        notifyRoomUpdated();
                                        break;
                                    }
                                }
                            });
                        }
                    });
                    return;
                }

                if (currentRoom != null && currentRoom.getId().equals(roomId)) {
                    switch (action) {
                        case "PLAY" -> {
                            String[] p = payload.split(":");
                            if (p.length >= 1) currentTrackIndex = Integer.parseInt(p[0]);
                            if (p.length >= 2) currentPositionSeconds = Double.parseDouble(p[1]);
                            isPlaying = true;
                            syncTrackPlayback();
                            notifyTrackChanged();
                            notifyPlayStateChanged();
                        }
                        case "PAUSE" -> {
                            isPlaying = false;
                            notifyPlayStateChanged();
                        }
                        case "NEXT", "PREV" -> {
                            currentTrackIndex = Integer.parseInt(payload);
                            currentPositionSeconds = 0.0;
                            syncTrackPlayback();
                            notifyTrackChanged();
                        }
                        case "SEEK" -> {
                            currentPositionSeconds = Double.parseDouble(payload);
                            notifyTimeUpdated();
                        }
                        case "ROOM_CHANGE" -> {
                            currentRoom.addListener(originUser);
                            notifyRoomUpdated();
                        }
                    }
                }
            });
        } catch (Exception ignored) { }
    }

    private void notifyTrackChanged() {
        MusicTrack t = getCurrentTrack();
        Platform.runLater(() -> {
            for (var l : trackChangeListeners) l.accept(t);
        });
    }

    private void notifyPlayStateChanged() {
        Platform.runLater(() -> {
            for (var l : playStateListeners) l.accept(isPlaying);
        });
    }

    private void notifyTimeUpdated() {
        Platform.runLater(() -> {
            for (var l : timeUpdateListeners) l.accept(currentPositionSeconds);
        });
    }

    private void notifyVisualizerUpdated() {
        float[] copy = Arrays.copyOf(visualizerBars, visualizerBars.length);
        Platform.runLater(() -> {
            for (var l : visualizerListeners) l.accept(copy);
        });
    }

    private void notifyRoomChanged() {
        Platform.runLater(() -> {
            for (var l : roomChangeListeners) l.accept(currentRoom);
        });
    }

    private void notifyRoomUpdated() {
        Platform.runLater(() -> {
            for (var l : roomUpdateListeners) l.run();
        });
    }

    // =========================================================================
    // BACKGROUND REAL HIGH-FIDELITY SOUND GENERATOR (100% RELIABLE PLAYBACK)
    // =========================================================================
    private void startAudioEngine() {
        audioThread = new Thread(() -> {
            try {
                AudioFormat format = new AudioFormat(44100f, 16, 2, true, false);
                audioLine = AudioSystem.getSourceDataLine(format);
                audioLine.open(format, 44100 * 2);
                audioLine.start();

                final int bufferSize = 2048; // ~23ms chunks
                byte[] audioBuffer = new byte[bufferSize];
                double pinkState0 = 0, pinkState1 = 0, pinkState2 = 0;
                long lastTimeUpdateMillis = System.currentTimeMillis();
                long lastVisUpdateMillis = System.currentTimeMillis();

                while (isRunning) {
                    if (!isPlaying) {
                        Thread.sleep(40);
                        decayVisualizer();
                        if (System.currentTimeMillis() - lastVisUpdateMillis > 50) {
                            notifyVisualizerUpdated();
                            lastVisUpdateMillis = System.currentTimeMillis();
                        }
                        continue;
                    }

                    if (activeMediaPlayer != null) {
                        Thread.sleep(40);
                        updateVisualizerRealtime();
                        if (System.currentTimeMillis() - lastVisUpdateMillis > 50) {
                            notifyVisualizerUpdated();
                            lastVisUpdateMillis = System.currentTimeMillis();
                        }
                        continue;
                    }

                    MusicTrack track = getCurrentTrack();
                    if (track == null) {
                        Thread.sleep(50);
                        continue;
                    }

                    double effectiveVol = isMuted ? 0.0 : volume;
                    String soundType = track.soundType();
                    int trackSeed = Math.abs(track.id().hashCode());

                    for (int i = 0; i < bufferSize; i += 4) {
                        double sampleLeft = 0;
                        double sampleRight = 0;
                        double t = currentPositionSeconds + (double)i / (44100.0 * 4.0);

                        switch (soundType) {
                            case "LOFI" -> {
                                // Rich Rhodes Lo-Fi Chord progressions
                                int chordCycle = (trackSeed % 2 == 0) ? 0 : 1;
                                int chordIndex = (int)(t / 3.0) % 4;
                                double[] freqs;
                                if (chordCycle == 0) {
                                    freqs = switch (chordIndex) {
                                        case 0 -> new double[]{293.66, 349.23, 440.00, 523.25}; // Dm9
                                        case 1 -> new double[]{246.94, 329.63, 392.00, 493.88}; // Em7
                                        case 2 -> new double[]{261.63, 329.63, 392.00, 493.88}; // Cmaj7
                                        default -> new double[]{220.00, 261.63, 329.63, 392.00}; // Am7
                                    };
                                } else {
                                    freqs = switch (chordIndex) {
                                        case 0 -> new double[]{349.23, 440.00, 523.25, 659.25}; // Fmaj7
                                        case 1 -> new double[]{329.63, 392.00, 493.88, 587.33}; // Em7
                                        case 2 -> new double[]{293.66, 349.23, 440.00, 523.25}; // Dm7
                                        default -> new double[]{261.63, 329.63, 392.00, 523.25}; // Cmaj7
                                    };
                                }
                                double chordEnvelope = Math.max(0.12, 1.0 - ((t % 3.0) / 3.0) * 0.65);
                                double wave = 0;
                                for (double f : freqs) {
                                    wave += Math.sin(2 * Math.PI * f * t) * 0.16;
                                    wave += Math.sin(4 * Math.PI * f * t) * 0.04;
                                }
                                wave *= chordEnvelope;

                                // Sub bass kick
                                double kickEnv = Math.exp(-((t % 1.5) / 1.5) * 6.0);
                                double kick = Math.sin(2 * Math.PI * 65.0 * t) * kickEnv * 0.15;

                                // Gentle vinyl crackle
                                double crackle = (random.nextDouble() > 0.995) ? (random.nextDouble() * 0.12) : 0.0;
                                double hiss = (random.nextDouble() - 0.5) * 0.012;

                                sampleLeft = wave + kick + crackle + hiss;
                                sampleRight = wave * 0.96 + kick + crackle + hiss;
                            }
                            case "PIANO" -> {
                                // Emotional Romantic Classical Piano
                                int pStep = (int)(t / 1.4) % 8;
                                double[] pNotes = (trackSeed % 2 == 0)
                                    ? new double[]{261.63, 329.63, 392.00, 523.25, 493.88, 392.00, 329.63, 293.66}
                                    : new double[]{220.00, 261.63, 329.63, 440.00, 392.00, 329.63, 261.63, 196.00};
                                double pFreq = pNotes[pStep];
                                double pEnv = Math.exp(-((t / 1.4) % 1.0) * 2.2);
                                double piano = (Math.sin(2 * Math.PI * pFreq * t) + 0.45 * Math.sin(4 * Math.PI * pFreq * t) + 0.25 * Math.sin(6 * Math.PI * pFreq * t)) * pEnv * 0.38;
                                double bassNote = Math.sin(2 * Math.PI * (pFreq * 0.5) * t) * pEnv * 0.12;
                                sampleLeft = piano + bassNote;
                                sampleRight = piano * 0.95 + bassNote;
                            }
                            case "CAFE" -> {
                                // Acoustic guitar arpeggio in cozy coffeehouse
                                int noteIdx = (int)(t * 2.2) % 8;
                                double[] melody = {261.63, 329.63, 392.00, 523.25, 392.00, 329.63, 293.66, 349.23};
                                double noteFreq = melody[noteIdx];
                                double noteEnv = Math.exp(-((t * 2.2) % 1.0) * 3.2);
                                double guitar = (Math.sin(2 * Math.PI * noteFreq * t) + 0.35 * Math.sin(4 * Math.PI * noteFreq * t)) * noteEnv * 0.32;
                                double cafeNoise = (random.nextDouble() - 0.5) * 0.015;
                                sampleLeft = guitar + cafeNoise;
                                sampleRight = guitar * 0.92 + cafeNoise;
                            }
                            case "VPOP" -> {
                                // Vietnamese Indie & Acoustic Ballad
                                int vStep = (int)(t * 1.8) % 8;
                                double[] vMelody = {261.63, 329.63, 392.00, 440.00, 523.25, 440.00, 392.00, 329.63};
                                double vFreq = vMelody[vStep];
                                double vEnv = Math.exp(-((t * 1.8) % 1.0) * 2.5);
                                double acoustic = (Math.sin(2 * Math.PI * vFreq * t) + 0.3 * Math.sin(4 * Math.PI * vFreq * t)) * vEnv * 0.32;
                                double pad = Math.sin(2 * Math.PI * 130.81 * t) * 0.08;
                                sampleLeft = acoustic + pad;
                                sampleRight = acoustic * 0.95 + pad;
                            }
                            case "BINAURAL" -> {
                                // 432 Hz alpha wave focus harmonics
                                double carrier = 432.0;
                                double beat = 10.0;
                                double leftTone = Math.sin(2 * Math.PI * carrier * t) * 0.22;
                                double rightTone = Math.sin(2 * Math.PI * (carrier + beat) * t) * 0.22;
                                double pad = Math.sin(2 * Math.PI * 108.0 * t) * 0.12;
                                sampleLeft = leftTone + pad;
                                sampleRight = rightTone + pad;
                            }
                            case "SYNTHWAVE" -> {
                                // Retro 80s bassline & synth lead
                                int step = (int)(t * 3.6) % 8;
                                double[] bass = {110.0, 110.0, 130.81, 110.0, 146.83, 110.0, 164.81, 130.81};
                                double bFreq = bass[step];
                                double bEnv = Math.exp(-((t * 3.6) % 1.0) * 2.0);
                                double synth = Math.sin(2 * Math.PI * bFreq * t) * bEnv * 0.36;
                                double lead = Math.sin(2 * Math.PI * 440.0 * t) * 0.09;
                                sampleLeft = synth + lead;
                                sampleRight = synth * 0.94 + lead;
                            }
                            case "RAIN" -> {
                                // Pink noise rainfall with raindrops
                                double white = random.nextDouble() * 2.0 - 1.0;
                                pinkState0 = 0.997 * pinkState0 + white * 0.04;
                                pinkState1 = 0.985 * pinkState1 + white * 0.08;
                                pinkState2 = 0.950 * pinkState2 + white * 0.15;
                                double rain = (pinkState0 + pinkState1 + pinkState2 + white * 0.1) * 0.22;
                                if (random.nextDouble() > 0.999) {
                                    rain += Math.sin(2 * Math.PI * (600 + random.nextInt(400)) * t) * 0.15;
                                }
                                sampleLeft = rain;
                                sampleRight = rain * 0.98;
                            }
                            default -> {
                                int defIdx = (int)(t / 2.5) % 4;
                                double[] defFreqs = {261.63, 329.63, 392.00, 523.25};
                                double defF = defFreqs[defIdx];
                                double defEnv = Math.exp(-((t / 2.5) % 1.0) * 2.0);
                                double val = Math.sin(2 * Math.PI * defF * t) * defEnv * 0.25;
                                sampleLeft = val;
                                sampleRight = val;
                            }
                        }

                        sampleLeft = Math.max(-1.0, Math.min(1.0, sampleLeft * effectiveVol));
                        sampleRight = Math.max(-1.0, Math.min(1.0, sampleRight * effectiveVol));

                        short valLeft = (short)(sampleLeft * 32767);
                        short valRight = (short)(sampleRight * 32767);

                        audioBuffer[i] = (byte)(valLeft & 0xFF);
                        audioBuffer[i + 1] = (byte)((valLeft >> 8) & 0xFF);
                        audioBuffer[i + 2] = (byte)(valRight & 0xFF);
                        audioBuffer[i + 3] = (byte)((valRight >> 8) & 0xFF);
                    }

                    audioLine.write(audioBuffer, 0, bufferSize);
                    double chunkSeconds = (double)bufferSize / (44100.0 * 4.0);
                    currentPositionSeconds += chunkSeconds;

                    if (currentPositionSeconds >= track.durationSeconds()) {
                        currentPositionSeconds = 0.0;
                        if (!isRepeat) {
                            Platform.runLater(this::next);
                        }
                    }

                    long now = System.currentTimeMillis();
                    if (now - lastTimeUpdateMillis >= 500) {
                        notifyTimeUpdated();
                        lastTimeUpdateMillis = now;
                    }

                    if (now - lastVisUpdateMillis >= 45) {
                        updateVisualizerRealtime();
                        notifyVisualizerUpdated();
                        lastVisUpdateMillis = now;
                    }
                }
            } catch (Exception ex) {
                System.err.println("Music player audio engine error: " + ex.getMessage());
            } finally {
                if (audioLine != null) {
                    try { audioLine.drain(); audioLine.close(); } catch (Exception ignored) {}
                }
            }
        });
        audioThread.setDaemon(true);
        audioThread.setName("Studyroom-MusicAudioEngine");
        audioThread.start();
    }

    private void updateVisualizerRealtime() {
        for (int i = 0; i < visualizerBars.length; i++) {
            double base = 0.25 + 0.65 * Math.abs(Math.sin((currentPositionSeconds * 4.0) + (double)i * 0.35));
            double jitter = (random.nextDouble() - 0.5) * 0.15;
            float target = (float)Math.max(0.08, Math.min(1.0, base + jitter));
            visualizerBars[i] = visualizerBars[i] * 0.5f + target * 0.5f;
        }
    }

    private void decayVisualizer() {
        for (int i = 0; i < visualizerBars.length; i++) {
            visualizerBars[i] = Math.max(0.05f, visualizerBars[i] * 0.85f);
        }
    }

    public void stop() {
        isRunning = false;
        isPlaying = false;
        spotifyBridge.stop();
        if (audioThread != null) {
            audioThread.interrupt();
        }
    }
}
