package com.musicplayer.controllers;

import com.musicplayer.models.LibraryGroup;
import com.musicplayer.models.Song;
import com.musicplayer.models.YouTubePlaylistInfo;
import com.musicplayer.services.ConfigLoader;
import com.musicplayer.services.DownloadService;
import com.musicplayer.services.LibraryService;
import com.musicplayer.services.YouTubeService;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.shape.Circle;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;

import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controlador principal de la aplicación Bardo.
 *
 * <p>Actúa como coordinador central: gestiona el sistema de pestañas, los reproductores
 * activos, la pantalla de inicio, la búsqueda en YouTube, la biblioteca local y las
 * descargas de audio.
 *
 * <p><b>Multi-reproductor:</b> cada canción abre una nueva {@link PlayerInstance} con su
 * propio {@code MediaPlayer} y panel completo. El reproductor enfocado ({@code focusedPlayer})
 * es el que controla la barra inferior "Now Playing". El foco cambia con el botón ⇆ o
 * activando la pestaña correspondiente.
 *
 * <p><b>Bucle manual:</b> {@code cycleCount} es siempre 1. El bucle se gestiona en
 * {@code setOnEndOfMedia}: si {@code pi.looping == true} hace seek a cero y vuelve a
 * reproducir; de lo contrario llama a {@link #onSongEnded}.
 *
 * <p><b>Forma de onda en tiempo real:</b> el {@code AudioSpectrumListener} del
 * {@code MediaPlayer} alimenta 32 bandas de frecuencia a {@link #drawWaveCanvas} a 30 fps.
 * El espectro es simétrico: el centro corresponde a los graves (banda 0) y los bordes
 * a los agudos. Al pausar, {@link #startWaveDecay} anima la bajada de las barras.
 *
 * <p><b>Pantalla de inicio:</b> {@link #refreshHomePanel()} muestra las canciones pineadas
 * (sección "CANCIONES PINEADAS") y las tres colecciones más reproducidas con ≥ 5
 * reproducciones (sección "COLECCIONES MÁS ESCUCHADAS"). El contador de uso se incrementa
 * en {@link #downloadAndPlay} cada vez que se reproduce una canción de un grupo.
 *
 * <p><b>Ventana redimensionable:</b> el stage usa {@code UNDECORATED}; {@link ResizeHelper}
 * añade redimensionado por bordes. Doble clic en la barra de título maximiza/restaura;
 * arrastrar hasta el borde superior activa pantalla completa. {@code F11} la alterna.
 */
public class MainController implements Initializable {

    // ── FXML fields ───────────────────────────────────────────────────────────
    @FXML private HBox   titleBar;
    @FXML private Button btnClose, btnMinimize, btnMaximize;

    @FXML private VBox             sidebar;
    @FXML private Button           btnHome, btnLibrary, btnSettings, btnNewGroup;
    @FXML private ListView<String> groupListView;

    @FXML private ScrollPane tabBarScroll;
    @FXML private HBox       tabBar;

    @FXML private StackPane contentArea;
    @FXML private VBox      homePanel, searchPanel, libraryPanel, settingsPanel;

    @FXML private TextField         searchField;
    @FXML private Button            btnSearchGo, btnSearchVideos, btnSearchPlaylists;
    @FXML private FlowPane          searchResultsPane;
    @FXML private Label             searchStatusLabel;
    @FXML private ProgressIndicator searchSpinner;

    @FXML private VBox libraryGroupsContainer;

    @FXML private HBox      nowPlayingBar;
    @FXML private ImageView albumArt;
    @FXML private Label     nowPlayingTitle, nowPlayingArtist;
    @FXML private Button    btnPrev, btnPlayPause, btnNext, btnShuffle, btnRepeat, btnVolume;
    @FXML private Slider    progressSlider, volumeSlider;
    @FXML private Label     timeElapsed, timeTotal, volumeLabel;
    @FXML private StackPane vinylContainer;
    @FXML private javafx.scene.canvas.Canvas miniWaveCanvas;

    private final List<Timeline> homeCarouselTimelines = new ArrayList<>();

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean isShuffle, searchPlaylistMode, seekingByUser, titleBarDragging, fakeFullScreen;
    private double  windowX, windowY, savedStageX, savedStageY, savedStageW, savedStageH;

    private final java.util.Map<String, String> currentTheme    = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, String> baseTheme       = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, String> themeVarModes   = new java.util.LinkedHashMap<>();
    private boolean dynamicColorsEnabled = true;
    private final java.util.Map<String, Timeline> colorFadeTimelines = new java.util.HashMap<>();
    private PauseTransition themeSavePause;

    private static final String DYN_STATIC    = "static";
    private static final String DYN_PRIMARY   = "primary";
    private static final String DYN_SECONDARY = "secondary";

    private boolean textContrastEnabled = true;
    private static final double CONTRAST_THRESHOLD = 3.0;

    private static final String[][] THEME_VARS = {
        {"bardo-bg",         "#faf8fc", "Fondo principal"},
        {"bardo-sidebar-bg", "#f5f0fc", "Barra lateral"},
        {"bardo-accent",     "#f4a7b9", "Acento principal"},
        {"bardo-accent2",    "#9dc4e8", "Acento secundario"},
        {"bardo-text",       "#5a4a6a", "Texto principal"},
        {"bardo-text-muted", "#a090b0", "Texto secundario"},
        {"bardo-player-bg1", "#fdf6ff", "Reproductor (superior)"},
        {"bardo-player-bg2", "#f0e8ff", "Reproductor (inferior)"},
    };

    private ResizeHelper     resizeHelper;
    private Timeline         globalProgressTimer;
    private PauseTransition  volumeSavePause;
    private javafx.stage.Popup toastPopup;
    private SequentialTransition toastAnim;

    private YouTubeService  youTubeService;
    private LibraryService  libraryService;
    private DownloadService downloadService;

    private final Set<String>        downloadingNow = new HashSet<>();
    private final List<AppTab>       openTabs       = new ArrayList<>();
    private final List<PlayerInstance> activePlayers = new ArrayList<>();
    private AppTab         activeTab;
    private PlayerInstance focusedPlayer;

    // Ambient ducking
    private double ambientDuckRatio = 0.60;
    private PauseTransition ambientDuckSavePause;

    // Tab drag-reorder state
    private AppTab  tabDragging;
    private double  tabDragStartX;
    private int     tabDragOrigIdx;
    private int     tabDragTargetIdx;
    private boolean tabDragActive;   // true only after crossing the movement threshold
    private boolean tabJustDragged;

    // ── Initialize ────────────────────────────────────────────────────────────

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        libraryService  = LibraryService.getInstance();
        java.util.Map<String, String> savedTheme = libraryService.loadTheme();
        java.util.Map<String, String> savedModes = libraryService.loadThemeVarModes();
        dynamicColorsEnabled = libraryService.loadDynamicColorsEnabled();
        textContrastEnabled  = libraryService.loadTextContrastEnabled();
        for (String[] tv : THEME_VARS) {
            String c = savedTheme.getOrDefault(tv[0], tv[1]);
            baseTheme.put(tv[0], c);
            currentTheme.put(tv[0], c);
            themeVarModes.put(tv[0], savedModes.getOrDefault(tv[0], defaultModeFor(tv[0])));
        }
        String apiKey   = libraryService.loadYouTubeApiKey();
        if (apiKey == null || apiKey.isBlank()) apiKey = ConfigLoader.get("youtube.api.key");
        youTubeService  = new YouTubeService(apiKey);
        downloadService = new DownloadService();

        setupTitleBar();
        setupWindowDrag();
        setupProgressSlider();
        setupVolumeSlider();
        volumeSlider.setValue(libraryService.loadVolume());
        setupSidebarNavigation();
        setupSettingsPanel();
        ambientDuckRatio = libraryService.loadAmbientDuck() / 100.0;
        applyCircularClip();

        btnPlayPause.setOnAction(e -> { if (focusedPlayer != null) togglePlayInstance(focusedPlayer); });
        btnShuffle.setOnAction(e   -> toggleShuffle());
        btnRepeat.setOnAction(e    -> toggleRepeat());
        btnPrev.setOnAction(e      -> playPrevInInstance(focusedPlayer));
        btnNext.setOnAction(e      -> playNextInInstance(focusedPlayer));

        nowPlayingBar.setVisible(false); nowPlayingBar.setManaged(false);
        contentArea.sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) return;
            applyTheme(scene);
            scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onGlobalKey);
            // Attach resize helper and store stage reference once the window is known
            scene.windowProperty().addListener((obs2, old2, win) -> {
                if (win instanceof javafx.stage.Stage st) {
                    resizeHelper = ResizeHelper.attach(st, scene);
                    syncMaximizeIcon(st);
                    st.maximizedProperty().addListener((o, wasMax, isMax) -> syncMaximizeIcon(st));
                }
            });
        });

        globalProgressTimer = new Timeline(new KeyFrame(Duration.millis(200), e -> {
            for (PlayerInstance pi : new ArrayList<>(activePlayers)) {
                if (pi.mediaPlayer == null || !pi.isPlaying) continue;
                Duration cur = pi.mediaPlayer.getCurrentTime();
                if (cur != null) updateProgressUI(pi, cur);
            }
        }));
        globalProgressTimer.setCycleCount(Animation.INDEFINITE);
        globalProgressTimer.play();

        contentArea.getChildren().forEach(n -> { n.setVisible(false); n.setManaged(false); n.setMouseTransparent(true); });

        tabBarScroll.setOnScroll(e -> {
            if (e.getDeltaY() == 0) return;
            double cw = tabBar.getBoundsInLocal().getWidth(), vw = tabBarScroll.getViewportBounds().getWidth();
            if (cw <= vw) return;
            tabBarScroll.setHvalue(Math.max(0, Math.min(1, tabBarScroll.getHvalue() - e.getDeltaY() / (cw - vw))));
            e.consume();
        });

        refreshHomePanel();
        openTab("home", "🏠", "Inicio", homePanel, true, btnHome);
        animateEntrance();
        Platform.runLater(this::checkApiKey);
    }

    private void checkApiKey() {
        String key = libraryService.loadYouTubeApiKey();
        if (key != null && !key.isBlank()) return;

        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Clave de API requerida");
        alert.setHeaderText("No hay ninguna clave de API de YouTube configurada");
        alert.setContentText(
            "Sin ella no podrás buscar canciones ni importar playlists de YouTube.\n\n" +
            "Pulsa «Ir a Configuración» para introducirla.");
        ButtonType goBtn     = new ButtonType("Ir a Configuración", ButtonBar.ButtonData.OK_DONE);
        ButtonType laterBtn  = new ButtonType("Ahora no",            ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(goBtn, laterBtn);
        alert.showAndWait().ifPresent(bt -> {
            if (bt.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                openTab("settings", "⚙", "Configuración", settingsPanel, true, btnSettings);
        });
    }

    private void applyCircularClip() {
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(78, 44);
        clip.setArcWidth(10); clip.setArcHeight(10);
        albumArt.setClip(clip);
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private void setupTitleBar() {
        btnClose.setOnAction(e -> {
            if (globalProgressTimer != null) globalProgressTimer.stop();
            activePlayers.forEach(pi -> { if (pi.mediaPlayer != null) pi.mediaPlayer.stop(); });
            Platform.exit();
        });
        btnMinimize.setOnAction(e -> stage().setIconified(true));
        btnMaximize.setOnAction(e -> {
            javafx.stage.Stage st = stage();
            if (fakeFullScreen) {
                setFakeFullScreen(st, false);
            } else {
                st.setMaximized(!st.isMaximized());
            }
        });
    }

    /** Obtiene el {@code Stage} principal a partir de cualquier nodo ya adjunto. */
    private javafx.stage.Stage stage() {
        return (javafx.stage.Stage) btnClose.getScene().getWindow();
    }

    /** Sincroniza el icono del botón maximizar con el estado actual de la ventana. */
    private void syncMaximizeIcon(javafx.stage.Stage st) {
        Platform.runLater(() -> btnMaximize.setText(
            (st.isMaximized() || fakeFullScreen) ? "❐" : ""
        ));
    }

    private void setupWindowDrag() {
        titleBar.setOnMousePressed(e -> {
            titleBarDragging = false;
            if (resizeHelper != null && resizeHelper.isActive()) return;
            if (stage().isMaximized() || fakeFullScreen) return;
            windowX = e.getSceneX(); windowY = e.getSceneY();
        });
        titleBar.setOnMouseDragged(e -> {
            if (resizeHelper != null && resizeHelper.isActive()) return;
            titleBarDragging = true;
            javafx.stage.Stage st = stage();
            if (fakeFullScreen) {
                // Exit fake fullscreen when dragged more than 60 px below this screen's top edge
                javafx.stage.Screen scr = screenAt(e.getScreenX(), e.getScreenY());
                if (e.getScreenY() > scr.getBounds().getMinY() + 60) {
                    double ratio = (e.getScreenX() - scr.getBounds().getMinX()) / scr.getBounds().getWidth();
                    setFakeFullScreen(st, false);
                    windowX = st.getWidth() * ratio;
                    windowY = e.getSceneY();
                    // fall through to reposition
                } else {
                    return;
                }
            }
            if (st.isMaximized()) {
                double ratio = e.getScreenX() / st.getWidth();
                st.setMaximized(false);
                windowX = st.getWidth() * ratio;
                windowY = e.getSceneY();
            }
            st.setX(e.getScreenX() - windowX);
            st.setY(e.getScreenY() - windowY);
        });
        titleBar.setOnMouseReleased(e -> {
            if (titleBarDragging) {
                // Snap to fullscreen when released within 10 px of the top edge of any screen
                javafx.stage.Screen scr = screenAt(e.getScreenX(), e.getScreenY());
                if (e.getScreenY() <= scr.getBounds().getMinY() + 10) {
                    setFakeFullScreen(stage(), true, scr);
                }
            }
            titleBarDragging = false;
        });
        titleBar.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) btnMaximize.fire();
        });
    }

    /** Returns the screen that contains the given screen-coordinate point. */
    private javafx.stage.Screen screenAt(double sx, double sy) {
        return javafx.stage.Screen.getScreensForRectangle(sx, sy, 1, 1)
                .stream().findFirst().orElse(javafx.stage.Screen.getPrimary());
    }

    private void applyTheme(javafx.scene.Scene sc) {
        StringBuilder sb = new StringBuilder();
        currentTheme.forEach((k, v) -> sb.append(k).append(": ").append(v).append("; "));
        sc.getRoot().setStyle(sb.toString());
        applyContrastStrokes();
    }

    private void applyContrastStrokes() {
        applyContrastStroke(sidebar,       "bardo-text", "bardo-sidebar-bg");
        applyContrastStroke(tabBarScroll,  "bardo-text", "bardo-sidebar-bg");
        applyContrastStroke(nowPlayingBar, "bardo-text", "bardo-player-bg1");
        Set<javafx.scene.Node> playerPanels = activePlayers.stream()
            .filter(pi -> pi.panel != null)
            .map(pi -> (javafx.scene.Node) pi.panel)
            .collect(Collectors.toSet());
        for (javafx.scene.Node child : contentArea.getChildren()) {
            if (playerPanels.contains(child))
                applyContrastStroke(child, "bardo-text", "bardo-player-bg1");
            else
                applyContrastStroke(child, "bardo-text", "bardo-bg");
        }
    }

    private void applyContrastStroke(javafx.scene.Node container, String textVar, String bgVar) {
        if (container == null) return;
        container.getStyleClass().removeAll("bardo-contrast-dark", "bardo-contrast-light");
        if (!textContrastEnabled) return;
        javafx.scene.paint.Color tc = tryParseColor(currentTheme.getOrDefault(textVar, "#5a4a6a"));
        javafx.scene.paint.Color bc = tryParseColor(currentTheme.getOrDefault(bgVar,   "#faf8fc"));
        if (contrastRatio(tc, bc) >= CONTRAST_THRESHOLD) return;
        double avgLum = (relativeLuminance(tc) + relativeLuminance(bc)) / 2.0;
        container.getStyleClass().add(avgLum > 0.5 ? "bardo-contrast-dark" : "bardo-contrast-light");
    }

    private static double contrastRatio(javafx.scene.paint.Color a, javafx.scene.paint.Color b) {
        double la = relativeLuminance(a) + 0.05, lb = relativeLuminance(b) + 0.05;
        return la > lb ? la / lb : lb / la;
    }

    private static double relativeLuminance(javafx.scene.paint.Color c) {
        return 0.2126 * linearize(c.getRed()) + 0.7152 * linearize(c.getGreen()) + 0.0722 * linearize(c.getBlue());
    }

    private static double linearize(double ch) {
        return ch <= 0.04045 ? ch / 12.92 : Math.pow((ch + 0.055) / 1.055, 2.4);
    }

    private void applyTheme() {
        if (contentArea.getScene() != null) applyTheme(contentArea.getScene());
    }

    private void debouncedSaveTheme() {
        if (themeSavePause == null) {
            themeSavePause = new PauseTransition(Duration.millis(500));
            themeSavePause.setOnFinished(e -> libraryService.saveTheme(baseTheme));
        }
        themeSavePause.playFromStart();
    }

    private static String defaultModeFor(String varName) {
        return switch (varName) {
            case "bardo-accent", "bardo-player-bg1"  -> DYN_PRIMARY;
            case "bardo-accent2", "bardo-player-bg2" -> DYN_SECONDARY;
            default -> DYN_STATIC;
        };
    }

    private static javafx.scene.paint.Color tryParseColor(String hex) {
        try { return javafx.scene.paint.Color.web(hex); }
        catch (Exception e) { return javafx.scene.paint.Color.web("#ffffff"); }
    }

    private static String colorToHex(javafx.scene.paint.Color c) {
        return String.format("#%02x%02x%02x",
            (int) Math.round(c.getRed()   * 255),
            (int) Math.round(c.getGreen() * 255),
            (int) Math.round(c.getBlue()  * 255));
    }

    /** Fades a single theme variable from its current value to {@code toHex} over ~700 ms. */
    private void fadeThemeVar(String varName, String toHex) {
        String fromHex = currentTheme.getOrDefault(varName, toHex);
        Timeline existing = colorFadeTimelines.get(varName);
        if (existing != null) existing.stop();
        if (fromHex.equalsIgnoreCase(toHex)) {
            currentTheme.put(varName, toHex); applyTheme(); return;
        }
        try {
            int fr = Integer.parseInt(fromHex.substring(1, 3), 16);
            int fg = Integer.parseInt(fromHex.substring(3, 5), 16);
            int fb = Integer.parseInt(fromHex.substring(5, 7), 16);
            int tr = Integer.parseInt(toHex.substring(1, 3), 16);
            int tg = Integer.parseInt(toHex.substring(3, 5), 16);
            int tb = Integer.parseInt(toHex.substring(5, 7), 16);
            Timeline tl = new Timeline();
            int STEPS = 20;
            for (int i = 1; i <= STEPS; i++) {
                final double t = (double) i / STEPS;
                final int r = (int) Math.round(fr + (tr - fr) * t);
                final int g = (int) Math.round(fg + (tg - fg) * t);
                final int b = (int) Math.round(fb + (tb - fb) * t);
                tl.getKeyFrames().add(new KeyFrame(Duration.millis(700 * t), ev -> {
                    currentTheme.put(varName, String.format("#%02x%02x%02x", r, g, b));
                    applyTheme();
                }));
            }
            colorFadeTimelines.put(varName, tl);
            tl.setOnFinished(e -> colorFadeTimelines.remove(varName));
            tl.play();
        } catch (Exception ex) {
            currentTheme.put(varName, toHex); applyTheme();
        }
    }

    private void fadeAllToBase() {
        for (String[] tv : THEME_VARS) {
            String varName = tv[0];
            if (DYN_STATIC.equals(themeVarModes.getOrDefault(varName, DYN_STATIC))) continue;
            fadeThemeVar(varName, baseTheme.getOrDefault(varName, tv[1]));
        }
    }

    private void updateDynamicColors() {
        if (!dynamicColorsEnabled) { fadeAllToBase(); return; }
        boolean anyDynamic = themeVarModes.values().stream().anyMatch(m -> !DYN_STATIC.equals(m));
        if (!anyDynamic) return;
        PlayerInstance pi = focusedPlayer;
        if (pi == null || !pi.isPlaying || pi.song == null ||
                pi.song.getThumbnailUrl() == null || pi.song.getThumbnailUrl().isBlank()) {
            fadeAllToBase(); return;
        }
        final String thumbUrl = pi.song.getThumbnailUrl();
        final PlayerInstance captured = pi;
        Thread extractor = new Thread(() -> {
            try {
                javafx.scene.image.Image img = new javafx.scene.image.Image(thumbUrl, 128, 128, false, true);
                List<javafx.scene.paint.Color> colors = img.isError() ? List.of() : extractDominantColors(img, 2);
                Platform.runLater(() -> {
                    if (focusedPlayer != captured) return;
                    if (colors.isEmpty()) { fadeAllToBase(); return; }
                    applyDynamicColors(colors);
                });
            } catch (Exception e) {
                Platform.runLater(this::fadeAllToBase);
            }
        }, "bardo-color-extractor");
        extractor.setDaemon(true);
        extractor.start();
    }

    private void applyDynamicColors(List<javafx.scene.paint.Color> colors) {
        for (String[] tv : THEME_VARS) {
            String varName = tv[0];
            String mode = themeVarModes.getOrDefault(varName, DYN_STATIC);
            if (DYN_STATIC.equals(mode)) continue;
            int idx = DYN_PRIMARY.equals(mode) ? 0 : 1;
            if (idx >= colors.size()) idx = 0;
            fadeThemeVar(varName, colorToHex(colors.get(idx)));
        }
    }

    private static List<javafx.scene.paint.Color> extractDominantColors(javafx.scene.image.Image img, int count) {
        if (img == null || img.isError() || img.getWidth() == 0) return List.of();
        javafx.scene.image.PixelReader pr = img.getPixelReader();
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        java.util.Map<Integer, double[]> buckets = new java.util.HashMap<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                javafx.scene.paint.Color c = pr.getColor(x, y);
                if (c.getSaturation() < 0.2 || c.getBrightness() < 0.2 || c.getBrightness() > 0.92) continue;
                int hb = (int) (c.getHue() / 20); // 18 hue buckets of 20°
                buckets.computeIfAbsent(hb, k -> new double[4]);
                double[] acc = buckets.get(hb);
                acc[0] += c.getRed(); acc[1] += c.getGreen(); acc[2] += c.getBlue(); acc[3]++;
            }
        }
        if (buckets.isEmpty()) return List.of();
        List<java.util.Map.Entry<Integer, double[]>> entries = new java.util.ArrayList<>(buckets.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue()[3], a.getValue()[3]));
        List<javafx.scene.paint.Color> result = new java.util.ArrayList<>();
        List<Integer> usedHueBuckets = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, double[]> e : entries) {
            int hb = e.getKey();
            boolean tooClose = usedHueBuckets.stream().anyMatch(u -> {
                int d = Math.abs(hb - u); return Math.min(d, 18 - d) < 3;
            });
            if (tooClose) continue;
            usedHueBuckets.add(hb);
            double[] acc = e.getValue(); double n = acc[3];
            result.add(javafx.scene.paint.Color.color(
                Math.min(1, acc[0] / n), Math.min(1, acc[1] / n), Math.min(1, acc[2] / n)));
            if (result.size() >= count) break;
        }
        return result;
    }

    private void setFakeFullScreen(javafx.stage.Stage st, boolean enter) {
        setFakeFullScreen(st, enter, null);
    }

    private void setFakeFullScreen(javafx.stage.Stage st, boolean enter, javafx.stage.Screen targetScreen) {
        if (enter == fakeFullScreen) return;
        fakeFullScreen = enter;
        if (enter) {
            savedStageX = st.getX(); savedStageY = st.getY();
            savedStageW = st.getWidth(); savedStageH = st.getHeight();
            if (st.isMaximized()) st.setMaximized(false);
            javafx.stage.Screen scr = targetScreen != null ? targetScreen
                : screenAt(st.getX() + st.getWidth() / 2, st.getY() + st.getHeight() / 2);
            javafx.geometry.Rectangle2D b = scr.getBounds();
            st.setX(b.getMinX()); st.setY(b.getMinY());
            st.setWidth(b.getWidth()); st.setHeight(b.getHeight());
        } else {
            st.setX(savedStageX); st.setY(savedStageY);
            st.setWidth(savedStageW); st.setHeight(savedStageH);
        }
        syncMaximizeIcon(st);
    }

    private void setupProgressSlider() {
        progressSlider.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            javafx.scene.Node t = (javafx.scene.Node) e.getTarget();
            if (t.getStyleClass().contains("thumb") || t.getStyleClass().contains("track")) {
                seekingByUser = true;
            } else {
                e.consume(); // click missed the track — block Slider's internal snap
            }
        });
        progressSlider.setOnMouseReleased(e -> {
            if (!seekingByUser) return;
            seekingByUser = false;
            if (focusedPlayer != null && focusedPlayer.mediaPlayer != null) {
                Duration total = focusedPlayer.mediaPlayer.getMedia().getDuration();
                if (total != null && total.greaterThan(Duration.ZERO))
                    focusedPlayer.mediaPlayer.seek(total.multiply(progressSlider.getValue() / 100.0));
            }
        });
        progressSlider.valueProperty().addListener((obs, old, val) -> {
            if (!seekingByUser || focusedPlayer == null || focusedPlayer.mediaPlayer == null) return;
            Duration total = focusedPlayer.mediaPlayer.getMedia().getDuration();
            if (total != null && total.greaterThan(Duration.ZERO))
                timeElapsed.setText(UIUtils.formatTime((int)(val.doubleValue() / 100.0 * total.toSeconds())));
        });
    }

    private void setupVolumeSlider() {
        volumeSavePause = new PauseTransition(Duration.millis(400));
        volumeSavePause.setOnFinished(e -> libraryService.saveVolume((int) Math.round(volumeSlider.getValue())));

        volumeSlider.valueProperty().addListener((obs, old, val) -> {
            int pct = (int) Math.round(val.doubleValue());
            btnVolume.setText(pct == 0 ? "🔇" : pct < 40 ? "🔈" : "🔊");
            if (volumeLabel != null) volumeLabel.setText(pct + "%");
            volumeSavePause.playFromStart();
            if (focusedPlayer == null) return;
            if (focusedPlayer.mashupPartner != null || focusedPlayer.isMashupLinked) return;
            focusedPlayer.volume = pct / 100.0;
            applyVolumesToAll();
            if (focusedPlayer.panelVolumeSlider != null && Math.abs(focusedPlayer.panelVolumeSlider.getValue() - pct) > 0.5)
                focusedPlayer.panelVolumeSlider.setValue(pct);
        });
    }

    private void setupSidebarNavigation() {
        btnHome.setOnAction(e     -> { refreshHomePanel(); openTab("home", "🏠", "Inicio", homePanel, true, btnHome); });
        btnLibrary.setOnAction(e  -> { refreshLibraryPanel(); openTab("library", "📚", "Biblioteca",   libraryPanel,  true, btnLibrary); });
        btnSettings.setOnAction(e -> openTab("settings", "⚙",  "Configuración", settingsPanel, true, btnSettings));

        libraryService.getGroups().addListener(
            (javafx.collections.ListChangeListener<LibraryGroup>) c -> refreshSidebarList()
        );
        refreshSidebarList();

        groupListView.setOnMouseClicked(e -> {
            int idx = groupListView.getSelectionModel().getSelectedIndex();
            if (idx >= 0 && idx < libraryService.getGroups().size())
                showGroupDetail(libraryService.getGroups().get(idx));
        });
    }

    private void refreshSidebarList() {
        ObservableList<String> names = FXCollections.observableArrayList();
        libraryService.getGroups().forEach(g -> names.add((g.isYoutubePlaylist() ? "📺 " : "🎵 ") + g.getName()));
        groupListView.setItems(names);
    }

    private void setupSettingsPanel() {
        int savedPct = libraryService.loadAmbientDuck();

        Label titleLbl = new Label("Configuración");
        titleLbl.getStyleClass().add("greeting");

        Label subtitleLbl = new Label("Ajustes de reproducción");
        subtitleLbl.getStyleClass().add("greeting-sub");

        VBox header = new VBox(4, titleLbl, subtitleLbl);

        Label sectionLbl = new Label("VOLUMEN DE AMBIENTE");
        sectionLbl.getStyleClass().add("sidebar-section-label");

        Label descLbl = new Label(
            "Volumen al que suenan las canciones de tipo Ambiente cuando otra canción está reproduciendo.");
        descLbl.setWrapText(true);
        descLbl.getStyleClass().add("greeting-sub");

        Slider duckSlider = new Slider(0, 100, savedPct);
        duckSlider.setMajorTickUnit(25);
        duckSlider.setMinorTickCount(4);
        duckSlider.setShowTickMarks(true);
        duckSlider.setShowTickLabels(true);
        duckSlider.setSnapToTicks(false);
        duckSlider.getStyleClass().add("volume-slider");
        duckSlider.setMaxWidth(340);

        Label duckValueLbl = new Label(savedPct + "%");
        duckValueLbl.getStyleClass().add("volume-pct-label");

        HBox sliderRow = new HBox(12, duckSlider, duckValueLbl);
        sliderRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        duckSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int pct = (int) Math.round(newVal.doubleValue());
            duckValueLbl.setText(pct + "%");
            ambientDuckRatio = pct / 100.0;
            applyVolumesToAll();
            if (ambientDuckSavePause == null) {
                ambientDuckSavePause = new PauseTransition(Duration.millis(600));
                ambientDuckSavePause.setOnFinished(e -> libraryService.saveAmbientDuck((int) Math.round(duckSlider.getValue())));
            }
            ambientDuckSavePause.playFromStart();
        });

        VBox section = new VBox(8, sectionLbl, descLbl, sliderRow);

        // ── YouTube API key section ───────────────────────────────────────────
        Label apiSectionLbl = new Label("CLAVE DE API DE YOUTUBE");
        apiSectionLbl.getStyleClass().add("sidebar-section-label");

        Label apiDescLbl = new Label(
            "Necesaria para buscar canciones y obtener playlists de YouTube. " +
            "Los cambios se aplican al reiniciar la aplicación.");
        apiDescLbl.setWrapText(true);
        apiDescLbl.getStyleClass().add("greeting-sub");

        String currentKey = libraryService.loadYouTubeApiKey();
        if (currentKey.isBlank()) currentKey = ConfigLoader.get("youtube.api.key");

        TextField apiField = new TextField(currentKey);
        apiField.setPromptText("AIza…");
        apiField.getStyleClass().add("detail-search-field");
        apiField.setPrefWidth(320);

        Label apiStatusLbl = new Label("");
        apiStatusLbl.getStyleClass().add("greeting-sub");
        apiStatusLbl.setStyle("-fx-text-fill:#c0392b;");

        Button saveRestartBtn = new Button("💾  Guardar y reiniciar");
        saveRestartBtn.getStyleClass().add("btn-primary");
        saveRestartBtn.setOnAction(e -> {
            String key = apiField.getText().strip();
            if (key.isBlank()) {
                apiStatusLbl.setText("⚠ La clave no puede estar vacía.");
                return;
            }
            libraryService.saveYouTubeApiKey(key);
            try {
                ProcessHandle.current().info().command().ifPresent(cmd -> {
                    String[] args = ProcessHandle.current().info().arguments().orElse(new String[0]);
                    List<String> command = new ArrayList<>();
                    command.add(cmd);
                    command.addAll(java.util.Arrays.asList(args));
                    try { new ProcessBuilder(command).inheritIO().start(); }
                    catch (java.io.IOException ignored) {}
                });
            } catch (Exception ignored) {}
            Platform.exit();
        });

        HBox apiRow = new HBox(10, apiField, saveRestartBtn);
        apiRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Separator settingsSep = new Separator();
        settingsSep.setPadding(new javafx.geometry.Insets(8, 0, 8, 0));

        VBox apiSection = new VBox(8, apiSectionLbl, apiDescLbl, apiRow, apiStatusLbl);

        // ── Appearance section ────────────────────────────────────────────────
        Label appearanceSectionLbl = new Label("APARIENCIA");
        appearanceSectionLbl.getStyleClass().add("sidebar-section-label");

        Label appearanceDesc = new Label("Personaliza los colores de la interfaz. Los cambios se aplican al instante.");
        appearanceDesc.setWrapText(true);
        appearanceDesc.getStyleClass().add("greeting-sub");

        CheckBox dynColorsCheck = new CheckBox("Colores dinámicos");
        dynColorsCheck.setSelected(dynamicColorsEnabled);
        dynColorsCheck.getStyleClass().add("greeting-sub");
        Label dynColorsDesc = new Label("Cuando está activo, los colores marcados con un selector de modo cambian automáticamente con la miniatura de la canción en reproducción.");
        dynColorsDesc.setWrapText(true);
        dynColorsDesc.getStyleClass().add("greeting-sub");
        dynColorsCheck.setOnAction(e -> {
            dynamicColorsEnabled = dynColorsCheck.isSelected();
            libraryService.saveDynamicColorsEnabled(dynamicColorsEnabled);
            updateDynamicColors();
        });

        CheckBox textContrastCheck = new CheckBox("Contraste de texto automático");
        textContrastCheck.setSelected(textContrastEnabled);
        textContrastCheck.getStyleClass().add("greeting-sub");
        Label textContrastDesc = new Label("Añade un borde fino alrededor del texto cuando el color del texto y el fondo son demasiado similares, para garantizar la legibilidad.");
        textContrastDesc.setWrapText(true);
        textContrastDesc.getStyleClass().add("greeting-sub");
        textContrastCheck.setOnAction(e -> {
            textContrastEnabled = textContrastCheck.isSelected();
            libraryService.saveTextContrastEnabled(textContrastEnabled);
            applyTheme();
        });

        VBox colorRows = new VBox(10);
        colorRows.setPadding(new javafx.geometry.Insets(4, 0, 4, 0));

        List<javafx.scene.control.ColorPicker> pickers = new java.util.ArrayList<>();
        List<javafx.scene.control.ComboBox<String>> modeCombos = new java.util.ArrayList<>();

        for (String[] tv : THEME_VARS) {
            Label lbl = new Label(tv[2]);
            lbl.getStyleClass().add("greeting-sub");
            lbl.setMinWidth(140);

            javafx.scene.control.ColorPicker cp = new javafx.scene.control.ColorPicker(
                tryParseColor(baseTheme.getOrDefault(tv[0], tv[1])));
            cp.getStyleClass().addAll("theme-color-picker", "button");
            cp.setPrefWidth(130);
            pickers.add(cp);

            javafx.scene.control.ComboBox<String> modeCombo = new javafx.scene.control.ComboBox<>(
                FXCollections.observableArrayList("Estático", "Color primario de canción", "Color secundario de canción"));
            String savedMode = themeVarModes.getOrDefault(tv[0], DYN_STATIC);
            modeCombo.setValue(
                DYN_PRIMARY.equals(savedMode)   ? "Color primario de canción" :
                DYN_SECONDARY.equals(savedMode) ? "Color secundario de canción" : "Estático");
            modeCombo.setPrefWidth(210);
            modeCombos.add(modeCombo);

            final String varName = tv[0];
            final String defColor = tv[1];

            Button resetColorBtn = new Button();
            resetColorBtn.getStyleClass().add("color-reset-btn");
            resetColorBtn.setStyle("-fx-background-color: " + defColor + ";");
            resetColorBtn.setTooltip(new Tooltip("Restaurar color predeterminado"));
            resetColorBtn.setOnAction(e -> cp.setValue(tryParseColor(defColor)));

            cp.valueProperty().addListener((obs, old, col) -> {
                String hex = colorToHex(col);
                baseTheme.put(varName, hex);
                if (DYN_STATIC.equals(themeVarModes.getOrDefault(varName, DYN_STATIC))) {
                    currentTheme.put(varName, hex);
                    applyTheme();
                }
                debouncedSaveTheme();
            });
            modeCombo.setOnAction(e -> {
                String sel = modeCombo.getValue();
                String mode = "Color primario de canción".equals(sel)   ? DYN_PRIMARY :
                              "Color secundario de canción".equals(sel) ? DYN_SECONDARY : DYN_STATIC;
                themeVarModes.put(varName, mode);
                libraryService.saveThemeVarModes(themeVarModes);
                updateDynamicColors();
            });

            HBox row = new HBox(12, lbl, cp, resetColorBtn, modeCombo);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            colorRows.getChildren().add(row);
        }

        Button resetThemeBtn = new Button("↺  Restaurar colores predeterminados");
        resetThemeBtn.getStyleClass().add("btn-secondary");
        resetThemeBtn.setOnAction(e -> {
            for (int i = 0; i < THEME_VARS.length; i++) {
                String var = THEME_VARS[i][0], def = THEME_VARS[i][1];
                baseTheme.put(var, def);
                currentTheme.put(var, def);
                pickers.get(i).setValue(tryParseColor(def));
                String defMode = defaultModeFor(var);
                themeVarModes.put(var, defMode);
                modeCombos.get(i).setValue(DYN_PRIMARY.equals(defMode)   ? "Color primario de canción" :
                                           DYN_SECONDARY.equals(defMode) ? "Color secundario de canción" : "Estático");
            }
            dynamicColorsEnabled = true;
            dynColorsCheck.setSelected(true);
            textContrastEnabled = true;
            textContrastCheck.setSelected(true);
            applyTheme();
            updateDynamicColors();
            debouncedSaveTheme();
            libraryService.saveThemeVarModes(themeVarModes);
            libraryService.saveDynamicColorsEnabled(true);
            libraryService.saveTextContrastEnabled(true);
        });

        VBox appearanceSection = new VBox(8, appearanceSectionLbl, appearanceDesc,
            dynColorsCheck, dynColorsDesc, textContrastCheck, textContrastDesc,
            colorRows, resetThemeBtn);

        Separator appearanceSep = new Separator();
        appearanceSep.setPadding(new javafx.geometry.Insets(8, 0, 8, 0));

        // Wrap all content in a ScrollPane so it scrolls on small screens
        VBox scrollContent = new VBox(24, header, section, settingsSep, apiSection, appearanceSep, appearanceSection);
        scrollContent.setPadding(new javafx.geometry.Insets(28, 28, 28, 28));

        ScrollPane settingsScroll = new ScrollPane(scrollContent);
        settingsScroll.setFitToWidth(true);
        settingsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        settingsScroll.getStyleClass().add("results-scroll");
        VBox.setVgrow(settingsScroll, Priority.ALWAYS);

        settingsPanel.getChildren().setAll(settingsScroll);
    }

    // ── Tab system ────────────────────────────────────────────────────────────

    /**
     * Abre una pestaña con el ID dado, o la activa si ya existe.
     * El panel se añade a {@code contentArea} la primera vez.
     */
    private void openTab(String id, String icon, String title, VBox panel, boolean closeable, Button sidebarBtn) {
        AppTab existing = findTab(id);
        if (existing != null) { existing.title = title; activateTab(existing); return; }
        AppTab tab = new AppTab(id, icon, title, panel, closeable, sidebarBtn);
        openTabs.add(tab);
        if (!contentArea.getChildren().contains(panel)) {
            panel.setVisible(false); panel.setManaged(false); contentArea.getChildren().add(panel);
        }
        activateTab(tab);
    }

    private AppTab findTab(String id) {
        return openTabs.stream().filter(t -> t.id.equals(id)).findFirst().orElse(null);
    }

    private void activateTab(AppTab tab) {
        activeTab = tab;
        contentArea.getChildren().forEach(n -> { n.setVisible(false); n.setManaged(false); n.setMouseTransparent(true); });
        tab.panel.setVisible(true); tab.panel.setManaged(true); tab.panel.setMouseTransparent(false);

        if (tab.id.startsWith("player:") || tab.id.startsWith("mashup:")) {
            PlayerInstance pi = findPlayerInstance(tab.id);
            if (pi != null) setFocusedPlayer(pi);
        }
        updateMiniPlayerVisibility();

        for (Button b : new Button[]{btnHome, btnLibrary, btnSettings}) b.getStyleClass().remove("nav-btn-active");
        if (tab.sidebarBtn != null) tab.sidebarBtn.getStyleClass().add("nav-btn-active");

        rebuildTabBar();
        FadeTransition ft = new FadeTransition(Duration.millis(150), tab.panel);
        ft.setFromValue(0); ft.setToValue(1); ft.play();
    }

    private void closeTab(String id) {
        AppTab tab = findTab(id);
        if (tab == null || !tab.closeable) return;
        int idx = openTabs.indexOf(tab);
        openTabs.remove(tab);
        contentArea.getChildren().remove(tab.panel);

        if (id.startsWith("player:") || id.startsWith("mashup:")) {
            PlayerInstance pi = findPlayerInstance(id);
            if (pi != null) {
                if (pi.mashupXfadeAnim != null) { pi.mashupXfadeAnim.stop(); pi.mashupXfadeAnim = null; }
                if (pi.mediaPlayer != null) { pi.mediaPlayer.stop(); pi.mediaPlayer.dispose(); pi.mediaPlayer = null; }
                if (pi.waveDecayAnim != null) { pi.waveDecayAnim.stop(); pi.waveDecayAnim = null; }
                pi.isPlaying = false;
                if (pi.mashupPartner != null) {
                    PlayerInstance pb = pi.mashupPartner;
                    if (pb.mediaPlayer != null) { pb.mediaPlayer.stop(); pb.mediaPlayer.dispose(); pb.mediaPlayer = null; }
                    pb.isPlaying = false;
                    activePlayers.remove(pb);
                    pi.mashupPartner = null;
                }
                activePlayers.remove(pi);
                if (focusedPlayer == pi) focusedPlayer = null;
                applyVolumesToAll();
            }
            pickBestFocusedPlayer();
            updateMiniPlayerVisibility();
        }
        if (!openTabs.isEmpty()) activateTab(openTabs.get(Math.max(0, idx - 1)));
        else rebuildTabBar();
    }

    private static final double TAB_WIDTH = 168;

    private void rebuildTabBar() {
        tabBar.getChildren().clear();
        for (AppTab tab : openTabs) {
            HBox btn = new HBox(4); btn.getStyleClass().add("tab-btn");
            if (tab == activeTab)   btn.getStyleClass().add("tab-btn-active");
            btn.setAlignment(Pos.CENTER); btn.setPrefWidth(TAB_WIDTH); btn.setMinWidth(TAB_WIDTH); btn.setMaxWidth(TAB_WIDTH);

            PlayerInstance tabPi = (tab.id.startsWith("player:") || tab.id.startsWith("mashup:"))
                ? findPlayerInstance(tab.id) : null;
            boolean playing = tabPi != null && tabPi.isPlaying;
            if (playing) btn.getStyleClass().add("tab-btn-playing");

            Label lbl = new Label(tab.icon + "  " + tab.title); lbl.getStyleClass().add("tab-label");
            lbl.setMaxWidth(tab.closeable ? TAB_WIDTH - 48 : TAB_WIDTH - 20); lbl.setMinWidth(0);
            HBox.setHgrow(lbl, Priority.ALWAYS); btn.getChildren().add(lbl);

            if (playing) {
                Label dot = new Label("▶"); dot.setStyle("-fx-font-size: 7px; -fx-text-fill: #e8729a; -fx-padding: 0 2 0 0;");
                btn.getChildren().add(dot);
            }
            if (tab.closeable) {
                Button x = new Button("×"); x.getStyleClass().add("tab-close-btn");
                final String tid = tab.id; x.setOnAction(e -> { e.consume(); closeTab(tid); });
                btn.getChildren().add(x);
            }
            final AppTab t = tab;
            btn.setUserData(tab);
            btn.setOnMouseClicked(e -> {
                if (tabJustDragged) { tabJustDragged = false; return; }
                if      (e.getButton() == MouseButton.MIDDLE  && t.closeable)                       closeTab(t.id);
                else if (e.getButton() == MouseButton.PRIMARY && !(e.getTarget() instanceof Button)) activateTab(t);
            });
            setupTabDrag(btn, tab);
            tabBar.getChildren().add(btn);
        }
        scrollActiveTabIntoView();
    }

    private void scrollActiveTabIntoView() {
        if (activeTab == null || tabBarScroll == null) return;
        int idx = openTabs.indexOf(activeTab); if (idx < 0) return;
        Platform.runLater(() -> {
            double cw = tabBar.getBoundsInLocal().getWidth(), vw = tabBarScroll.getViewportBounds().getWidth();
            if (cw <= vw) { tabBarScroll.setHvalue(0); return; }
            double range = cw - vw, start = idx * TAB_WIDTH, end = start + TAB_WIDTH, scrolled = tabBarScroll.getHvalue() * range;
            if (start < scrolled)            tabBarScroll.setHvalue(start / range);
            else if (end > scrolled + vw)    tabBarScroll.setHvalue((end - vw) / range);
        });
    }

    private void setupTabDrag(HBox btn, AppTab tab) {
        btn.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (e.getTarget() instanceof Button) return;
            tabDragging      = tab;
            tabDragStartX    = e.getScreenX();
            tabDragOrigIdx   = tabBar.getChildren().indexOf(btn);
            tabDragTargetIdx = tabDragOrigIdx;
            tabDragActive    = false;  // drag not yet activated
        });

        btn.setOnMouseDragged(e -> {
            if (tabDragging != tab) return;

            // Activate drag only after moving past the threshold
            if (!tabDragActive) {
                if (Math.abs(e.getScreenX() - tabDragStartX) < 6) return;
                tabDragActive = true;
                btn.setViewOrder(-1);
                btn.getStyleClass().add("tab-btn-dragging");
            }

            javafx.collections.ObservableList<javafx.scene.Node> ch = tabBar.getChildren();
            javafx.geometry.Point2D local = tabBar.screenToLocal(e.getScreenX(), e.getScreenY());
            if (local == null) { e.consume(); return; }
            double barX = local.getX();

            btn.setTranslateX(barX - (tabDragOrigIdx + 0.5) * TAB_WIDTH);

            int newTarget = tabDragTargetIdx;
            if (newTarget > 0               && barX < (newTarget - 0.5) * TAB_WIDTH) newTarget--;
            else if (newTarget < ch.size()-1 && barX > (newTarget + 1.5) * TAB_WIDTH) newTarget++;

            if (newTarget != tabDragTargetIdx) {
                tabDragTargetIdx = newTarget;
                for (int i = 0; i < ch.size(); i++) {
                    if (i == tabDragOrigIdx) continue;
                    double shift = 0;
                    if (tabDragTargetIdx > tabDragOrigIdx && i > tabDragOrigIdx && i <= tabDragTargetIdx)
                        shift = -TAB_WIDTH;
                    else if (tabDragTargetIdx < tabDragOrigIdx && i >= tabDragTargetIdx && i < tabDragOrigIdx)
                        shift = TAB_WIDTH;
                    ch.get(i).setTranslateX(shift);
                }
            }
            e.consume();
        });

        btn.setOnMouseReleased(e -> {
            if (tabDragging != tab) return;
            tabDragging = null;

            if (!tabDragActive) return;  // was a click, let MOUSE_CLICKED handle it
            tabDragActive = false;

            btn.setViewOrder(0);
            btn.getStyleClass().remove("tab-btn-dragging");
            tabBar.getChildren().forEach(n -> n.setTranslateX(0));

            tabJustDragged = true;
            if (tabDragTargetIdx != tabDragOrigIdx) {
                javafx.collections.ObservableList<javafx.scene.Node> ch = tabBar.getChildren();
                javafx.scene.Node node = ch.remove(tabDragOrigIdx);
                ch.add(tabDragTargetIdx, node);
                openTabs.clear();
                for (javafx.scene.Node n : ch) {
                    if (n.getUserData() instanceof AppTab t) openTabs.add(t);
                }
            }
            rebuildTabBar();
        });
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    /**
     * Crea una nueva instancia de reproductor con una cola de un solo elemento
     * y comienza la reproducción.
     */
    public void playSong(Song song) {
        String tabId = "player:" + System.currentTimeMillis();
        PlayerInstance pi = new PlayerInstance(tabId);
        pi.volume = volumeSlider.getValue() / 100.0;
        pi.queue.add(song);
        buildPanel(pi); activePlayers.add(pi); loadSong(pi, song);
    }

    /**
     * Crea una nueva instancia de reproductor con una cola completa y comienza
     * desde {@code song} (que debe pertenecer a {@code fullQueue}).
     */
    public void playSongInQueue(Song song, List<Song> fullQueue) {
        String tabId = "player:" + System.currentTimeMillis();
        PlayerInstance pi = new PlayerInstance(tabId);
        pi.volume = volumeSlider.getValue() / 100.0;
        pi.queue.setAll(fullQueue);
        buildPanel(pi); activePlayers.add(pi); loadSong(pi, song);
    }

    private void openSongPaused(Song song, LibraryGroup group) {
        if (!song.isLocal()) { showToast("Descarga la canción primero"); return; }
        List<Song> q = group != null
            ? group.getSongs().stream().filter(Song::isLocal).collect(Collectors.toList())
            : List.of(song);

        AppTab prevTab = activeTab;
        PlayerInstance prevFocused = focusedPlayer;

        String tabId = "player:" + System.currentTimeMillis();
        PlayerInstance pi = new PlayerInstance(tabId);
        pi.volume = volumeSlider.getValue() / 100.0;
        pi.queue.setAll(q.isEmpty() ? List.of(song) : q);
        buildPanel(pi); activePlayers.add(pi);
        loadSong(pi, song);

        if (pi.mediaPlayer != null) {
            pi.mediaPlayer.pause();
            pi.isPlaying = false;
            if (pi.panelPlayPause != null) pi.panelPlayPause.setText("▶");
        }

        // Stay on the previous view
        if (prevTab != null) activateTab(prevTab);
        if (prevFocused != null) setFocusedPlayer(prevFocused);
        rebuildTabBar();
    }

    private void buildPanel(PlayerInstance pi) {
        PlayerPanelBuilder.build(pi,
            this::togglePlayInstance,
            p -> navigateTab(-1),
            p -> navigateTab(+1),
            this::toggleShuffle,
            (p, pct) -> { if (p == focusedPlayer && Math.abs(volumeSlider.getValue() - pct) > 0.5) volumeSlider.setValue(pct); applyVolumesToAll(); },
            p -> { if (p == focusedPlayer) UIUtils.toggleStyleClass(btnRepeat, "control-active", p.looping); }
        );
        applyContrastStroke(pi.panel, "bardo-text", "bardo-player-bg1");
    }

    private void navigateTab(int direction) {
        if (openTabs.isEmpty()) return;
        int idx = openTabs.indexOf(activeTab);
        if (idx < 0) return;
        int next = (idx + direction + openTabs.size()) % openTabs.size();
        activateTab(openTabs.get(next));
    }

    /**
     * Carga y reproduce {@code song} en la instancia {@code pi}, reemplazando
     * cualquier {@code MediaPlayer} anterior. Si la canción no es local, abre
     * el navegador con la URL de YouTube en su lugar.
     */
    private void loadSong(PlayerInstance pi, Song song) {
        if (!song.isLocal()) { UIUtils.openInBrowser(song.getVideoId()); return; }

        if (pi.mediaPlayer != null) { pi.mediaPlayer.stop(); pi.mediaPlayer.dispose(); pi.mediaPlayer = null; }

        pi.song = song;
        File file = new File(song.getLocalFilePath());
        if (!file.exists()) { showToast("Archivo no encontrado: " + file.getName()); return; }

        try {
            Media media = new Media(file.toURI().toString());
            pi.mediaPlayer = new MediaPlayer(media);
            pi.mediaPlayer.setVolume(pi.volume);
            pi.mediaPlayer.setCycleCount(1);

            media.durationProperty().addListener((obs, old, dur) -> {
                if (dur != null && dur.greaterThan(Duration.ZERO) && !dur.equals(Duration.UNKNOWN)) {
                    String totalStr = UIUtils.formatTime((int) dur.toSeconds());
                    Platform.runLater(() -> {
                        if (pi.panelTotal != null) pi.panelTotal.setText(totalStr);
                        if (pi == focusedPlayer) timeTotal.setText(totalStr);
                    });
                }
            });

            media.getMetadata().addListener((javafx.collections.MapChangeListener<String, Object>) ch -> {
                if (ch.wasAdded() && "image".equals(ch.getKey())) {
                    Image img = (Image) ch.getValueAdded();
                    Platform.runLater(() -> {
                        if (pi.artView  != null) pi.artView.setImage(img);
                        if (pi == focusedPlayer) albumArt.setImage(img);
                    });
                }
            });

            pi.mediaPlayer.setOnEndOfMedia(() -> Platform.runLater(() -> {
                if (pi.looping) { pi.mediaPlayer.seek(Duration.ZERO); pi.mediaPlayer.play(); }
                else            { onSongEnded(pi); }
            }));
            pi.mediaPlayer.setOnError(() -> showToast("Error al reproducir: " + file.getName()));

            // ── Audio spectrum → real-time waveform ──────────────────────────
            final int BANDS = 32;
            pi.waveSmoothed = new float[BANDS];
            final float[] smoothed = pi.waveSmoothed;
            final boolean[] pending = {false};
            pi.mediaPlayer.setAudioSpectrumNumBands(BANDS);
            pi.mediaPlayer.setAudioSpectrumInterval(1.0 / 30);
            pi.mediaPlayer.setAudioSpectrumListener((ts, dur2, mags, phases) -> {
                for (int i = 0; i < BANDS; i++) {
                    float target = Math.max(0f, (mags[i] + 60f) / 60f);
                    smoothed[i] = smoothed[i] * 0.55f + target * 0.45f;
                }
                if (!pending[0]) {
                    pending[0] = true;
                    Platform.runLater(() -> {
                        drawWaveCanvas(pi.panelWaveCanvas, smoothed);
                        if (pi == focusedPlayer) drawWaveCanvas(miniWaveCanvas, smoothed);
                        pending[0] = false;
                    });
                }
            });

            pi.mediaPlayer.play();
            pi.isPlaying = true;
            applyVolumesToAll();
            rebuildTabBar();
        } catch (Exception e) { showToast("No se puede reproducir: " + file.getName()); return; }

        if (pi.panelTitle != null) {
            pi.panelTitle.setText(song.getTitle()); pi.panelArtist.setText(song.getArtist());
            pi.panelProgress.setValue(0); pi.panelElapsed.setText("0:00"); pi.panelTotal.setText("—");
            pi.panelPlayPause.setText("⏸");
        }
        if (song.getThumbnailUrl() != null && !song.getThumbnailUrl().isBlank()) {
            try { Image thumb = new Image(song.getThumbnailUrl(), true); if (pi.artView != null) pi.artView.setImage(thumb); }
            catch (Exception ignored) {}
        }
        AppTab existingTab = findTab(pi.tabId);
        if (existingTab != null) existingTab.title = song.getTitle();
        openTab(pi.tabId, "🎵", song.getTitle(), pi.panel, true, null);

        setFocusedPlayer(pi);
        updateLoopButtons();
        updateMiniPlayerVisibility();
        if (nowPlayingBar.isVisible()) animateNowPlaying();
    }

    private void togglePlayInstance(PlayerInstance pi) {
        if (pi == null || pi.mediaPlayer == null) return;
        if (pi.mashupPartner != null) { toggleMashupPlay(pi); return; }
        if (!pi.isPlaying) {
            if (pi.fadeOutAnim != null) { pi.fadeOutAnim.stop(); pi.fadeOutAnim = null; }
            pi.mediaPlayer.play();
            pi.isPlaying = true;
            applyVolumesToAll();
            if (pi == focusedPlayer) { addGlowEffect(); updateDynamicColors(); }
            updatePlayPauseButton(); rebuildTabBar();
        } else {
            fadeOutAndPause(pi);
        }
    }

    private void toggleMashupPlay(PlayerInstance piA) {
        PlayerInstance piB = piA.mashupPartner;
        if (piA.mediaPlayer == null) return;
        if (!piA.isPlaying) {
            piA.mediaPlayer.play(); piA.isPlaying = true;
            if (piB != null && piB.mediaPlayer != null) { piB.mediaPlayer.play(); piB.isPlaying = true; }
            if (piA == focusedPlayer) addGlowEffect();
            updatePlayPauseButton(); rebuildTabBar();
        } else {
            piA.mediaPlayer.pause(); piA.isPlaying = false;
            if (piB != null && piB.mediaPlayer != null) { piB.mediaPlayer.pause(); piB.isPlaying = false; }
            startWaveDecay(piA);
            if (piA == focusedPlayer) removeGlowEffect();
            updatePlayPauseButton(); rebuildTabBar();
        }
    }

    private void crossfade(PlayerInstance piA) {
        PlayerInstance piB = piA.mashupPartner;
        if (piB == null || piA.mediaPlayer == null || piB.mediaPlayer == null) return;
        if (piA.mashupXfadeAnim != null) { piA.mashupXfadeAnim.stop(); piA.mashupXfadeAnim = null; }
        double fromA = piA.mediaPlayer.getVolume(), fromB = piB.mediaPlayer.getVolume();
        double toA = fromB, toB = fromA;
        piA.mashupXfadeAnim = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(Duration.ZERO,
                new javafx.animation.KeyValue(piA.mediaPlayer.volumeProperty(), fromA),
                new javafx.animation.KeyValue(piB.mediaPlayer.volumeProperty(), fromB)),
            new javafx.animation.KeyFrame(Duration.millis(1500),
                new javafx.animation.KeyValue(piA.mediaPlayer.volumeProperty(), toA, javafx.animation.Interpolator.EASE_BOTH),
                new javafx.animation.KeyValue(piB.mediaPlayer.volumeProperty(), toB, javafx.animation.Interpolator.EASE_BOTH))
        );
        piA.mashupXfadeAnim.setOnFinished(ev -> {
            piA.mashupXfadeAnim = null;
            piA.volume = toA; piB.volume = toB;
            if (piA.panelVolumeSlider != null) piA.panelVolumeSlider.setValue(toA * 100);
            if (piB.panelVolumeSlider != null) piB.panelVolumeSlider.setValue(toB * 100);
        });
        piA.mashupXfadeAnim.play();
    }

    /**
     * Baja el volumen gradualmente en 700 ms (fade-out) y luego pausa el reproductor.
     * El volumen se restaura al valor original al finalizar el fade para que la siguiente
     * pulsación de play retome con el volumen correcto.
     */
    private void fadeOutAndPause(PlayerInstance pi) {
        if (pi.mediaPlayer == null) return;
        pi.isPlaying = false;
        if (pi == focusedPlayer) {
            removeGlowEffect(); updatePlayPauseButton();
        } else {
            if (pi.panelPlayPause != null) pi.panelPlayPause.setText("▶");
        }
        if (pi.fadeOutAnim != null) pi.fadeOutAnim.stop();
        pi.fadeOutAnim = new Timeline(new KeyFrame(Duration.millis(700),
            new KeyValue(pi.mediaPlayer.volumeProperty(), 0.0, Interpolator.EASE_IN)));
        pi.fadeOutAnim.setOnFinished(ev -> {
            pi.mediaPlayer.pause();
            startWaveDecay(pi);
            pi.fadeOutAnim = null;
            applyVolumesToAll();
            rebuildTabBar();
            if (pi == focusedPlayer || focusedPlayer == null) updateDynamicColors();
        });
        pi.fadeOutAnim.play();
    }

    private void onSongEnded(PlayerInstance pi) {
        pi.isPlaying = false;
        applyVolumesToAll();
        startWaveDecay(pi);
        if (pi.panelPlayPause != null) pi.panelPlayPause.setText("▶");
        pickBestFocusedPlayer();
        updateDynamicColors();
    }

    private void updateProgressUI(PlayerInstance pi, Duration current) {
        if (pi.mediaPlayer == null) return;
        Duration total = pi.mediaPlayer.getMedia().getDuration();
        if (total == null || !total.greaterThan(Duration.ZERO)) return;
        double pct = (current.toSeconds() / total.toSeconds()) * 100;
        String elapsed = UIUtils.formatTime((int) current.toSeconds());
        if (pi.panelProgress != null && !pi.seeking) { pi.panelProgress.setValue(pct); pi.panelElapsed.setText(elapsed); }
        if (pi == focusedPlayer && !seekingByUser) { progressSlider.setValue(pct); timeElapsed.setText(elapsed); }
    }

    private void playPrevInInstance(PlayerInstance pi) {
        if (pi == null) return;
        int idx = pi.queue.indexOf(pi.song);
        if (idx > 0) loadSong(pi, pi.queue.get(idx - 1));
    }

    private void playNextInInstance(PlayerInstance pi) {
        if (pi == null) return;
        if (isShuffle) { loadSong(pi, pi.queue.get((int)(Math.random() * pi.queue.size()))); return; }
        int idx = pi.queue.indexOf(pi.song);
        if (idx >= 0 && idx < pi.queue.size() - 1) loadSong(pi, pi.queue.get(idx + 1));
    }

    private void toggleShuffle() { isShuffle = !isShuffle; UIUtils.toggleStyleClass(btnShuffle, "control-active", isShuffle); }

    private void toggleRepeat() {
        if (focusedPlayer == null) return;
        focusedPlayer.looping = !focusedPlayer.looping;
        updateLoopButtons();
    }

    private void updateLoopButtons() {
        boolean on = focusedPlayer != null && focusedPlayer.looping;
        UIUtils.toggleStyleClass(btnRepeat, "control-active", on);
        if (focusedPlayer != null && focusedPlayer.panelRepeat != null)
            UIUtils.toggleStyleClass(focusedPlayer.panelRepeat, "control-active", on);
    }

    private void drawWaveCanvas(javafx.scene.canvas.Canvas canvas, float[] smoothed) {
        if (canvas == null) return;
        javafx.scene.canvas.GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        gc.clearRect(0, 0, w, h);
        if (smoothed == null) return;

        boolean isMini = (w <= 60); // mini canvas overlaid on thumbnail
        double gap    = isMini ? 1.5 : 2.0;
        double minBarW = isMini ? 2.5 : 4.0;
        int n = smoothed.length;
        int displayBars = Math.min(n, Math.max(2, (int)((w + gap) / (minBarW + gap))));
        if (displayBars % 2 != 0) displayBars--;   // keep even for clean mirror
        double barW = (w - gap * (displayBars - 1)) / displayBars;
        int half = displayBars / 2;

        // Semi-transparent overlay so bars are legible over the art thumbnail
        if (isMini) {
            gc.setFill(javafx.scene.paint.Color.rgb(15, 5, 25, 0.35));
            gc.fillRect(0, 0, w, h);
        }

        for (int i = 0; i < displayBars; i++) {
            // Mirror: distFromCenter=0 → bass (band 0, loudest), edge → treble
            int dist = (i < half) ? (half - 1 - i) : (i - half);
            int bandIdx = (half > 1) ? (int)((double) dist / (half - 1) * (n - 1)) : 0;
            bandIdx = Math.max(0, Math.min(n - 1, bandIdx));
            float energy = smoothed[bandIdx];
            // Smooth with neighbour for visual continuity
            if (bandIdx + 1 < n) energy = energy * 0.7f + smoothed[bandIdx + 1] * 0.3f;

            double barH = Math.max(isMini ? 2.0 : 3.0, energy * h * 0.92);
            double x = i * (barW + gap);
            double y = (h - barH) / 2.0;

            // Color: center = pink (bass), edges = purple (treble)
            double t = (half > 1) ? (double) dist / (half - 1) : 0;
            int r = (int)(244 - t * 64), g = (int)(167 - t * 27), b = (int)(185 + t * 35);
            gc.setFill(javafx.scene.paint.Color.rgb(r, g, b, isMini ? 0.92 : 0.88));
            gc.fillRoundRect(x, y, barW, barH, 3, 3);
        }
    }

    private void startWaveDecay(PlayerInstance pi) {
        if (pi.waveDecayAnim != null) { pi.waveDecayAnim.stop(); pi.waveDecayAnim = null; }
        if (pi.waveSmoothed == null) return;
        pi.waveDecayAnim = new Timeline(new KeyFrame(Duration.millis(33), e -> {
            if (pi.waveSmoothed == null) { pi.waveDecayAnim.stop(); return; }
            boolean allZero = true;
            for (int i = 0; i < pi.waveSmoothed.length; i++) {
                pi.waveSmoothed[i] *= 0.82f;
                if (pi.waveSmoothed[i] > 0.004f) allZero = false;
            }
            drawWaveCanvas(pi.panelWaveCanvas, pi.waveSmoothed);
            if (pi == focusedPlayer) drawWaveCanvas(miniWaveCanvas, pi.waveSmoothed);
            if (allZero) { pi.waveDecayAnim.stop(); pi.waveDecayAnim = null; }
        }));
        pi.waveDecayAnim.setCycleCount(Animation.INDEFINITE);
        pi.waveDecayAnim.play();
    }

    private void addGlowEffect() {
        vinylContainer.setStyle("-fx-effect: dropshadow(gaussian, #f4a7b9, 20, 0.5, 0, 0);");
        if (focusedPlayer != null && focusedPlayer.artStack != null)
            UIUtils.toggleStyleClass(focusedPlayer.artStack, "glow", true);
    }

    private void removeGlowEffect() {
        vinylContainer.setStyle("");
        if (focusedPlayer != null && focusedPlayer.artStack != null)
            UIUtils.toggleStyleClass(focusedPlayer.artStack, "glow", false);
    }

    private void updatePlayPauseButton() {
        boolean playing = focusedPlayer != null && focusedPlayer.isPlaying;
        btnPlayPause.setText(playing ? "⏸" : "▶");
        if (focusedPlayer != null && focusedPlayer.panelPlayPause != null)
            focusedPlayer.panelPlayPause.setText(playing ? "⏸" : "▶");
        rebuildTabBar();
    }

    // ── Player instance management ────────────────────────────────────────────

    /** Devuelve la instancia cuya pestaña coincide con {@code tabId}, o {@code null}. */
    private PlayerInstance findPlayerInstance(String tabId) {
        return activePlayers.stream().filter(pi -> pi.tabId.equals(tabId)).findFirst().orElse(null);
    }

    /**
     * Establece {@code pi} como reproductor enfocado y sincroniza toda la barra
     * "Now Playing": portada, título, artista, sliders de progreso y volumen,
     * botón play/pausa y estado de bucle.
     */
    private void setFocusedPlayer(PlayerInstance pi) {
        focusedPlayer = pi;
        Song song = pi.song;
        if (song != null) {
            nowPlayingTitle.setText(song.getTitle()); nowPlayingArtist.setText(song.getArtist());
            if (song.getThumbnailUrl() != null && !song.getThumbnailUrl().isBlank())
                try { albumArt.setImage(new Image(song.getThumbnailUrl(), true)); } catch (Exception ignored) {}
        }
        if (pi.mediaPlayer != null) {
            Duration cur = pi.mediaPlayer.getCurrentTime(), total = pi.mediaPlayer.getMedia().getDuration();
            if (cur != null && total != null && total.greaterThan(Duration.ZERO)) {
                progressSlider.setValue((cur.toSeconds() / total.toSeconds()) * 100);
                timeElapsed.setText(UIUtils.formatTime((int) cur.toSeconds()));
                timeTotal.setText(UIUtils.formatTime((int) total.toSeconds()));
            }
        }
        if (pi.mashupPartner == null && !pi.isMashupLinked &&
            Math.abs(volumeSlider.getValue() - pi.volume * 100) > 0.5) volumeSlider.setValue(pi.volume * 100);
        if (pi.isPlaying) addGlowEffect(); else removeGlowEffect();
        drawWaveCanvas(miniWaveCanvas, pi.waveSmoothed);
        updatePlayPauseButton(); updateLoopButtons();
        updateDynamicColors();
    }

    @FXML private void onSwitchFocusedPlayer() {
        List<PlayerInstance> switchable = activePlayers.stream().filter(p -> !p.isMashupLinked).collect(Collectors.toList());
        if (switchable.size() <= 1) return;
        int idx = switchable.indexOf(focusedPlayer);
        setFocusedPlayer(switchable.get((idx + 1) % switchable.size()));
    }

    /**
     * Selecciona el mejor reproductor para enfocar: prefiere el que está sonando
     * (el más reciente primero); si ninguno está sonando, mantiene el más reciente.
     * Si no hay instancias activas, oculta la barra "Now Playing".
     */
    private void pickBestFocusedPlayer() {
        List<PlayerInstance> candidates = activePlayers.stream().filter(p -> !p.isMashupLinked).collect(Collectors.toList());
        if (candidates.isEmpty()) {
            focusedPlayer = null; removeGlowEffect();
            fadeAllToBase();
            updatePlayPauseButton(); updateMiniPlayerVisibility(); return;
        }
        PlayerInstance best = null;
        for (int i = candidates.size() - 1; i >= 0; i--)
            if (candidates.get(i).isPlaying) { best = candidates.get(i); break; }
        if (best == null) best = candidates.get(candidates.size() - 1);

        if (best != focusedPlayer) setFocusedPlayer(best);
        else {
            updatePlayPauseButton();
            if (focusedPlayer.isPlaying) addGlowEffect(); else removeGlowEffect();
        }
        updateMiniPlayerVisibility();
    }

    private void updateMiniPlayerVisibility() {
        boolean anyPlayer = activePlayers.stream().anyMatch(p -> !p.isMashupLinked);
        boolean onPlayerTab = activeTab != null && (activeTab.id.startsWith("player:") || activeTab.id.startsWith("mashup:"));
        boolean visible = anyPlayer && !onPlayerTab;
        nowPlayingBar.setVisible(visible); nowPlayingBar.setManaged(visible);
    }

    // ── Download & playback flow ──────────────────────────────────────────────

    /**
     * Si la canción ya es local, la reproduce directamente con la cola del grupo.
     * Si no, la descarga con {@link DownloadService} (mostrando el progreso en la
     * barra "Now Playing") y la reproduce al terminar. Las descargas en paralelo del
     * mismo vídeo se ignoran con un toast informativo.
     */
    private void downloadAndPlay(Song song, LibraryGroup group) {
        if (song.isLocal()) {
            if (group != null) { group.incrementPlayCount(); libraryService.save(); }
            List<Song> q = group != null
                ? group.getSongs().stream().filter(Song::isLocal).collect(Collectors.toList())
                : List.of(song);
            playSongInQueue(song, q.isEmpty() ? List.of(song) : q);
            return;
        }
        final LibraryGroup target = group != null ? group : libraryService.getOrCreateHistorial();
        if (group == null) target.addSong(song);

        String vid = song.getVideoId();
        if (downloadingNow.contains(vid)) { showToast("Ya se está descargando «" + song.getTitle() + "»"); return; }
        downloadingNow.add(vid);
        nowPlayingTitle.setText("⬇ Descargando…"); nowPlayingArtist.setText(song.getTitle());
        nowPlayingBar.setVisible(true); nowPlayingBar.setManaged(true);

        downloadService.downloadAudio(song, target.getId())
            .thenAccept(path -> Platform.runLater(() -> {
                downloadingNow.remove(vid);
                song.setLocalFilePath(path.toString());
                if (group != null) group.incrementPlayCount();
                libraryService.save(); refreshLibraryPanel(); refreshSidebarList();
                List<Song> locals = target.getSongs().stream().filter(Song::isLocal).collect(Collectors.toList());
                playSongInQueue(song, locals.isEmpty() ? List.of(song) : locals);
            }))
            .exceptionally(ex -> {
                Platform.runLater(() -> {
                    downloadingNow.remove(vid);
                    showToast("Error: " + (ex.getCause() != null ? ex.getCause() : ex).getMessage());
                    updateMiniPlayerVisibility();
                });
                return null;
            });
    }

    // ── Search ────────────────────────────────────────────────────────────────

    @FXML private void onToggleSearchMode() {
        searchPlaylistMode = !searchPlaylistMode;
        UIUtils.toggleStyleClass(btnSearchPlaylists, "toggle-btn-active",  searchPlaylistMode);
        UIUtils.toggleStyleClass(btnSearchVideos,    "toggle-btn-active", !searchPlaylistMode);
        if (!searchField.getText().trim().isEmpty()) onSearchAction();
    }

    @FXML private void onSearchAction() {
        String query = searchField.getText().trim(); if (query.isEmpty()) return;

        // ── Detect YouTube URL ────────────────────────────────────────────────
        String videoId    = extractYouTubeVideoId(query);
        String playlistId = extractYouTubePlaylistId(query);

        if (playlistId != null) { fetchPlaylistByUrl(playlistId); return; }
        if (videoId    != null) { fetchVideoByUrl(videoId);       return; }

        // ── Normal text search ────────────────────────────────────────────────
        openTab("search", "🔍", "🔍 " + query, searchPanel, true, null);
        searchStatusLabel.setText("Buscando «" + query + "»…");
        searchSpinner.setVisible(true); searchResultsPane.getChildren().clear();
        int max = ConfigLoader.getInt("youtube.search.max_results", 20);

        if (searchPlaylistMode) {
            youTubeService.searchPlaylists(query, max)
                .thenAccept(pl -> Platform.runLater(() -> {
                    searchSpinner.setVisible(false);
                    if (pl.isEmpty()) { searchStatusLabel.setText("Sin resultados."); return; }
                    searchStatusLabel.setText(pl.size() + " playlists");
                    pl.forEach(p -> searchResultsPane.getChildren().add(
                        CardBuilder.playlistCard(p, () -> importPlaylistToLibrary(p))));
                }))
                .exceptionally(ex -> { Platform.runLater(() -> { searchSpinner.setVisible(false); searchStatusLabel.setText("Error de conexión."); }); return null; });
        } else {
            youTubeService.search(query, max)
                .thenAccept(songs -> Platform.runLater(() -> {
                    searchSpinner.setVisible(false);
                    if (songs.isEmpty()) { searchStatusLabel.setText("Sin resultados."); return; }
                    searchStatusLabel.setText(songs.size() + " resultados");
                    songs.forEach(s -> searchResultsPane.getChildren().add(
                        CardBuilder.songCard(s, () -> downloadAndPlay(s, null), UIUtils::openInBrowser, this::showGroupSelector, libraryService)));
                }))
                .exceptionally(ex -> { Platform.runLater(() -> { searchSpinner.setVisible(false); searchStatusLabel.setText("Error de conexión."); }); return null; });
        }
    }

    private String extractYouTubeVideoId(String input) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?v=|shorts/|embed/)|youtu\\.be/)([a-zA-Z0-9_-]{11})"
        ).matcher(input);
        return m.find() ? m.group(1) : null;
    }

    private String extractYouTubePlaylistId(String input) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "[?&]list=([a-zA-Z0-9_-]+)"
        ).matcher(input);
        return m.find() ? m.group(1) : null;
    }

    private void fetchVideoByUrl(String videoId) {
        openTab("search", "🔍", "🔍 vídeo", searchPanel, true, null);
        searchStatusLabel.setText("Obteniendo vídeo…");
        searchSpinner.setVisible(true); searchResultsPane.getChildren().clear();
        youTubeService.getVideoById(videoId)
            .thenAccept(song -> Platform.runLater(() -> {
                searchSpinner.setVisible(false);
                if (song == null) { searchStatusLabel.setText("Vídeo no encontrado."); return; }
                searchStatusLabel.setText("1 resultado");
                searchResultsPane.getChildren().add(
                    CardBuilder.songCard(song, () -> downloadAndPlay(song, null), UIUtils::openInBrowser, this::showGroupSelector, libraryService));
            }))
            .exceptionally(ex -> { Platform.runLater(() -> { searchSpinner.setVisible(false); searchStatusLabel.setText("Error de conexión."); }); return null; });
    }

    private void fetchPlaylistByUrl(String playlistId) {
        openTab("search", "🔍", "🔍 playlist", searchPanel, true, null);
        searchStatusLabel.setText("Obteniendo playlist…");
        searchSpinner.setVisible(true); searchResultsPane.getChildren().clear();
        youTubeService.getPlaylistById(playlistId)
            .thenAccept(pl -> Platform.runLater(() -> {
                searchSpinner.setVisible(false);
                if (pl == null) { searchStatusLabel.setText("Playlist no encontrada."); return; }
                searchStatusLabel.setText("1 playlist");
                searchResultsPane.getChildren().add(
                    CardBuilder.playlistCard(pl, () -> importPlaylistToLibrary(pl)));
            }))
            .exceptionally(ex -> { Platform.runLater(() -> { searchSpinner.setVisible(false); searchStatusLabel.setText("Error de conexión."); }); return null; });
    }

    @FXML private void onQuickSearchRecent()    { searchField.setText("top hits 2024");  onSearchAction(); }
    @FXML private void onQuickSearchFavorites() { searchField.setText("favorites mix");   onSearchAction(); }
    @FXML private void onQuickSearchRadio()     { searchField.setText("lofi radio");      onSearchAction(); }
    @FXML private void onQuickSearchNew()       { searchField.setText("new music 2025");  onSearchAction(); }
    @FXML private void onOpenInBrowser()        { if (focusedPlayer != null && focusedPlayer.song != null && !focusedPlayer.song.isLocal()) UIUtils.openInBrowser(focusedPlayer.song.getVideoId()); }
    @FXML private void onShowQueue()            { /* reservado */ }

    // ── Library ───────────────────────────────────────────────────────────────

    @FXML private void onNewGroupAction() {
        TextInputDialog dlg = new TextInputDialog();
        dlg.setTitle("Nueva colección"); dlg.setHeaderText(null); dlg.setContentText("Nombre de la colección:");
        dlg.showAndWait().filter(s -> !s.isBlank()).ifPresent(name -> {
            LibraryGroup g = libraryService.createGroup(name);
            refreshLibraryPanel();
            openTab("library", "📚", "Biblioteca", libraryPanel, true, btnLibrary);
            offerImportFolder(g);
        });
    }

    private void importPlaylistToLibrary(YouTubePlaylistInfo pl) {
        if (libraryService.getGroups().stream().anyMatch(g -> g.getId().equals(pl.getPlaylistId()))) {
            showToast("Ya está en tu biblioteca"); return;
        }
        LibraryGroup group = LibraryGroup.fromYouTubePlaylist(pl.getPlaylistId(), pl.getTitle(), pl.getThumbnailUrl(), pl.getDescription());
        libraryService.addGroup(group);
        youTubeService.getPlaylistItems(pl.getPlaylistId())
            .thenAccept(songs -> Platform.runLater(() -> {
                songs.forEach(group::addSong);
                refreshLibraryPanel();
                showToast("«" + pl.getTitle() + "» añadida a la biblioteca");
            }));
    }

    private void refreshLibraryPanel() {
        libraryGroupsContainer.getChildren().clear();
        if (libraryService.getGroups().isEmpty()) {
            Label empty = new Label("Aún no tienes colecciones.\nUsa «Nueva colección» para empezar.");
            empty.getStyleClass().add("empty-library-hint"); empty.setWrapText(true);
            libraryGroupsContainer.getChildren().add(empty); return;
        }
        libraryService.getGroups().forEach(g ->
            libraryGroupsContainer.getChildren().add(GroupDetailBuilder.groupSection(g,
                () -> { libraryService.removeGroup(g); refreshLibraryPanel(); refreshSidebarList(); },
                btn -> refreshYouTubePlaylist(g, btn),
                () -> showGroupDetail(g)))
        );
    }

    private void showGroupDetail(LibraryGroup group) {
        String tabId = "col:" + group.getId();
        AppTab existing = findTab(tabId); if (existing != null) { activateTab(existing); return; }
        VBox panel = GroupDetailBuilder.detailPanel(group,
            () -> { refreshLibraryPanel(); openTab("library", "📚", "Biblioteca", libraryPanel, true, btnLibrary); },
            () -> { List<Song> loc = group.getSongs().stream().filter(Song::isLocal).collect(Collectors.toList());
                    if (!loc.isEmpty()) playSongInQueue(loc.get(0), loc); else showToast("Sin archivos locales"); },
            () -> { importFolder(group); showGroupDetail(group); },
            btn -> refreshYouTubePlaylist(group, btn),
            () -> onDownloadAllAction(group),
            UIUtils::openInBrowser,
            (song, grp) -> downloadAndPlay(song, grp),
            (song, grp) -> openSongPaused(song, grp),
            songs -> openMashupPlayer(songs.get(0), songs.get(1)),
            libraryService, this::showToast);
        openTab(tabId, group.isYoutubePlaylist() ? "📺" : "📋", group.getName(), panel, true, btnLibrary);
        applyContrastStroke(panel, "bardo-text", "bardo-bg");
    }

    private void openMashupPlayer(Song songA, Song songB) {
        if (!songA.isLocal()) { showToast("Descarga «" + songA.getTitle() + "» primero"); return; }
        if (!songB.isLocal()) { showToast("Descarga «" + songB.getTitle() + "» primero"); return; }

        String tabId = "mashup:" + System.currentTimeMillis();

        PlayerInstance piA = new PlayerInstance(tabId);
        piA.volume = 1.0;
        piA.looping = true;
        piA.queue.add(songA);

        PlayerInstance piB = new PlayerInstance(tabId + ":b");
        piB.isMashupLinked = true;
        piB.volume = 0.0;
        piB.looping = true;
        piB.queue.add(songB);

        piA.mashupPartner = piB;

        MashupPanelBuilder.build(piA, piB,
            () -> toggleMashupPlay(piA),
            () -> crossfade(piA));

        activePlayers.add(piA);
        activePlayers.add(piB);

        loadMashupSong(piA, songA);
        loadMashupSong(piB, songB);

        openTab(tabId, "⇄", "Mashup", piA.panel, true, null);
        setFocusedPlayer(piA);
        updateMiniPlayerVisibility();
    }

    private void loadMashupSong(PlayerInstance pi, Song song) {
        if (pi.mediaPlayer != null) { pi.mediaPlayer.stop(); pi.mediaPlayer.dispose(); pi.mediaPlayer = null; }
        pi.song = song;
        java.io.File file = new java.io.File(song.getLocalFilePath());
        if (!file.exists()) { showToast("Archivo no encontrado: " + file.getName()); return; }
        try {
            javafx.scene.media.Media media = new javafx.scene.media.Media(file.toURI().toString());
            pi.mediaPlayer = new javafx.scene.media.MediaPlayer(media);
            pi.mediaPlayer.setVolume(pi.volume);
            pi.mediaPlayer.setCycleCount(1);
            pi.mediaPlayer.setOnEndOfMedia(() ->
                javafx.application.Platform.runLater(() -> {
                    if (pi.mediaPlayer != null) { pi.mediaPlayer.seek(Duration.ZERO); pi.mediaPlayer.play(); }
                })
            );
            pi.mediaPlayer.setOnError(() -> showToast("Error al reproducir: " + file.getName()));
            media.durationProperty().addListener((obs, old, dur) -> {
                if (dur != null && dur.greaterThan(Duration.ZERO) && !dur.equals(Duration.UNKNOWN)) {
                    String totalStr = UIUtils.formatTime((int) dur.toSeconds());
                    javafx.application.Platform.runLater(() -> {
                        if (pi.panelTotal != null) pi.panelTotal.setText(totalStr);
                        if (pi == focusedPlayer) timeTotal.setText(totalStr);
                    });
                }
            });
            pi.mediaPlayer.play();
            pi.isPlaying = true;
        } catch (Exception e) {
            showToast("No se puede reproducir: " + file.getName());
        }
    }

    private void refreshYouTubePlaylist(LibraryGroup group, Button refreshBtn) {
        String original = refreshBtn.getText(); refreshBtn.setDisable(true); refreshBtn.setText("…");
        youTubeService.getPlaylistItems(group.getYoutubePlaylistId())
            .thenAccept(songs -> Platform.runLater(() -> {
                Set<String> existing = group.getSongs().stream().map(Song::getVideoId).collect(Collectors.toSet());
                List<Song> newSongs = songs.stream().filter(s -> !existing.contains(s.getVideoId())).collect(Collectors.toList());
                for (int i = newSongs.size() - 1; i >= 0; i--) group.getSongs().add(0, newSongs.get(i));
                refreshBtn.setDisable(false); refreshBtn.setText(original);
                int added = newSongs.size();
                showToast(added > 0
                    ? "+" + added + " canción" + (added == 1 ? "" : "es") + " nueva" + (added == 1 ? "" : "s") + " en «" + group.getName() + "»"
                    : "«" + group.getName() + "» ya está al día");
                refreshLibraryPanel();
            }))
            .exceptionally(ex -> {
                Platform.runLater(() -> { refreshBtn.setDisable(false); refreshBtn.setText(original); showToast("Error al actualizar: " + (ex.getCause() != null ? ex.getCause() : ex).getMessage()); });
                return null;
            });
    }

    private void onDownloadAllAction(LibraryGroup group) {
        List<Song> toDownload = group.getSongs().stream().filter(s -> !s.isLocal()).collect(Collectors.toList());
        if (toDownload.isEmpty()) { showToast("Todas las canciones ya están descargadas"); return; }
        DownloadDialogs.confirmAndStart(group, toDownload, contentArea.getScene().getWindow(),
            downloadService, libraryService,
            getClass().getResource("/com/musicplayer/styles/main.css").toExternalForm(),
            this::showToast, () -> { libraryService.save(); refreshLibraryPanel(); });
    }

    private void offerImportFolder(LibraryGroup group) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Importar carpeta"); alert.setHeaderText(null);
        alert.setContentText("¿Quieres añadir archivos de una carpeta a «" + group.getName() + "»?");
        alert.showAndWait().filter(r -> r == ButtonType.OK).ifPresent(r -> importFolder(group));
    }

    private void importFolder(LibraryGroup group) {
        DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("Seleccionar carpeta de música");
        File dir = chooser.showDialog(contentArea.getScene().getWindow()); if (dir == null) return;
        List<File> files = scanAudioFiles(dir);
        if (files.isEmpty()) { showToast("No se encontraron archivos de audio en la carpeta"); return; }
        files.forEach(f -> group.addSong(Song.fromLocalFile(f)));
        refreshLibraryPanel();
        showToast(files.size() + " archivos añadidos a «" + group.getName() + "»");
    }

    private List<File> scanAudioFiles(File dir) {
        Set<String> ext = Set.of("mp3", "wav", "m4a", "aac", "mp4", "aif", "aiff", "ogg");
        File[] files = dir.listFiles(); if (files == null) return Collections.emptyList();
        return Arrays.stream(files).filter(File::isFile)
            .filter(f -> { String n = f.getName().toLowerCase(); int d = n.lastIndexOf('.'); return d >= 0 && ext.contains(n.substring(d + 1)); })
            .sorted(Comparator.comparing(f -> f.getName().toLowerCase()))
            .collect(Collectors.toList());
    }

    private void showGroupSelector(Song song, Node anchor) {
        ContextMenu menu = new ContextMenu();
        MenuItem newItem = new MenuItem("＋  Nueva colección");
        newItem.setOnAction(e -> {
            TextInputDialog dlg = new TextInputDialog();
            dlg.setTitle("Nueva colección"); dlg.setHeaderText(null); dlg.setContentText("Nombre:");
            dlg.showAndWait().filter(s -> !s.isBlank()).ifPresent(name -> {
                LibraryGroup g = libraryService.createGroup(name);
                libraryService.addSongToGroup(song, g); refreshLibraryPanel(); showToast("Añadido a «" + name + "»");
            });
        });
        menu.getItems().add(newItem);
        List<LibraryGroup> groups = libraryService.getGroups();
        if (!groups.isEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
            groups.forEach(g -> {
                MenuItem item = new MenuItem((g.isYoutubePlaylist() ? "📺 " : "🎵 ") + g.getName());
                item.setOnAction(ev -> { libraryService.addSongToGroup(song, g); refreshLibraryPanel(); showToast("Añadido a «" + g.getName() + "»"); });
                menu.getItems().add(item);
            });
        }
        menu.show(anchor, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    // ── Animations ────────────────────────────────────────────────────────────

    private void animateNowPlaying() {
        FadeTransition ft = new FadeTransition(Duration.millis(400), nowPlayingBar);
        ft.setFromValue(0.6); ft.setToValue(1.0);
        TranslateTransition tt = new TranslateTransition(Duration.millis(300), nowPlayingBar);
        tt.setFromY(10); tt.setToY(0);
        new ParallelTransition(ft, tt).play();
    }

    private void animateEntrance() {
        sidebar.setOpacity(0); contentArea.setOpacity(0);
        FadeTransition fadeSide   = new FadeTransition(Duration.millis(500), sidebar);    fadeSide.setFromValue(0);   fadeSide.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(500), sidebar); slide.setFromX(-30);          slide.setToX(0);
        FadeTransition fadeMain   = new FadeTransition(Duration.millis(600), contentArea); fadeMain.setFromValue(0);   fadeMain.setToValue(1); fadeMain.setDelay(Duration.millis(200));
        new ParallelTransition(new ParallelTransition(fadeSide, slide), fadeMain).play();
    }

    private void refreshHomePanel() {
        homeCarouselTimelines.forEach(Timeline::stop);
        homeCarouselTimelines.clear();
        homePanel.getChildren().clear();
        homePanel.setPadding(new javafx.geometry.Insets(28, 28, 28, 28));

        int hour = java.time.LocalTime.now().getHour();
        Label greeting = new Label(hour < 12 ? "Buenos días ☀️" : hour < 18 ? "Buenas tardes 🌤" : "Buenas noches 🌙");
        greeting.getStyleClass().add("greeting");
        Label sub = new Label("Tu música destacada");
        sub.getStyleClass().add("greeting-sub");
        homePanel.getChildren().add(new VBox(4, greeting, sub));

        List<Song>         pinned = new java.util.ArrayList<>(libraryService.getPinnedSongs());
        List<LibraryGroup> top    = libraryService.getGroups().stream()
            .filter(g -> g.getPlayCount() >= 5)
            .sorted(Comparator.comparingInt(LibraryGroup::getPlayCount).reversed())
            .limit(3)
            .collect(Collectors.toList());

        if (pinned.isEmpty() && top.isEmpty()) {
            Label hint = new Label(
                "Aquí aparecerán tus canciones pineadas y tus colecciones más escuchadas.\n" +
                "Pulsa 📌 en cualquier canción para pinearla, o reproduce canciones de una colección\n" +
                "(se necesitan al menos 5 reproducciones para que aparezca).");
            hint.setWrapText(true);
            hint.getStyleClass().add("empty-library-hint");
            VBox.setVgrow(hint, Priority.ALWAYS);
            homePanel.getChildren().add(hint);
            return;
        }

        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("results-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox sections = new VBox(24);
        sections.setPadding(new javafx.geometry.Insets(4, 0, 12, 0));

        if (!pinned.isEmpty()) {
            Label sectionLbl = new Label("CANCIONES PINEADAS");
            sectionLbl.getStyleClass().add("sidebar-section-label");
            javafx.scene.layout.FlowPane pinnedFlow = new javafx.scene.layout.FlowPane(20, 20);
            pinnedFlow.setAlignment(Pos.TOP_LEFT);
            pinned.forEach(s -> pinnedFlow.getChildren().add(buildPinnedSongCard(s)));
            sections.getChildren().addAll(sectionLbl, pinnedFlow);
        }

        if (!top.isEmpty()) {
            Label sectionLbl = new Label("COLECCIONES MÁS ESCUCHADAS");
            sectionLbl.getStyleClass().add("sidebar-section-label");
            HBox topRow = new HBox(20);
            topRow.setAlignment(Pos.CENTER_LEFT);
            top.forEach(g -> topRow.getChildren().add(buildTopPlaylistCard(g)));
            sections.getChildren().addAll(sectionLbl, topRow);
        }

        scroll.setContent(sections);
        homePanel.getChildren().add(scroll);
    }

    private VBox buildTopPlaylistCard(LibraryGroup group) {
        VBox card = new VBox(10);
        card.getStyleClass().add("home-playlist-card");

        List<String> thumbUrls = group.getSongs().stream()
            .map(Song::getThumbnailUrl)
            .filter(url -> url != null && !url.isBlank())
            .distinct()
            .collect(Collectors.toList());
        Collections.shuffle(thumbUrls);

        ImageView imgView = new ImageView();
        imgView.setFitWidth(220); imgView.setFitHeight(220);
        imgView.setPreserveRatio(false);
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(220, 220);
        clip.setArcWidth(18); clip.setArcHeight(18);
        imgView.setClip(clip);
        imgView.setStyle("-fx-cursor: hand;");
        imgView.setOnMouseClicked(e -> showGroupDetail(group));

        if (!thumbUrls.isEmpty()) CardBuilder.loadImage(imgView, thumbUrls.get(0));

        Button removeBtn = new Button("✕");
        removeBtn.getStyleClass().add("home-overlay-btn");
        removeBtn.setOpacity(0);
        StackPane.setAlignment(removeBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(removeBtn, new javafx.geometry.Insets(7, 7, 0, 0));
        removeBtn.setOnAction(e -> { group.setPlayCount(0); libraryService.save(); refreshHomePanel(); });

        StackPane imgContainer = new StackPane(imgView, removeBtn);
        imgContainer.setPrefSize(220, 220); imgContainer.setMaxSize(220, 220);
        imgContainer.getStyleClass().add("home-playlist-thumb");
        imgContainer.setOnMouseEntered(e -> { FadeTransition ft = new FadeTransition(Duration.millis(150), removeBtn); ft.setToValue(1); ft.play(); });
        imgContainer.setOnMouseExited(e  -> { FadeTransition ft = new FadeTransition(Duration.millis(150), removeBtn); ft.setToValue(0); ft.play(); });

        if (thumbUrls.size() > 1) {
            int[] idx = {0};
            Timeline slider = new Timeline(new KeyFrame(Duration.seconds(5), e -> {
                idx[0] = (idx[0] + 1) % thumbUrls.size();
                FadeTransition out = new FadeTransition(Duration.millis(400), imgView);
                out.setFromValue(1); out.setToValue(0);
                out.setOnFinished(ev -> {
                    CardBuilder.loadImage(imgView, thumbUrls.get(idx[0]));
                    FadeTransition in = new FadeTransition(Duration.millis(400), imgView);
                    in.setFromValue(0); in.setToValue(1); in.play();
                });
                out.play();
            }));
            slider.setCycleCount(Animation.INDEFINITE);
            slider.play();
            homeCarouselTimelines.add(slider);
        }

        Label nameLbl = new Label(group.getName());
        nameLbl.getStyleClass().add("home-playlist-name");
        nameLbl.setMaxWidth(220);

        Label countLbl = new Label(group.getPlayCount() + " reproducciones");
        countLbl.getStyleClass().add("home-playlist-count");

        card.getChildren().addAll(imgContainer, nameLbl, countLbl);
        return card;
    }

    private VBox buildPinnedSongCard(Song song) {
        VBox card = new VBox(10);
        card.getStyleClass().add("home-playlist-card");

        ImageView imgView = new ImageView();
        imgView.setFitWidth(220); imgView.setFitHeight(220);
        imgView.setPreserveRatio(false);
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(220, 220);
        clip.setArcWidth(18); clip.setArcHeight(18);
        imgView.setClip(clip);
        imgView.setStyle("-fx-cursor: hand;");
        if (song.getThumbnailUrl() != null && !song.getThumbnailUrl().isBlank())
            CardBuilder.loadImage(imgView, song.getThumbnailUrl());
        imgView.setOnMouseClicked(e -> downloadAndPlay(song, null));

        Button unpinBtn = new Button("📌");
        unpinBtn.getStyleClass().add("home-overlay-btn");
        unpinBtn.setOpacity(0);
        StackPane.setAlignment(unpinBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(unpinBtn, new javafx.geometry.Insets(7, 7, 0, 0));
        unpinBtn.setOnAction(e -> { libraryService.unpinSong(song.getVideoId()); refreshHomePanel(); });

        StackPane imgContainer = new StackPane(imgView, unpinBtn);
        imgContainer.setPrefSize(220, 220); imgContainer.setMaxSize(220, 220);
        imgContainer.getStyleClass().add("home-playlist-thumb");
        imgContainer.setOnMouseEntered(e -> { FadeTransition ft = new FadeTransition(Duration.millis(150), unpinBtn); ft.setToValue(1); ft.play(); });
        imgContainer.setOnMouseExited(e  -> { FadeTransition ft = new FadeTransition(Duration.millis(150), unpinBtn); ft.setToValue(0); ft.play(); });

        Label titleLbl = new Label(song.getTitle());
        titleLbl.getStyleClass().add("home-playlist-name");
        titleLbl.setMaxWidth(220);

        Label artistLbl = new Label(song.getArtist());
        artistLbl.getStyleClass().add("home-playlist-count");

        card.getChildren().addAll(imgContainer, titleLbl, artistLbl);
        return card;
    }

    // ── Keyboard shortcuts ────────────────────────────────────────────────────

    private void onGlobalKey(KeyEvent e) {
        if (e.getTarget() instanceof TextInputControl) return;
        switch (e.getCode()) {
            case SPACE  -> { if (focusedPlayer != null) { togglePlayInstance(focusedPlayer); e.consume(); } }
            case LEFT   -> { seekRelative(-5); e.consume(); }
            case RIGHT  -> { seekRelative( 5); e.consume(); }
            case UP     -> { adjustVolume( 1); e.consume(); }
            case DOWN   -> { adjustVolume(-1); e.consume(); }
            case F11    -> { setFakeFullScreen(stage(), !fakeFullScreen); e.consume(); }
            default     -> {}
        }
    }

    private void seekRelative(int seconds) {
        if (focusedPlayer == null || focusedPlayer.mediaPlayer == null) return;
        Duration cur = focusedPlayer.mediaPlayer.getCurrentTime(), total = focusedPlayer.mediaPlayer.getMedia().getDuration();
        if (cur == null || total == null) return;
        Duration next = cur.add(Duration.seconds(seconds));
        focusedPlayer.mediaPlayer.seek(next.lessThan(Duration.ZERO) ? Duration.ZERO : next.greaterThan(total) ? total : next);
    }

    private void adjustVolume(int delta) {
        volumeSlider.setValue(Math.max(0, Math.min(100, volumeSlider.getValue() + delta)));
    }

    // ── Volume ducking ────────────────────────────────────────────────────────

    private double effectiveVolume(PlayerInstance pi) {
        if (pi.song == null || !"Ambiente".equals(pi.song.getType())) return pi.volume;
        boolean nonAmbientePlaying = activePlayers.stream().anyMatch(p ->
            p != pi && p.isPlaying && !p.isMashupLinked &&
            (p.song == null || !"Ambiente".equals(p.song.getType())));
        return nonAmbientePlaying ? pi.volume * ambientDuckRatio : pi.volume;
    }

    private void applyVolumesToAll() {
        for (PlayerInstance p : new ArrayList<>(activePlayers)) {
            if (p.isMashupLinked || p.mashupPartner != null) continue;
            if (p.mediaPlayer != null && p.fadeOutAnim == null)
                p.mediaPlayer.setVolume(effectiveVolume(p));
        }
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    private void showToast(String message) {
        Platform.runLater(() -> {
            if (toastAnim != null) { toastAnim.stop(); toastAnim = null; }
            if (toastPopup != null) toastPopup.hide();

            Label lbl = new Label("✓  " + message);
            lbl.setStyle(
                "-fx-background-color: #5a3878;" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 13px;" +
                "-fx-font-weight: bold;" +
                "-fx-padding: 10 22 10 22;" +
                "-fx-background-radius: 20px;" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 12, 0.2, 0, 3);"
            );
            lbl.setOpacity(0);

            toastPopup = new javafx.stage.Popup();
            toastPopup.setAutoHide(false);
            toastPopup.getContent().add(lbl);

            javafx.stage.Stage st = stage();
            toastPopup.show(st, st.getX(), st.getY() + 62);
            // reposition to center once the label has been laid out
            lbl.widthProperty().addListener(new javafx.beans.value.ChangeListener<Number>() {
                @Override
                public void changed(javafx.beans.value.ObservableValue<? extends Number> o,
                                    Number old, Number w) {
                    if (w.doubleValue() > 0) {
                        toastPopup.setX(st.getX() + (st.getWidth() - w.doubleValue()) / 2.0);
                        lbl.widthProperty().removeListener(this);
                    }
                }
            });

            FadeTransition fadeIn = new FadeTransition(Duration.millis(220), lbl);
            fadeIn.setToValue(1);

            PauseTransition hold = new PauseTransition(Duration.millis(2200));

            FadeTransition fadeOut = new FadeTransition(Duration.millis(350), lbl);
            fadeOut.setToValue(0);

            toastAnim = new SequentialTransition(fadeIn, hold, fadeOut);
            toastAnim.setOnFinished(e -> { toastPopup.hide(); toastPopup = null; toastAnim = null; });
            toastAnim.play();
        });
    }
}
