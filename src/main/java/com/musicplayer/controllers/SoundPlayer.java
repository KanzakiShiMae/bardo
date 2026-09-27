package com.musicplayer.controllers;

import javafx.scene.media.AudioClip;

import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reproduce efectos de sonido cortos empaquetados en
 * {@code src/main/resources/com/musicplayer/sound/<name>.mp3}.
 *
 * <p>Cada clip se carga una sola vez (cacheado por nombre) y {@link AudioClip} soporta
 * llamadas a {@code play()} repetidas/solapadas sin recargar el archivo. Falla en silencio
 * si el recurso no existe o el audio no se puede inicializar — un efecto de sonido nunca
 * debe romper una acción real de la app.
 */
final class SoundPlayer {

    private static final Map<String, AudioClip> CACHE = new ConcurrentHashMap<>();

    private SoundPlayer() {}

    /** Reproduce {@code name}.mp3 (sin extensión, p.ej. {@code "ok"}, {@code "nop"}). */
    static void play(String name) {
        try {
            AudioClip clip = CACHE.computeIfAbsent(name, SoundPlayer::load);
            if (clip != null) clip.play();
        } catch (Exception ignored) {}
    }

    private static AudioClip load(String name) {
        URL url = SoundPlayer.class.getResource("/com/musicplayer/sound/" + name + ".mp3");
        return url != null ? new AudioClip(url.toExternalForm()) : null;
    }
}
