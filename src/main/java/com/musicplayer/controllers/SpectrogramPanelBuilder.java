package com.musicplayer.controllers;

import com.musicplayer.models.Song;
import com.musicplayer.services.SpectrogramService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.function.Supplier;

/**
 * Adjunta un renderizador de espectrograma a un {@link Canvas} existente.
 *
 * <p>El canvas vive dentro del {@code StackPane} del slider de progreso, superpuesto
 * con la misma anchura exacta. La visualización es una forma de onda con picos y olas
 * cuyo color se lee en tiempo real del thumb del slider (variable CSS {@code bardo-accent}).
 */
public class SpectrogramPanelBuilder {

    static final Color FALLBACK_COLOR = Color.web("#f4a7b9");

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Conecta un ciclo de actualización (300 ms) al canvas dado.
     *
     * @param colorSupplier Lambda que devuelve el color de acento actual (se llama en cada frame).
     * @return El {@link Timeline} iniciado; el llamador debe detenerlo cuando proceda.
     */
    public static Timeline attachToCanvas(Canvas canvas, Song song,
                                          SpectrogramService service, PlayerInstance player,
                                          Supplier<Color> colorSupplier) {
        String songId = service.getSongId(song);
        final byte[][][] lastData = {null};

        Runnable updateFn = () -> {
            // canvas.isVisible() es la propia propiedad del nodo: sigue siendo true aunque
            // la pestaña (panel) esté oculta. Comprobamos también el panel para no ejecutar
            // el bucle por píxel del espectrograma en pestañas en segundo plano.
            if (!canvas.isVisible() || (player.panel != null && !player.panel.isVisible())) return;
            Color color = colorSupplier.get();
            if (service.hasCached(songId)) {
                byte[][] data = service.load(songId);
                if (data != null) {
                    lastData[0] = data;
                    renderWaveform(canvas, data, safePos(player), color);
                }
            } else if (service.isGenerating(songId)) {
                drawLoading(canvas, service.getProgress(songId), color);
            } else if (lastData[0] != null) {
                renderWaveform(canvas, lastData[0], safePos(player), color);
            }
            if (player.loopMarkersActive) drawMarkers(canvas, player, color);
        };
        player.spectroRedraw = updateFn;

        // Re-attaching (toggling the spectrogram off/on) must drop the previous pair of
        // listeners first, otherwise they accumulate on the same canvas indefinitely.
        if (player.spectroWidthListener  != null) canvas.widthProperty().removeListener(player.spectroWidthListener);
        if (player.spectroHeightListener != null) canvas.heightProperty().removeListener(player.spectroHeightListener);

        // JavaFX clears canvas contents on every setWidth/setHeight call, so redraw after resize
        player.spectroWidthListener  = (obs, o, n) -> { if (n.doubleValue() > 0) Platform.runLater(updateFn); };
        player.spectroHeightListener = (obs, o, n) -> { if (n.doubleValue() > 0) Platform.runLater(updateFn); };
        canvas.widthProperty().addListener(player.spectroWidthListener);
        canvas.heightProperty().addListener(player.spectroHeightListener);

        Timeline timer = new Timeline(new KeyFrame(Duration.millis(300), e -> updateFn.run()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();

        canvas.sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) timer.stop();
        });

        Platform.runLater(updateFn);
        return timer;
    }

    // ── Renderizado ───────────────────────────────────────────────────────────

    static void renderWaveform(Canvas canvas, byte[][] data, double playedFraction, Color wc) {
        int w = (int) canvas.getWidth();
        int h = (int) canvas.getHeight();
        if (w <= 0 || h <= 0 || data.length == 0) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        int numSlices = data.length;
        int numBands  = data[0].length;
        double centerY = h / 2.0;

        // Amplitude per pixel column: max across all bands (emphasises peaks)
        double[] amps = new double[w];
        for (int x = 0; x < w; x++) {
            int si = Math.min(numSlices - 1, (int)((x / (double) w) * numSlices));
            byte[] slice = data[si];
            int max = 0;
            for (int b = 0; b < numBands; b++) {
                int v = slice[b] & 0xFF;
                if (v > max) max = v;
            }
            amps[x] = max / 255.0;
        }

        // Mirrored polygon: top and bottom halves share x-coords
        int n = w + 2;
        double[] xPts = new double[n];
        double[] topY = new double[n];
        double[] botY = new double[n];
        xPts[0] = 0; topY[0] = botY[0] = centerY;
        for (int x = 0; x < w; x++) {
            double halfH = amps[x] * centerY * 0.88;
            xPts[x + 1] = x;
            topY[x + 1] = centerY - halfH;
            botY[x + 1] = centerY + halfH;
        }
        xPts[n - 1] = w - 1; topY[n - 1] = botY[n - 1] = centerY;

        double r = wc.getRed(), g = wc.getGreen(), b = wc.getBlue();

        // 1. Unplayed portion (full width, tint)
        gc.setFill(Color.color(r, g, b, 0.28));
        gc.fillPolygon(xPts, topY, n);
        gc.fillPolygon(xPts, botY, n);

        // 2. Played portion (clip to left side, full opacity)
        if (playedFraction > 0) {
            double clipW = Math.min(playedFraction, 1.0) * w;
            gc.save();
            gc.beginPath();
            gc.rect(0, 0, clipW, h);
            gc.clip();
            gc.setFill(Color.color(r, g, b, 0.72));
            gc.fillPolygon(xPts, topY, n);
            gc.fillPolygon(xPts, botY, n);
            gc.restore();
        }

        // 3. Silhouette outline
        gc.setStroke(Color.color(r, g, b, 0.55));
        gc.setLineWidth(1.0);
        gc.beginPath();
        gc.moveTo(0, centerY);
        for (int x = 0; x < w; x++) gc.lineTo(x, topY[x + 1]);
        gc.stroke();
        gc.beginPath();
        gc.moveTo(0, centerY);
        for (int x = 0; x < w; x++) gc.lineTo(x, botY[x + 1]);
        gc.stroke();
    }

    static void drawLoading(Canvas canvas, float progress, Color wc) {
        int w = (int) canvas.getWidth();
        int h = (int) canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);
        if (progress >= 0) {
            gc.setFill(Color.color(wc.getRed(), wc.getGreen(), wc.getBlue(), 0.35));
            gc.fillRect(0, h - 3, progress * w, 3);
            gc.setFill(Color.color(1, 1, 1, 0.60));
            gc.setFont(javafx.scene.text.Font.font(10));
            gc.setTextBaseline(javafx.geometry.VPos.CENTER);
            gc.fillText((int)(progress * 100) + "%", 8, h / 2.0);
        }
    }

    static void drawMarkers(Canvas canvas, PlayerInstance player, Color wc) {
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        GraphicsContext gc = canvas.getGraphicsContext2D();

        double inX  = player.loopInPct  / 100.0 * w;
        double outX = player.loopOutPct / 100.0 * w;

        Color ci = Color.color(0.40, 0.88, 0.55, 0.92);
        gc.setStroke(ci); gc.setLineWidth(1.5);
        gc.strokeLine(inX, 0, inX, h);
        gc.setFill(ci);
        gc.fillPolygon(new double[]{inX - 5, inX + 5, inX}, new double[]{0, 0, 9}, 3);

        Color co = Color.color(0.92, 0.38, 0.38, 0.92);
        gc.setStroke(co); gc.setLineWidth(1.5);
        gc.strokeLine(outX, 0, outX, h);
        gc.setFill(co);
        gc.fillPolygon(new double[]{outX - 5, outX + 5, outX}, new double[]{0, 0, 9}, 3);
    }

    static double safePos(PlayerInstance player) {
        if (player == null || player.mediaPlayer == null) return -1;
        try {
            double cur = player.mediaPlayer.getCurrentTime().toSeconds();
            double tot = player.mediaPlayer.getTotalDuration().toSeconds();
            return tot > 0 ? Math.min(1, cur / tot) : -1;
        } catch (Exception e) { return -1; }
    }

}
