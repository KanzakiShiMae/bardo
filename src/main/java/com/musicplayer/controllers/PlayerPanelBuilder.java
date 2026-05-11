package com.musicplayer.controllers;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Construye el panel de reproductor completo y asigna todas las referencias de UI
 * a la {@link PlayerInstance} dada. La visualización de onda se actualiza externamente
 * desde {@code MainController} vía el {@code AudioSpectrumListener} del MediaPlayer.
 */
public final class PlayerPanelBuilder {

    private PlayerPanelBuilder() {}

    public static void build(PlayerInstance pi,
                             Consumer<PlayerInstance> onTogglePlay,
                             Consumer<PlayerInstance> onPrev,
                             Consumer<PlayerInstance> onNext,
                             Runnable onToggleShuffle,
                             BiConsumer<PlayerInstance, Integer> onVolumeChange,
                             Consumer<PlayerInstance> onLoopToggle) {
        VBox panel = new VBox(20);
        panel.getStyleClass().addAll("panel", "player-full-panel");
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(40, 80, 40, 80));
        panel.setVisible(false); panel.setManaged(false);

        // ── Album art (square with rounded corners) ──────────────────────────
        ImageView artView = new ImageView();
        artView.setFitWidth(200); artView.setFitHeight(200); artView.setPreserveRatio(false);
        Rectangle artClip = new Rectangle(200, 200);
        artClip.setArcWidth(20); artClip.setArcHeight(20);
        artView.setClip(artClip);

        StackPane artStack = new StackPane(artView);
        artStack.setMaxSize(200, 200); artStack.setMinSize(200, 200);
        artStack.getStyleClass().add("player-art-container");

        // ── Waveform canvas ──────────────────────────────────────────────────
        Canvas waveCanvas = new Canvas(340, 64);

        // ── Song info ────────────────────────────────────────────────────────
        Label panelTitle  = new Label("Selecciona una canción");
        panelTitle.getStyleClass().add("player-full-title");
        Label panelArtist = new Label("–");
        panelArtist.getStyleClass().add("player-full-artist");

        // ── Progress ─────────────────────────────────────────────────────────
        Label panelElapsed = new Label("0:00"); panelElapsed.getStyleClass().add("time-label");
        Label panelTotal   = new Label("0:00"); panelTotal.getStyleClass().add("time-label");
        Slider panelProgress = new Slider(0, 100, 0);
        panelProgress.getStyleClass().add("progress-slider"); panelProgress.setPrefWidth(520);
        panelProgress.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            Node t = (Node) e.getTarget();
            if (t.getStyleClass().contains("thumb") || t.getStyleClass().contains("track")) {
                pi.seeking = true;
            } else {
                e.consume();
            }
        });
        panelProgress.setOnMouseReleased(e -> {
            if (!pi.seeking) return;
            pi.seeking = false;
            if (pi.mediaPlayer != null) {
                Duration total = pi.mediaPlayer.getMedia().getDuration();
                if (total != null && total.greaterThan(Duration.ZERO))
                    pi.mediaPlayer.seek(total.multiply(panelProgress.getValue() / 100.0));
            }
        });
        panelProgress.valueProperty().addListener((obs, old, val) -> {
            if (!pi.seeking || pi.mediaPlayer == null) return;
            Duration total = pi.mediaPlayer.getMedia().getDuration();
            if (total != null && total.greaterThan(Duration.ZERO))
                panelElapsed.setText(UIUtils.formatTime((int)(val.doubleValue() / 100.0 * total.toSeconds())));
        });
        HBox timeRow = new HBox(14, panelElapsed, panelProgress, panelTotal);
        timeRow.setAlignment(Pos.CENTER);

        // ── Controls ─────────────────────────────────────────────────────────
        Button ppShuffle = new Button("⇄"); ppShuffle.getStyleClass().add("control-btn"); ppShuffle.setOnAction(e -> onToggleShuffle.run());
        Button ppPrev    = new Button("⏮"); ppPrev.getStyleClass().add("control-btn");    ppPrev.setOnAction(e -> onPrev.accept(pi));
        Button ppPlay    = new Button("▶");  ppPlay.getStyleClass().add("play-btn");
        ppPlay.setStyle("-fx-min-width:64px;-fx-min-height:64px;-fx-font-size:22px;");
        ppPlay.setOnAction(e -> onTogglePlay.accept(pi));
        Button ppNext   = new Button("⏭"); ppNext.getStyleClass().add("control-btn"); ppNext.setOnAction(e -> onNext.accept(pi));
        Button ppRepeat = new Button("↺"); ppRepeat.getStyleClass().add("control-btn");
        ppRepeat.setOnAction(e -> {
            pi.looping = !pi.looping;
            UIUtils.toggleStyleClass(ppRepeat, "control-active", pi.looping);
            onLoopToggle.accept(pi);
        });
        if (pi.looping) ppRepeat.getStyleClass().add("control-active");
        HBox controls = new HBox(32, ppShuffle, ppPrev, ppPlay, ppNext, ppRepeat);
        controls.setAlignment(Pos.CENTER);

        // ── Volume ───────────────────────────────────────────────────────────
        Label volLbl = new Label("🔊"); volLbl.getStyleClass().add("time-label");
        Slider volSlider = new Slider(0, 100, pi.volume * 100);
        volSlider.getStyleClass().add("volume-slider"); volSlider.setPrefWidth(160);
        Label volPct = new Label((int) Math.round(pi.volume * 100) + "%");
        volPct.getStyleClass().add("volume-pct-label"); volPct.setMinWidth(36);
        volSlider.valueProperty().addListener((obs, old, val) -> {
            int pct = (int) Math.round(val.doubleValue());
            pi.volume = pct / 100.0;
            if (pi.mediaPlayer != null) pi.mediaPlayer.setVolume(pi.volume);
            volPct.setText(pct + "%");
            onVolumeChange.accept(pi, pct);
        });
        HBox volRow = new HBox(10, volLbl, volSlider, volPct); volRow.setAlignment(Pos.CENTER);

        panel.getChildren().addAll(artStack, waveCanvas, panelTitle, panelArtist, timeRow, controls, volRow);

        pi.panel = panel; pi.artStack = artStack; pi.artView = artView;
        pi.panelWaveCanvas = waveCanvas;
        pi.panelTitle = panelTitle; pi.panelArtist = panelArtist;
        pi.panelProgress = panelProgress; pi.panelElapsed = panelElapsed; pi.panelTotal = panelTotal;
        pi.panelPlayPause = ppPlay; pi.panelRepeat = ppRepeat; pi.panelVolumeSlider = volSlider;
    }
}
