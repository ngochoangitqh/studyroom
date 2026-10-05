package vn.studyroom;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

/**
 * Headless/Background bridge that embeds the official SoundCloud HTML5 Widget
 * using JavaFX WebView. Allows full JavaScript API control (play, pause, next, volume)
 * and seamless background streaming.
 */
public class SoundCloudAudioBridge {
    private final WebView webView;
    private final WebEngine webEngine;
    private String currentWidgetSrc = null;
    private boolean isReady = false;
    private double currentVolume = 70.0;

    public SoundCloudAudioBridge() {
        webView = new WebView();
        // Keep small footprint and headless
        webView.setMinSize(1, 1);
        webView.setPrefSize(1, 1);
        webView.setMaxSize(1, 1);
        webView.setOpacity(0.01);

        webEngine = webView.getEngine();
        webEngine.setJavaScriptEnabled(true);
        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                isReady = true;
                setVolume(currentVolume);
            }
        });
    }

    public WebView getWebView() {
        return webView;
    }

    public void loadTrack(String widgetSrc) {
        if (widgetSrc == null || widgetSrc.isBlank()) return;
        this.currentWidgetSrc = widgetSrc;
        this.isReady = false;

        String html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <style>body { margin: 0; background: transparent; overflow: hidden; }</style>
              <script src="https://w.soundcloud.com/player/api.js"></script>
            </head>
            <body>
              <iframe id="sc_player" width="100%" height="166" scrolling="no" frameborder="no" allow="autoplay"
                src="%s">
              </iframe>
              <script>
                var widget;
                window.onload = function() {
                  var iframe = document.getElementById('sc_player');
                  widget = SC.Widget(iframe);
                  widget.bind(SC.Widget.Events.READY, function() {
                    widget.setVolume(%f);
                    widget.play();
                  });
                };
                function scPlay() { if (widget) widget.play(); }
                function scPause() { if (widget) widget.pause(); }
                function scToggle() { if (widget) widget.toggle(); }
                function scNext() { if (widget) widget.next(); }
                function scPrev() { if (widget) widget.prev(); }
                function scVolume(vol) { if (widget) widget.setVolume(vol); }
                function scSeek(percent) {
                  if (widget) {
                    widget.getDuration(function(d) {
                      widget.seekTo(d * percent);
                    });
                  }
                }
              </script>
            </body>
            </html>
            """.formatted(widgetSrc, currentVolume);

        Platform.runLater(() -> webEngine.loadContent(html));
    }

    public void play() {
        evalJs("scPlay();");
    }

    public void pause() {
        evalJs("scPause();");
    }

    public void toggle() {
        evalJs("scToggle();");
    }

    public void next() {
        evalJs("scNext();");
    }

    public void prev() {
        evalJs("scPrev();");
    }

    public void setVolume(double volumeRatio0to1) {
        this.currentVolume = Math.max(0.0, Math.min(100.0, volumeRatio0to1 * 100.0));
        evalJs("scVolume(" + currentVolume + ");");
    }

    public void seek(double progressRatio0to1) {
        evalJs("scSeek(" + Math.max(0.0, Math.min(1.0, progressRatio0to1)) + ");");
    }

    public void stop() {
        pause();
        Platform.runLater(() -> webEngine.loadContent("<html><body></body></html>"));
    }

    private void evalJs(String script) {
        Platform.runLater(() -> {
            try {
                if (isReady) {
                    webEngine.executeScript(script);
                }
            } catch (Exception ignored) {}
        });
    }
}
