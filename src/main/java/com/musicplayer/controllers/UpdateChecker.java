package com.musicplayer.controllers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.musicplayer.services.DownloadService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import okhttp3.*;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;

/**
 * Comprueba si hay una nueva versión de Bardo en GitHub Releases y,
 * si la hay, ofrece descargarla e instalarla sin que el usuario
 * tenga que ir a la web manualmente.
 *
 * <p>Flujo:
 * <ol>
 *   <li>Llamar {@link #checkAsync(String, Stage)} al arrancar la app.</li>
 *   <li>Se hace GET a la API de GitHub para obtener la última release.</li>
 *   <li>Se compara el {@code tag_name} con la versión actual (sin prefijo «v»).
 *       Si son distintos, se muestra un diálogo de actualización.</li>
 *   <li>Si el usuario acepta: se descarga {@code bardo.jar} con barra de progreso,
 *       se escribe un script {@code bardo-updater.bat} y se lanza; la app se cierra.</li>
 * </ol>
 */
public class UpdateChecker {

    private static final String API_URL = "https://api.github.com/repos/KanzakiShiMae/bardo/releases/latest";
    private static final String YTDLP_API_URL = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private UpdateChecker() {}

    /** Lanza la comprobación en background. No bloquea el hilo FX. */
    public static void checkAsync(String currentVersion, Stage ownerStage) {
        CompletableFuture.runAsync(() -> {
            try {
                Request req = new Request.Builder()
                        .url(API_URL)
                        .header("Accept", "application/vnd.github.v3+json")
                        .build();
                try (Response resp = HTTP.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) return;
                    JsonObject json = JsonParser.parseString(resp.body().string()).getAsJsonObject();

                    String tagName       = json.get("tag_name").getAsString();
                    String remoteVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
                    String localVersion  = currentVersion.startsWith("v") ? currentVersion.substring(1) : currentVersion;

                    if (localVersion.isBlank() || remoteVersion.equals(localVersion)) return;

                    // Buscar el asset bardo.jar (lowercase)
                    JsonArray assets = json.getAsJsonArray("assets");
                    String downloadUrl = null;
                    for (var el : assets) {
                        JsonObject asset = el.getAsJsonObject();
                        if ("bardo.jar".equals(asset.get("name").getAsString())) {
                            downloadUrl = asset.get("browser_download_url").getAsString();
                            break;
                        }
                    }

                    final String finalUrl = downloadUrl;
                    Platform.runLater(() -> showUpdateDialog(tagName, finalUrl, ownerStage));
                }
            } catch (Exception ignored) {
                // La comprobación de actualizaciones es best-effort; fallar silenciosamente
            }
        });
    }

    // ── yt-dlp ─────────────────────────────────────────────────────────────────

    /**
     * Comprueba si hay una versión más reciente de yt-dlp en GitHub Releases y,
     * si la hay, ofrece descargarla y reemplazar el binario local.
     * A diferencia de {@link #checkAsync}, no requiere reiniciar la app: yt-dlp
     * es un binario externo que se invoca como subproceso en cada descarga.
     */
    public static void checkYtDlpAsync(Stage ownerStage, DownloadService downloadService) {
        CompletableFuture.runAsync(() -> {
            try {
                String exePath = downloadService.getYtDlpPath();
                String localVersion = runYtDlpVersion(exePath);
                if (localVersion == null || localVersion.isBlank()) return;

                Request req = new Request.Builder()
                        .url(YTDLP_API_URL)
                        .header("Accept", "application/vnd.github.v3+json")
                        .build();
                try (Response resp = HTTP.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) return;
                    JsonObject json = JsonParser.parseString(resp.body().string()).getAsJsonObject();

                    String remoteVersion = json.get("tag_name").getAsString();
                    if (remoteVersion.equals(localVersion)) return;

                    JsonArray assets = json.getAsJsonArray("assets");
                    String downloadUrl = null;
                    for (var el : assets) {
                        JsonObject asset = el.getAsJsonObject();
                        if ("yt-dlp.exe".equals(asset.get("name").getAsString())) {
                            downloadUrl = asset.get("browser_download_url").getAsString();
                            break;
                        }
                    }
                    if (downloadUrl == null) return;

                    final String finalUrl = downloadUrl;
                    Platform.runLater(() -> showYtDlpUpdateDialog(localVersion, remoteVersion, finalUrl, exePath, ownerStage));
                }
            } catch (Exception ignored) {
                // Best-effort: si yt-dlp no responde a --version o no hay red, no molestamos al usuario
            }
        });
    }

    /** Ejecuta {@code yt-dlp --version} y devuelve la primera línea de salida, o null si falla. */
    private static String runYtDlpVersion(String exePath) {
        try {
            Process proc = new ProcessBuilder(exePath, "--version").start();
            String line;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                line = r.readLine();
            }
            proc.waitFor();
            return line != null ? line.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void showYtDlpUpdateDialog(String localVersion, String remoteVersion, String downloadUrl, String exePath, Stage ownerStage) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(ownerStage);
        alert.setTitle("Actualización de yt-dlp disponible");
        alert.setHeaderText("Nueva versión de yt-dlp: " + remoteVersion);
        alert.setContentText(
                "Tu versión de yt-dlp (" + localVersion + ") está desactualizada.\n" +
                "YouTube cambia con frecuencia y las versiones antiguas dejan de poder descargar audio " +
                "(errores como \"HTTP 403\" o \"yt-dlp terminó con código 1\").\n\n" +
                "¿Deseas descargar e instalar la versión " + remoteVersion + " ahora?");

        ButtonType updateBtn = new ButtonType("Actualizar ahora", ButtonBar.ButtonData.OK_DONE);
        ButtonType laterBtn  = new ButtonType("Ahora no",         ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(updateBtn, laterBtn);

        alert.showAndWait().ifPresent(btn -> {
            if (btn == updateBtn) downloadYtDlpAndReplace(downloadUrl, exePath, remoteVersion, ownerStage);
        });
    }

    private static void downloadYtDlpAndReplace(String downloadUrl, String exePath, String remoteVersion, Stage ownerStage) {
        Dialog<Void> progressDlg = new Dialog<>();
        progressDlg.initOwner(ownerStage);
        progressDlg.setTitle("Actualizando yt-dlp");
        progressDlg.setHeaderText("Descargando yt-dlp " + remoteVersion + "...");

        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(340);
        Label info = new Label("Preparando descarga…");
        VBox content = new VBox(8, bar, info);
        content.setPadding(new Insets(4, 0, 4, 0));
        progressDlg.getDialogPane().setContent(content);

        progressDlg.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        Button cancelNode = (Button) progressDlg.getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancelNode != null) cancelNode.setVisible(false);

        progressDlg.show();

        CompletableFuture.runAsync(() -> {
            try {
                Path exeFile = Paths.get(exePath);
                Path tmpFile = exeFile.resolveSibling("yt-dlp.exe.new");

                Request req = new Request.Builder().url(downloadUrl).build();
                try (Response resp = HTTP.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null)
                        throw new IOException("HTTP " + resp.code());
                    long total = resp.body().contentLength();
                    try (InputStream in  = resp.body().byteStream();
                         OutputStream out = Files.newOutputStream(tmpFile)) {
                        byte[] buf = new byte[16_384];
                        long downloaded = 0;
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                            downloaded += n;
                            final long dl = downloaded, tot = total;
                            Platform.runLater(() -> {
                                if (tot > 0) bar.setProgress((double) dl / tot);
                                info.setText(formatBytes(dl) + (tot > 0 ? " / " + formatBytes(tot) : ""));
                            });
                        }
                    }
                }

                Files.move(tmpFile, exeFile, StandardCopyOption.REPLACE_EXISTING);

                Platform.runLater(() -> {
                    closeDialog(progressDlg);
                    Alert done = new Alert(Alert.AlertType.INFORMATION);
                    done.initOwner(ownerStage);
                    done.setTitle("yt-dlp actualizado");
                    done.setHeaderText(null);
                    done.setContentText("yt-dlp se actualizó correctamente a la versión " + remoteVersion + ".");
                    done.show();
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    closeDialog(progressDlg);
                    showError(ownerStage, "Error al actualizar yt-dlp: " + ex.getMessage());
                });
            }
        });
    }

    // ── bore (túnel de Party) ────────────────────────────────────────────────────

    /**
     * Comprueba si hay una versión más reciente de bore (usado como túnel de acceso
     * remoto opcional en Party) y ofrece actualizarla. A diferencia de yt-dlp, bore no
     * viene bundleado: solo se descarga la primera vez que el usuario elige el modo de
     * túnel "bore" en Party. Por eso esta comprobación no hace nada si el binario local
     * no existe todavía — no tiene sentido descargarlo solo para comprobar su versión.
     */
    public static void checkBoreAsync(Stage ownerStage) {
        CompletableFuture.runAsync(() -> {
            try {
                Path exeFile = PartyPanelBuilder.boreExePath();
                if (!Files.exists(exeFile)) return;

                String localVersion = runBoreVersion(exeFile.toString());
                if (localVersion == null) return;

                PartyPanelBuilder.BoreRelease latest = PartyPanelBuilder.latestBoreRelease();
                if (latest.version().equals(localVersion)) return;

                Platform.runLater(() -> showBoreUpdateDialog(localVersion, latest.version(), latest.downloadUrl(), exeFile, ownerStage));
            } catch (Exception ignored) {
                // Best-effort, igual que checkAsync y checkYtDlpAsync
            }
        });
    }

    private static String runBoreVersion(String exePath) {
        try {
            Process proc = new ProcessBuilder(exePath, "--version").start();
            String line;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                line = r.readLine();
            }
            proc.waitFor();
            if (line == null) return null;
            // Salida esperada: "bore-cli 0.5.0" — nos quedamos con el último token
            String[] parts = line.trim().split("\\s+");
            return parts.length > 0 ? parts[parts.length - 1] : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void showBoreUpdateDialog(String localVersion, String remoteVersion, String downloadUrl, Path exeFile, Stage ownerStage) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(ownerStage);
        alert.setTitle("Actualización de bore disponible");
        alert.setHeaderText("Nueva versión de bore: " + remoteVersion);
        alert.setContentText(
                "Tu versión de bore (" + localVersion + "), usada como túnel de acceso remoto en Party, está desactualizada.\n\n" +
                "¿Deseas descargar e instalar la versión " + remoteVersion + " ahora?");

        ButtonType updateBtn = new ButtonType("Actualizar ahora", ButtonBar.ButtonData.OK_DONE);
        ButtonType laterBtn  = new ButtonType("Ahora no",         ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(updateBtn, laterBtn);

        alert.showAndWait().ifPresent(btn -> {
            if (btn == updateBtn) downloadBoreAndReplace(downloadUrl, exeFile, remoteVersion, ownerStage);
        });
    }

    private static void downloadBoreAndReplace(String downloadUrl, Path exeFile, String remoteVersion, Stage ownerStage) {
        Dialog<Void> progressDlg = new Dialog<>();
        progressDlg.initOwner(ownerStage);
        progressDlg.setTitle("Actualizando bore");
        progressDlg.setHeaderText("Descargando bore " + remoteVersion + "...");

        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(340);
        Label info = new Label("Preparando descarga…");
        VBox content = new VBox(8, bar, info);
        content.setPadding(new Insets(4, 0, 4, 0));
        progressDlg.getDialogPane().setContent(content);

        progressDlg.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        Button cancelNode = (Button) progressDlg.getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancelNode != null) cancelNode.setVisible(false);

        progressDlg.show();

        CompletableFuture.runAsync(() -> {
            try {
                Path tmpZip = exeFile.resolveSibling("bore-update.zip");

                Request req = new Request.Builder().url(downloadUrl).build();
                try (Response resp = HTTP.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null)
                        throw new IOException("HTTP " + resp.code());
                    long total = resp.body().contentLength();
                    try (InputStream in  = resp.body().byteStream();
                         OutputStream out = Files.newOutputStream(tmpZip)) {
                        byte[] buf = new byte[16_384];
                        long downloaded = 0;
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                            downloaded += n;
                            final long dl = downloaded, tot = total;
                            Platform.runLater(() -> {
                                if (tot > 0) bar.setProgress((double) dl / tot);
                                info.setText(formatBytes(dl) + (tot > 0 ? " / " + formatBytes(tot) : ""));
                            });
                        }
                    }
                }

                Path tmpExe = exeFile.resolveSibling("bore.exe.new");
                try (InputStream zipIn = Files.newInputStream(tmpZip)) {
                    PartyPanelBuilder.extractExeFromZip(zipIn, tmpExe);
                }
                Files.deleteIfExists(tmpZip);
                Files.move(tmpExe, exeFile, StandardCopyOption.REPLACE_EXISTING);

                Platform.runLater(() -> {
                    closeDialog(progressDlg);
                    Alert done = new Alert(Alert.AlertType.INFORMATION);
                    done.initOwner(ownerStage);
                    done.setTitle("bore actualizado");
                    done.setHeaderText(null);
                    done.setContentText("bore se actualizó correctamente a la versión " + remoteVersion + ".");
                    done.show();
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    closeDialog(progressDlg);
                    showError(ownerStage, "Error al actualizar bore: " + ex.getMessage());
                });
            }
        });
    }

    // ── Diálogo de notificación ───────────────────────────────────────────────

    private static void showUpdateDialog(String newVersion, String downloadUrl, Stage ownerStage) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(ownerStage);
        alert.setTitle("Actualización disponible");
        alert.setHeaderText("Nueva versión: " + newVersion);
        alert.setContentText(
                "Hay una nueva versión de Bardo disponible.\n" +
                "¿Deseas descargarla e instalarla ahora?\n\n" +
                "La aplicación se reiniciará automáticamente.");

        ButtonType updateBtn = new ButtonType("Actualizar ahora", ButtonBar.ButtonData.OK_DONE);
        ButtonType laterBtn  = new ButtonType("Ahora no",         ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(updateBtn, laterBtn);

        alert.showAndWait().ifPresent(btn -> {
            if (btn == updateBtn) {
                if (downloadUrl == null) {
                    showError(ownerStage, "No se encontró el archivo bardo.jar en la release.");
                } else {
                    downloadAndReplace(downloadUrl, ownerStage);
                }
            }
        });
    }

    // ── Descarga con progreso ─────────────────────────────────────────────────

    private static void downloadAndReplace(String downloadUrl, Stage ownerStage) {
        Dialog<Void> progressDlg = new Dialog<>();
        progressDlg.initOwner(ownerStage);
        progressDlg.setTitle("Actualizando Bardo");
        progressDlg.setHeaderText("Descargando actualización...");

        ProgressBar bar   = new ProgressBar(0);
        bar.setPrefWidth(340);
        Label info = new Label("Preparando descarga…");
        VBox content = new VBox(8, bar, info);
        content.setPadding(new Insets(4, 0, 4, 0));
        progressDlg.getDialogPane().setContent(content);

        // Botón oculto (sin él algunos entornos no muestran el diálogo)
        progressDlg.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        Button cancelNode = (Button) progressDlg.getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancelNode != null) cancelNode.setVisible(false);

        progressDlg.show();

        CompletableFuture.runAsync(() -> {
            try {
                Path jarPath = getRunningJarPath();
                if (jarPath == null || !jarPath.toString().toLowerCase().endsWith(".jar")) {
                    Platform.runLater(() -> {
                        closeDialog(progressDlg);
                        showError(ownerStage, "No se pudo localizar bardo.jar.\n(¿Estás ejecutando desde el IDE?)");
                    });
                    return;
                }

                Path updatePath = jarPath.resolveSibling("bardo-update.jar");

                Request req = new Request.Builder().url(downloadUrl).build();
                try (Response resp = HTTP.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null)
                        throw new IOException("HTTP " + resp.code());
                    long total = resp.body().contentLength();
                    try (InputStream in  = resp.body().byteStream();
                         OutputStream out = Files.newOutputStream(updatePath)) {
                        byte[] buf = new byte[16_384];
                        long downloaded = 0;
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                            downloaded += n;
                            final long dl = downloaded, tot = total;
                            Platform.runLater(() -> {
                                if (tot > 0) bar.setProgress((double) dl / tot);
                                info.setText(formatBytes(dl) + (tot > 0 ? " / " + formatBytes(tot) : ""));
                            });
                        }
                    }
                }

                // Escribir bat y relanzar (sigue en background)
                launchUpdater(jarPath, updatePath, ownerStage, progressDlg);

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    closeDialog(progressDlg);
                    showError(ownerStage, "Error al descargar: " + ex.getMessage());
                });
            }
        });
    }

    // ── Script updater ────────────────────────────────────────────────────────

    private static void launchUpdater(Path jarPath, Path updatePath, Stage ownerStage, Dialog<?> progressDlg)
            throws IOException {

        // Detectar launcher: jpackage tiene Bardo.exe dos niveles arriba del jar
        Path exePath = jarPath.getParent().getParent().resolve("Bardo.exe");
        String relaunchLine;
        if (Files.exists(exePath)) {
            relaunchLine = "start \"\" \"" + exePath.toAbsolutePath() + "\"";
        } else {
            // Fallback: relanzar con javaw -jar (JAR independiente)
            String javaExe = ProcessHandle.current().info().command()
                    .map(p -> p.replace("java.exe", "javaw.exe"))
                    .orElse("javaw");
            relaunchLine = "start \"\" \"" + javaExe + "\" -jar \"" + jarPath.toAbsolutePath() + "\"";
        }

        Path batPath = jarPath.resolveSibling("bardo-updater.bat");
        String bat = "@echo off\r\n" +
                     "timeout /t 2 /nobreak > nul\r\n" +
                     "move /y \"" + updatePath.toAbsolutePath() + "\" \"" + jarPath.toAbsolutePath() + "\"\r\n" +
                     relaunchLine + "\r\n" +
                     "(del \"%~f0\") > nul 2>&1\r\n";
        Files.writeString(batPath, bat, java.nio.charset.StandardCharsets.UTF_8);

        Platform.runLater(() -> {
            closeDialog(progressDlg);
            try {
                new ProcessBuilder("cmd", "/c", batPath.toAbsolutePath().toString())
                        .inheritIO()
                        .start();
                Platform.exit();
            } catch (IOException ex) {
                showError(ownerStage, "No se pudo lanzar el actualizador:\n" + ex.getMessage());
            }
        });
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    private static Path getRunningJarPath() {
        try {
            URI uri = UpdateChecker.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Paths.get(uri);
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1_024)          return bytes + " B";
        if (bytes < 1_048_576)      return String.format("%.1f KB", bytes / 1_024.0);
        return                             String.format("%.1f MB", bytes / 1_048_576.0);
    }

    private static void closeDialog(Dialog<?> dlg) {
        try {
            ((Stage) dlg.getDialogPane().getScene().getWindow()).close();
        } catch (Exception ignored) {}
    }

    private static void showError(Stage owner, String message) {
        Alert err = new Alert(Alert.AlertType.ERROR);
        err.initOwner(owner);
        err.setTitle("Error de actualización");
        err.setHeaderText(null);
        err.setContentText(message);
        err.show();
    }
}
