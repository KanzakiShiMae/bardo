package com.musicplayer.controllers;

import com.musicplayer.models.Song;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.MediaPlayer;

/**
 * Contiene el estado completo de una instancia de reproducción.
 *
 * <p>Cada vez que el usuario reproduce una canción se crea una nueva
 * {@code PlayerInstance}. Múltiples instancias pueden existir simultáneamente;
 * la que controla la barra "Now Playing" es {@code MainController.focusedPlayer}.
 *
 * <p>Los campos {@code panel*} son referencias a los nodos del panel completo
 * y se asignan por {@link PlayerPanelBuilder#build}; son {@code null} hasta
 * que el panel esté construido.
 */
public class PlayerInstance {
    /** Identificador único de la pestaña asociada, formato {@code "player:<timestamp>"}. */
    public final String tabId;

    /** Canción actualmente cargada. */
    public Song song;

    /** Reproductor JavaFX activo. {@code null} antes de la primera carga o tras disponer. */
    public MediaPlayer mediaPlayer;

    /** {@code true} mientras el audio se está reproduciendo (no en pausa ni parado). */
    public boolean isPlaying;

    /** {@code true} mientras el usuario está arrastrando la barra de progreso del panel. */
    public boolean seeking;

    /** Cola de canciones de esta instancia. La canción activa es {@code song}. */
    public final ObservableList<Song> queue = FXCollections.observableArrayList();

    /** Volumen en [0.0, 1.0]. Valor por defecto 70 %. */
    public double  volume  = 0.70;

    /** Si {@code true}, la canción vuelve a empezar al terminar en lugar de pasar a la siguiente. */
    public boolean looping = true;

    // ── Referencias al panel completo (asignadas por PlayerPanelBuilder) ─────
    public VBox      panel;
    public StackPane artStack;
    public ImageView artView;
    public Canvas    panelWaveCanvas;
    public float[]   waveSmoothed;
    public float[]   wavePeaks;
    public Timeline  waveDecayAnim;
    public Timeline  waveAnim;      // kept for null-safe legacy refs; not used for real animation
    public Timeline  fadeOutAnim;
    public Label     panelTitle, panelArtist, panelElapsed, panelTotal;
    public Slider    panelProgress, panelVolumeSlider;
    public Button    panelPlayPause, panelRepeat, panelShuffleBtn;
    public VBox      panelSpectroContainer; // legacy — no longer assigned; use panelSpectroCanvas
    public Canvas    panelSpectroCanvas;
    public Timeline  spectroTimeline;

    // ── Mashup pairing ─────────────────────────────────────────────────────────
    /** For Mashup players: the paired secondary player. Null for normal players. */
    public PlayerInstance mashupPartner  = null;
    /** True for the secondary (Song B) player in a Mashup pair. */
    public boolean        isMashupLinked = false;
    /** Crossfade animation (stored on the mashup primary). */
    public Timeline       mashupXfadeAnim = null;

    public PlayerInstance(String tabId) { this.tabId = tabId; }
}
