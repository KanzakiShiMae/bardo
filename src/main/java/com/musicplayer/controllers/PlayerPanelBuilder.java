package com.musicplayer.controllers;

import org.kordamp.ikonli.javafx.StackedFontIcon;
import org.kordamp.ikonli.boxicons.BoxiconsRegular;
import org.kordamp.ikonli.boxicons.BoxiconsSolid;

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

public final class PlayerPanelBuilder {

    private PlayerPanelBuilder() {}

    /**
     * Canvas que participa en el sistema de layout de JavaFX.
     * El StackPane puede redimensionarlo libremente; no añade restricciones
     * de ancho/alto mínimos ni preferidos al contenedor padre.
     */
    private static final class ResizableCanvas extends Canvas {
        @Override public boolean isResizable()          { return true; }
        @Override public double minWidth(double h)      { return 0; }
        @Override public double minHeight(double w)     { return 0; }
        @Override public double maxWidth(double h)      { return Double.MAX_VALUE; }
        @Override public double maxHeight(double w)     { return Double.MAX_VALUE; }
        @Override public double prefWidth(double h)     { return 0; }
        @Override public double prefHeight(double w)    { return 40; }
        @Override public void resize(double w, double h){ setWidth(w); setHeight(h); }
    }

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

        // ── Album art ────────────────────────────────────────────────────────
        ImageView artView = new ImageView();
        artView.setFitWidth(320); artView.setFitHeight(180); artView.setPreserveRatio(false);
        Rectangle artClip = new Rectangle(320, 180);
        artClip.setArcWidth(20); artClip.setArcHeight(20);
        artView.setClip(artClip);

        StackPane artStack = new StackPane(artView);
        artStack.setMaxSize(320, 180); artStack.setMinSize(320, 180);
        artStack.getStyleClass().add("player-art-container");

        // ── Waveform canvas ──────────────────────────────────────────────────
        Canvas waveCanvas = new Canvas(340, 64);
        waveCanvas.getStyleClass().add("wave-canvas");

        // ── Song info ────────────────────────────────────────────────────────
        Label panelTitle  = new Label("Selecciona una canción");
        panelTitle.getStyleClass().add("player-full-title");
        Label panelArtist = new Label("–");
        panelArtist.getStyleClass().add("player-full-artist");

        // ── Progress row ─────────────────────────────────────────────────────
        Label panelElapsed = new Label("0:00");
        panelElapsed.getStyleClass().add("time-label");
        panelElapsed.setMinWidth(Region.USE_PREF_SIZE); // never get squeezed by the slider

        Label panelTotal = new Label("0:00");
        panelTotal.getStyleClass().add("time-label");
        panelTotal.setMinWidth(Region.USE_PREF_SIZE);

        Slider panelProgress = new Slider(0, 100, 0);
        panelProgress.getStyleClass().add("progress-slider");
        panelProgress.setMaxWidth(Double.MAX_VALUE); // allow filling the StackPane
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
                if (total != null && total.greaterThan(Duration.ZERO)) {
                    Duration seekTo = total.multiply(panelProgress.getValue() / 100.0);
                    pi.mediaPlayer.seek(seekTo);
                    if (pi.onPartySeek != null) pi.onPartySeek.accept((long) seekTo.toMillis());
                }
            }
        });
        panelProgress.valueProperty().addListener((obs, old, val) -> {
            if (!pi.seeking || pi.mediaPlayer == null) return;
            Duration total = pi.mediaPlayer.getMedia().getDuration();
            if (total != null && total.greaterThan(Duration.ZERO))
                panelElapsed.setText(UIUtils.formatTime((int)(val.doubleValue() / 100.0 * total.toSeconds())));
        });

        // ── Spectrogram overlay canvas (resizable, lives behind the slider) ──
        ResizableCanvas spectroCanvas = new ResizableCanvas();
        spectroCanvas.getStyleClass().add("wave-canvas");
        spectroCanvas.setMouseTransparent(true);
        spectroCanvas.setVisible(false);

        // StackPane sizes the canvas automatically; no fixed-width binding needed
        StackPane progressStack = new StackPane(spectroCanvas, panelProgress);
        progressStack.setMinHeight(40);
        progressStack.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progressStack, Priority.ALWAYS);

        // Drag handlers for loop markers A/B (only active when loopMarkersActive)
        final double SNAP = 8.0;
        final int[]  drag = {0}; // 0=none, 1=loopIn, 2=loopOut
        progressStack.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (!pi.loopMarkersActive) return;
            double sw = progressStack.getWidth();
            if      (Math.abs(e.getX() - pi.loopInPct  / 100.0 * sw) <= SNAP) { drag[0] = 1; e.consume(); }
            else if (Math.abs(e.getX() - pi.loopOutPct / 100.0 * sw) <= SNAP) { drag[0] = 2; e.consume(); }
        });
        progressStack.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> {
            if (drag[0] == 0) return;
            double pct = Math.max(0, Math.min(100, e.getX() / progressStack.getWidth() * 100));
            if (drag[0] == 1) pi.loopInPct  = Math.min(pct, pi.loopOutPct - 1);
            else              pi.loopOutPct = Math.max(pct, pi.loopInPct  + 1);
            if (pi.spectroRedraw    != null) pi.spectroRedraw.run();
            if (pi.onMarkersChanged != null) pi.onMarkersChanged.run();
            e.consume();
        });
        progressStack.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
            if (drag[0] != 0) { drag[0] = 0; e.consume(); }
        });

        HBox timeRow = new HBox(14, panelElapsed, progressStack, panelTotal);
        timeRow.setAlignment(Pos.CENTER);
        timeRow.setMaxWidth(Double.MAX_VALUE);

        pi.panel = panel; pi.artStack = artStack; pi.artView = artView;
        pi.panelWaveCanvas = waveCanvas;
        pi.panelTitle = panelTitle; pi.panelArtist = panelArtist;
        pi.panelProgress = panelProgress; pi.panelElapsed = panelElapsed; pi.panelTotal = panelTotal;
        pi.panelSpectroCanvas = spectroCanvas;

        if (pi.isPartyListener) {
            // ── Listener mode: read-only — no controls, no volume, progress not interactive ──
            progressStack.setMouseTransparent(true);
            panel.getChildren().addAll(artStack, waveCanvas, panelTitle, panelArtist, timeRow);
            return;
        }

        // ── Controls ─────────────────────────────────────────────────────────
        Button ppShuffle = new Button(); ppShuffle.getStyleClass().add("control-btn"); ppShuffle.setOnAction(e -> onToggleShuffle.run());
        MainController.ico(ppShuffle, BoxiconsRegular.SHUFFLE, 18, false);
        Button ppPlay = new Button(); ppPlay.getStyleClass().add("play-btn");
        ppPlay.setStyle("-fx-min-width:64px;-fx-min-height:64px;");
        MainController.ico(ppPlay, BoxiconsRegular.PLAY, 24, true);
        ppPlay.setOnAction(e -> onTogglePlay.accept(pi));
        Button ppRepeat = new Button(); ppRepeat.getStyleClass().add("control-btn");
        MainController.ico(ppRepeat, BoxiconsRegular.REPEAT, 18, false);
        ppRepeat.setOnAction(e -> {
            pi.looping = !pi.looping;
            UIUtils.toggleStyleClass(ppRepeat, "control-active-2", pi.looping);
            onLoopToggle.accept(pi);
        });
        if (pi.looping) ppRepeat.getStyleClass().add("control-active-2");

        HBox controls;
        if (pi.isMasterPartyPlayer) {
            // Party master: sin anterior/siguiente
            controls = new HBox(32, ppShuffle, ppPlay, ppRepeat);
        } else {
            Button ppPrev = new Button(); ppPrev.getStyleClass().add("control-btn"); ppPrev.setOnAction(e -> onPrev.accept(pi));
            MainController.ico(ppPrev, BoxiconsRegular.SKIP_PREVIOUS, 18, true);
            Button ppNext = new Button(); ppNext.getStyleClass().add("control-btn"); ppNext.setOnAction(e -> onNext.accept(pi));
            MainController.ico(ppNext, BoxiconsRegular.SKIP_NEXT, 18, true);
            controls = new HBox(32, ppShuffle, ppPrev, ppPlay, ppNext, ppRepeat);
        }
        controls.setAlignment(Pos.CENTER);

        // ── Volume ───────────────────────────────────────────────────────────
        Label volLbl = new Label(); volLbl.getStyleClass().add("time-label");
        volLbl.setGraphic(IkonUtil.duotone(BoxiconsSolid.VOLUME_FULL, BoxiconsRegular.VOLUME_FULL, 16));
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

        pi.panelPlayPause = ppPlay; pi.panelRepeat = ppRepeat; pi.panelVolumeSlider = volSlider;
        pi.panelShuffleBtn = ppShuffle;
    }
}
