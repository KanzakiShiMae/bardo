package com.musicplayer.controllers;

import javafx.scene.control.Button;
import javafx.scene.layout.VBox;

/**
 * Representa una pestaña abierta en la barra de pestañas.
 *
 * <p>Las pestañas de navegación fijas ("home", "library") se reutilizan con el
 * mismo {@code id}. Las pestañas de reproductor tienen el formato
 * {@code "player:<timestamp>"} y las de colección {@code "col:<groupId>"}.
 */
public class AppTab {
    /** Identificador único de la pestaña. */
    public final String  id;
    /** Emoji o carácter mostrado antes del título. */
    public final String  icon;
    /** Texto mutable: se actualiza al cargar una canción nueva. */
    public       String  title;
    /** Panel de contenido que se muestra en el área central al activar la pestaña. */
    public final VBox    panel;
    /** Si {@code false}, la pestaña no tiene botón de cierre. */
    public final boolean closeable;
    /** Botón de la barra lateral que se marca como activo al seleccionar la pestaña. */
    public final Button  sidebarBtn;

    public AppTab(String id, String icon, String title, VBox panel, boolean closeable, Button sidebarBtn) {
        this.id = id; this.icon = icon; this.title = title;
        this.panel = panel; this.closeable = closeable; this.sidebarBtn = sidebarBtn;
    }
}
