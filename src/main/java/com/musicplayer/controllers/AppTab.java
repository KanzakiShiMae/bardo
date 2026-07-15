package com.musicplayer.controllers;

import org.kordamp.ikonli.Ikon;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
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
    /** Icono mostrado en la pestaña. */
    public final Ikon    icon;
    /** Texto mutable: se actualiza al cargar una canción nueva. */
    public       String  title;
    /** Panel de contenido que se muestra en el área central al activar la pestaña. */
    public final VBox    panel;
    /** Si {@code false}, la pestaña no tiene botón de cierre. */
    public final boolean closeable;
    /** Botón de la barra lateral que se marca como activo al seleccionar la pestaña. */
    public final Button  sidebarBtn;

    /** Nodo de la barra de pestañas asociado a esta pestaña; {@code null} hasta que se crea.
     *  Se reutiliza entre refrescos de estilo en lugar de recrearse en cada cambio. */
    public HBox barNode;

    public AppTab(String id, Ikon icon, String title, VBox panel, boolean closeable, Button sidebarBtn) {
        this.id = id; this.icon = icon; this.title = title;
        this.panel = panel; this.closeable = closeable; this.sidebarBtn = sidebarBtn;
    }
}
