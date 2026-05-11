package com.musicplayer.controllers;

import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * Añade redimensionado por ratón a una ventana {@code UNDECORATED}.
 *
 * <p>Registra filtros de eventos en la {@link Scene} para detectar los bordes de la
 * ventana (zona de 6 px) y cambiar el cursor. Al pulsar y arrastrar en esa zona se
 * ajustan las dimensiones y posición del {@link Stage}.
 *
 * <p>Los eventos consumidos durante un arrastre de redimensionado impiden que otros
 * manejadores (p. ej. el arrastre de la barra de título) interfieran.
 *
 * <p>El redimensionado se desactiva automáticamente cuando la ventana está maximizada
 * o en pantalla completa.
 */
public final class ResizeHelper {

    private static final int BORDER = 6;

    private Dir    dir      = Dir.NONE;
    private boolean dragging;
    private double  dragX, dragY;
    private double  origX, origY, origW, origH;

    private ResizeHelper() {}

    /**
     * Adjunta los filtros de redimensionado a la escena y devuelve la instancia.
     *
     * @param stage escena propietaria del stage
     * @param scene escena sobre la que se registran los filtros
     * @return instancia activa consultable con {@link #isActive()}
     */
    public static ResizeHelper attach(Stage stage, Scene scene) {
        ResizeHelper r = new ResizeHelper();

        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (stage.isMaximized() || stage.isFullScreen()) {
                scene.setCursor(Cursor.DEFAULT);
                r.dir = Dir.NONE;
                return;
            }
            r.dir = detect(e.getX(), e.getY(), scene.getWidth(), scene.getHeight());
            scene.setCursor(r.dir.cursor);
        });

        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (r.dir == Dir.NONE) return;
            r.dragging = true;
            r.dragX = e.getScreenX(); r.dragY = e.getScreenY();
            r.origX = stage.getX();   r.origY = stage.getY();
            r.origW = stage.getWidth(); r.origH = stage.getHeight();
            e.consume();
        });

        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!r.dragging) return;
            double dx = e.getScreenX() - r.dragX;
            double dy = e.getScreenY() - r.dragY;
            double nx = r.origX, ny = r.origY, nw = r.origW, nh = r.origH;

            if (r.dir.east)  nw = Math.max(stage.getMinWidth(),  r.origW + dx);
            if (r.dir.west)  { nw = Math.max(stage.getMinWidth(),  r.origW - dx); nx = r.origX + (r.origW - nw); }
            if (r.dir.south) nh = Math.max(stage.getMinHeight(), r.origH + dy);
            if (r.dir.north) { nh = Math.max(stage.getMinHeight(), r.origH - dy); ny = r.origY + (r.origH - nh); }

            stage.setX(nx); stage.setY(ny);
            stage.setWidth(nw); stage.setHeight(nh);
            e.consume();
        });

        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (r.dragging) { r.dragging = false; e.consume(); }
        });

        scene.addEventFilter(MouseEvent.MOUSE_EXITED, e -> {
            if (!r.dragging) { r.dir = Dir.NONE; scene.setCursor(Cursor.DEFAULT); }
        });

        return r;
    }

    /**
     * {@code true} si el cursor está en la zona de borde (se mostraría cursor de resize)
     * o si hay un arrastre de redimensionado en curso.
     * Útil para que el arrastre de la barra de título ceda prioridad.
     */
    public boolean isActive() { return dir != Dir.NONE || dragging; }

    // ── Detección de dirección ────────────────────────────────────────────────

    private static Dir detect(double mx, double my, double w, double h) {
        boolean n = my < BORDER,      s = my > h - BORDER;
        boolean e = mx > w - BORDER,  west = mx < BORDER;
        if (n && west) return Dir.NW;
        if (n && e)    return Dir.NE;
        if (s && west) return Dir.SW;
        if (s && e)    return Dir.SE;
        if (n) return Dir.N; if (s) return Dir.S;
        if (e) return Dir.E; if (west) return Dir.W;
        return Dir.NONE;
    }

    // ── Enum de dirección ─────────────────────────────────────────────────────

    private enum Dir {
        NONE(Cursor.DEFAULT,     false, false, false, false),
        N   (Cursor.N_RESIZE,    true,  false, false, false),
        S   (Cursor.S_RESIZE,    false, true,  false, false),
        E   (Cursor.E_RESIZE,    false, false, true,  false),
        W   (Cursor.W_RESIZE,    false, false, false, true ),
        NE  (Cursor.NE_RESIZE,   true,  false, true,  false),
        NW  (Cursor.NW_RESIZE,   true,  false, false, true ),
        SE  (Cursor.SE_RESIZE,   false, true,  true,  false),
        SW  (Cursor.SW_RESIZE,   false, true,  false, true );

        final Cursor  cursor;
        final boolean north, south, east, west;

        Dir(Cursor c, boolean n, boolean s, boolean e, boolean w) {
            this.cursor = c; this.north = n; this.south = s; this.east = e; this.west = w;
        }
    }
}
