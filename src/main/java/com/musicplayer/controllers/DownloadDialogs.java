package com.musicplayer.controllers;

import com.musicplayer.models.LibraryGroup;
import com.musicplayer.models.Song;
import com.musicplayer.services.DownloadService;
import com.musicplayer.services.LibraryService;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.javafx.StackedFontIcon;
import org.kordamp.ikonli.boxicons.BoxiconsRegular;
import org.kordamp.ikonli.boxicons.BoxiconsSolid;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Diálogos modales para la descarga masiva de una colección.
 *
 * <p>El flujo tiene dos pasos:
 * <ol>
 *   <li>{@link #confirmAndStart} muestra un diálogo de confirmación con el número de
 *       canciones y el espacio estimado.</li>
 *   <li>Al confirmar, {@code showProgress} descarga las canciones <em>secuencialmente</em>
 *       (una a la vez) y actualiza las barras de progreso en tiempo real.</li>
 * </ol>
 *
 * <p>El usuario puede cancelar la descarga en curso con el botón ✕; las canciones ya
 * descargadas quedan guardadas.
 */
public final class DownloadDialogs {

    private DownloadDialogs() {}

    /**
     * Muestra el diálogo de confirmación y, si el usuario acepta, inicia la descarga
     * secuencial de {@code toDownload}.
     *
     * @param group        colección destino
     * @param toDownload   canciones pendientes de descarga (sin archivo local)
     * @param owner        ventana propietaria del diálogo modal
     * @param dl           servicio que ejecuta la descarga
     * @param lib          servicio de biblioteca (para persistir tras cada descarga)
     * @param cssPath      ruta absoluta al CSS para aplicar el tema al diálogo
     * @param toast        callback para mostrar mensajes de resultado al usuario
     * @param onDone       callback ejecutado al cerrar el diálogo (completo o cancelado)
     */
    public static void confirmAndStart(LibraryGroup group, List<Song> toDownload, Window owner,
                                       DownloadService dl, LibraryService lib, String cssPath,
                                       Consumer<String> toast, Runnable onDone) {
        int count  = toDownload.size();
        double estMb = count * 5.0;

        Stage stage = makeStage(owner);
        Label titleLbl = label("  Descargar todo", "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #5a4a6a;");
        titleLbl.setGraphic(IkonUtil.duotone(BoxiconsSolid.DOWNLOAD, BoxiconsRegular.DOWNLOAD, 18));
        Label bodyLbl  = label("Se van a descargar " + count + " canción" + (count == 1 ? "" : "es") + " de «" + group.getName() + "».",
                               "-fx-font-size: 13px; -fx-text-fill: #6a5a7a;");
        bodyLbl.setWrapText(true);
        Label spaceLbl = label("Espacio estimado: " + String.format("%.1f", estMb) + " MB",
                               "-fx-font-size: 12px; -fx-text-fill: #a090b0; -fx-font-style: italic;");

        Button cancelBtn = new Button("Cancelar"); cancelBtn.getStyleClass().add("btn-secondary");
        Button dlBtn     = new Button("  Descargar"); dlBtn.getStyleClass().add("btn-primary");
        MainController.ico(dlBtn, BoxiconsSolid.DOWNLOAD, 14, true);
        HBox btnRow = new HBox(10, cancelBtn, dlBtn); btnRow.setAlignment(Pos.CENTER_RIGHT);

        show(stage, root(380, titleLbl, bodyLbl, spaceLbl, btnRow), cssPath);
        centerOver(stage, owner);

        cancelBtn.setOnAction(e -> stage.close());
        dlBtn.setOnAction(e -> {
            stage.close();
            showProgress(group, toDownload, estMb, owner, dl, lib, cssPath, toast, onDone);
        });
    }

    private static void showProgress(LibraryGroup group, List<Song> songs, double estMb,
                                     Window owner, DownloadService dl, LibraryService lib,
                                     String cssPath, Consumer<String> toast, Runnable onDone) {
        int total = songs.size();
        AtomicBoolean cancelled = new AtomicBoolean();
        int[]    done   = {0};
        double[] doneMb = {0.0};

        Stage stage = makeStage(owner);

        Label titleLbl = label("Descargando «" + group.getName() + "»",
                               "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #5a4a6a;");
        HBox.setHgrow(titleLbl, Priority.ALWAYS);
        Button closeBtn = new Button();
        closeBtn.setStyle("-fx-background-color: transparent; -fx-border-width: 0; -fx-cursor: hand; -fx-padding: 0 4 0 4;");
        FontIcon closeIcon = new FontIcon(BoxiconsRegular.X); closeIcon.setIconSize(14); closeIcon.getStyleClass().add("icon-secondary");
        closeBtn.setGraphic(closeIcon);
        closeBtn.setOnAction(e -> { cancelled.set(true); closeBtn.setDisable(true); closeBtn.setGraphic(null); closeBtn.setText("…"); });
        HBox titleRow = new HBox(10, titleLbl, closeBtn); titleRow.setAlignment(Pos.CENTER_LEFT);

        Label currentLbl = label("Preparando…", "-fx-font-size: 12px; -fx-text-fill: #8a7a9a;"); currentLbl.setWrapText(true); currentLbl.setMaxWidth(400);

        ProgressBar songsBar = bar(); Label songsLbl = label("0 / " + total + " canciones", "-fx-font-size: 12px; -fx-text-fill: #5a4a6a;");
        ProgressBar mbBar    = bar(); Label mbLbl    = label("0.0 / " + String.format("%.1f", estMb) + " MB (estimado)", "-fx-font-size: 12px; -fx-text-fill: #5a4a6a;");

        show(stage, root(460, titleRow, currentLbl,
            header("CANCIONES"), songsBar, songsLbl,
            header("ALMACENAMIENTO"), mbBar, mbLbl), cssPath);
        centerOver(stage, owner);

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Song song : songs) {
            chain = chain.thenCompose(v -> {
                if (cancelled.get()) return CompletableFuture.completedFuture(null);
                Platform.runLater(() -> currentLbl.setText(song.getTitle()));
                return dl.downloadAudio(song, group.getId())
                    .thenAccept(path -> Platform.runLater(() -> {
                        song.setLocalFilePath(path.toString());
                        lib.save();
                        done[0]++;
                        doneMb[0] += path.toFile().length() / 1_000_000.0;
                        double totalMb = Math.max(estMb, doneMb[0]);
                        songsBar.setProgress((double) done[0] / total);
                        songsLbl.setText(done[0] + " / " + total + " canciones");
                        mbBar.setProgress(Math.min(1.0, doneMb[0] / totalMb));
                        mbLbl.setText(String.format("%.1f / %.1f MB", doneMb[0], totalMb));
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            done[0]++;
                            songsBar.setProgress((double) done[0] / total);
                            songsLbl.setText(done[0] + " / " + total + " canciones");
                        });
                        return null;
                    });
            });
        }
        chain.thenRun(() -> Platform.runLater(() -> {
            stage.close();
            onDone.run();
            if (cancelled.get())
                toast.accept("Descarga cancelada (" + done[0] + " / " + total + " descargadas)");
            else
                toast.accept("Descarga completada — " + String.format("%.1f", doneMb[0]) + " MB");
        }));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Stage makeStage(Window owner) {
        Stage s = new Stage();
        s.initOwner(owner); s.initModality(Modality.WINDOW_MODAL);
        s.initStyle(StageStyle.UNDECORATED); s.setResizable(false);
        return s;
    }

    private static void show(Stage stage, VBox root, String cssPath) {
        Scene scene = new Scene(root); scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(cssPath);
        stage.setScene(scene); stage.show();
    }

    private static void centerOver(Stage child, Window owner) {
        Platform.runLater(() -> {
            child.setX(owner.getX() + (owner.getWidth()  - child.getWidth())  / 2);
            child.setY(owner.getY() + (owner.getHeight() - child.getHeight()) / 2);
        });
    }

    private static VBox root(double width, javafx.scene.Node... children) {
        VBox v = new VBox(10); v.setPadding(new Insets(24, 28, 24, 28)); v.setPrefWidth(width);
        v.getStyleClass().add("dl-dialog-root"); v.getChildren().addAll(children);
        return v;
    }

    private static Label label(String text, String style) { Label l = new Label(text); l.setStyle(style); return l; }
    private static Label header(String text) { return label(text, "-fx-font-size: 10px; -fx-text-fill: #b0a0c0; -fx-font-weight: bold;"); }
    private static ProgressBar bar() { ProgressBar b = new ProgressBar(0); b.setPrefWidth(400); b.setPrefHeight(12); b.getStyleClass().add("dl-progress-bar"); return b; }
}
