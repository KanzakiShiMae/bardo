package com.musicplayer.controllers;

import com.musicplayer.models.LibraryGroup;
import com.musicplayer.models.Song;
import com.musicplayer.services.LibraryService;
import com.musicplayer.services.PersistenceService;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.javafx.StackedFontIcon;
import org.kordamp.ikonli.boxicons.BoxiconsRegular;
import org.kordamp.ikonli.boxicons.BoxiconsSolid;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import javafx.scene.shape.Rectangle;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.collections.transformation.FilteredList;

/**
 * Construye los paneles de la biblioteca: filas de grupo resumidas ({@link #groupSection})
 * y el panel de detalle completo de una colección ({@link #detailPanel}).
 *
 * <p>El panel de detalle incluye:
 * <ul>
 *   <li>Barra de búsqueda case/accent-insensitive sobre la lista de canciones.</li>
 *   <li>Reordenación por arrastre (drag-to-reorder) desactivada mientras la búsqueda esté activa.</li>
 *   <li>Botón de pin (📌) en cada fila para añadir o quitar la canción de la pantalla de inicio.</li>
 *   <li>Checklist de playlists para copiar/mover canciones entre colecciones.</li>
 *   <li>Modo Mashup: selección de dos canciones para reproducirlas simultáneamente.</li>
 * </ul>
 *
 * <p>Todos los fondos y colores de texto usan variables CSS de {@link ThemeManager#THEME_VARS}
 * ({@code bardo-bg}, {@code bardo-text}, etc.) para ser configurables desde el panel de
 * Configuración. El contraste de texto se aplica en {@code MainController} con
 * {@link ThemeManager#applyContrastStroke} al abrir el panel de detalle.
 */
public final class GroupDetailBuilder {

    private GroupDetailBuilder() {}

    /** Tipos de lista disponibles en el Dropdown. */
    private static final String[] TYPES = {"Música", "Ambiente", "Mashup"};

    // ── Imágenes: icono/banner personalizados, resolución de fallback y recorte ─────

    /** Icono a mostrar en Biblioteca (fila clásica o tarjeta moderna): icono personalizado,
     *  si no hay, el banner personalizado, si no hay, el thumbnail de YouTube de la playlist. */
    private static String resolveGroupIconUrl(LibraryGroup group) {
        String custom = group.getCustomIconUrl();
        if (custom == null || custom.isBlank()) custom = group.getCustomBannerUrl();
        if (custom != null && !custom.isBlank()) return custom;
        return group.getThumbnailUrl();
    }

    /** Banner a mostrar en la cabecera de detalle: banner personalizado, si no hay, el icono
     *  personalizado, si no hay, la miniatura de la última canción añadida (índice 0 — las
     *  canciones nuevas se insertan al principio en toda la app), si no hay, el thumbnail de
     *  la playlist. Se reevalúa cada vez que cambia cualquiera de esas fuentes (ver
     *  {@link #buildModernHero}), así que el banner "sigue" a la canción más reciente. */
    private static String resolveHeroBannerUrl(LibraryGroup group) {
        String custom = group.getCustomBannerUrl();
        if (custom == null || custom.isBlank()) custom = group.getCustomIconUrl();
        if (custom != null && !custom.isBlank()) return custom;
        if (!group.getSongs().isEmpty()) {
            String t = group.getSongs().get(0).getThumbnailUrl();
            if (t != null && !t.isBlank()) return t;
        }
        return group.getThumbnailUrl();
    }

    /** Como {@link CardBuilder#loadImage}, pero sin pasar por su caché para URIs {@code file:}
     *  (imágenes personalizadas locales) — si el usuario reemplaza su banner/icono con un
     *  archivo del mismo nombre, la caché (indexada por URL) devolvería la imagen vieja. Cargar
     *  un archivo local es prácticamente instantáneo, así que no hay coste real en no cachearlo. */
    private static void loadThumb(ImageView iv, String url) {
        if (url == null || url.isBlank()) return;
        if (url.startsWith("file:")) {
            try { iv.setImage(new Image(url, true)); } catch (Exception ignored) {}
        } else {
            CardBuilder.loadImage(iv, url);
        }
        fadeInOnLoad(iv);
    }

    /** Deja {@code iv} invisible y la desvanece suavemente en cuanto su imagen termina de
     *  cargar (o la deja tal cual, sin parpadeo, si ya estaba cargada del todo — p. ej. viene de
     *  la caché LRU de {@link CardBuilder#loadImage}, no tiene sentido volver a desvanecerla). */
    private static void fadeInOnLoad(ImageView iv) {
        Image img = iv.getImage();
        if (img == null) return;
        if (img.getProgress() >= 1.0 && !img.isError()) { iv.setOpacity(1); return; }
        iv.setOpacity(0);
        img.progressProperty().addListener(new ChangeListener<Number>() {
            @Override public void changed(ObservableValue<? extends Number> obs, Number old, Number val) {
                if (val.doubleValue() < 1.0 && !img.isError()) return;
                img.progressProperty().removeListener(this);
                FadeTransition ft = new FadeTransition(Duration.millis(220), iv);
                ft.setFromValue(0); ft.setToValue(1);
                ft.play();
            }
        });
    }

    /** {@link #loadThumb} + {@link #applyCleanViewport}, saltando el recorte de bandas para
     *  URIs {@code file:} — una imagen personalizada ya viene recortada al formato exacto por
     *  {@link #openCropDialog}, así que "limpiarla" de nuevo (asumiendo bandas de letterbox que
     *  no tiene) solo la recortaría de más sin motivo. */
    private static void loadThumbClean(ImageView iv, String url, boolean square) {
        loadThumb(iv, url);
        if (url != null && !url.startsWith("file:")) applyCleanViewport(iv, square);
    }

    /** Región (en coordenadas de la imagen original) con las bandas de letterbox superior/
     *  inferior descartadas — YouTube incrusta miniaturas panorámicas 16:9 dentro de marcos
     *  4:3 (p.ej. {@code hqdefault.jpg}, 480×360) rellenando arriba/abajo con negro. Si la
     *  imagen ya es igual o más panorámica que 16:9, devuelve la imagen completa (no hay nada
     *  que recortar). */
    private static Rectangle2D cleanContentViewport(double w, double h) {
        double contentH = Math.min(h, w * 9.0 / 16.0);
        double y = (h - contentH) / 2.0;
        return new Rectangle2D(0, y, w, contentH);
    }

    /** Aplica {@link #cleanContentViewport} a {@code iv} en cuanto se conocen las dimensiones
     *  reales de su imagen (puede tardar si aún se está descargando/decodificando en segundo
     *  plano). Si {@code square} es {@code true}, recorta además al cuadrado central de ese
     *  contenido limpio — así una miniatura 4:3 con bandas puede mostrarse como un cuadrado
     *  sin bandas ni distorsión, en vez de achatar el fotograma completo (bandas incluidas). */
    private static void applyCleanViewport(ImageView iv, boolean square) {
        Image img = iv.getImage();
        if (img == null) return;
        Runnable apply = () -> {
            double w = img.getWidth(), h = img.getHeight();
            if (w <= 0 || h <= 0) return;
            Rectangle2D clean = cleanContentViewport(w, h);
            if (!square) { iv.setViewport(clean); return; }
            double side = Math.min(clean.getWidth(), clean.getHeight());
            double x = clean.getMinX() + (clean.getWidth() - side) / 2.0;
            double y = clean.getMinY() + (clean.getHeight() - side) / 2.0;
            iv.setViewport(new Rectangle2D(x, y, side, side));
        };
        if (img.getWidth() > 0 && img.getHeight() > 0) { apply.run(); return; }
        ChangeListener<Number> l = (obs, old, val) -> { if (img.getWidth() > 0 && img.getHeight() > 0) apply.run(); };
        img.widthProperty().addListener(l);
        img.heightProperty().addListener(l);
    }

    /** Ajusta el fondo del hero en modo "cover" — nunca estira, recorta lo que sobre — a la
     *  proporción REAL del hero en cada momento (reactivo a {@code hero.widthProperty()}, que
     *  cambia con el tamaño de la ventana; el alto del hero es fijo). El recorte de
     *  {@code openCropDialog} usa una proporción FIJA para el visor en pantalla, pero el hero
     *  real casi nunca tiene exactamente esa proporción — estirar la imagen para llenar ese
     *  hueco de ancho variable la achataba visiblemente. {@code removeBands} solo se aplica a
     *  miniaturas de YouTube (letterbox real); una imagen personalizada ya viene limpia.
     *  Cada rebanner (reemplazo de imagen) crea un {@code ImageView} nuevo y llama aquí otra
     *  vez — sin quitar el listener anterior de {@code hero.widthProperty()} se acumularía uno
     *  por cada reemplazo (mismo patrón de fuga que {@code typeProperty}/el hero reactivo; ver
     *  {@link #detachChangeListener}), así que se guarda/quita en {@code panel.getProperties()}. */
    private static void applyHeroCoverViewport(VBox panel, ImageView bg, StackPane hero, boolean removeBands) {
        Image img = bg.getImage();
        if (img == null) return;
        Runnable recompute = () -> {
            double w = img.getWidth(), h = img.getHeight();
            double heroW = hero.getWidth();
            if (w <= 0 || h <= 0 || heroW <= 0) return;
            Rectangle2D base = removeBands ? cleanContentViewport(w, h) : new Rectangle2D(0, 0, w, h);
            double targetAspect = heroW / HERO_HEIGHT;
            double cw = base.getWidth(), ch = base.getHeight();
            double srcAspect = cw / ch;
            Rectangle2D vp;
            if (srcAspect > targetAspect) {
                double newW = ch * targetAspect;
                vp = new Rectangle2D(base.getMinX() + (cw - newW) / 2.0, base.getMinY(), newW, ch);
            } else {
                double newH = cw / targetAspect;
                vp = new Rectangle2D(base.getMinX(), base.getMinY() + (ch - newH) / 2.0, cw, newH);
            }
            bg.setViewport(vp);
        };
        detachChangeListener(panel, "heroCoverWidthListener", hero.widthProperty());
        ChangeListener<Number> widthListener = (obs, old, val) -> recompute.run();
        hero.widthProperty().addListener(widthListener);
        panel.getProperties().put("heroCoverWidthListener", widthListener);
        if (img.getWidth() > 0 && img.getHeight() > 0) { recompute.run(); return; }
        ChangeListener<Number> l = (obs, old, val) -> { if (img.getWidth() > 0 && img.getHeight() > 0) recompute.run(); };
        img.widthProperty().addListener(l);
        img.heightProperty().addListener(l);
    }

    /** Diálogo simple para elegir/quitar el icono y el banner personalizados de {@code group}. */
    private static void openCustomizeImagesDialog(LibraryGroup group, Window owner, Consumer<String> onToast) {
        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("Imágenes de «" + group.getName() + "»");
        if (owner != null) dlg.initOwner(owner);

        Button pickBannerBtn = new Button("Elegir imagen de banner…");
        pickBannerBtn.setOnAction(e -> pickAndStoreImage(group, owner, true, onToast));
        Button clearBannerBtn = new Button("Quitar banner personalizado");
        clearBannerBtn.setOnAction(e -> { group.setCustomBannerUrl(null); onToast.accept("Banner personalizado eliminado"); });

        Button pickIconBtn = new Button("Elegir icono…");
        pickIconBtn.setOnAction(e -> pickAndStoreImage(group, owner, false, onToast));
        Button clearIconBtn = new Button("Quitar icono personalizado");
        clearIconBtn.setOnAction(e -> { group.setCustomIconUrl(null); onToast.accept("Icono personalizado eliminado"); });

        Label hint = new Label("Si solo defines uno de los dos, se usa también como el otro.");
        hint.getStyleClass().add("greeting-sub"); hint.setWrapText(true); hint.setMaxWidth(280);

        VBox content = new VBox(10, pickBannerBtn, clearBannerBtn, new Separator(), pickIconBtn, clearIconBtn, hint);
        content.setPadding(new Insets(12));
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dlg.showAndWait();
    }

    private static void pickAndStoreImage(LibraryGroup group, Window owner, boolean banner, Consumer<String> onToast) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(banner ? "Elegir imagen de banner" : "Elegir icono");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            "Imágenes", "*.png", "*.jpg", "*.jpeg", "*.webp", "*.gif", "*.bmp"));
        File picked = chooser.showOpenDialog(owner);
        if (picked == null) return;

        Image src;
        try { src = new Image(picked.toURI().toString()); } catch (Exception ex) {
            onToast.accept("No se pudo abrir la imagen: " + ex.getMessage()); return;
        }
        if (src.isError() || src.getWidth() <= 0) {
            onToast.accept("No se pudo leer la imagen elegida."); return;
        }

        openCropDialog(src, banner, owner, cropped -> {
            try {
                Path dir = PersistenceService.bardoBaseDir().resolve("covers");
                Files.createDirectories(dir);
                Path dest = dir.resolve(group.getId() + (banner ? "-banner.png" : "-icon.png"));
                BufferedImage buffered = toBufferedImage(cropped);
                ImageIO.write(buffered, "png", dest.toFile());
                String uri = dest.toUri().toString();
                if (banner) group.setCustomBannerUrl(uri); else group.setCustomIconUrl(uri);
                onToast.accept(banner ? "Banner actualizado" : "Icono actualizado");
            } catch (Exception ex) {
                onToast.accept("Error al guardar la imagen: " + ex.getMessage());
            }
        });
    }

    private static final double ICON_CROP_SIZE = 280;
    private static final double BANNER_CROP_W  = 480;
    private static final double BANNER_CROP_H  = 135;
    /** Multiplicador de resolución de salida respecto al visor en pantalla — ver {@link #openCropDialog}. */
    private static final double CROP_SUPERSAMPLE = 3.0;

    /** Ventana modal de recorte: el usuario arrastra la imagen y ajusta el zoom dentro de un
     *  visor con el formato exacto necesario (cuadrado para el icono, panorámico fijo para el
     *  banner) — así elige él qué parte de la imagen se conserva, en vez de que la aplicación
     *  la recorte o la achate automáticamente. Al confirmar, se captura exactamente lo que se
     *  ve en el visor ({@code viewport.snapshot(...)}) y se entrega ya recortado a {@code onDone}. */
    private static void openCropDialog(Image src, boolean banner, Window owner, Consumer<WritableImage> onDone) {
        double previewW = banner ? BANNER_CROP_W : ICON_CROP_SIZE;
        double previewH = banner ? BANNER_CROP_H : ICON_CROP_SIZE;
        double imgW = src.getWidth(), imgH = src.getHeight();
        double minScale = Math.max(previewW / imgW, previewH / imgH);
        double maxScale = minScale * 4;

        ImageView iv = new ImageView(src); iv.setPreserveRatio(true); iv.setSmooth(true);
        DoubleProperty scale = new SimpleDoubleProperty(minScale);
        DoubleProperty panX  = new SimpleDoubleProperty(0);
        DoubleProperty panY  = new SimpleDoubleProperty(0);
        iv.scaleXProperty().bind(scale); iv.scaleYProperty().bind(scale);
        iv.translateXProperty().bind(panX); iv.translateYProperty().bind(panY);

        Runnable clampPan = () -> {
            double dw = imgW * scale.get(), dh = imgH * scale.get();
            double maxPanX = Math.max(0, (dw - previewW) / 2);
            double maxPanY = Math.max(0, (dh - previewH) / 2);
            panX.set(Math.max(-maxPanX, Math.min(maxPanX, panX.get())));
            panY.set(Math.max(-maxPanY, Math.min(maxPanY, panY.get())));
        };

        StackPane viewport = new StackPane(iv);
        viewport.setMinSize(previewW, previewH); viewport.setPrefSize(previewW, previewH); viewport.setMaxSize(previewW, previewH);
        viewport.setClip(new Rectangle(previewW, previewH));
        viewport.getStyleClass().add("crop-viewport");
        viewport.setCursor(Cursor.MOVE);

        double[] dragStart = {0, 0, 0, 0};
        viewport.setOnMousePressed(e -> {
            dragStart[0] = e.getSceneX(); dragStart[1] = e.getSceneY();
            dragStart[2] = panX.get(); dragStart[3] = panY.get();
        });
        viewport.setOnMouseDragged(e -> {
            panX.set(dragStart[2] + (e.getSceneX() - dragStart[0]));
            panY.set(dragStart[3] + (e.getSceneY() - dragStart[1]));
            clampPan.run();
        });
        viewport.setOnScroll(e -> {
            double factor = e.getDeltaY() > 0 ? 1.08 : 1 / 1.08;
            scale.set(Math.max(minScale, Math.min(maxScale, scale.get() * factor)));
            clampPan.run();
        });

        Slider zoomSlider = new Slider(minScale, maxScale, minScale);
        zoomSlider.getStyleClass().add("volume-slider"); zoomSlider.setPrefWidth(previewW);
        zoomSlider.valueProperty().bindBidirectional(scale);
        zoomSlider.valueProperty().addListener((o, ov, nv) -> clampPan.run());

        Label hint = new Label("Arrastra para mover, rueda del ratón o el deslizador para acercar/alejar.");
        hint.getStyleClass().add("greeting-sub");

        Button confirmBtn = new Button("  Recortar y guardar"); confirmBtn.getStyleClass().add("btn-primary");
        MainController.ico(confirmBtn, BoxiconsSolid.SAVE, 14, true);
        Button cancelBtn = new Button("Cancelar"); cancelBtn.getStyleClass().add("btn-secondary");

        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.initStyle(javafx.stage.StageStyle.UTILITY);
        dlg.setTitle(banner ? "Recortar banner" : "Recortar icono");
        dlg.setResizable(false);

        VBox root = new VBox(14, hint, viewport, zoomSlider, new HBox(10, cancelBtn, confirmBtn));
        root.setPadding(new Insets(18)); root.setAlignment(Pos.CENTER);
        ((HBox) root.getChildren().get(3)).setAlignment(Pos.CENTER_RIGHT);
        Scene scene = new Scene(root);
        URL css = GroupDetailBuilder.class.getResource("/com/musicplayer/styles/main.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        dlg.setScene(scene);

        cancelBtn.setOnAction(e -> dlg.close());
        confirmBtn.setOnAction(e -> {
            // El visor en pantalla es deliberadamente pequeño (cómodo para arrastrar/hacer zoom),
            // pero eso NO debe limitar la resolución del archivo final — se captura a más
            // resolución que el propio visor (supersampling) multiplicando la escala del
            // snapshot. El factor se limita a 1/scale.get(): a más zoom del usuario, menos
            // detalle real queda disponible en la imagen origen para este recorte concreto, así
            // que si hace falta se reescala hacia ABAJO para no "inventar" píxeles de más
            // (upscaling borroso) — nunca se pide más resolución de la que la imagen puede dar.
            double usedFactor = Math.min(CROP_SUPERSAMPLE, 1.0 / scale.get());
            SnapshotParameters params = new SnapshotParameters();
            params.setTransform(new javafx.scene.transform.Scale(usedFactor, usedFactor));
            WritableImage snap = viewport.snapshot(params, null);
            dlg.close();
            onDone.accept(snap);
        });

        dlg.showAndWait();
    }

    private static BufferedImage toBufferedImage(WritableImage img) {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = img.getPixelReader();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                buffered.setRGB(x, y, reader.getArgb(x, y));
        return buffered;
    }

    // ── groupSection ─────────────────────────────────────────────────────────

    public static VBox groupSection(LibraryGroup group, Runnable onRemove,
                                    Consumer<Button> onRefreshYt, Runnable onClick) {
        VBox card = new VBox(0); card.getStyleClass().add("group-section");
        HBox row  = new HBox(12); row.getStyleClass().add("group-header"); row.setAlignment(Pos.CENTER_LEFT);

        String thumbUrl = resolveGroupIconUrl(group);
        if (thumbUrl != null && !thumbUrl.isBlank()) {
            ImageView iv = new ImageView(); iv.setFitWidth(56); iv.setFitHeight(32); iv.setPreserveRatio(false);
            loadThumb(iv, thumbUrl);
            row.getChildren().add(iv);
        } else {
            Label icon = new Label();
            icon.setGraphic(group.isYoutubePlaylist()
                ? IkonUtil.duotone(BoxiconsSolid.TV, BoxiconsRegular.TV, 20)
                : IkonUtil.duotone(BoxiconsSolid.MUSIC, BoxiconsRegular.MUSIC, 20));
            icon.setStyle("-fx-min-width: 48px; -fx-alignment: center;");
            row.getChildren().add(icon);
        }

        Label nameLbl = new Label(group.getName()); nameLbl.getStyleClass().add("group-name");
        Label cntLbl  = new Label();
        cntLbl.textProperty().bind(Bindings.createStringBinding(() -> group.size() + " canciones", group.getSongs()));
        cntLbl.getStyleClass().add("group-count");
        VBox meta = new VBox(2, nameLbl, cntLbl); HBox.setHgrow(meta, Priority.ALWAYS);

        ComboBox<String> typeCombo = buildTypeCombo(group);

        Label chevron = new Label(); chevron.getStyleClass().add("group-arrow");
        chevron.setGraphic(IkonUtil.duotone(BoxiconsSolid.CHEVRON_RIGHT, BoxiconsRegular.CHEVRON_RIGHT, 18));
        Button removeBtn = new Button(); removeBtn.getStyleClass().add("group-remove-btn"); removeBtn.setOnAction(e -> onRemove.run());
        MainController.ico(removeBtn, BoxiconsRegular.X, 13, false);

        if (group.isYoutubePlaylist() && onRefreshYt != null) {
            Button refreshBtn = new Button(); refreshBtn.getStyleClass().add("group-refresh-btn");
            MainController.ico(refreshBtn, BoxiconsRegular.REFRESH, 14, false);
            refreshBtn.setOnAction(e -> { e.consume(); onRefreshYt.accept(refreshBtn); });
            row.getChildren().addAll(meta, typeCombo, chevron, refreshBtn, removeBtn);
        } else {
            row.getChildren().addAll(meta, typeCombo, chevron, removeBtn);
        }
        row.setOnMouseClicked(e -> { if (!(e.getTarget() instanceof Button) && !(e.getTarget() instanceof ComboBox)) onClick.run(); });
        card.getChildren().add(row);
        return card;
    }

    /** Despacha entre la fila clásica ({@link #groupSection}) y la tarjeta moderna
     *  ({@link #groupCardModern}) según la preferencia de diseño del usuario. */
    public static Node groupCard(LibraryGroup group, Runnable onRemove,
                                 Consumer<Button> onRefreshYt, Runnable onClick, boolean modern) {
        return modern ? groupCardModern(group, onRemove, onRefreshYt, onClick)
                      : groupSection(group, onRemove, onRefreshYt, onClick);
    }

    private static final double CARD_COVER = 168;

    /** Tarjeta grande con portada para la cuadrícula de Biblioteca en modo moderno. */
    private static VBox groupCardModern(LibraryGroup group, Runnable onRemove,
                                        Consumer<Button> onRefreshYt, Runnable onClick) {
        VBox card = new VBox(8); card.getStyleClass().add("group-card-modern");

        StackPane coverStack = new StackPane();
        coverStack.setMinSize(CARD_COVER, CARD_COVER); coverStack.setMaxSize(CARD_COVER, CARD_COVER);
        String thumbUrl = resolveGroupIconUrl(group);
        if (thumbUrl != null && !thumbUrl.isBlank()) {
            ImageView iv = new ImageView();
            iv.setFitWidth(CARD_COVER); iv.setFitHeight(CARD_COVER); iv.setPreserveRatio(false);
            Rectangle clip = new Rectangle(CARD_COVER, CARD_COVER);
            clip.setArcWidth(16); clip.setArcHeight(16);
            iv.setClip(clip);
            loadThumbClean(iv, thumbUrl, true);
            coverStack.getChildren().add(iv);
        } else {
            Region placeholder = new Region(); placeholder.getStyleClass().add("group-card-cover-placeholder");
            Label icon = new Label();
            icon.setGraphic(group.isYoutubePlaylist()
                ? IkonUtil.duotone(BoxiconsSolid.TV, BoxiconsRegular.TV, 36)
                : IkonUtil.duotone(BoxiconsSolid.MUSIC, BoxiconsRegular.MUSIC, 36));
            coverStack.getChildren().addAll(placeholder, icon);
        }

        Label nameLbl = new Label(group.getName()); nameLbl.getStyleClass().add("group-card-modern-title");
        nameLbl.setWrapText(true); nameLbl.setMaxWidth(CARD_COVER); nameLbl.setMinHeight(Region.USE_PREF_SIZE);
        Label cntLbl  = new Label();
        cntLbl.textProperty().bind(Bindings.createStringBinding(() -> group.size() + " canciones", group.getSongs()));
        cntLbl.getStyleClass().add("group-card-modern-count");

        ComboBox<String> typeCombo = buildTypeCombo(group);
        typeCombo.setPrefWidth(100);

        Button removeBtn = new Button(); removeBtn.getStyleClass().add("group-remove-btn"); removeBtn.setOnAction(e -> onRemove.run());
        MainController.ico(removeBtn, BoxiconsRegular.X, 13, false);

        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottomRow = new HBox(6, typeCombo);
        if (group.isYoutubePlaylist() && onRefreshYt != null) {
            Button refreshBtn = new Button(); refreshBtn.getStyleClass().add("group-refresh-btn");
            MainController.ico(refreshBtn, BoxiconsRegular.REFRESH, 14, false);
            refreshBtn.setOnAction(e -> { e.consume(); onRefreshYt.accept(refreshBtn); });
            bottomRow.getChildren().add(refreshBtn);
        }
        bottomRow.getChildren().addAll(spacer, removeBtn);
        bottomRow.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(coverStack, nameLbl, cntLbl, bottomRow);
        card.setOnMouseClicked(e -> { if (!(e.getTarget() instanceof Button) && !(e.getTarget() instanceof ComboBox)) onClick.run(); });

        card.setOnMouseEntered(e -> {
            TranslateTransition tt = new TranslateTransition(Duration.millis(120), card); tt.setToY(-4); tt.play();
        });
        card.setOnMouseExited(e -> {
            TranslateTransition tt = new TranslateTransition(Duration.millis(120), card); tt.setToY(0); tt.play();
        });
        return card;
    }

    // ── detailPanel ───────────────────────────────────────────────────────────

    /** Cabecera clásica: barra fina con icono, título y botones de acción. */
    private static HBox buildClassicTopBar(LibraryGroup group, Runnable onBack, Runnable onPlayAll,
                                           Runnable onImportFolder, Consumer<Button> onRefreshYt,
                                           Runnable onDownloadAll, ComboBox<String> typeCombo) {
        Button backBtn = new Button(); backBtn.getStyleClass().add("back-btn"); backBtn.setOnAction(e -> onBack.run());
        MainController.ico(backBtn, BoxiconsRegular.ARROW_BACK, 16, false);
        Label iconLbl = new Label();
        iconLbl.setGraphic(group.isYoutubePlaylist()
            ? IkonUtil.duotone(BoxiconsSolid.TV, BoxiconsRegular.TV, 22)
            : IkonUtil.duotone(BoxiconsSolid.MUSIC, BoxiconsRegular.MUSIC, 22));

        Label titleLbl = new Label(group.getName()); titleLbl.getStyleClass().add("greeting");
        Label cntLbl   = new Label();
        cntLbl.textProperty().bind(Bindings.createStringBinding(() -> group.size() + " canciones", group.getSongs()));
        cntLbl.getStyleClass().add("greeting-sub");
        VBox titleBox = new VBox(3, titleLbl, cntLbl); HBox.setHgrow(titleBox, Priority.ALWAYS);

        Button playAllBtn = new Button(); playAllBtn.getStyleClass().add("btn-icon"); playAllBtn.setOnAction(e -> onPlayAll.run());
        MainController.ico(playAllBtn, BoxiconsRegular.PLAY, 15, true);
        Button importBtn = new Button(); importBtn.getStyleClass().add("btn-icon"); importBtn.setOnAction(e -> onImportFolder.run());
        MainController.ico(importBtn, BoxiconsSolid.FOLDER_OPEN, 15, false);
        HBox actionBtns = new HBox(6, typeCombo, playAllBtn, importBtn); actionBtns.setAlignment(Pos.CENTER_RIGHT);

        if (group.isYoutubePlaylist()) {
            Button refreshBtn = new Button(); refreshBtn.getStyleClass().add("btn-icon");
            MainController.ico(refreshBtn, BoxiconsRegular.REFRESH, 15, false);
            refreshBtn.setOnAction(e -> { if (onRefreshYt != null) onRefreshYt.accept(refreshBtn); });
            Button dlBtn = new Button(); dlBtn.getStyleClass().add("btn-icon");
            MainController.ico(dlBtn, BoxiconsSolid.DOWNLOAD, 15, false);
            dlBtn.setOnAction(e -> { if (onDownloadAll != null) onDownloadAll.run(); });
            actionBtns.getChildren().addAll(refreshBtn, dlBtn);
        }

        HBox topBar = new HBox(12, backBtn, iconLbl, titleBox, actionBtns);
        topBar.setAlignment(Pos.CENTER_LEFT); topBar.setPadding(new Insets(22, 28, 14, 28));
        return topBar;
    }

    private static final double HERO_HEIGHT = 190;

    /** Cabecera moderna: banner compacto con la portada de fondo (reactiva: sigue al banner/
     *  icono personalizados si existen, o si no a la última canción añadida — ver
     *  {@link #resolveHeroBannerUrl}) y degradado. Se contrae y desaparece al hacer scroll en
     *  la lista (ver {@link #wireHeroCollapse}) — no debe permanecer fijo ocupando espacio todo
     *  el rato. Sin botón de reproducir: no tiene sentido reproducir una playlist entera desde
     *  el principio en esta aplicación. */
    private static StackPane buildModernHero(VBox panel, LibraryGroup group, Runnable onBack,
                                              Runnable onImportFolder, Consumer<Button> onRefreshYt,
                                              Runnable onDownloadAll, ComboBox<String> typeCombo,
                                              Consumer<String> onToast) {
        StackPane hero = new StackPane(); hero.getStyleClass().add("library-hero");
        hero.setMinHeight(HERO_HEIGHT); hero.setPrefHeight(HERO_HEIGHT); hero.setMaxHeight(HERO_HEIGHT);
        // El fondo se carga con una altura FIJA (fitHeight = HERO_HEIGHT, ver más abajo) que no
        // se actualiza sola durante la animación de colapso — sin este clip, mientras el hero se
        // contrae la imagen/degradado de fondo seguían pintándose a su altura completa y
        // "sobresalían" por debajo del hero ya encogido, viéndose como si el degradado blanco se
        // separase de su base y se solapase con el contenido de debajo. El clip recorta TODO lo
        // que hay dentro del hero a su tamaño real en cada instante de la animación.
        Rectangle heroClip = new Rectangle();
        heroClip.widthProperty().bind(hero.widthProperty());
        heroClip.heightProperty().bind(hero.heightProperty());
        hero.setClip(heroClip);

        // Fondo reactivo: se reconstruye solo cuando la URL resuelta cambia de verdad (no en
        // cada tirón del drag-to-reorder, que también dispara el listener de group.getSongs())
        // — SALVO que el usuario acabe de elegir/reemplazar su banner o icono personalizado
        // (force=true): el archivo tiene un nombre FIJO por grupo, así que reemplazarlo por uno
        // nuevo no cambia la URL/URI en sí, aunque el contenido del archivo sí haya cambiado —
        // sin el "force", la comprobación de "¿cambió la URL?" se lo saltaba y el banner se
        // quedaba con la imagen vieja hasta cerrar y volver a abrir la pestaña.
        Node[]   bgNode     = {null};
        String[] currentUrl = {null};
        Consumer<Boolean> refreshBg = force -> {
            String url = resolveHeroBannerUrl(group);
            if (!force && java.util.Objects.equals(url, currentUrl[0])) return;
            currentUrl[0] = url;
            Node newBg;
            if (url != null && !url.isBlank()) {
                ImageView bg = new ImageView(); bg.setPreserveRatio(false); bg.setFitHeight(HERO_HEIGHT);
                bg.fitWidthProperty().bind(hero.widthProperty());
                loadThumb(bg, url);
                // "Cover", nunca estira: el hero es tan ancho como el panel (cambia con la
                // ventana) pero el recorte del icono/banner se hace a una proporción FIJA —
                // estirar la imagen para llenar ese hueco de ancho variable la achataba. En su
                // lugar se recorta (no se deforma) al ancho real en cada momento, reactivo a
                // hero.widthProperty() (incluye redimensionar la ventana).
                applyHeroCoverViewport(panel, bg, hero, !url.startsWith("file:"));
                newBg = bg;
            } else {
                Region fallback = new Region(); fallback.getStyleClass().add("library-hero-fallback");
                newBg = fallback;
            }
            if (bgNode[0] != null) hero.getChildren().remove(bgNode[0]);
            hero.getChildren().add(0, newBg);
            bgNode[0] = newBg;
        };
        refreshBg.accept(true);
        ListChangeListener<Song> songsListener = c -> refreshBg.accept(false);
        ChangeListener<String> bannerListener  = (o, ov, nv) -> refreshBg.accept(true);
        ChangeListener<String> iconListener    = (o, ov, nv) -> refreshBg.accept(true);
        group.getSongs().addListener(songsListener);
        group.customBannerUrlProperty().addListener(bannerListener);
        group.customIconUrlProperty().addListener(iconListener);
        panel.getProperties().put("heroSongsListener", songsListener);
        panel.getProperties().put("heroBannerListener", bannerListener);
        panel.getProperties().put("heroIconListener", iconListener);

        Region overlay = new Region(); overlay.getStyleClass().add("library-hero-overlay");
        hero.getChildren().add(overlay);

        Label titleLbl = new Label(group.getName()); titleLbl.getStyleClass().add("library-hero-title");
        // Una sola línea con elipsis (no wrapText): con el banner tan compacto (128px), un
        // nombre largo envuelto a 2-3 líneas podía crecer lo bastante para solaparse con el
        // botón de volver — visto como un bug real ("el botón de volver no funciona").
        titleLbl.setWrapText(false); titleLbl.setMaxWidth(420);
        Label cntLbl = new Label();
        cntLbl.textProperty().bind(Bindings.createStringBinding(() -> group.size() + " canciones", group.getSongs()));
        cntLbl.getStyleClass().add("library-hero-sub");
        VBox titleBox = new VBox(3, titleLbl, cntLbl);

        // Botón de volver: se añade EL ÚLTIMO (arriba del todo en el z-order) a propósito, para
        // que nada le pueda robar el click aunque algo se solape con él.
        Button backBtn = new Button(); backBtn.getStyleClass().add("library-hero-back-btn"); backBtn.setOnAction(e -> onBack.run());
        MainController.ico(backBtn, BoxiconsRegular.ARROW_BACK, 15, false);
        HBox backRow = new HBox(backBtn); backRow.setAlignment(Pos.CENTER_LEFT);

        // El botón de volver (arriba) y el título (abajo) van en UNA sola columna con un
        // espaciador que crece entre ambos — así es el propio layout, no un cálculo de márgenes
        // a mano, el que garantiza que nunca puedan solaparse, sea cual sea la altura real del
        // hero. Los dos intentos anteriores (centrar el título, luego anclarlo abajo con un
        // margen fijo) seguían solapándose en pantalla porque ese margen fijo no tenía en
        // cuenta la altura real de cada elemento — con esto ya no hace falta calcularlo.
        Region vSpacer = new Region(); VBox.setVgrow(vSpacer, Priority.ALWAYS);
        VBox contentCol = new VBox(backRow, vSpacer, titleBox);
        contentCol.setPadding(new Insets(12, 0, 14, 20));
        hero.getChildren().add(contentCol);

        Button customizeBtn = new Button(); customizeBtn.getStyleClass().add("library-hero-action-btn");
        MainController.ico(customizeBtn, BoxiconsRegular.IMAGE_ADD, 14, false);
        customizeBtn.setTooltip(new Tooltip("Personalizar banner/icono"));
        customizeBtn.setOnAction(e -> openCustomizeImagesDialog(group, customizeBtn.getScene() != null ? customizeBtn.getScene().getWindow() : null, onToast));
        Button importBtn = new Button(); importBtn.getStyleClass().add("library-hero-action-btn"); importBtn.setOnAction(e -> onImportFolder.run());
        MainController.ico(importBtn, BoxiconsSolid.FOLDER_OPEN, 14, false);
        HBox actionBtns = new HBox(6, typeCombo, customizeBtn, importBtn); actionBtns.setAlignment(Pos.CENTER_RIGHT);
        // Sin este tope, un HBox (a diferencia de un Button) no tiene máximo de tamaño propio,
        // así que el StackPane lo estira para llenar casi todo el hero (visto con un test real:
        // el HBox, invisible pero "sólido" para clics en su parte vacía, tapaba por completo al
        // botón de volver aunque no hubiera nada pintado ahí) — esa era la causa real de que el
        // botón de volver no respondiera, no la posición del título.
        actionBtns.setMaxWidth(Region.USE_PREF_SIZE); actionBtns.setMaxHeight(Region.USE_PREF_SIZE);
        if (group.isYoutubePlaylist()) {
            Button refreshBtn = new Button(); refreshBtn.getStyleClass().add("library-hero-action-btn");
            MainController.ico(refreshBtn, BoxiconsRegular.REFRESH, 14, false);
            refreshBtn.setOnAction(e -> { if (onRefreshYt != null) onRefreshYt.accept(refreshBtn); });
            Button dlBtn = new Button(); dlBtn.getStyleClass().add("library-hero-action-btn");
            MainController.ico(dlBtn, BoxiconsSolid.DOWNLOAD, 14, false);
            dlBtn.setOnAction(e -> { if (onDownloadAll != null) onDownloadAll.run(); });
            actionBtns.getChildren().addAll(refreshBtn, dlBtn);
        }
        StackPane.setAlignment(actionBtns, Pos.BOTTOM_RIGHT); StackPane.setMargin(actionBtns, new Insets(0, 20, 14, 0));
        hero.getChildren().add(actionBtns);

        return hero;
    }

    /** Reconstruye el contenido de {@code panel} in situ — permite alternar clásico/moderno
     *  en caliente sin recrear la pestaña (que perdería su identidad en {@code AppTab}). */
    public static void populateDetailPanel(VBox panel, LibraryGroup group, Runnable onBack, Runnable onPlayAll,
                                           Runnable onImportFolder, Consumer<Button> onRefreshYt,
                                           Runnable onDownloadAll, Consumer<String> onBrowser,
                                           BiConsumer<Song, LibraryGroup> onPlaySong,
                                           BiConsumer<Song, LibraryGroup> onOpenPaused,
                                           Consumer<List<Song>> onOpenMashup,
                                           LibraryService libraryService, Consumer<String> onToast,
                                           Runnable onPinChanged,
                                           ReadOnlyBooleanProperty masterActive, Consumer<Song> onAddToParty,
                                           boolean modern) {
        panel.getChildren().clear();

        // Repoblar (p.ej. al alternar clásico/moderno, o el cambio de tipo justo debajo) no debe
        // acumular listeners sobre el GRUPO (que sobrevive a la reconstrucción de la UI) por
        // cada repoblado — solo puede haber uno vivo de cada uno.
        detachChangeListener(panel, "groupDetailTypeListener", group.typeProperty());
        detachListChangeListener(panel, "heroSongsListener", group.getSongs());
        detachChangeListener(panel, "heroBannerListener", group.customBannerUrlProperty());
        detachChangeListener(panel, "heroIconListener", group.customIconUrlProperty());

        ComboBox<String> typeCombo = buildTypeCombo(group);
        Node header = modern
            ? buildModernHero(panel, group, onBack, onImportFolder, onRefreshYt, onDownloadAll, typeCombo, onToast)
            : buildClassicTopBar(group, onBack, onPlayAll, onImportFolder, onRefreshYt, onDownloadAll, typeCombo);
        Separator sep = new Separator(); sep.setStyle("-fx-background-color: #ece8f4;");

        // Mashup selection state
        Song[]    mashupSel     = {null, null};
        Button[]  mashupPlayBtn = {new Button("  Reproducir Mashup")};
        MainController.ico(mashupPlayBtn[0], BoxiconsRegular.HEADPHONE, 15, true);
        mashupPlayBtn[0].getStyleClass().add("btn-primary");
        mashupPlayBtn[0].setDisable(true);
        mashupPlayBtn[0].setOnAction(e -> {
            if (onOpenMashup != null && mashupSel[0] != null && mashupSel[1] != null)
                onOpenMashup.accept(List.of(mashupSel[0], mashupSel[1]));
        });
        Label mashupHint = new Label("Selecciona dos canciones (① y ②) y pulsa el botón para reproducirlas juntas.");
        mashupHint.getStyleClass().add("mashup-hint"); mashupHint.setWrapText(true);
        HBox mashupBar = new HBox(12, mashupHint, mashupPlayBtn[0]);
        mashupBar.setAlignment(Pos.CENTER_LEFT);
        mashupBar.getStyleClass().add("mashup-bar");
        mashupBar.setVisible("Mashup".equals(group.getType()));
        mashupBar.setManaged("Mashup".equals(group.getType()));
        javafx.beans.value.ChangeListener<String> typeListener = (obs, old, t) -> {
            boolean m = "Mashup".equals(t);
            // En modo moderno, Mashup usa la lista de filas (no la cuadrícula) — cambiar hacia
            // o desde Mashup cambia de widget por completo, así que hay que repoblar el panel
            // entero en vez de solo alternar la barra de mashup.
            if (modern && (m != "Mashup".equals(old))) {
                populateDetailPanel(panel, group, onBack, onPlayAll, onImportFolder, onRefreshYt, onDownloadAll,
                    onBrowser, onPlaySong, onOpenPaused, onOpenMashup, libraryService, onToast, onPinChanged,
                    masterActive, onAddToParty, modern);
                return;
            }
            mashupBar.setVisible(m); mashupBar.setManaged(m);
            if (!m) { mashupSel[0] = null; mashupSel[1] = null; mashupPlayBtn[0].setDisable(true); }
        };
        group.typeProperty().addListener(typeListener);
        panel.getProperties().put("groupDetailTypeListener", typeListener);

        // ── Search ───────────────────────────────────────────────────────────
        FilteredList<Song> filteredSongs = new FilteredList<>(group.getSongs(), p -> true);

        TextField searchField = new TextField();
        searchField.getStyleClass().add("detail-search-field");
        searchField.setPromptText("Buscar canción…");
        searchField.setPrefWidth(190);
        searchField.textProperty().addListener((obs, old, query) -> {
            String q = UIUtils.normalize(query);
            filteredSongs.setPredicate(q.isEmpty() ? null
                : s -> UIUtils.normalize(s.getTitle()).contains(q));
        });

        Label searchIcon = new Label();
        searchIcon.setGraphic(IkonUtil.duotone(BoxiconsSolid.SEARCH, BoxiconsRegular.SEARCH, 14));
        HBox searchRow = new HBox(6, searchIcon, searchField);
        searchRow.setAlignment(Pos.CENTER_RIGHT);
        searchRow.setPadding(new Insets(8, 28, 6, 28));

        boolean useGrid = modern && !"Mashup".equals(group.getType());
        ListView<?> songList = useGrid
            ? buildSongGrid(group, filteredSongs, searchField, onBrowser, onPlaySong, onOpenPaused,
                libraryService, onToast, onPinChanged, masterActive, onAddToParty)
            : buildClassicSongList(group, filteredSongs, searchField, mashupSel, mashupPlayBtn,
                onBrowser, onPlaySong, onOpenPaused, libraryService, onToast, onPinChanged,
                masterActive, onAddToParty, modern);
        VBox.setVgrow(songList, Priority.ALWAYS);

        if (modern) panel.getChildren().addAll(header, searchRow, mashupBar, songList);
        else        panel.getChildren().addAll(header, sep, searchRow, mashupBar, songList);

        // El hero no debe quedarse fijo ocupando espacio mientras se hace scroll en la lista:
        // se contrae y se desvanece en cuanto el ListView (virtualizado, sigue con su propio
        // scroll interno — no se toca su rendimiento) se desplaza del todo arriba.
        if (modern && header instanceof StackPane hero) wireHeroCollapse(hero, songList);
        // Solo en diseño moderno — el clásico se mantiene tal cual estaba, sin tocar su scroll.
        if (modern) installSmoothScroll(songList);
    }

    /** Sustituye el scroll de rueda "a saltos" por defecto del {@code ListView} por uno animado
     *  y con inercia (ease-out): cada muesca de rueda anima el {@code ScrollBar} hacia un nuevo
     *  objetivo en vez de saltar directamente, y si el usuario sigue girando la rueda antes de
     *  que termine, el objetivo se actualiza y la animación en curso se retoma desde donde va —
     *  no se apilan animaciones ni se pelean entre sí. Consume el evento para que el scroll
     *  nativo (instantáneo) no se dispare también. No afecta al rendimiento de la virtualización:
     *  solo anima la posición del scroll, el {@code ListView} sigue virtualizando igual. */
    /** Cuánto se desplaza (en píxeles reales, no en fracción del recorrido) por cada muesca de
     *  la rueda del ratón. */
    private static final double SMOOTH_SCROLL_PX_PER_TICK = 90;

    private static void installSmoothScroll(ListView<?> list) {
        Timeline[] anim   = {null};
        double[]   target = {0};
        list.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            ScrollBar vsb = verticalScrollBar(list);
            if (vsb == null || !vsb.isVisible()) return;
            e.consume();
            // El valor del ScrollBar es una FRACCIÓN (0–1) de todo el contenido, no píxeles —
            // un paso fijo como "0.09" saltaba una barbaridad en una lista larga (9% de,
            // digamos, 3000px son casi 300px de un solo tirón) y casi nada en una corta. Se
            // convierte un salto fijo en píxeles a la fracción equivalente según el contenido
            // real (nº de filas × alto de fila) menos lo que ya cabe en pantalla.
            double cellSize    = list.getFixedCellSize();
            int    itemCount   = list.getItems().size();
            double scrollable  = Math.max(1, cellSize * itemCount - list.getHeight());
            double step        = SMOOTH_SCROLL_PX_PER_TICK / scrollable;

            // "target[0]" solo es fiable como punto de partida mientras la animación anterior
            // SIGUE en marcha (así varias muescas rápidas seguidas encadenan suave desde el
            // destino, no desde el valor a medio camino, que se vería con tirones). Si no hay
            // animación en marcha, el valor real del ScrollBar puede haber cambiado por otra vía
            // (arrastrar la barra lateral a mano, scrollTo programático) sin pasar por aquí —
            // confiar en el target[0] antiguo en ese caso saltaba desde una posición obsoleta.
            boolean animRunning = anim[0] != null && anim[0].getStatus() == Animation.Status.RUNNING;
            double current = animRunning ? target[0] : vsb.getValue();
            double delta = e.getDeltaY() > 0 ? -step : step;
            target[0] = Math.max(0, Math.min(1, current + delta));
            if (anim[0] != null) anim[0].stop();
            anim[0] = new Timeline(new KeyFrame(Duration.millis(260),
                new KeyValue(vsb.valueProperty(), target[0], Interpolator.EASE_OUT)));
            anim[0].play();
        });
    }

    /** Contrae/desvanece {@code hero} cuando el usuario hace scroll hacia abajo en la lista, y
     *  lo restaura al volver arriba del todo. No escucha el valor del {@code ScrollBar} interno
     *  (ver commit anterior con esa versión) — se comprobó en pantalla que la cuadrícula lo deja
     *  colapsado para siempre nada más abrir la playlist: mientras {@code buildSongGrid} todavía
     *  está recalculando cuántas tarjetas caben por fila ({@code cardsPerRowFor}), reparte
     *  {@code filteredSongs} en más "filas" virtuales de las que habrá al final, y ese vaivén de
     *  contenido puede alterar el valor del ScrollBar SIN que el usuario haya tocado nada —
     *  interpretado (mal) como "se ha hecho scroll", colapsaba el banner desde el primer
     *  instante y no había ningún gesto real que lo hiciera "volver arriba" para restaurarlo.
     *  En su lugar, se escucha el evento de scroll real del ratón/trackpad
     *  ({@link ScrollEvent#SCROLL}), que solo se dispara por una acción genuina del usuario —
     *  nunca por cambios de contenido o de layout. */
    private static void wireHeroCollapse(StackPane hero, ListView<?> songList) {
        boolean[] collapsed = {false};
        Runnable animate = () -> {
            double target = collapsed[0] ? 0 : HERO_HEIGHT;
            Timeline tl = new Timeline(new KeyFrame(Duration.millis(180),
                new KeyValue(hero.minHeightProperty(), target),
                new KeyValue(hero.prefHeightProperty(), target),
                new KeyValue(hero.maxHeightProperty(), target),
                new KeyValue(hero.opacityProperty(), collapsed[0] ? 0 : 1)));
            tl.play();
        };
        songList.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            ScrollBar vsb = verticalScrollBar(songList);
            // Si no hay nada que desplazar (la lista entera cabe en el viewport) el gesto de
            // rueda no mueve nada de verdad — no colapsar el banner porque sí.
            if (vsb == null || !vsb.isVisible()) return;
            boolean scrollingDown = e.getDeltaY() < 0;
            if (scrollingDown) {
                if (!collapsed[0]) { collapsed[0] = true; animate.run(); }
                return;
            }
            // Scrollea hacia arriba: solo se restaura si ya está (o queda, tras este gesto) arriba del todo.
            boolean atTop = vsb.getValue() <= 0.02;
            if (atTop && collapsed[0]) { collapsed[0] = false; animate.run(); }
        });
    }

    private static final double GRID_CARD_W      = 150;
    private static final double GRID_CARD_GAP     = 14;
    private static final double GRID_ROW_HEIGHT   = 236;

    /** Cuadrícula de tarjetas para las canciones de una playlist en diseño moderno (no aplica a
     *  grupos tipo Mashup, que siguen usando {@link #buildClassicSongList} con sus badges ①/②).
     *
     * <p>Sigue virtualizada — cada "fila" del {@code ListView} contiene varias tarjetas en
     * horizontal ({@link #cardsPerRowFor}), no una tarjeta por fila, para no perder rendimiento
     * en playlists de cientos/miles de canciones. Las filas se recalculan (no se recrean nodos
     * de más, solo se reparte {@code filteredSongs} en sublistas) cuando cambia el ancho
     * disponible o el contenido filtrado.
     */
    private static ListView<List<Song>> buildSongGrid(LibraryGroup group, FilteredList<Song> filteredSongs,
                                                       TextField searchField, Consumer<String> onBrowser,
                                                       BiConsumer<Song, LibraryGroup> onPlaySong,
                                                       BiConsumer<Song, LibraryGroup> onOpenPaused,
                                                       LibraryService libraryService, Consumer<String> onToast,
                                                       Runnable onPinChanged, ReadOnlyBooleanProperty masterActive,
                                                       Consumer<Song> onAddToParty) {
        ObservableList<List<Song>> gridRows = FXCollections.observableArrayList();
        ListView<List<Song>> songList = new ListView<>(gridRows);
        songList.getStyleClass().add("detail-listview");
        songList.setFixedCellSize(GRID_ROW_HEIGHT);
        songList.setFocusTraversable(false);

        int[] cardsPerRow = {1};
        Runnable rechunk = () -> {
            List<List<Song>> chunks = new ArrayList<>();
            int n = Math.max(1, cardsPerRow[0]);
            for (int i = 0; i < filteredSongs.size(); i += n)
                chunks.add(new ArrayList<>(filteredSongs.subList(i, Math.min(i + n, filteredSongs.size()))));
            // Actualización mínima, NO gridRows.setAll(chunks): un reemplazo total de la lista
            // hace que el ListView virtualizado trate TODAS las filas como cambiadas (aunque su
            // contenido sea idéntico) y reasigne qué celda física renderiza qué fila lógica —
            // esto rompía el seguimiento de "posición anterior" de cada celda para el
            // deslizamiento al arrastrar (una celda podía comparar su fila actual contra el
            // contenido ANTERIOR de una fila totalmente distinta, y el resultado se veía como
            // si canciones sin relación "se movieran" también). Solo se toca (con `.set`) la(s)
            // fila(s) cuyo contenido cambió de verdad — así el ListView solo pide actualizar esa
            // celda concreta, y el resto conserva su identidad de fila con normalidad.
            int oldSize = gridRows.size(), newSize = chunks.size();
            for (int i = 0; i < Math.min(oldSize, newSize); i++) {
                if (!gridRows.get(i).equals(chunks.get(i))) gridRows.set(i, chunks.get(i));
            }
            if (newSize > oldSize) gridRows.addAll(chunks.subList(oldSize, newSize));
            else if (newSize < oldSize) gridRows.remove(newSize, oldSize);
        };
        songList.widthProperty().addListener((obs, old, w) -> {
            int n = cardsPerRowFor(w.doubleValue());
            if (n != cardsPerRow[0]) { cardsPerRow[0] = n; rechunk.run(); }
        });
        filteredSongs.addListener((ListChangeListener<Song>) c -> rechunk.run());
        cardsPerRow[0] = cardsPerRowFor(songList.getWidth());
        rechunk.run();

        // ── Drag-to-reorder state (misma mecánica que la lista clásica, adaptada a columnas) ──
        Song[]     dragging     = {null};
        boolean[]  dragActive   = {false};
        boolean[]  justDragged  = {false};
        boolean[]  scrollDir    = {false};
        double[]   pressScreenY = {0};
        Timeline[] scrollTl     = {null};

        songList.setCellFactory(lv -> {
            ListCell<List<Song>> cell = new ListCell<>() {
                private List<Song> lastRowSongs; // para detectar qué posiciones "heredan" otra canción durante un arrastre
                @Override protected void updateItem(List<Song> rowSongs, boolean empty) {
                    super.updateItem(rowSongs, empty);
                    setPadding(Insets.EMPTY);
                    setStyle("-fx-background-color: transparent; -fx-border-width: 0;");
                    if (empty || rowSongs == null) { setGraphic(null); lastRowSongs = null; return; }
                    // Índice anterior de cada canción DENTRO de esta misma fila — permite
                    // distinguir "se movió de columna en esta misma fila" (se puede deslizar de
                    // verdad, sabemos su posición de píxeles anterior) de "vino de otra fila"
                    // (no hay un delta de píxeles razonable sin tocar otra celda virtualizada;
                    // se usa un fundido en su lugar).
                    Map<Song, Integer> prevIndex = null;
                    if (dragActive[0] && lastRowSongs != null) {
                        prevIndex = new HashMap<>();
                        for (int j = 0; j < lastRowSongs.size(); j++) prevIndex.put(lastRowSongs.get(j), j);
                    }
                    HBox row = new HBox(GRID_CARD_GAP);
                    row.setPadding(new Insets(10, 4, 10, 4));
                    for (int i = 0; i < rowSongs.size(); i++) {
                        Song song = rowSongs.get(i);
                        VBox card = buildSongGridCard(song, group, onBrowser,
                            () -> { if (!justDragged[0]) onPlaySong.accept(song, group); justDragged[0] = false; },
                            () -> onOpenPaused.accept(song, group),
                            libraryService, onToast, onPinChanged, masterActive, onAddToParty);
                        // Igual que en la lista clásica: si esta tarjeta se reconstruye mientras
                        // se está arrastrando precisamente esa canción, debe seguir "levantada" —
                        // pero también se desliza igual que el resto cuando cambia de columna, en
                        // vez de teletransportarse a su nueva posición.
                        if (dragActive[0] && song == dragging[0]) {
                            card.getStyleClass().add("song-grid-card-dragging");
                            card.setScaleX(1.045); card.setScaleY(1.045);
                            if (prevIndex != null) {
                                Integer oldI = prevIndex.get(song);
                                if (oldI != null && oldI != i) {
                                    double deltaX = (oldI - i) * (GRID_CARD_W + GRID_CARD_GAP);
                                    card.setTranslateX(deltaX);
                                    TranslateTransition slide = new TranslateTransition(Duration.millis(180), card);
                                    slide.setToX(0);
                                    slide.setInterpolator(Interpolator.EASE_OUT);
                                    slide.play();
                                } else if (oldI == null) {
                                    card.setOpacity(0);
                                    FadeTransition ft = new FadeTransition(Duration.millis(160), card);
                                    ft.setFromValue(0); ft.setToValue(1);
                                    ft.play();
                                }
                            }
                        } else if (prevIndex != null) {
                            Integer oldI = prevIndex.get(song);
                            if (oldI != null && oldI != i) {
                                // Deslizamiento real desde su posición de columna anterior —
                                // nada de "teletransporte", se ve venir desde donde estaba.
                                double deltaX = (oldI - i) * (GRID_CARD_W + GRID_CARD_GAP);
                                card.setTranslateX(deltaX);
                                TranslateTransition slide = new TranslateTransition(Duration.millis(180), card);
                                slide.setToX(0);
                                slide.setInterpolator(Interpolator.EASE_OUT);
                                slide.play();
                            } else if (oldI == null) {
                                card.setOpacity(0);
                                FadeTransition ft = new FadeTransition(Duration.millis(160), card);
                                ft.setFromValue(0); ft.setToValue(1);
                                ft.play();
                            }
                        }
                        row.getChildren().add(card);
                    }
                    lastRowSongs = rowSongs;
                    setGraphic(row);
                }
            };

            cell.setOnMousePressed(e -> {
                if (e.getTarget() instanceof Button || e.getTarget() instanceof ComboBox || cell.isEmpty()) return;
                if (!searchField.getText().isEmpty()) return;
                List<Song> rowSongs = cell.getItem();
                int col = (int) Math.max(0, Math.min(rowSongs.size() - 1, e.getX() / (GRID_CARD_W + GRID_CARD_GAP)));
                dragging[0] = rowSongs.get(col);
                pressScreenY[0] = e.getScreenY(); dragActive[0] = false;
            });

            cell.setOnMouseDragged(e -> {
                if (dragging[0] == null) return;
                if (!dragActive[0]) {
                    if (Math.abs(e.getScreenY() - pressScreenY[0]) < 8) return;
                    dragActive[0] = true; justDragged[0] = false; songList.setCursor(Cursor.CLOSED_HAND);
                    // Levantamiento suave y elegante al empezar a arrastrar de verdad.
                    Node draggedCard = findCardForSong(cell.getGraphic(), dragging[0]);
                    if (draggedCard != null) {
                        draggedCard.getStyleClass().add("song-grid-card-dragging");
                        ScaleTransition lift = new ScaleTransition(Duration.millis(120), draggedCard);
                        lift.setToX(1.045); lift.setToY(1.045);
                        lift.play();
                    }
                }

                Bounds  lb     = songList.localToScene(songList.getBoundsInLocal());
                double  mouseY = e.getSceneY(), top = lb.getMinY(), bot = lb.getMaxY();
                boolean goDown = mouseY > bot - 60, goUp = mouseY < top + 60;

                if (goDown || goUp) {
                    if (scrollTl[0] == null || goDown != scrollDir[0]) {
                        if (scrollTl[0] != null) scrollTl[0].stop();
                        scrollDir[0] = goDown;
                        ScrollBar vsb = verticalScrollBar(songList);
                        if (vsb != null) {
                            double step = goDown ? 0.04 : -0.04;
                            scrollTl[0] = new Timeline(new KeyFrame(Duration.millis(80),
                                ev -> vsb.setValue(Math.max(0, Math.min(1, vsb.getValue() + step)))));
                            scrollTl[0].setCycleCount(Animation.INDEFINITE);
                            scrollTl[0].play();
                        }
                    }
                } else if (scrollTl[0] != null) { scrollTl[0].stop(); scrollTl[0] = null; }

                ScrollBar vsb2      = verticalScrollBar(songList);
                int    totalRows    = gridRows.size();
                double visibleRows  = songList.getHeight() / GRID_ROW_HEIGHT;
                double scroll       = vsb2 != null ? vsb2.getValue() : 0;
                double firstVisRow  = scroll * Math.max(0, totalRows - visibleRows);
                int    rowIdx       = (int) Math.max(0, Math.min(Math.max(0, totalRows - 1),
                                          firstVisRow + Math.max(0, mouseY - top) / GRID_ROW_HEIGHT));
                int    n            = Math.max(1, cardsPerRow[0]);
                double localX       = e.getSceneX() - lb.getMinX();
                int    col          = (int) Math.max(0, Math.min(n - 1, localX / (GRID_CARD_W + GRID_CARD_GAP)));
                int    total        = filteredSongs.size();
                int    targetIdx    = Math.max(0, Math.min(total - 1, rowIdx * n + col));
                int    curIdx       = group.getSongs().indexOf(dragging[0]);
                if (targetIdx != curIdx && curIdx >= 0) {
                    // Mover con un único setAll (no remove()+add() por separado): esas dos
                    // mutaciones disparan DOS notificaciones de cambio seguidas, y en el instante
                    // intermedio (tras el remove, antes del add) la lista tiene una canción menos,
                    // así que TODAS las canciones posteriores a curIdx se desplazan una posición de
                    // más durante ese instante — eso es lo que se veía como "se mueven todas las
                    // canciones" al arrastrar. Con un solo setAll, los listeners (incluido rechunk)
                    // solo ven el estado final correcto, una sola vez.
                    List<Song> reordered = new ArrayList<>(group.getSongs());
                    reordered.remove(curIdx);
                    reordered.add(targetIdx, dragging[0]);
                    group.getSongs().setAll(reordered);
                }
                e.consume();
            });

            cell.setOnMouseReleased(e -> {
                if (dragActive[0]) {
                    justDragged[0] = true; dragActive[0] = false; songList.setCursor(Cursor.DEFAULT);
                    if (scrollTl[0] != null) { scrollTl[0].stop(); scrollTl[0] = null; }
                    // "Asentamiento" elegante al soltar.
                    Node draggedCard = findCardForSong(cell.getGraphic(), dragging[0]);
                    if (draggedCard != null) {
                        draggedCard.getStyleClass().remove("song-grid-card-dragging");
                        ScaleTransition settle = new ScaleTransition(Duration.millis(160), draggedCard);
                        settle.setToX(1.0); settle.setToY(1.0);
                        settle.play();
                    }
                }
                dragging[0] = null;
            });

            return cell;
        });

        return songList;
    }

    /** Busca, dentro de una fila de la cuadrícula, la tarjeta que corresponde a {@code song} —
     *  usada para aplicar/quitar el efecto de "levantada" a la tarjeta concreta que se arrastra
     *  (cada fila contiene varias tarjetas, a diferencia de la lista clásica donde cada celda
     *  es una única fila/canción). */
    private static Node findCardForSong(Node rowGraphic, Song song) {
        if (!(rowGraphic instanceof HBox row) || song == null) return null;
        for (Node n : row.getChildren())
            if (n instanceof VBox card && card.getUserData() == song) return card;
        return null;
    }

    /** Nº de tarjetas que caben por fila para un ancho de lista dado (mínimo 1). */
    private static int cardsPerRowFor(double listWidth) {
        double available = listWidth - 16; // margen de padding/scrollbar de la lista
        return Math.max(1, (int) Math.floor((available + GRID_CARD_GAP) / (GRID_CARD_W + GRID_CARD_GAP)));
    }

    /** Tarjeta individual de canción para {@link #buildSongGrid}: portada, título, duración,
     *  pin flotante sobre la portada, y un botón "⋮" con las acciones menos frecuentes (abrir
     *  pausado, ver en YouTube, añadir a la sala, eliminar) para no saturar una tarjeta pequeña. */
    private static VBox buildSongGridCard(Song song, LibraryGroup group, Consumer<String> onBrowser,
                                          Runnable onPlay, Runnable onOpenPaused,
                                          LibraryService libraryService, Consumer<String> onToast,
                                          Runnable onPinChanged, ReadOnlyBooleanProperty masterActive,
                                          Consumer<Song> onAddToParty) {
        VBox card = new VBox(6); card.getStyleClass().add("song-grid-card");
        card.setUserData(song); // permite localizar esta tarjeta concreta durante el arrastre
        card.setPrefWidth(GRID_CARD_W); card.setMaxWidth(GRID_CARD_W); card.setMinWidth(GRID_CARD_W);

        StackPane coverStack = new StackPane();
        coverStack.setMinSize(GRID_CARD_W, GRID_CARD_W); coverStack.setMaxSize(GRID_CARD_W, GRID_CARD_W);
        boolean hasThumb = song.getThumbnailUrl() != null && !song.getThumbnailUrl().isBlank();
        if (hasThumb) {
            ImageView iv = new ImageView();
            iv.setFitWidth(GRID_CARD_W); iv.setFitHeight(GRID_CARD_W); iv.setPreserveRatio(false);
            Rectangle clip = new Rectangle(GRID_CARD_W, GRID_CARD_W); clip.setArcWidth(14); clip.setArcHeight(14);
            iv.setClip(clip);
            loadThumbClean(iv, song.getThumbnailUrl(), true);
            coverStack.getChildren().add(iv);
        } else {
            Region placeholder = new Region(); placeholder.getStyleClass().add("song-grid-card-placeholder");
            placeholder.setMinSize(GRID_CARD_W, GRID_CARD_W); placeholder.setMaxSize(GRID_CARD_W, GRID_CARD_W);
            Label icon = new Label();
            icon.setGraphic(IkonUtil.duotone(BoxiconsSolid.MUSIC, BoxiconsRegular.MUSIC, 30));
            coverStack.getChildren().addAll(placeholder, icon);
        }

        if (!song.isLocal()) {
            Label dlBadge = new Label();
            FontIcon dlIcon = new FontIcon(BoxiconsSolid.DOWNLOAD); dlIcon.setIconSize(11); dlIcon.getStyleClass().add("icon-secondary");
            dlBadge.setGraphic(dlIcon); dlBadge.getStyleClass().add("dl-badge");
            StackPane.setAlignment(dlBadge, Pos.TOP_LEFT); StackPane.setMargin(dlBadge, new Insets(6, 0, 0, 6));
            coverStack.getChildren().add(dlBadge);
        }

        boolean[] pinned = {libraryService.isSongPinned(song.getVideoId())};
        Button pinBtn = new Button(); pinBtn.getStyleClass().add("song-grid-card-pin-btn");
        if (pinned[0]) pinBtn.getStyleClass().add("song-grid-card-pin-btn-active");
        MainController.ico(pinBtn, pinned[0] ? BoxiconsSolid.BOOKMARK : BoxiconsRegular.BOOKMARK, 12, pinned[0]);
        pinBtn.setOnAction(e -> {
            if (pinned[0]) {
                libraryService.unpinSong(song.getVideoId());
                pinned[0] = false;
                pinBtn.getStyleClass().remove("song-grid-card-pin-btn-active");
                MainController.ico(pinBtn, BoxiconsRegular.BOOKMARK, 12, false);
            } else {
                libraryService.pinSong(song);
                pinned[0] = true;
                pinBtn.getStyleClass().add("song-grid-card-pin-btn-active");
                MainController.ico(pinBtn, BoxiconsSolid.BOOKMARK, 12, true);
            }
            onPinChanged.run();
        });
        StackPane.setAlignment(pinBtn, Pos.TOP_RIGHT); StackPane.setMargin(pinBtn, new Insets(6, 6, 0, 0));
        coverStack.getChildren().add(pinBtn);

        Label titleLbl = new Label(song.getTitle()); titleLbl.getStyleClass().add("song-grid-card-title");
        titleLbl.setWrapText(true); titleLbl.setMaxWidth(GRID_CARD_W); titleLbl.setMinHeight(32); titleLbl.setMaxHeight(32);

        String dur = song.getDuration();
        Label durLbl = new Label(dur != null && !dur.isBlank() ? dur : "—");
        durLbl.getStyleClass().add("song-grid-card-duration");

        Button checklistBtn = new Button(); checklistBtn.getStyleClass().add("row-checklist-btn");
        MainController.ico(checklistBtn, BoxiconsRegular.LIST_UL, 12, false);
        ContextMenu[] checklistMenuRef = {null};
        checklistBtn.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (checklistMenuRef[0] != null && checklistMenuRef[0].isShowing()) { checklistMenuRef[0].hide(); e.consume(); }
        });
        checklistBtn.setOnAction(e -> {
            checklistMenuRef[0] = buildPlaylistChecklist(song, group, libraryService, onToast);
            checklistMenuRef[0].show(checklistBtn, Side.BOTTOM, 0, 0);
        });

        Button moreBtn = new Button(); moreBtn.getStyleClass().add("row-checklist-btn");
        MainController.ico(moreBtn, BoxiconsRegular.DOTS_VERTICAL_ROUNDED, 13, false);
        ContextMenu[] moreMenuRef = {null};
        moreBtn.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (moreMenuRef[0] != null && moreMenuRef[0].isShowing()) { moreMenuRef[0].hide(); e.consume(); }
        });
        moreBtn.setOnAction(e -> {
            ContextMenu menu = new ContextMenu();
            MenuItem openItem = new MenuItem("Abrir en reproductor (pausado)");
            openItem.setOnAction(ev -> onOpenPaused.run());
            menu.getItems().add(openItem);
            if (hasThumb) {
                MenuItem linkItem = new MenuItem("Ver en YouTube");
                linkItem.setOnAction(ev -> onBrowser.accept(song.getVideoId()));
                menu.getItems().add(linkItem);
            }
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem removeItem = new MenuItem("Eliminar de esta lista");
            removeItem.setOnAction(ev -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Eliminar canción"); alert.setHeaderText(null);
                alert.setContentText("¿Eliminar «" + song.getTitle() + "» de «" + group.getName() + "»?");
                alert.showAndWait().filter(r -> r == ButtonType.OK).ifPresent(r -> group.removeSong(song));
            });
            menu.getItems().add(removeItem);
            moreMenuRef[0] = menu;
            menu.show(moreBtn, Side.BOTTOM, 0, 0);
        });

        // Botón propio para añadir a la sala (en vez de vivir dentro del menú "⋮") — solo
        // ocupa espacio mientras haya una sala Party activa como Master.
        Button partyBtn = new Button(); partyBtn.getStyleClass().add("row-link-btn");
        MainController.ico(partyBtn, BoxiconsSolid.MEGAPHONE, 12, false);
        partyBtn.setTooltip(new Tooltip("Añadir a la sala"));
        if (masterActive != null) { partyBtn.visibleProperty().bind(masterActive); partyBtn.managedProperty().bind(masterActive); }
        if (onAddToParty != null) partyBtn.setOnAction(e -> onAddToParty.accept(song));

        Region metaSpacer = new Region(); HBox.setHgrow(metaSpacer, Priority.ALWAYS);
        HBox metaRow = new HBox(2, durLbl, metaSpacer, partyBtn, checklistBtn, moreBtn);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(coverStack, titleLbl, metaRow);
        card.setOnMouseClicked(e -> {
            if (e.getTarget() instanceof Button) return;
            // Click derecho añade directamente a la cola de la party, pero solo si hay una sala
            // activa como Master — de lo contrario esta funcionalidad no existe (sin fallback).
            if (e.getButton() == MouseButton.SECONDARY) {
                if (masterActive != null && masterActive.get() && onAddToParty != null) onAddToParty.accept(song);
                return;
            }
            if (e.getButton() == MouseButton.MIDDLE) { onOpenPaused.run(); return; }
            onPlay.run();
        });
        hoverLift(card, coverStack);
        return card;
    }

    /** Pequeño "levantamiento" al pasar el ratón (mismo patrón que {@code CardBuilder}/
     *  {@code groupCardModern}: sube 4px con una transición corta) más una sombra suave en la
     *  portada — resalta la tarjeta bajo el cursor sin ser un efecto pesado de renderizar
     *  (solo dos propiedades animadas, nada por fotograma más allá de la propia transición). */
    private static void hoverLift(Node card, Node cover) {
        card.setOnMouseEntered(e -> {
            TranslateTransition tt = new TranslateTransition(Duration.millis(140), card); tt.setToY(-4); tt.play();
            cover.getStyleClass().add("song-grid-card-cover-hover");
        });
        card.setOnMouseExited(e -> {
            TranslateTransition tt = new TranslateTransition(Duration.millis(140), card); tt.setToY(0); tt.play();
            cover.getStyleClass().remove("song-grid-card-cover-hover");
        });
    }

    /** Lista clásica (y modo Mashup incluso en diseño moderno, ver {@link #populateDetailPanel}):
     *  un {@code ListView<Song>} virtualizado con una fila por canción. */
    private static ListView<Song> buildClassicSongList(LibraryGroup group, FilteredList<Song> filteredSongs,
                                                        TextField searchField, Song[] mashupSel, Button[] mashupPlayBtn,
                                                        Consumer<String> onBrowser,
                                                        BiConsumer<Song, LibraryGroup> onPlaySong,
                                                        BiConsumer<Song, LibraryGroup> onOpenPaused,
                                                        LibraryService libraryService, Consumer<String> onToast,
                                                        Runnable onPinChanged, ReadOnlyBooleanProperty masterActive,
                                                        Consumer<Song> onAddToParty, boolean modern) {
        double cellSize = modern ? 68 : 56;
        ListView<Song> songList = new ListView<>(filteredSongs);
        songList.getStyleClass().add("detail-listview");
        songList.setFixedCellSize(cellSize);
        songList.setFocusTraversable(false);

        // ── Drag-to-reorder state ─────────────────────────────────────────────
        Song[]     dragging     = {null};
        boolean[]  dragActive   = {false};
        boolean[]  justDragged  = {false};
        boolean[]  scrollDir    = {false};
        double[]   pressScreenY = {0};
        Timeline[] scrollTl     = {null};
        // Última fila (índice) en la que se vio cada canción — compartida entre TODAS las
        // celdas (a diferencia de la cuadrícula, aquí cada celda es una única canción, así que
        // "¿de qué fila venía?" necesita mirar más allá de la propia celda). Permite deslizar de
        // verdad las filas vecinas afectadas por el arrastre en vez de solo desvanecerlas.
        Map<Song, Integer> lastIndexOf = new HashMap<>();

        songList.setCellFactory(lv -> {
            ListCell<Song> cell = new ListCell<>() {
                private Song lastSong; // para detectar cuándo esta celda "hereda" otra canción durante un arrastre
                @Override protected void updateItem(Song song, boolean empty) {
                    super.updateItem(song, empty);
                    setPadding(Insets.EMPTY);
                    setStyle("-fx-background-color: transparent; -fx-border-width: 0;");
                    if (empty || song == null) { setGraphic(null); lastSong = null; return; }
                    // Durante un arrastre, cuando otra canción "cae" en esta celda por el
                    // desplazamiento (no es la que se está arrastrando, que ya tiene su propio
                    // levantamiento), su contenido cambiaba de golpe — un fundido corto lo
                    // suaviza en vez de que parezca un teletransporte.
                    boolean displacedSwap = dragActive[0] && lastSong != null && lastSong != song && song != dragging[0];
                    lastSong = song;
                    int curCellIndex = getIndex();
                    HBox row;
                    Consumer<Song> onMoved = moved -> Platform.runLater(() -> songList.scrollTo(filteredSongs.indexOf(moved)));
                    if ("Mashup".equals(group.getType())) {
                        String badge = song == mashupSel[0] ? "①" : song == mashupSel[1] ? "②" : null;
                        row = detailSongRowMashup(song, group, badge, onBrowser, () -> {
                            if (justDragged[0]) { justDragged[0] = false; return; }
                            if      (song == mashupSel[0]) mashupSel[0] = null;
                            else if (song == mashupSel[1]) mashupSel[1] = null;
                            else if (mashupSel[0] == null) mashupSel[0] = song;
                            else                           mashupSel[1] = song;
                            mashupPlayBtn[0].setDisable(mashupSel[0] == null || mashupSel[1] == null);
                            songList.refresh();
                        }, onMoved, libraryService, onToast, onPinChanged, modern);
                    } else {
                        row = detailSongRow(song, group, onBrowser,
                            () -> { if (!justDragged[0]) onPlaySong.accept(song, group); justDragged[0] = false; },
                            () -> onOpenPaused.accept(song, group), onMoved,
                            libraryService, onToast, onPinChanged,
                            masterActive, onAddToParty, modern);
                    }
                    row.prefWidthProperty().bind(lv.widthProperty().subtract(2));
                    // Si esta fila se está (re)construyendo mientras se arrastra precisamente
                    // esa canción (el contenido se reparte de nuevo en cada paso del arrastre),
                    // debe conservar el aspecto "levantada" sin esperar a un nuevo evento de ratón
                    // — pero también se desliza igual que las filas desplazadas, en vez de
                    // teletransportarse a su nueva posición.
                    if (dragActive[0] && song == dragging[0]) {
                        row.getStyleClass().add(modern ? "detail-song-row-modern-dragging" : "detail-song-row-dragging");
                        row.setScaleX(1.035); row.setScaleY(1.035);
                        Integer oldIdx = lastIndexOf.get(song);
                        if (oldIdx != null && oldIdx != curCellIndex) {
                            double deltaY = (oldIdx - curCellIndex) * cellSize;
                            row.setTranslateY(deltaY);
                            TranslateTransition slide = new TranslateTransition(Duration.millis(180), row);
                            slide.setToY(0);
                            slide.setInterpolator(Interpolator.EASE_OUT);
                            slide.play();
                        }
                    } else if (displacedSwap) {
                        Integer oldIdx = lastIndexOf.get(song);
                        if (oldIdx != null && oldIdx != curCellIndex) {
                            // Deslizamiento real desde su fila anterior — nada de "teletransporte".
                            double deltaY = (oldIdx - curCellIndex) * cellSize;
                            row.setTranslateY(deltaY);
                            TranslateTransition slide = new TranslateTransition(Duration.millis(180), row);
                            slide.setToY(0);
                            slide.setInterpolator(Interpolator.EASE_OUT);
                            slide.play();
                        } else {
                            row.setOpacity(0);
                            FadeTransition ft = new FadeTransition(Duration.millis(160), row);
                            ft.setFromValue(0); ft.setToValue(1);
                            ft.play();
                        }
                    }
                    if (curCellIndex >= 0) lastIndexOf.put(song, curCellIndex);
                    setGraphic(row);
                }
            };

            cell.setOnMousePressed(e -> {
                if (e.getTarget() instanceof Button || e.getTarget() instanceof ComboBox || cell.isEmpty()) return;
                if (!searchField.getText().isEmpty()) return;
                dragging[0] = cell.getItem(); pressScreenY[0] = e.getScreenY(); dragActive[0] = false;
            });

            cell.setOnMouseDragged(e -> {
                if (dragging[0] == null) return;
                if (!dragActive[0]) {
                    if (Math.abs(e.getScreenY() - pressScreenY[0]) < 8) return;
                    dragActive[0] = true; justDragged[0] = false; songList.setCursor(Cursor.CLOSED_HAND);
                    // Levantamiento suave y elegante al empezar a arrastrar de verdad.
                    Node graphic = cell.getGraphic();
                    if (graphic != null) {
                        graphic.getStyleClass().add(modern ? "detail-song-row-modern-dragging" : "detail-song-row-dragging");
                        ScaleTransition lift = new ScaleTransition(Duration.millis(120), graphic);
                        lift.setToX(1.035); lift.setToY(1.035);
                        lift.play();
                    }
                }

                Bounds  lb     = songList.localToScene(songList.getBoundsInLocal());
                double  mouseY = e.getSceneY(), top = lb.getMinY(), bot = lb.getMaxY();
                boolean goDown = mouseY > bot - 60, goUp = mouseY < top + 60;

                if (goDown || goUp) {
                    if (scrollTl[0] == null || goDown != scrollDir[0]) {
                        if (scrollTl[0] != null) scrollTl[0].stop();
                        scrollDir[0] = goDown;
                        ScrollBar vsb = verticalScrollBar(songList);
                        if (vsb != null) {
                            double step = goDown ? 0.04 : -0.04;
                            scrollTl[0] = new Timeline(new KeyFrame(Duration.millis(80),
                                ev -> vsb.setValue(Math.max(0, Math.min(1, vsb.getValue() + step)))));
                            scrollTl[0].setCycleCount(Animation.INDEFINITE);
                            scrollTl[0].play();
                        }
                    }
                } else if (scrollTl[0] != null) { scrollTl[0].stop(); scrollTl[0] = null; }

                ScrollBar vsb2  = verticalScrollBar(songList);
                int   total     = filteredSongs.size();
                double visible  = songList.getHeight() / cellSize;
                double scroll   = vsb2 != null ? vsb2.getValue() : 0;
                double firstVis = scroll * Math.max(0, total - visible);
                int targetIdx   = (int) Math.max(0, Math.min(total - 1, firstVis + Math.max(0, mouseY - top) / cellSize));
                int curIdx      = group.getSongs().indexOf(dragging[0]);
                if (targetIdx != curIdx && curIdx >= 0) {
                    // Ver el comentario equivalente en buildSongGrid: un solo setAll evita el
                    // "salto masivo" que causaba remove()+add() como dos mutaciones separadas.
                    List<Song> reordered = new ArrayList<>(group.getSongs());
                    reordered.remove(curIdx);
                    reordered.add(targetIdx, dragging[0]);
                    group.getSongs().setAll(reordered);
                }
                e.consume();
            });

            cell.setOnMouseReleased(e -> {
                if (dragActive[0]) {
                    justDragged[0] = true; dragActive[0] = false; songList.setCursor(Cursor.DEFAULT);
                    if (scrollTl[0] != null) { scrollTl[0].stop(); scrollTl[0] = null; }
                    // "Asentamiento" elegante al soltar: vuelve a su tamaño normal con una
                    // transición corta en vez de saltar de golpe al estado final.
                    Node graphic = cell.getGraphic();
                    if (graphic != null) {
                        graphic.getStyleClass().remove(modern ? "detail-song-row-modern-dragging" : "detail-song-row-dragging");
                        ScaleTransition settle = new ScaleTransition(Duration.millis(160), graphic);
                        settle.setToX(1.0); settle.setToY(1.0);
                        settle.play();
                    }
                }
                dragging[0] = null;
            });

            return cell;
        });

        return songList;
    }

    // ── Song row ──────────────────────────────────────────────────────────────

    /** Miniatura de fila compartida por {@link #detailSongRow} y {@link #detailSongRowMashup}.
     *  En modo moderno es cuadrada y con esquinas redondeadas (clip); en clásico, rectangular. */
    private static Node buildRowThumb(Song song, boolean modern) {
        double w = 56, h = modern ? 56 : 32;
        boolean hasThumb = song.getThumbnailUrl() != null && !song.getThumbnailUrl().isBlank();
        if (hasThumb) {
            ImageView iv = new ImageView(); iv.getStyleClass().add(modern ? "detail-thumb-modern" : "detail-thumb");
            iv.setFitWidth(w); iv.setFitHeight(h); iv.setPreserveRatio(false);
            if (modern) {
                Rectangle clip = new Rectangle(w, h); clip.setArcWidth(10); clip.setArcHeight(10);
                iv.setClip(clip);
                // Carga a resolución nativa (no pre-escalada a 56x56) para poder recortar las
                // bandas de letterbox reales antes de encajarla en el cuadrado — ver applyCleanViewport.
                loadThumbClean(iv, song.getThumbnailUrl(), true);
            } else {
                try { iv.setImage(new Image(song.getThumbnailUrl(), w, h, false, true, true)); } catch (Exception ignored) {}
            }
            return iv;
        } else {
            Label ph = new Label();
            ph.setGraphic(IkonUtil.duotone(BoxiconsSolid.MUSIC, BoxiconsRegular.MUSIC, modern ? 22 : 18));
            ph.setStyle("-fx-min-width: " + w + "px; -fx-alignment: center;");
            return ph;
        }
    }

    private static HBox detailSongRow(Song song, LibraryGroup group,
                                      Consumer<String> onBrowser, Runnable onPlay,
                                      Runnable onOpenPaused, Consumer<Song> onMoved,
                                      LibraryService libraryService, Consumer<String> onToast,
                                      Runnable onPinChanged,
                                      ReadOnlyBooleanProperty masterActive, Consumer<Song> onAddToParty,
                                      boolean modern) {
        HBox row = new HBox(12); row.getStyleClass().add(modern ? "detail-song-row-modern" : "detail-song-row");
        row.setAlignment(Pos.CENTER_LEFT); row.setPadding(new Insets(0, 16, 0, 16));

        boolean hasThumb = song.getThumbnailUrl() != null && !song.getThumbnailUrl().isBlank();
        row.getChildren().add(buildRowThumb(song, modern));

        Label titleLbl = new Label(song.getTitle()); titleLbl.getStyleClass().add("song-title");
        titleLbl.setMaxWidth(Double.MAX_VALUE); titleLbl.setMinWidth(0);
        HBox.setHgrow(titleLbl, Priority.ALWAYS);
        row.getChildren().add(titleLbl);

        if (!song.isLocal()) {
            Label dlBadge = new Label();
            FontIcon dlIcon = new FontIcon(BoxiconsSolid.DOWNLOAD); dlIcon.setIconSize(12); dlIcon.getStyleClass().add("icon-secondary");
            dlBadge.setGraphic(dlIcon); dlBadge.getStyleClass().add("dl-badge");
            row.getChildren().add(dlBadge);
        }

        String dur = song.getDuration();
        Label durLbl = new Label(dur != null && !dur.isBlank() ? dur : "—");
        durLbl.getStyleClass().add("song-duration"); durLbl.setMinWidth(40);
        row.getChildren().add(durLbl);

        if (hasThumb) {
            Button linkBtn = new Button(); linkBtn.getStyleClass().add("row-link-btn");
            MainController.ico(linkBtn, BoxiconsRegular.LINK_ALT, 14, false);
            linkBtn.setOnAction(e -> onBrowser.accept(song.getVideoId()));
            row.getChildren().add(linkBtn);
        }

        Button openBtn = new Button(); openBtn.getStyleClass().add("row-open-btn");
        MainController.ico(openBtn, BoxiconsRegular.PLAY_CIRCLE, 14, true);
        openBtn.setTooltip(new Tooltip("Abrir en reproductor (pausado)"));
        openBtn.setOnAction(e -> onOpenPaused.run());
        row.getChildren().add(openBtn);

        // Playlist checklist button
        Button checklistBtn = new Button(); checklistBtn.getStyleClass().add("row-checklist-btn");
        MainController.ico(checklistBtn, BoxiconsRegular.LIST_UL, 14, false);
        ContextMenu[] menuRef = {null};
        checklistBtn.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (menuRef[0] != null && menuRef[0].isShowing()) {
                menuRef[0].hide();
                e.consume();
            }
        });
        checklistBtn.setOnAction(e -> {
            menuRef[0] = buildPlaylistChecklist(song, group, libraryService, onToast);
            menuRef[0].show(checklistBtn, Side.BOTTOM, 0, 0);
        });
        row.getChildren().add(checklistBtn);

        // Pin button
        boolean[] pinned = {libraryService.isSongPinned(song.getVideoId())};
        Button pinBtn = new Button();
        pinBtn.getStyleClass().add("row-pin-btn");
        if (pinned[0]) pinBtn.getStyleClass().add("row-pin-btn-active");
        MainController.ico(pinBtn, pinned[0] ? BoxiconsSolid.BOOKMARK : BoxiconsRegular.BOOKMARK, 14, pinned[0]);
        pinBtn.setOnAction(e -> {
            if (pinned[0]) {
                libraryService.unpinSong(song.getVideoId());
                pinned[0] = false;
                pinBtn.getStyleClass().remove("row-pin-btn-active");
                MainController.ico(pinBtn, BoxiconsRegular.BOOKMARK, 14, false);
            } else {
                libraryService.pinSong(song);
                pinned[0] = true;
                pinBtn.getStyleClass().add("row-pin-btn-active");
                MainController.ico(pinBtn, BoxiconsSolid.BOOKMARK, 14, true);
            }
            onPinChanged.run();
        });
        row.getChildren().add(pinBtn);

        if (masterActive != null && onAddToParty != null) {
            Button partyBtn = new Button(); partyBtn.getStyleClass().add("row-link-btn");
            MainController.ico(partyBtn, BoxiconsSolid.MEGAPHONE, 14, false);
            partyBtn.setTooltip(new Tooltip("Añadir a la sala"));
            partyBtn.visibleProperty().bind(masterActive);
            partyBtn.managedProperty().bind(masterActive);
            partyBtn.setOnAction(e -> onAddToParty.accept(song));
            row.getChildren().add(partyBtn);
        }

        Button removeBtn = new Button(); removeBtn.getStyleClass().add("row-remove-btn");
        MainController.ico(removeBtn, BoxiconsRegular.X, 13, false);
        removeBtn.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Eliminar canción");
            alert.setHeaderText(null);
            alert.setContentText("¿Eliminar «" + song.getTitle() + "» de «" + group.getName() + "»?");
            alert.showAndWait().filter(r -> r == ButtonType.OK).ifPresent(r -> group.removeSong(song));
        });
        row.getChildren().add(removeBtn);

        row.setOnMouseClicked(e -> {
            if (e.getTarget() instanceof Button) return;
            if (e.getButton() == MouseButton.MIDDLE) { onOpenPaused.run(); return; }
            onPlay.run();
        });
        return row;
    }

    // ── Mashup song row (selection mode) ─────────────────────────────────────

    private static HBox detailSongRowMashup(Song song, LibraryGroup group,
                                            String selBadge, Consumer<String> onBrowser,
                                            Runnable onSelect, Consumer<Song> onMoved,
                                            LibraryService libraryService, Consumer<String> onToast,
                                            Runnable onPinChanged, boolean modern) {
        HBox row = new HBox(12); row.getStyleClass().add(modern ? "detail-song-row-modern" : "detail-song-row");
        row.setAlignment(Pos.CENTER_LEFT); row.setPadding(new Insets(0, 16, 0, 16));
        if (selBadge != null)
            row.getStyleClass().add("①".equals(selBadge) ? "mashup-row-sel-a" : "mashup-row-sel-b");

        boolean hasThumb = song.getThumbnailUrl() != null && !song.getThumbnailUrl().isBlank();
        row.getChildren().add(buildRowThumb(song, modern));

        Label titleLbl = new Label(song.getTitle()); titleLbl.getStyleClass().add("song-title");
        titleLbl.setMaxWidth(Double.MAX_VALUE); titleLbl.setMinWidth(0);
        HBox.setHgrow(titleLbl, Priority.ALWAYS);
        row.getChildren().add(titleLbl);

        if (!song.isLocal()) {
            Label dlBadge = new Label();
            FontIcon dlIconM = new FontIcon(BoxiconsSolid.DOWNLOAD); dlIconM.setIconSize(12); dlIconM.getStyleClass().add("icon-secondary");
            dlBadge.setGraphic(dlIconM); dlBadge.getStyleClass().add("dl-badge");
            row.getChildren().add(dlBadge);
        }

        String durM = song.getDuration();
        Label durLbl = new Label(durM != null && !durM.isBlank() ? durM : "—");
        durLbl.getStyleClass().add("song-duration"); durLbl.setMinWidth(40);
        row.getChildren().add(durLbl);

        if (hasThumb) {
            Button linkBtn = new Button(); linkBtn.getStyleClass().add("row-link-btn");
            MainController.ico(linkBtn, BoxiconsRegular.LINK_ALT, 14, false);
            linkBtn.setOnAction(e -> onBrowser.accept(song.getVideoId()));
            row.getChildren().add(linkBtn);
        }

        // Selection badge button
        String btnText = selBadge != null ? selBadge : "○";
        Button selBtn = new Button(btnText); selBtn.getStyleClass().add("mashup-sel-btn");
        if (selBadge != null) selBtn.getStyleClass().add("①".equals(selBadge) ? "mashup-sel-btn-active-a" : "mashup-sel-btn-active-b");
        selBtn.setOnAction(e -> onSelect.run());
        row.getChildren().add(selBtn);

        // Playlist checklist
        Button checklistBtn = new Button(); checklistBtn.getStyleClass().add("row-checklist-btn");
        MainController.ico(checklistBtn, BoxiconsRegular.LIST_UL, 14, false);
        ContextMenu[] menuRef = {null};
        checklistBtn.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (menuRef[0] != null && menuRef[0].isShowing()) { menuRef[0].hide(); e.consume(); }
        });
        checklistBtn.setOnAction(e -> {
            menuRef[0] = buildPlaylistChecklist(song, group, libraryService, onToast);
            menuRef[0].show(checklistBtn, Side.BOTTOM, 0, 0);
        });
        row.getChildren().add(checklistBtn);

        // Pin button
        boolean[] pinnedM = {libraryService.isSongPinned(song.getVideoId())};
        Button pinBtnM = new Button();
        pinBtnM.getStyleClass().add("row-pin-btn");
        if (pinnedM[0]) pinBtnM.getStyleClass().add("row-pin-btn-active");
        MainController.ico(pinBtnM, pinnedM[0] ? BoxiconsSolid.BOOKMARK : BoxiconsRegular.BOOKMARK, 14, pinnedM[0]);
        pinBtnM.setOnAction(e -> {
            if (pinnedM[0]) {
                libraryService.unpinSong(song.getVideoId());
                pinnedM[0] = false;
                pinBtnM.getStyleClass().remove("row-pin-btn-active");
                MainController.ico(pinBtnM, BoxiconsRegular.BOOKMARK, 14, false);
            } else {
                libraryService.pinSong(song);
                pinnedM[0] = true;
                pinBtnM.getStyleClass().add("row-pin-btn-active");
                MainController.ico(pinBtnM, BoxiconsSolid.BOOKMARK, 14, true);
            }
            onPinChanged.run();
        });
        row.getChildren().add(pinBtnM);

        Button removeBtn = new Button(); removeBtn.getStyleClass().add("row-remove-btn");
        MainController.ico(removeBtn, BoxiconsRegular.X, 13, false);
        removeBtn.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Eliminar canción");
            alert.setHeaderText(null);
            alert.setContentText("¿Eliminar «" + song.getTitle() + "» de «" + group.getName() + "»?");
            alert.showAndWait().filter(r -> r == ButtonType.OK).ifPresent(r -> group.removeSong(song));
        });
        row.getChildren().add(removeBtn);

        row.setOnMouseClicked(e -> { if (!(e.getTarget() instanceof Button)) onSelect.run(); });
        return row;
    }

    // ── Playlist checklist popup ──────────────────────────────────────────────

    private static ContextMenu buildPlaylistChecklist(Song song, LibraryGroup currentGroup,
                                                      LibraryService libraryService, Consumer<String> onToast) {
        ContextMenu menu = new ContextMenu();
        for (LibraryGroup g : libraryService.getGroups()) {
            boolean inGroup = g.getSongs().stream().anyMatch(s -> s.getVideoId().equals(song.getVideoId()));
            CheckMenuItem item = new CheckMenuItem(g.getName());
            FontIcon menuItemIcon = new FontIcon(g.isYoutubePlaylist() ? BoxiconsSolid.TV : BoxiconsSolid.MUSIC);
            menuItemIcon.setIconSize(13); menuItemIcon.getStyleClass().add("icon-secondary");
            item.setGraphic(menuItemIcon);
            item.setSelected(inGroup);
            item.setOnAction(ev -> {
                if (item.isSelected()) {
                    if (!g.getSongs().stream().anyMatch(s -> s.getVideoId().equals(song.getVideoId()))) {
                        song.setType(g.getType());
                        g.getSongs().add(0, song);
                        onToast.accept("Añadido a «" + g.getName() + "»");
                    }
                } else {
                    // Remove from this playlist
                    if (g == currentGroup) {
                        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                        alert.setTitle("Eliminar canción");
                        alert.setHeaderText(null);
                        alert.setContentText("¿Eliminar «" + song.getTitle() + "» de la lista actual «" + g.getName() + "»?");
                        alert.showAndWait()
                            .filter(r -> r == ButtonType.OK)
                            .ifPresent(r -> {
                                g.removeSong(song);
                                onToast.accept("Eliminado de «" + g.getName() + "»");
                            });
                    } else {
                        g.removeSong(song);
                        onToast.accept("Eliminado de «" + g.getName() + "»");
                    }
                }
            });
            menu.getItems().add(item);
        }
        if (menu.getItems().isEmpty()) {
            MenuItem empty = new MenuItem("(Sin listas de reproducción)");
            empty.setDisable(true);
            menu.getItems().add(empty);
        }
        return menu;
    }

    // ── Type ComboBox ─────────────────────────────────────────────────────────

    private static ComboBox<String> buildTypeCombo(LibraryGroup group) {
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().addAll(TYPES);
        combo.setValue(group.getType());
        combo.getStyleClass().add("type-combo");
        combo.setPrefWidth(110);
        combo.setOnAction(e -> group.setType(combo.getValue()));
        // Prevent click on combo from propagating as row click
        combo.setOnMouseClicked(javafx.event.Event::consume);
        return combo;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Quita (si existe) el {@link ChangeListener} guardado bajo {@code key} en
     *  {@code panel.getProperties()} — ver el bloque al principio de {@link #populateDetailPanel}. */
    private static <T> void detachChangeListener(VBox panel, String key, javafx.beans.value.ObservableValue<T> prop) {
        Object l = panel.getProperties().get(key);
        if (l instanceof ChangeListener<?>) {
            @SuppressWarnings("unchecked")
            ChangeListener<T> typed = (ChangeListener<T>) l;
            prop.removeListener(typed);
        }
    }

    /** Igual que {@link #detachChangeListener} pero para un {@link ListChangeListener} sobre
     *  una {@code ObservableList<Song>} (p.ej. {@code group.getSongs()}). */
    private static void detachListChangeListener(VBox panel, String key, ObservableList<Song> list) {
        Object l = panel.getProperties().get(key);
        if (l instanceof ListChangeListener<?>) {
            @SuppressWarnings("unchecked")
            ListChangeListener<Song> typed = (ListChangeListener<Song>) l;
            list.removeListener(typed);
        }
    }

    private static ScrollBar verticalScrollBar(ListView<?> list) {
        for (javafx.scene.Node n : list.lookupAll(".scroll-bar"))
            if (n instanceof ScrollBar sb && sb.getOrientation() == Orientation.VERTICAL) return sb;
        return null;
    }

    private static void moveInList(ObservableList<Song> list, Song song, int delta) {
        int i = list.indexOf(song);
        int j = Math.max(0, Math.min(list.size() - 1, i + delta));
        if (j != i) { list.remove(i); list.add(j, song); }
    }
}
