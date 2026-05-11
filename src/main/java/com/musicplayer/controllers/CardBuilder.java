package com.musicplayer.controllers;

import com.musicplayer.models.Song;
import com.musicplayer.models.YouTubePlaylistInfo;
import javafx.animation.*;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Fábrica de tarjetas visuales para los paneles de búsqueda e inicio.
 *
 * <p>Todos los métodos son estáticos y devuelven nodos JavaFX listos para insertar
 * en un {@code FlowPane}. Las acciones se pasan como lambdas para desacoplar la
 * lógica de negocio de la construcción de la UI.
 */
public final class CardBuilder {

    private CardBuilder() {}

    /**
     * Crea una tarjeta de canción de YouTube con miniatura, título, canal y botones
     * de enlace y "añadir a colección".
     *
     * @param song      datos de la canción
     * @param onPlay    acción al hacer clic en la tarjeta (descargar y reproducir)
     * @param onBrowser acción del botón 🔗 — recibe el {@code videoId}
     * @param onAdd     acción del botón ＋ — recibe la canción y el nodo ancla
     */
    public static VBox songCard(Song song, Runnable onPlay,
                                Consumer<String> onBrowser, BiConsumer<Song, Node> onAdd) {
        VBox card = new VBox(6);
        card.getStyleClass().add("song-card");

        ImageView thumb = new ImageView();
        thumb.setFitWidth(178); thumb.setFitHeight(100); thumb.setPreserveRatio(false);
        loadImage(thumb, song.getThumbnailUrl());

        VBox overlay = new VBox();
        overlay.setAlignment(Pos.CENTER);
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.45); -fx-background-radius: 8px 8px 0 0;");
        overlay.setOpacity(0);
        Label playIcon = new Label("🔗"); playIcon.setStyle("-fx-font-size: 24px;");
        overlay.getChildren().add(playIcon);

        StackPane thumbStack = new StackPane(thumb, overlay);
        Label titleLbl   = new Label(song.getTitle());       titleLbl.getStyleClass().add("song-title");  titleLbl.setMaxWidth(178);
        Label channelLbl = new Label(song.getChannelName()); channelLbl.getStyleClass().add("song-artist");

        Label ytBadge = new Label("YouTube"); ytBadge.getStyleClass().add("yt-badge"); HBox.setHgrow(ytBadge, Priority.ALWAYS);
        Button linkBtn = new Button("🔗"); linkBtn.getStyleClass().add("row-link-btn");
        linkBtn.setOnAction(e -> { e.consume(); onBrowser.accept(song.getVideoId()); });
        Button addBtn = new Button("＋"); addBtn.getStyleClass().add("add-to-lib-btn");
        addBtn.setOnAction(e -> { e.consume(); onAdd.accept(song, addBtn); });
        HBox bottomRow = new HBox(6, ytBadge, linkBtn, addBtn); bottomRow.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(thumbStack, titleLbl, channelLbl, bottomRow);
        hoverLift(card, overlay);
        card.setOnMouseClicked(e -> { if (e.getTarget() != addBtn) onPlay.run(); });
        return card;
    }

    /**
     * Crea una tarjeta de playlist de YouTube con miniatura grande, título y botón
     * "Añadir a biblioteca".
     *
     * @param pl       datos de la playlist
     * @param onImport acción del botón "Añadir a biblioteca"
     */
    public static VBox playlistCard(YouTubePlaylistInfo pl, Runnable onImport) {
        VBox card = new VBox(10);
        card.getStyleClass().add("playlist-card");

        ImageView thumb = new ImageView();
        thumb.setFitWidth(280); thumb.setFitHeight(157); thumb.setPreserveRatio(false);
        loadImage(thumb, pl.getThumbnailUrl());

        Label titleLbl   = new Label(pl.getTitle());       titleLbl.getStyleClass().add("playlist-card-title"); titleLbl.setWrapText(true); titleLbl.setMaxWidth(280);
        Label channelLbl = new Label(pl.getChannelName()); channelLbl.getStyleClass().add("song-artist");
        Label countLbl   = new Label(pl.getItemCount() > 0 ? pl.getItemCount() + " videos" : "Playlist"); countLbl.getStyleClass().add("song-duration");

        Button addLib = new Button("＋  Añadir a biblioteca"); addLib.getStyleClass().add("btn-primary");
        addLib.setOnAction(e -> onImport.run());
        HBox actions = new HBox(10, addLib); actions.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(thumb, titleLbl, channelLbl, countLbl, actions);
        card.setOnMouseEntered(e -> scale(card, 1.02));
        card.setOnMouseExited(e  -> scale(card, 1.0));
        return card;
    }

    /**
     * Crea una tarjeta de género destacado para el panel de inicio.
     *
     * @param title    nombre del género (p. ej. "Lo-fi Beats")
     * @param mood     estado de ánimo (p. ej. "Relajante")
     * @param color    color de fondo en formato CSS (p. ej. {@code "#fdd5e2"})
     * @param onSearch acción al hacer clic — recibe la query de búsqueda
     */
    public static VBox featuredCard(String title, String mood, String color, Consumer<String> onSearch) {
        VBox card = new VBox(8); card.getStyleClass().add("featured-card");
        card.setStyle("-fx-background-color: " + color + ";");
        card.setPrefSize(160, 160); card.setAlignment(Pos.BOTTOM_LEFT);

        Text emoji = new Text(moodEmoji(mood)); emoji.setStyle("-fx-font-size: 36px;");
        Label nameLbl = new Label(title); nameLbl.getStyleClass().add("card-title");
        Label subLbl  = new Label(mood);  subLbl.getStyleClass().add("card-subtitle");
        card.getChildren().addAll(emoji, nameLbl, subLbl);

        card.setOnMouseEntered(e -> scale(card, 1.04));
        card.setOnMouseExited(e  -> scale(card, 1.0));
        card.setOnMouseClicked(e -> onSearch.accept(title + " music"));
        return card;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static void loadImage(ImageView iv, String url) {
        if (url != null && !url.isBlank()) {
            try { iv.setImage(new Image(url, true)); } catch (Exception ignored) {}
        }
    }

    private static void hoverLift(VBox card, VBox overlay) {
        card.setOnMouseEntered(e -> {
            TranslateTransition tt = new TranslateTransition(Duration.millis(120), card); tt.setToY(-4); tt.play();
            FadeTransition ft = new FadeTransition(Duration.millis(150), overlay); ft.setToValue(1); ft.play();
        });
        card.setOnMouseExited(e -> {
            TranslateTransition tt = new TranslateTransition(Duration.millis(120), card); tt.setToY(0); tt.play();
            FadeTransition ft = new FadeTransition(Duration.millis(150), overlay); ft.setToValue(0); ft.play();
        });
    }

    private static void scale(VBox card, double factor) {
        ScaleTransition s = new ScaleTransition(Duration.millis(130), card);
        s.setToX(factor); s.setToY(factor); s.play();
    }

    private static String moodEmoji(String mood) {
        return switch (mood) {
            case "Relajante" -> "🌊"; case "Energético" -> "⚡"; case "Tranquilo" -> "🍃";
            case "Atmosférico" -> "☕"; case "Acogedor" -> "🎸"; default -> "🎛";
        };
    }
}
