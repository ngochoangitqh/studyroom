package vn.studyroom;

import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

/**
 * Embedded bridge for Spotify Web Player Widget using JavaFX WebView.
 * Allows playing and displaying Spotify tracks & playlists with background streaming.
 */
public class SpotifyAudioBridge {
    private final WebView webView;
    private final WebEngine webEngine;
    private String currentIframeUrl = null;

    public SpotifyAudioBridge() {
        webView = new WebView();
        webView.setMinSize(1, 1);
        webView.setPrefSize(1, 1);
        webView.setMaxSize(1, 1);
        webView.setOpacity(0.01);

        webEngine = webView.getEngine();
        webEngine.setJavaScriptEnabled(true);
    }

    public WebView getWebView() {
        return webView;
    }

    public void loadSpotifyEmbed(String iframeUrl) {
        if (iframeUrl == null || iframeUrl.isBlank()) return;
        this.currentIframeUrl = iframeUrl;

        String html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <style>
                body { margin: 0; padding: 0; background: #121212; overflow: hidden; }
                iframe { width: 100%; height: 352px; border: none; border-radius: 12px; }
              </style>
            </head>
            <body>
              <iframe src="%s" frameborder="0"
                allow="autoplay; clipboard-write; encrypted-media; fullscreen; picture-in-picture" loading="lazy">
              </iframe>
            </body>
            </html>
            """.formatted(iframeUrl);

        Platform.runLater(() -> webEngine.loadContent(html));
    }

    public void stop() {
        Platform.runLater(() -> webEngine.loadContent("<html><body style='background:#121212'></body></html>"));
    }
}
