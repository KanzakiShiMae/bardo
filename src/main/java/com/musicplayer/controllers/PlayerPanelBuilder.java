package com.musicplayer.controllers;

import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Construye el panel de reproductor completo (la vista grande que aparece en la pestaña)
 * y asigna todas las referencias de UI a la {@link PlayerInstance} dada.
 *
 * <p>El panel incluye: portada circular estática, animación de ondas, información de la
 * canción, barra de progreso, controles de transporte y slider de volumen individual.
 *
 * <p>La lógica de negocio (reproducir, pausar, cambiar pista) se inyecta como lambdas
 * para mantener este constructor sin dependencias del controlador principal.
 */
public final class PlayerPanelBuilder {

    private PlayerPanelBuilder() {}

    /**
     * Builds the full player panel and assigns all UI refs to {@code pi}.
     *
     * @param onVolumeChange  called with (pi, newPctInt) when volume slider changes
     * @param onLoopToggle    called after pi.looping changes (to sync outer btnRepeat)
     */
    public static void build(PlayerInstance pi,
                             Consumer<PlayerInstance> onTogglePlay,
                             Consumer<PlayerInstance> onPrev,
                             Consumer<PlayerInstance> onNext,
                             Runnable onToggleShuffle,
                             BiConsumer<PlayerInstance, Integer> onVolumeChange,
                             Consumer<PlayerInstance> onLoopToggle) {
        VBox panel = new VBox(24);
        panel.getStyleClass().addAll("panel", "player-full-panel");
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(40, 80, 40, 80));
        panel.setVisible(false); panel.setManaged(false);

        // Album art
        ImageView artView = new ImageView();
        artView.setFitWidth(200); artView.setFitHeight(200); artView.setPreserveRatio(false);
        artView.setClip(new Circle(100, 100, 100));
        StackPane artStack = new StackPane(artView);
        artStack.setMaxSize(200, 200); artStack.setMinSize(200, 200);
        artStack.getStyleClass().add("player-art-container");

        // Wave bars
        int N = 5;
        Rectangle[] bars = new Rectangle[N];
        HBox waveBox = new HBox(5); waveBox.setAlignment(Pos.BOTTOM_CENTER);
        waveBox.setPrefHeight(32); waveBox.setMinHeight(32); waveBox.setMaxHeight(32);
        for (int i = 0; i < N; i++) {
            bars[i] = new Rectangle(5, 8); bars[i].setArcWidth(3); bars[i].setArcHeight(3);
            bars[i].getStyleClass().add("wave-bar"); waveBox.getChildren().add(bars[i]);
        }
        int[][] frames = { {8,20,30,20,8}, {14,30,14,30,14}, {24,10,30,10,24}, {30,18,8,18,30}, {16,8,24,8,16} };
        int[] fi = {0};
        Timeline waveAnim = new Timeline(new KeyFrame(Duration.millis(330), e -> {
            fi[0] = (fi[0] + 1) % frames.length;
            for (int i = 0; i < N; i++) bars[i].setHeight(frames[fi[0]][i]);
        }));
        waveAnim.setCycleCount(Animation.INDEFINITE);

        // Song info
        Label panelTitle  = new Label("Selecciona una canción"); panelTitle.getStyleClass().add("player-full-title");
        Label panelArtist = new Label("–");                      panelArtist.getStyleClass().add("player-full-artist");

        // Progress
        Label panelElapsed = new Label("0:00"); panelElapsed.getStyleClass().add("time-label");
        Label panelTotal   = new Label("0:00"); panelTotal.getStyleClass().add("time-label");
        Slider panelProgress = new Slider(0, 100, 0); panelProgress.getStyleClass().add("progress-slider"); panelProgress.setPrefWidth(520);
        panelProgress.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            Node t = (Node) e.getTarget();
            if (t.getStyleClass().contains("thumb") || t.getStyleClass().contains("track")) {
                pi.seeking = true;
            } else {
                e.consume(); // click missed the track — block Slider's internal snap
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
        HBox timeRow = new HBox(14, panelElapsed, panelProgress, panelTotal); timeRow.setAlignment(Pos.CENTER);

        // Controls
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
        HBox controls = new HBox(32, ppShuffle, ppPrev, ppPlay, ppNext, ppRepeat); controls.setAlignment(Pos.CENTER);

        // Volume
        Label volLbl = new Label("🔊"); volLbl.getStyleClass().add("time-label");
        Slider volSlider = new Slider(0, 100, pi.volume * 100); volSlider.getStyleClass().add("volume-slider"); volSlider.setPrefWidth(160);
        Label volPct = new Label((int) Math.round(pi.volume * 100) + "%"); volPct.getStyleClass().add("volume-pct-label"); volPct.setMinWidth(36);
        volSlider.valueProperty().addListener((obs, old, val) -> {
            int pct = (int) Math.round(val.doubleValue());
            pi.volume = pct / 100.0;
            if (pi.mediaPlayer != null) pi.mediaPlayer.setVolume(pi.volume);
            volPct.setText(pct + "%");
            onVolumeChange.accept(pi, pct);
        });
        HBox volRow = new HBox(10, volLbl, volSlider, volPct); volRow.setAlignment(Pos.CENTER);

        panel.getChildren().addAll(artStack, waveBox, panelTitle, panelArtist, timeRow, controls, volRow);

        // Assign to pi
        pi.panel = panel; pi.artStack = artStack; pi.artView = artView; pi.waveAnim = waveAnim;
        pi.panelTitle = panelTitle; pi.panelArtist = panelArtist;
        pi.panelProgress = panelProgress; pi.panelElapsed = panelElapsed; pi.panelTotal = panelTotal;
        pi.panelPlayPause = ppPlay; pi.panelRepeat = ppRepeat; pi.panelVolumeSlider = volSlider;
    }
}
