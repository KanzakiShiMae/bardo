package com.musicplayer.services;

import com.musicplayer.models.Song;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;

public class DownloadService {

    private static final Path BASE_DIR;
    private static final Path BIN_DIR;

    static {
        String appData = System.getenv("APPDATA");
        Path base = appData != null
            ? Path.of(appData, "Bardo")
            : Path.of(System.getProperty("user.home"), ".bardo");
        BASE_DIR = base.resolve("audio");
        BIN_DIR  = base.resolve("bin");
    }

    // ── yt-dlp bundled ───────────────────────────────────────────────────────

    /**
     * Devuelve la ruta al ejecutable yt-dlp listo para usar.
     * Si no está extraído aún, lo copia desde los recursos del JAR.
     */
    private String getYtDlpPath() throws IOException {
        Path exe = BIN_DIR.resolve("yt-dlp.exe");
        if (!Files.exists(exe)) {
            Files.createDirectories(BIN_DIR);
            try (InputStream in = getClass().getResourceAsStream(
                    "/com/musicplayer/bin/yt-dlp.exe")) {
                if (in == null)
                    throw new IOException("yt-dlp.exe no encontrado en los recursos del JAR");
                Files.copy(in, exe, StandardCopyOption.REPLACE_EXISTING);
            }
            // Asegurarse de que es ejecutable (necesario en Linux/macOS)
            exe.toFile().setExecutable(true);
        }
        return exe.toString();
    }

    // ── Carpeta de grupo ─────────────────────────────────────────────────────

    public Path getGroupDir(String groupId) {
        return BASE_DIR.resolve(groupId);
    }

    // ── Descarga ─────────────────────────────────────────────────────────────

    /**
     * Descarga el audio de una canción de YouTube en la carpeta del grupo.
     * Si el archivo ya existe devuelve su ruta sin volver a descargarlo.
     */
    public CompletableFuture<Path> downloadAudio(Song song, String groupId) {
        return CompletableFuture.supplyAsync(() -> {
            Path dir = getGroupDir(groupId);

            // ¿Ya descargado?
            Path existing = findDownloaded(dir, song.getVideoId());
            if (existing != null) return existing;

            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new RuntimeException("No se pudo crear la carpeta de descarga: " + dir, e);
            }

            String ytDlp;
            try {
                ytDlp = getYtDlpPath();
            } catch (IOException e) {
                throw new RuntimeException("Error al preparar yt-dlp: " + e.getMessage(), e);
            }

            // Preferimos m4a: compatible con JavaFX en Windows sin necesitar ffmpeg
            String outputTemplate = dir.resolve(song.getVideoId() + ".%(ext)s").toString();

            ProcessBuilder pb = new ProcessBuilder(
                ytDlp,
                "--no-playlist",
                "-f", "bestaudio[ext=m4a]/bestaudio[ext=mp4]/bestaudio",
                "-o", outputTemplate,
                "https://www.youtube.com/watch?v=" + song.getVideoId()
            );
            pb.redirectErrorStream(true);

            try {
                Process proc = pb.start();
                proc.getInputStream().transferTo(System.out);
                int code = proc.waitFor();
                if (code != 0)
                    throw new RuntimeException(
                        "yt-dlp terminó con código " + code + " para " + song.getVideoId());
            } catch (IOException e) {
                throw new RuntimeException("Error al iniciar la descarga: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Descarga interrumpida", e);
            }

            Path result = findDownloaded(dir, song.getVideoId());
            if (result == null)
                throw new RuntimeException("Archivo descargado no encontrado en " + dir);
            return result;
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Busca un archivo cuyo nombre empiece por videoId+"." en el directorio dado. */
    private Path findDownloaded(Path dir, String videoId) {
        if (!Files.isDirectory(dir)) return null;
        try (var stream = Files.list(dir)) {
            return stream
                .filter(p -> p.getFileName().toString().startsWith(videoId + "."))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
