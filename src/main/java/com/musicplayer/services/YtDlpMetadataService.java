package com.musicplayer.services;

import com.musicplayer.models.Song;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Obtiene metadatos de vídeos y playlists de YouTube directamente desde yt-dlp (lee la página
 * pública tal cual haría un navegador), <b>sin usar la YouTube Data API</b> — no requiere clave
 * de API ni consume cuota. Complementa a {@link YouTubeService} (que sí usa la API) para el modo
 * "Introduce URL" del buscador: pegar una URL de vídeo o playlist y descargarla directamente.
 *
 * <p>Usa {@code yt-dlp --print} con un separador poco común ({@code \u001F}, unit separator) para
 * extraer campos en líneas de datos fácilmente distinguibles de los avisos/warnings que yt-dlp
 * imprime por la misma salida.
 */
public class YtDlpMetadataService {

    private static final char SEP = '\u001F';

    private final DownloadService downloadService;

    public YtDlpMetadataService(DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    /** Resultado de leer una playlist completa: metadatos de la playlist + sus canciones. */
    public record PlaylistResult(String playlistId, String title, String thumbnailUrl, List<Song> songs) {}

    /** Obtiene metadatos (título, duración, miniatura) de un único vídeo sin descargarlo. */
    public CompletableFuture<Song> fetchVideo(String videoId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String ytDlp = downloadService.getYtDlpPath();
                ProcessBuilder pb = new ProcessBuilder(
                    ytDlp, "--no-playlist", "--skip-download",
                    "--print", "%(id)s" + SEP + "%(title)s" + SEP + "%(duration)s",
                    "https://www.youtube.com/watch?v=" + videoId
                );
                pb.redirectErrorStream(true);
                List<String> lines = runAndReadDataLines(pb);
                if (lines.isEmpty())
                    throw new RuntimeException("No se pudo obtener información del vídeo (¿URL válida?)");

                String[] parts   = lines.get(0).split(String.valueOf(SEP), -1);
                String   id      = parts.length > 0 ? parts[0] : videoId;
                String   title   = parts.length > 1 && !parts[1].isBlank() ? parts[1] : videoId;
                String   duration = parts.length > 2 ? formatDuration(parts[2]) : "—";
                // Se ignora %(thumbnail)s: yt-dlp a menudo devuelve .webp (maxresdefault.webp),
                // que javafx.scene.image.Image no puede decodificar. El CDN jpg es fiable siempre.
                return new Song(id, title, "", duration, thumbnailFor(id), "");
            } catch (Exception e) {
                throw new RuntimeException("Error al obtener el vídeo: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Lista todas las canciones de una playlist pública (modo rápido, sin visitar cada vídeo).
     * @param playlistUrl URL completa de la playlist (yt-dlp la necesita tal cual)
     * @param playlistId  ID ya extraído de la URL, para usar como {@code LibraryGroup} id
     */
    public CompletableFuture<PlaylistResult> fetchPlaylist(String playlistUrl, String playlistId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String ytDlp = downloadService.getYtDlpPath();
                ProcessBuilder pb = new ProcessBuilder(
                    ytDlp, "--flat-playlist", "--skip-download",
                    "--print", "%(playlist_title)s" + SEP + "%(id)s" + SEP + "%(title)s" + SEP + "%(duration)s",
                    playlistUrl
                );
                pb.redirectErrorStream(true);
                List<String> lines = runAndReadDataLines(pb);
                if (lines.isEmpty())
                    throw new RuntimeException("Playlist vacía, privada o no encontrada");

                List<Song> songs = new ArrayList<>();
                String playlistTitle = null;
                for (String line : lines) {
                    String[] parts = line.split(String.valueOf(SEP), -1);
                    if (parts.length < 3) continue;
                    if (playlistTitle == null && !parts[0].isBlank()) playlistTitle = parts[0];
                    String id       = parts[1];
                    String title    = parts.length > 2 && !parts[2].isBlank() ? parts[2] : id;
                    String duration = parts.length > 3 ? formatDuration(parts[3]) : "—";
                    songs.add(new Song(id, title, "", duration, thumbnailFor(id), ""));
                }
                String thumb = songs.isEmpty() ? "" : songs.get(0).getThumbnailUrl();
                return new PlaylistResult(playlistId, playlistTitle != null ? playlistTitle : "Playlist", thumb, songs);
            } catch (Exception e) {
                throw new RuntimeException("Error al obtener la playlist: " + e.getMessage(), e);
            }
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Miniatura estándar de YouTube a partir del videoId — no requiere ninguna llamada extra. */
    private static String thumbnailFor(String videoId) {
        return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
    }

    /** yt-dlp da la duración en segundos (p.ej. "213" o "193.0") — se convierte a "m:ss". */
    private static String formatDuration(String rawSeconds) {
        try {
            int total = (int) Double.parseDouble(rawSeconds.trim());
            int m = total / 60, s = total % 60;
            return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
        } catch (Exception e) {
            return "—";
        }
    }

    /** Ejecuta el proceso y devuelve solo las líneas de {@code --print} (contienen {@link #SEP}),
     *  descartando warnings/avisos de yt-dlp que comparten la misma salida combinada. */
    private static List<String> runAndReadDataLines(ProcessBuilder pb) throws Exception {
        Process proc = pb.start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.indexOf(SEP) >= 0) lines.add(line);
            }
        }
        int code = proc.waitFor();
        if (code != 0 && lines.isEmpty())
            throw new RuntimeException("yt-dlp terminó con código " + code);
        return lines;
    }
}
