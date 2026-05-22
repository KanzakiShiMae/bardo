package com.musicplayer.controllers;

import com.musicplayer.models.Song;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.text.TextAlignment;

/**
 * Construye el panel del reproductor Mashup, que reproduce dos canciones simultáneamente
 * con sliders de volumen independientes y un botón de crossfade.
 *
 * <p>Las referencias UI se almacenan en {@code piA} (reproductor primario).
 * Excepcionalmente, {@code piB.panelVolumeSlider} también se asigna para que
 * {@code MainController} pueda actualizar el volumen de la canción secundaria.
 *
 * <p>El crossfade intercambia los volúmenes de {@code piA} y {@code piB} en 1,5 s
 * mediante una animación gestionada en {@code MainController.crossfade()}.
 */
public final class MashupPanelBuilder {

    private MashupPanelBuilder() {}

    public static void build(PlayerInstance piA, PlayerInstance piB,
                             Runnable onTogglePlay, Runnable onCrossfade) {
        Song songA = piA.queue.isEmpty() ? null : piA.queue.get(0);
        Song songB = piB.queue.isEmpty() ? null : piB.queue.get(0);

        VBox panel = new VBox(20);
        panel.getStyleClass().addAll("panel", "player-full-panel");
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(40, 80, 40, 80));
        panel.setVisible(false); panel.setManaged(false);

        // Header
        Label header = new Label("♪♪  Reproductor Mashup");
        header.getStyleClass().add("player-full-title");

        // Two-song info row
        VBox colA = songColumn(songA, "①");
        VBox colB = songColumn(songB, "②");
        Label vs = new Label("✦");
        vs.setStyle("-fx-font-size:20px; -fx-text-fill:#d0b8e8;");
        HBox songsRow = new HBox(32, colA, vs, colB);
        songsRow.setAlignment(Pos.CENTER);

        // Volume + crossfade row
        int initA = (int) Math.round(piA.volume * 100);
        int initB = (int) Math.round(piB.volume * 100);

        Label lblA = new Label("①"); lblA.setStyle("-fx-font-size:15px; -fx-text-fill:#e8729a;");
        Slider sliderA = new Slider(0, 100, initA);
        sliderA.getStyleClass().add("volume-slider"); sliderA.setPrefWidth(130);
        Label pctA = new Label(initA + "%"); pctA.getStyleClass().add("volume-pct-label"); pctA.setMinWidth(36);

        Button xBtn = new Button("⇄"); xBtn.getStyleClass().add("mashup-xfade-btn");
        xBtn.setOnAction(e -> onCrossfade.run());

        Label pctB = new Label(initB + "%"); pctB.getStyleClass().add("volume-pct-label"); pctB.setMinWidth(36);
        Slider sliderB = new Slider(0, 100, initB);
        sliderB.getStyleClass().add("volume-slider"); sliderB.setPrefWidth(130);
        Label lblB = new Label("②"); lblB.setStyle("-fx-font-size:15px; -fx-text-fill:#6ba3d6;");

        sliderA.valueProperty().addListener((obs, old, val) -> {
            if (piA.mashupXfadeAnim != null) return;
            int pct = (int) Math.round(val.doubleValue());
            piA.volume = pct / 100.0;
            if (piA.mediaPlayer != null) piA.mediaPlayer.setVolume(piA.volume);
            pctA.setText(pct + "%");
        });
        sliderB.valueProperty().addListener((obs, old, val) -> {
            if (piA.mashupXfadeAnim != null) return;
            int pct = (int) Math.round(val.doubleValue());
            piB.volume = pct / 100.0;
            if (piB.mediaPlayer != null) piB.mediaPlayer.setVolume(piB.volume);
            pctB.setText(pct + "%");
        });

        HBox volRow = new HBox(10, lblA, sliderA, pctA, xBtn, pctB, sliderB, lblB);
        volRow.setAlignment(Pos.CENTER);

        // Play/pause button
        Button ppBtn = new Button("⏸");
        ppBtn.getStyleClass().add("play-btn");
        ppBtn.setStyle("-fx-min-width:64px; -fx-min-height:64px; -fx-font-size:22px;");
        ppBtn.setOnAction(e -> onTogglePlay.run());

        // Progress row
        Label elapsed = new Label("0:00"); elapsed.getStyleClass().add("time-label");
        Label total   = new Label("0:00"); total.getStyleClass().add("time-label");
        Slider progress = new Slider(0, 100, 0);
        progress.getStyleClass().add("progress-slider"); progress.setPrefWidth(400);
        progress.setOnMousePressed(e -> piA.seeking = true);
        progress.setOnMouseReleased(e -> {
            if (!piA.seeking) return;
            piA.seeking = false;
            if (piA.mediaPlayer != null) {
                javafx.util.Duration dur = piA.mediaPlayer.getMedia().getDuration();
                if (dur != null && dur.greaterThan(javafx.util.Duration.ZERO)) {
                    javafx.util.Duration target = dur.multiply(progress.getValue() / 100.0);
                    piA.mediaPlayer.seek(target);
                    if (piB.mediaPlayer != null) piB.mediaPlayer.seek(target);
                }
            }
        });
        HBox timeRow = new HBox(14, elapsed, progress, total);
        timeRow.setAlignment(Pos.CENTER);

        panel.getChildren().addAll(header, songsRow, volRow, ppBtn, timeRow);

        piA.panel              = panel;
        piA.panelPlayPause     = ppBtn;
        piA.panelProgress      = progress;
        piA.panelElapsed       = elapsed;
        piA.panelTotal         = total;
        piA.panelVolumeSlider  = sliderA;
        piB.panelVolumeSlider  = sliderB;
    }

    private static VBox songColumn(Song song, String badge) {
        ImageView art = new ImageView();
        art.setFitWidth(80); art.setFitHeight(80); art.setPreserveRatio(false);
        art.setClip(new Circle(40, 40, 40));
        if (song != null && song.getThumbnailUrl() != null && !song.getThumbnailUrl().isBlank()) {
            try { art.setImage(new Image(song.getThumbnailUrl(), 80, 80, false, true, true)); } catch (Exception ignored) {}
        }
        Label bdg = new Label(badge);
        bdg.setStyle("-fx-font-size:18px; -fx-text-fill:" + ("①".equals(badge) ? "#e8729a" : "#6ba3d6") + ";");
        Label title = new Label(song != null ? song.getTitle() : "—");
        title.getStyleClass().add("player-full-artist");
        title.setWrapText(true); title.setMaxWidth(160);
        title.setAlignment(Pos.CENTER); title.setTextAlignment(TextAlignment.CENTER);
        VBox col = new VBox(6, bdg, art, title);
        col.setAlignment(Pos.CENTER);
        return col;
    }
}
