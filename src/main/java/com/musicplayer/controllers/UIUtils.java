package com.musicplayer.controllers;

import javafx.scene.Node;
import java.awt.Desktop;
import java.net.URI;
import java.text.Normalizer;

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
}
