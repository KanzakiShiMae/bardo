package com.musicplayer.services;

import com.musicplayer.models.Song;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gestiona la descarga de audio desde YouTube usando el binario yt-dlp.
 *
 * <p>El binario {@code yt-dlp.exe} se extrae desde los recursos del JAR al directorio
 * {@code %APPDATA%\Bardo\bin\} en el primer uso. Los archivos de audio descargados se
 * guardan en {@code %APPDATA%\Bardo\audio\{groupId}\{videoId}.m4a}.
 *
 * <p>Las operaciones de descarga se ejecutan en hilos del pool común mediante
 * {@link java.util.concurrent.CompletableFuture#supplyAsync}; el resultado es la
 * {@link java.nio.file.Path} del archivo descargado.
 */
public class DownloadService {

    private static final Path BIN_DIR = PersistenceService.bardoBaseDir().resolve("bin");
    /** yt-dlp imprime líneas como "[download]  42.0% of 4.23MiB at ..." — separadas por \r, no \n. */
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("\\[download]\\s+([\\d.]+)%");

    private Path getAudioBaseDir() {
        String saved = LibraryService.getInstance().loadAudioDir();
        return (saved != null && !saved.isBlank())
            ? Path.of(saved)
            : PersistenceService.bardoBaseDir().resolve("audio");
    }

    // ── yt-dlp bundled ───────────────────────────────────────────────────────

    /**
     * Devuelve la ruta al ejecutable yt-dlp listo para usar.
     * Si no está extraído aún, lo copia desde los recursos del JAR.
     */
    public String getYtDlpPath() throws IOException {
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
        return getAudioBaseDir().resolve(groupId);
    }

    // ── Descarga ─────────────────────────────────────────────────────────────

    /**
     * Descarga el audio de una canción de YouTube en la carpeta del grupo.
     * Si el archivo ya existe devuelve su ruta sin volver a descargarlo.
     */
    public CompletableFuture<Path> downloadAudio(Song song, String groupId) {
        return downloadAudio(song, groupId, null);
    }

    /**
     * Igual que {@link #downloadAudio(Song, String)}, pero invoca {@code onProgress} (0-100)
     * cada vez que yt-dlp reporta un nuevo porcentaje de descarga. Se invoca desde el hilo de
     * background de la descarga — el llamador debe envolver cualquier actualización de UI en
     * {@code Platform.runLater}. No se llama si el archivo ya estaba descargado.
     */
    public CompletableFuture<Path> downloadAudio(Song song, String groupId, IntConsumer onProgress) {
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
                List<String> outputLines = new ArrayList<>();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        System.out.println(line);
                        outputLines.add(line);
                        if (onProgress != null) {
                            Matcher m = PROGRESS_PATTERN.matcher(line);
                            if (m.find()) {
                                try { onProgress.accept((int) Double.parseDouble(m.group(1))); }
                                catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                }
                int code = proc.waitFor();
                if (code != 0)
                    throw new RuntimeException(
                        "yt-dlp terminó con código " + code + " para " + song.getVideoId()
                            + ": " + extractYtDlpError(outputLines));
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

    /**
     * Extrae la línea más útil de la salida (stdout+stderr combinados) de yt-dlp
     * para diagnosticar un fallo: prefiere la última línea "ERROR: ..." y, si no
     * hay ninguna, cae a la última línea no vacía de la salida.
     */
    private static String extractYtDlpError(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            String l = lines.get(i);
            if (l.startsWith("ERROR:")) return l;
        }
        for (int i = lines.size() - 1; i >= 0; i--) {
            String l = lines.get(i).trim();
            if (!l.isEmpty()) return l;
        }
        return "(sin salida de yt-dlp)";
    }

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
