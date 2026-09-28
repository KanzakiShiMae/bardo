package com.musicplayer.controllers;

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.URL;
import java.text.Normalizer;
import javax.imageio.ImageIO;

/**
 * Utilidades estáticas compartidas por los controladores.
 *
 * <p>No instanciable; todos los métodos son {@code static}.
 */
public final class UIUtils {

    private UIUtils() {}

    /**
     * Formatea segundos enteros como {@code "M:SS"} (p. ej. 125 → {@code "2:05"}).
     *
     * @param seconds duración total en segundos
     * @return cadena formateada
     */
    public static String formatTime(int seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * Añade o elimina {@code cls} de las clases CSS del nodo según {@code on},
     * evitando duplicados al añadir.
     */
    public static void toggleStyleClass(Node node, String cls, boolean on) {
        if (on) { if (!node.getStyleClass().contains(cls)) node.getStyleClass().add(cls); }
        else    { node.getStyleClass().remove(cls); }
    }

    /** Strips diacritics and lowercases {@code s} for accent-insensitive search. */
    public static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
            .toLowerCase();
    }

    /**
     * Abre {@code https://www.youtube.com/watch?v=<videoId>} en el navegador
     * predeterminado del sistema. Falla silenciosamente si no hay navegador disponible.
     */
    public static void openInBrowser(String videoId) {
        try { Desktop.getDesktop().browse(new URI("https://www.youtube.com/watch?v=" + videoId)); }
        catch (Exception ignored) {}
    }

    /**
     * Carga un PNG (vía {@link ImageIO}, no el decodificador de JavaFX) y lo reduce a
     * {@code targetSize} con Java2D antes de convertirlo a {@link Image}.
     *
     * <p>Ni el decodificador de {@code javafx.scene.image.Image} (constructor con
     * {@code requestedWidth/Height}) ni el escalado en tiempo de render de {@code ImageView}
     * (con {@code smooth=true}) bastan para arte de líneas duras y alto contraste (confirmado con
     * el logo del sidebar: las miniaturas normales, de textura fotográfica, se reducen bien con
     * JavaFX, pero este tipo de arte muestra claramente el bilineal de una sola pasada que usa
     * JavaFX para minificar). {@code targetSize} debe ser el tamaño EXACTO en el que se va a
     * mostrar la imagen (el {@code fitWidth/fitHeight} del {@code ImageView}) — dejarle a
     * {@code ImageView} cualquier reducción residual, aunque sea moderada (2:1, 3:1), sigue
     * perdiendo calidad visible en este tipo de contenido; a {@code targetSize} exacto, en
     * pantallas HiDPI como mucho amplía ligeramente, una operación mucho más segura.
     */
    public static Image loadDownscaledImage(URL url, int targetSize) throws java.io.IOException {
        BufferedImage src = ImageIO.read(url);
        BufferedImage scaled = highQualityDownscale(src, targetSize);
        int w = scaled.getWidth(), h = scaled.getHeight();
        int[] pixels = scaled.getRGB(0, 0, w, h, null, 0, w);
        WritableImage out = new WritableImage(w, h);
        out.getPixelWriter().setPixels(0, 0, w, h,
            javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);
        return out;
    }

    /** Reduce manteniendo proporción hasta que el lado mayor mida {@code targetSize}, a base de
     *  mitades sucesivas (cada paso reduce como mucho a la mitad) — a diferencia de un único paso
     *  bilineal, cada mitad SÍ promedia bien el área fuente, evitando aliasing en bordes duros. */
    private static BufferedImage highQualityDownscale(BufferedImage in, int targetSize) {
        double scale = (double) targetSize / Math.max(in.getWidth(), in.getHeight());
        int targetW = Math.max(1, (int) Math.round(in.getWidth()  * scale));
        int targetH = Math.max(1, (int) Math.round(in.getHeight() * scale));
        BufferedImage cur = in;
        int w = in.getWidth(), h = in.getHeight();
        while (w / 2 > targetW && h / 2 > targetH) {
            int nw = Math.max(targetW, w / 2), nh = Math.max(targetH, h / 2);
            cur = bilinearResize(cur, nw, nh);
            w = nw; h = nh;
        }
        return bilinearResize(cur, targetW, targetH);
    }

    private static BufferedImage bilinearResize(BufferedImage in, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(in, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
