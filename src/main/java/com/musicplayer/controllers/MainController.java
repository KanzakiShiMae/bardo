package com.musicplayer.controllers;

import com.musicplayer.models.LibraryGroup;
import com.musicplayer.models.Song;
import com.musicplayer.models.YouTubePlaylistInfo;
import com.musicplayer.services.ConfigLoader;
import com.musicplayer.services.DownloadService;
import com.musicplayer.services.LibraryService;
import com.musicplayer.services.PersistenceService;
import com.musicplayer.services.SpectrogramService;
import com.musicplayer.services.YouTubeQuotaTracker;
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
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;

import ws.schild.jave.MultimediaObject;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Controlador principal de la aplicación Bardo.
 *
 * <p>Actúa como coordinador central: gestiona el sistema de pestañas, los reproductores
 * activos, la pantalla de inicio, la búsqueda en YouTube, la biblioteca local y las
 * descargas de audio. Los subsistemas más complejos están extraídos en builders dedicados:
 * <ul>
 *   <li>{@link ThemeManager} — todo el estado y la lógica de temas y colores dinámicos.</li>
 *   <li>{@link SettingsPanelBuilder} — construcción del panel de Configuración.</li>
 *   <li>{@link PlayerPanelBuilder} — construcción del panel de reproductor expandido.</li>
 *   <li>{@link GroupDetailBuilder} — construcción del panel de detalle de colección.</li>
 *   <li>{@link MashupPanelBuilder} — construcción del panel de mashup.</li>
 *   <li>{@link CardBuilder} — tarjetas de canción y playlist en resultados de búsqueda.</li>
 * </ul>
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
 * (sección "CANCIONES PINEADAS"), las tres colecciones más reproducidas con ≥ 5
 * reproducciones (sección "COLECCIONES MÁS ESCUCHADAS") y, si el tracker de cuota está
 * habilitado, el widget de cuota diaria de la YouTube API. El contador de uso se incrementa
 * en {@link #downloadAndPlay} cada vez que se reproduce una canción de un grupo.
 *
 * <p><b>Cuota de YouTube API:</b> {@link com.musicplayer.services.YouTubeQuotaTracker}
 * estima el consumo diario de unidades de la API (búsqueda = 100 unidades, resto = 1).
 * Cuando se alcanza el límite, se bloquean las llamadas a la API hasta la medianoche
 * (hora del Pacífico) y aparece un temporizador de cuenta atrás en el panel de inicio
 * ligado reactivamente a {@code exhaustedProperty}. Las claves de desarrollador (prefijo
 * {@code "AIza"}, sufijo {@code "C5ik"}) activan el modo forzado: el límite de 1 000
 * unidades no puede desactivarse desde Configuración.
 *
 * <p><b>Pantalla de carga:</b> {@link LoadingOverlay} muestra un overlay animado durante
 * el arranque. Las piezas del icono entran desde las esquinas de la pantalla en una
 * animación de entrada obligatoria; después se ejecuta el bucle de animación hasta que
 * todas las tareas asíncronas hayan concluido y haya transcurrido el tiempo mínimo.
 *
 * <p><b>Ventana redimensionable:</b> el stage usa {@code UNDECORATED}; {@link ResizeHelper}
 * añade redimensionado por bordes. Doble clic en la barra de título maximiza/restaura;
 * arrastrar hasta el borde superior activa pantalla completa. {@code F11} la alterna.
 *
 * <p><b>Colores dinámicos:</b> {@link ThemeManager#updateDynamicColors()} extrae los colores
 * dominantes de la miniatura del reproductor enfocado y los aplica mediante fade (~700 ms)
 * a las variables configuradas en modo {@code DYN_PRIMARY} o {@code DYN_SECONDARY}.
 *
 * <p><b>Barra de título:</b> el label {@code appTitleLabel} muestra {@code "Bardo v{version}"},
 * donde la versión se lee de {@code app.properties} via {@link com.musicplayer.services.ConfigLoader#getVersion()},
 * generado por Maven en tiempo de build a partir de {@code <version>} en {@code pom.xml}.
 */
public class MainController implements Initializable {

    // ── FXML fields ───────────────────────────────────────────────────────────
    @FXML private javafx.scene.layout.StackPane mainRootPane;
    @FXML private HBox   titleBar;
    @FXML private Label  appTitleLabel;
    @FXML private Button btnClose, btnMinimize, btnMaximize;

    @FXML private ImageView        sidebarLogo;
    @FXML private ImageView        sidebarLogoNext;
    @FXML private ImageView        sidebarLogoFilter;
    @FXML private ImageView        sidebarLogoBorder;
    @FXML private javafx.scene.layout.StackPane sidebarLogoStack;
    @FXML private VBox             sidebar;
    @FXML private Button           btnHome, btnLibrary, btnSettings, btnNewGroup, btnParty;
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
    @FXML private Button    btnPrev, btnPlayPause, btnNext, btnShuffle, btnRepeat;
    @FXML private Slider    progressSlider, volumeSlider;
    @FXML private Label     timeElapsed, timeTotal, volumeLabel;
    @FXML private StackPane vinylContainer;
    @FXML private javafx.scene.canvas.Canvas miniWaveCanvas;

    private LoadingOverlay loadingOverlay;

    private final List<Timeline> homeCarouselTimelines = new ArrayList<>();
    private Image  logoPngSource;
    private byte[] logoZoneMap;
    private final java.util.concurrent.atomic.AtomicBoolean logoRecolorBusy =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private javafx.animation.Animation logoFadeAnim;

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean isShuffle, searchPlaylistMode, seekingByUser, titleBarDragging, fakeFullScreen;
    private double  windowX, windowY, savedStageX, savedStageY, savedStageW, savedStageH;

    private ThemeManager themeManager;

    private ResizeHelper     resizeHelper;
    private Timeline         globalProgressTimer;
    private javafx.beans.value.ChangeListener<Boolean> quotaExhaustedListener;
    private PauseTransition  volumeSavePause;
    private javafx.stage.Popup toastPopup;
    private SequentialTransition toastAnim;

    private YouTubeService      youTubeService;
    private YouTubeQuotaTracker quotaTracker;
    private LibraryService      libraryService;
    private DownloadService     downloadService;
    private SpectrogramService  spectrogramService;

    private final Set<String>          downloadingNow = new HashSet<>();
    private final List<AppTab>         openTabs       = new ArrayList<>();
    private final List<PlayerInstance> activePlayers  = new ArrayList<>();
    private final ArrayDeque<String>   tabHistory     = new ArrayDeque<>();
    private AppTab         activeTab;
    private PlayerInstance focusedPlayer;
    private PartyPanelBuilder partyPanelBuilder;

    // Ambient ducking
    private double ambientDuckRatio = 0.60;

    private final Map<String, double[]> loopMarkerMemory = new HashMap<>();

    private float[] miniWavePeaks = new float[32];

    // Cached theme colors for drawWaveCanvas — rebuilt only on theme change
    private String cachedWaveHexA, cachedWaveHexA2;
    private Color  cachedWaveCa,   cachedWaveCa2;

    // Cached gradients for drawWaveCanvas, one slot for the main panel canvases and one
    // for the mini canvas — rebuilt only when width or colors change, not every frame.
    private final LinearGradient[] cachedFillGrad   = new LinearGradient[2];
    private final LinearGradient[] cachedLineGrad   = new LinearGradient[2];
    private final double[]         cachedGradW      = {-1, -1};
    private final String[]         cachedGradHexA   = new String[2];
    private final String[]         cachedGradHexA2  = new String[2];

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
        loadingOverlay = new LoadingOverlay(mainRootPane, 1);
        loadingOverlay.setTargetNode(sidebarLogoStack);
        loadingOverlay.start();


        String v = ConfigLoader.getVersion();
        appTitleLabel.setText(v.isBlank() ? "Bardo" : "Bardo v" + v);

        libraryService = LibraryService.getInstance();
        spectrogramService = new SpectrogramService();
        themeManager = new ThemeManager(libraryService, () -> focusedPlayer, activePlayers,
            sidebar, tabBarScroll, nowPlayingBar, contentArea);
        themeManager.loadFromPersistence();
        String apiKey   = libraryService.loadYouTubeApiKey();
        if (apiKey == null || apiKey.isBlank()) apiKey = ConfigLoader.get("youtube.api.key");
        quotaTracker    = new YouTubeQuotaTracker(apiKey);
        youTubeService  = new YouTubeService(apiKey, quotaTracker);
        downloadService = new DownloadService();
        quotaTracker.exhaustedProperty().addListener((obs, wasEx, isEx) -> {
            if (isEx) showToast("Cuota de YouTube API agotada. La búsqueda se reanudará a medianoche (hora del Pacífico).");
        });

        URL iconUrl   = getClass().getResource("/com/musicplayer/icons/icon.png");
        URL filterUrl = getClass().getResource("/com/musicplayer/icons/icon_filter.png");
        URL borderUrl = getClass().getResource("/com/musicplayer/icons/icon_border.png");
        {
            final URL iconRef = iconUrl, filterRef = filterUrl, borderRef = borderUrl;
            Thread prep = new Thread(() -> {
                try {
                    Image png    = iconRef   != null ? new Image(iconRef.openStream())   : null;
                    Image filter = filterRef != null ? new Image(filterRef.openStream()) : null;
                    Image border = borderRef != null ? new Image(borderRef.openStream()) : null;
                    byte[] zoneMap = png != null ? buildZoneMapFromPng(png) : null;
                    Platform.runLater(() -> {
                        if (filter != null) sidebarLogoFilter.setImage(filter);
                        if (border != null) sidebarLogoBorder.setImage(border);
                        if (png != null) {
                            logoPngSource = png;
                            logoZoneMap   = zoneMap;
                            recolorLogo();
                        }
                        loadingOverlay.completeTask();
                    });
                } catch (Exception ex) { ex.printStackTrace(); }
            }, "bardo-logo-prep");
            prep.setDaemon(true);
            prep.start();
        }
        themeManager.onFadeStart = this::recolorLogo;

        setupTitleBar();
        setupWindowDrag();
        setupProgressSlider();
        setupVolumeSlider();
        volumeSlider.setValue(libraryService.loadVolume());
        setupSidebarNavigation();
        SettingsPanelBuilder.build(settingsPanel, themeManager, libraryService, quotaTracker,
            spectrogramService,
            pct -> { ambientDuckRatio = pct / 100.0; applyVolumesToAll(); },
            this::changeAudioDir);
        ambientDuckRatio = libraryService.loadAmbientDuck() / 100.0;
        applyCircularClip();
        setupLogoDrag();

        btnPlayPause.setOnAction(e -> { if (focusedPlayer != null) togglePlayInstance(focusedPlayer); });
        btnShuffle.setOnAction(e   -> toggleInlineSpectrogram(focusedPlayer));
        btnRepeat.setOnAction(e    -> toggleRepeat());
        btnPrev.setOnAction(e      -> playPrevInInstance(focusedPlayer));
        btnNext.setOnAction(e      -> playNextInInstance(focusedPlayer));

        nowPlayingBar.setVisible(false); nowPlayingBar.setManaged(false);
        contentArea.sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) return;
            themeManager.applyTheme(scene);
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
        btnClose.setOnAction(e -> closeApp());

        // Alt+F4 / cierre del sistema en ventana UNDECORATED → mismo handler
        btnClose.sceneProperty().addListener((obs, o, scene) -> {
            if (scene == null) return;
            scene.windowProperty().addListener((obs2, o2, win) -> {
                if (win instanceof javafx.stage.Stage s)
                    s.setOnCloseRequest(ev -> closeApp());
            });
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

    private void closeApp() {
        if (globalProgressTimer != null) globalProgressTimer.stop();
        activePlayers.forEach(pi -> { if (pi.mediaPlayer != null) pi.mediaPlayer.stop(); });
        spectrogramService.shutdown();
        if (partyPanelBuilder != null) partyPanelBuilder.shutdown();
        Platform.exit();
    }

    /** Obtiene el {@code Stage} principal a partir de cualquier nodo ya adjunto. */
    private javafx.stage.Stage stage() {
        return (javafx.stage.Stage) btnClose.getScene().getWindow();
    }

    /** Sincroniza el icono del botón maximizar con el estado actual de la ventana. */
    private void syncMaximizeIcon(javafx.stage.Stage st) {
        Platform.runLater(() -> btnMaximize.setText(
            (st.isMaximized() || fakeFullScreen) ? "❐" : "⛶"
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
            javafx.geometry.Rectangle2D b = scr.getVisualBounds();
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
        partyPanelBuilder = new PartyPanelBuilder(this, downloadService);
        btnParty.setOnAction(e -> openTab("party", "🎧", "Party", partyPanelBuilder.getPanel(), true, btnParty));

        // ── DEBUG: abre dos ventanas de Party (master + listener) ─────────────
        Button debugPartyBtn = new Button("🔧 DEBUG Party");
        debugPartyBtn.setStyle("-fx-background-color:#c0392b;-fx-text-fill:white;-fx-font-size:10px;-fx-padding:2 8;-fx-background-radius:4;");
        debugPartyBtn.setOnAction(e -> openDebugPartyTabs());
        debugPartyBtn.setVisible(false);
        debugPartyBtn.setManaged(false);
        titleBar.getChildren().add(titleBar.getChildren().size() - 1, debugPartyBtn);

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
        if (tabHistory.isEmpty() || !tabHistory.peek().equals(tab.id))
            tabHistory.push(tab.id);
        contentArea.getChildren().forEach(n -> { n.setVisible(false); n.setManaged(false); n.setMouseTransparent(true); });
        tab.panel.setVisible(true); tab.panel.setManaged(true); tab.panel.setMouseTransparent(false);

        if (tab.id.startsWith("player:") || tab.id.startsWith("mashup:")) {
            PlayerInstance pi = findPlayerInstance(tab.id);
            if (pi != null) {
                setFocusedPlayer(pi);
                // Redibujado inmediato del espectrograma: su ciclo de 300ms está gateado por
                // visibilidad, así que al volver a la pestaña refrescamos ya en vez de esperar.
                if (pi.spectroRedraw != null) pi.spectroRedraw.run();
            }
        }
        updateMiniPlayerVisibility();

        for (Button b : new Button[]{btnHome, btnLibrary, btnSettings, btnParty}) b.getStyleClass().remove("nav-btn-active");
        if (tab.sidebarBtn != null) tab.sidebarBtn.getStyleClass().add("nav-btn-active");

        rebuildTabBar();
    }

    private void closeTab(String id) { doCloseTab(id, false); }

    /** Cierra la pestaña incluso si {@code closeable == false} (uso exclusivo del sistema Party). */
    void closeTabForced(String id) { doCloseTab(id, true); }

    private void doCloseTab(String id, boolean force) {
        AppTab tab = findTab(id);
        if (tab == null || (!force && !tab.closeable)) return;
        int idx = openTabs.indexOf(tab);
        openTabs.remove(tab);
        contentArea.getChildren().remove(tab.panel);

        if (id.startsWith("player:") || id.startsWith("mashup:")) {
            PlayerInstance pi = findPlayerInstance(id);
            if (pi != null) {
                if (pi.isMasterPartyPlayer && partyPanelBuilder != null && pi.song != null)
                    partyPanelBuilder.masterBroadcastCloseTrack(pi.song.getVideoId());
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
        // Remove all history entries for the closed tab
        tabHistory.removeIf(hid -> hid.equals(id));

        if (!openTabs.isEmpty()) {
            if (tab == activeTab) {
                // Navigate to the most recently visited tab still open
                AppTab prev = tabHistory.stream()
                    .map(this::findTab).filter(Objects::nonNull).findFirst().orElse(null);
                activateTab(prev != null ? prev : openTabs.get(Math.max(0, idx - 1)));
            } else {
                rebuildTabBar();
            }
        } else rebuildTabBar();
    }

    private static final double TAB_WIDTH = 168;

    /**
     * Crea el nodo de la barra de pestañas para {@code tab} (estructura fija: layout,
     * label, dot de "reproduciendo", botón de cierre, handlers de clic y arrastre).
     * Se llama una sola vez por pestaña; los cambios posteriores de estado (activa,
     * reproduciendo, título) se aplican con {@link #updateTabNodeStyle} sin recrear el nodo.
     */
    private HBox createTabNode(AppTab tab) {
        HBox btn = new HBox(4); btn.getStyleClass().add("tab-btn");
        btn.setAlignment(Pos.CENTER); btn.setPrefWidth(TAB_WIDTH); btn.setMinWidth(TAB_WIDTH); btn.setMaxWidth(TAB_WIDTH);

        Label lbl = new Label(); lbl.getStyleClass().add("tab-label");
        lbl.setMaxWidth(tab.closeable ? TAB_WIDTH - 48 : TAB_WIDTH - 20); lbl.setMinWidth(0);
        HBox.setHgrow(lbl, Priority.ALWAYS);
        btn.getChildren().add(lbl);

        Label dot = new Label("▶"); dot.setStyle("-fx-font-size: 7px; -fx-text-fill: #e8729a; -fx-padding: 0 2 0 0;");
        dot.setManaged(false); dot.setVisible(false); // visibilidad real la fija updateTabNodeStyle
        btn.getChildren().add(dot);

        if (tab.id.startsWith("player:") && partyPanelBuilder != null) {
            Button partyBtn = new Button("📢"); partyBtn.getStyleClass().add("tab-close-btn");
            partyBtn.visibleProperty().bind(partyPanelBuilder.isMasterProperty());
            partyBtn.managedProperty().bind(partyPanelBuilder.isMasterProperty());
            partyBtn.setOnAction(e -> {
                e.consume();
                PlayerInstance pi = findPlayerInstance(tab.id);
                if (pi != null && pi.song != null) partyPanelBuilder.addSongToParty(pi.song);
            });
            btn.getChildren().add(partyBtn);
        }

        if (tab.closeable) {
            Button x = new Button("×"); x.getStyleClass().add("tab-close-btn");
            final String tid = tab.id; x.setOnAction(e -> { e.consume(); closeTab(tid); });
            btn.getChildren().add(x);
        }

        btn.setUserData(tab);
        btn.setOnMouseClicked(e -> {
            if (tabJustDragged) { tabJustDragged = false; return; }
            if      (e.getButton() == MouseButton.MIDDLE  && tab.closeable)                     closeTab(tab.id);
            else if (e.getButton() == MouseButton.PRIMARY && !(e.getTarget() instanceof Button)) activateTab(tab);
        });
        setupTabDrag(btn, tab);
        return btn;
    }

    /** Aplica al nodo ya existente de {@code tab} su estado actual (activa/reproduciendo/título). */
    private void updateTabNodeStyle(AppTab tab) {
        HBox btn = tab.barNode;
        if (btn == null) return;
        UIUtils.toggleStyleClass(btn, "tab-btn-active", tab == activeTab);

        PlayerInstance tabPi = (tab.id.startsWith("player:") || tab.id.startsWith("mashup:"))
            ? findPlayerInstance(tab.id) : null;
        boolean playing = tabPi != null && tabPi.isPlaying;
        UIUtils.toggleStyleClass(btn, "tab-btn-playing", playing);

        Label lbl = (Label) btn.getChildren().get(0);
        lbl.setText(tab.icon + "  " + tab.title);

        Label dot = (Label) btn.getChildren().get(1);
        dot.setVisible(playing); dot.setManaged(playing);
    }

    /**
     * Sincroniza la barra de pestañas con {@code openTabs} de forma incremental:
     * crea nodos solo para pestañas nuevas, quita los de pestañas cerradas, reordena
     * si hace falta, y refresca el estilo de todas — sin recrear nodos que ya existen.
     * Reemplaza el antiguo "clear + recrear todo" en cada apertura/cierre/cambio de estado.
     */
    private void rebuildTabBar() {
        List<Node> children = tabBar.getChildren();
        children.removeIf(n -> {
            boolean keep = n.getUserData() instanceof AppTab t && openTabs.contains(t);
            if (!keep && n.getUserData() instanceof AppTab removed) removed.barNode = null;
            return !keep;
        });

        for (int i = 0; i < openTabs.size(); i++) {
            AppTab tab = openTabs.get(i);
            HBox node = tab.barNode;
            if (node == null || !children.contains(node)) {
                node = createTabNode(tab);
                tab.barNode = node;
                children.add(Math.min(i, children.size()), node);
            } else {
                int curIdx = children.indexOf(node);
                if (curIdx != i) { children.remove(node); children.add(i, node); }
            }
            updateTabNodeStyle(tab);
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
        if (song.isLocal()) { doOpenSongPaused(song, group); return; }
        final LibraryGroup target = group != null ? group : libraryService.getOrCreateHistorial();
        if (group == null) target.addSong(song);
        downloadThen(song, target, () -> doOpenSongPaused(song, group));
    }

    private void downloadThen(Song song, LibraryGroup target, Runnable onReady) {
        String vid = song.getVideoId();
        if (downloadingNow.contains(vid)) { showToast("Ya se está descargando «" + song.getTitle() + "»"); return; }
        downloadingNow.add(vid);
        nowPlayingTitle.setText("⬇ Descargando…"); nowPlayingArtist.setText(song.getTitle());
        nowPlayingBar.setVisible(true); nowPlayingBar.setManaged(true);

        downloadService.downloadAudio(song, target.getId())
            .thenAccept(path -> Platform.runLater(() -> {
                downloadingNow.remove(vid);
                song.setLocalFilePath(path.toString());
                if (!song.hasDuration()) {
                    try {
                        long ms = new MultimediaObject(path.toFile()).getInfo().getDuration();
                        if (ms > 0) song.setDuration(UIUtils.formatTime((int) (ms / 1000)));
                    } catch (Exception ignored) {}
                }
                spectrogramService.computeFromFile(spectrogramService.getSongId(song), path);
                libraryService.save(); refreshLibraryPanel(); refreshSidebarList();
                onReady.run();
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

    private void doOpenSongPaused(Song song, LibraryGroup group) {
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

        if (prevTab != null) activateTab(prevTab);
        if (prevFocused != null) setFocusedPlayer(prevFocused);
        rebuildTabBar();
    }

    private void buildPanel(PlayerInstance pi) {
        PlayerPanelBuilder.build(pi,
            this::togglePlayInstance,
            p -> navigateTab(-1),
            p -> navigateTab(+1),
            () -> toggleInlineSpectrogram(pi),
            (p, pct) -> {
                if (p == focusedPlayer && Math.abs(volumeSlider.getValue() - pct) > 0.5) volumeSlider.setValue(pct);
                applyVolumesToAll();
                if (pi.isMasterPartyPlayer && partyPanelBuilder != null && pi.song != null)
                    partyPanelBuilder.masterBroadcastVolume(pi.song.getVideoId(), pct / 100.0);
            },
            p -> {
                if (p == focusedPlayer) UIUtils.toggleStyleClass(btnRepeat, "control-active", p.looping);
                if (pi.isMasterPartyPlayer && partyPanelBuilder != null && pi.song != null)
                    partyPanelBuilder.masterBroadcastLoop(pi.song.getVideoId(), p.looping);
            }
        );
        themeManager.applyContrastStroke(pi.panel, "bardo-text", "bardo-player-bg1");
        if (pi.isMasterPartyPlayer && partyPanelBuilder != null)
            pi.onPartySeek = posMs -> { if (pi.song != null) partyPanelBuilder.masterBroadcastSeek(pi.song.getVideoId(), posMs); };
    }

    private void toggleInlineSpectrogram(PlayerInstance pi) {
        if (pi == null || pi.panelSpectroCanvas == null || pi.song == null) {
            showToast("Reproduce una canción para ver el espectrograma.");
            return;
        }
        boolean show = !pi.panelSpectroCanvas.isVisible();
        if (show) {
            String songId = spectrogramService.getSongId(pi.song);
            if (!spectrogramService.hasCached(songId) && !spectrogramService.isGenerating(songId)) {
                String path = pi.song.getLocalFilePath();
                if (path != null) spectrogramService.computeFromFile(songId, Path.of(path));
            }
            if (pi.spectroTimeline != null) pi.spectroTimeline.stop();
            pi.spectroTimeline = SpectrogramPanelBuilder.attachToCanvas(
                pi.panelSpectroCanvas, pi.song, spectrogramService, pi,
                () -> {
                    String hex = themeManager.currentTheme.get("bardo-accent");
                    return hex != null ? javafx.scene.paint.Color.web(hex)
                                       : SpectrogramPanelBuilder.FALLBACK_COLOR;
                });
            pi.panelSpectroCanvas.setVisible(true);
            pi.loopMarkersActive = true;
            pi.stoppedAtLoopOut  = false;
            double[] saved = loopMarkerMemory.get(pi.song.getVideoId());
            pi.loopInPct  = saved != null ? saved[0] : 0.0;
            pi.loopOutPct = saved != null ? saved[1] : 100.0;
            pi.onMarkersChanged = () -> loopMarkerMemory.put(
                pi.song.getVideoId(), new double[]{pi.loopInPct, pi.loopOutPct});
            if (pi.panelProgress != null)
                UIUtils.toggleStyleClass(pi.panelProgress, "spectro-mode", true);
        } else {
            pi.panelSpectroCanvas.setVisible(false);
            pi.loopMarkersActive = false;
            if (pi.spectroTimeline != null) { pi.spectroTimeline.stop(); pi.spectroTimeline = null; }
            pi.panelSpectroCanvas.getGraphicsContext2D().clearRect(
                0, 0, pi.panelSpectroCanvas.getWidth(), pi.panelSpectroCanvas.getHeight());
            if (pi.panelProgress != null)
                UIUtils.toggleStyleClass(pi.panelProgress, "spectro-mode", false);
        }
        if (pi.panelShuffleBtn != null)
            UIUtils.toggleStyleClass(pi.panelShuffleBtn, "control-active-2", show);
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

        pi.stoppedAtLoopOut  = false;
        pi.loopMarkersActive = false;
        pi.loopInPct         = 0.0;
        pi.loopOutPct        = 100.0;
        pi.song = song;
        if (pi.panelSpectroCanvas != null) {
            pi.panelSpectroCanvas.setVisible(false);
            pi.panelSpectroCanvas.getGraphicsContext2D().clearRect(
                0, 0, pi.panelSpectroCanvas.getWidth(), pi.panelSpectroCanvas.getHeight());
        }
        if (pi.spectroTimeline != null) { pi.spectroTimeline.stop(); pi.spectroTimeline = null; }
        if (pi.panelProgress != null)
            UIUtils.toggleStyleClass(pi.panelProgress, "spectro-mode", false);
        if (pi.panelShuffleBtn != null)
            UIUtils.toggleStyleClass(pi.panelShuffleBtn, "control-active-2", false);
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
                        if (!song.hasDuration()) {
                            song.setDuration(totalStr);
                            libraryService.save();
                        }
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
                if (pi.looping) {
                    Duration tot    = pi.mediaPlayer.getMedia().getDuration();
                    Duration seekTo = (pi.loopMarkersActive && tot != null)
                                      ? tot.multiply(pi.loopInPct / 100.0) : Duration.ZERO;
                    pi.mediaPlayer.seek(seekTo);
                    pi.mediaPlayer.play();
                    if (pi.isMasterPartyPlayer && partyPanelBuilder != null && pi.song != null)
                        partyPanelBuilder.masterBroadcastPlay(pi.song.getVideoId(), (long) seekTo.toMillis());
                } else { onSongEnded(pi); }
            }));
            pi.mediaPlayer.setOnError(() -> showToast("Error al reproducir: " + file.getName()));

            // ── Audio spectrum → forma de onda en tiempo real ────────────────
            final int BANDS = 64;
            pi.waveSmoothed = new float[BANDS];
            pi.wavePeaks    = new float[BANDS];
            final float[] smoothed = pi.waveSmoothed;
            final boolean[] pending = {false};
            pi.mediaPlayer.setAudioSpectrumNumBands(BANDS);
            pi.mediaPlayer.setAudioSpectrumInterval(1.0 / 30);
            pi.mediaPlayer.setAudioSpectrumListener((ts, dur2, mags, phases) -> {
                for (int i = 0; i < BANDS; i++) {
                    float raw   = Math.max(0f, (mags[i] + 60f) / 60f);
                    // Los agudos tienen menos energía por naturaleza; boost progresivo
                    // para que todo el wave reaccione: 1x para graves, 3x para agudos
                    float boost  = 1.0f + (float) i / (BANDS - 1) * 2.0f;
                    float target = Math.min(1.0f, raw * boost);
                    smoothed[i] = smoothed[i] * 0.55f + target * 0.45f;
                }
                if (!pending[0]) {
                    pending[0] = true;
                    Platform.runLater(() -> {
                        // El canvas del panel solo es visible si su pestaña está activa;
                        // dibujarlo cuando está oculta es trabajo desperdiciado.
                        if (pi.panel != null && pi.panel.isVisible())
                            drawWaveCanvas(pi.panelWaveCanvas, smoothed, pi.wavePeaks);
                        if (pi == focusedPlayer) drawWaveCanvas(miniWaveCanvas, smoothed, miniWavePeaks);
                        pending[0] = false;
                    });
                }
            });

            if (pi.startPaused) {
                pi.isPlaying = false;
            } else {
                pi.mediaPlayer.play();
                pi.isPlaying = true;
            }
            applyVolumesToAll();
            rebuildTabBar();
        } catch (Exception e) { showToast("No se puede reproducir: " + file.getName()); return; }

        String displayTitle = (pi.isHiddenPartyTrack) ? "???" : song.getTitle();
        if (pi.panelTitle != null) {
            pi.panelTitle.setText(displayTitle); pi.panelArtist.setText(pi.isHiddenPartyTrack ? "" : song.getArtist());
            pi.panelProgress.setValue(0); pi.panelElapsed.setText("0:00"); pi.panelTotal.setText("—");
            if (pi.panelPlayPause != null) pi.panelPlayPause.setText(pi.startPaused ? "▶" : "⏸");
        }
        if (!pi.isHiddenPartyTrack && song.getThumbnailUrl() != null && !song.getThumbnailUrl().isBlank()) {
            try { Image thumb = new Image(song.getThumbnailUrl(), true); if (pi.artView != null) pi.artView.setImage(thumb); }
            catch (Exception ignored) {}
        }
        AppTab existingTab = findTab(pi.tabId);
        if (pi.isPartyListener) {
            // Abrir en segundo plano: no cambiar la pestaña activa ni el reproductor enfocado
            if (existingTab == null) {
                AppTab tab = new AppTab(pi.tabId, "🎵", displayTitle, pi.panel, false, null);
                openTabs.add(tab);
                if (!contentArea.getChildren().contains(pi.panel)) {
                    pi.panel.setVisible(false); pi.panel.setManaged(false); contentArea.getChildren().add(pi.panel);
                }
            } else {
                existingTab.title = displayTitle;
            }
            rebuildTabBar();
            updateMiniPlayerVisibility();
            if (focusedPlayer == null) { setFocusedPlayer(pi); updateLoopButtons(); }
            return;
        }
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
            if (pi.loopMarkersActive && pi.stoppedAtLoopOut) {
                pi.stoppedAtLoopOut = false;
                Duration tot = pi.mediaPlayer.getMedia().getDuration();
                if (tot != null && tot.greaterThan(Duration.ZERO))
                    pi.mediaPlayer.seek(tot.multiply(pi.loopInPct / 100.0));
            }
            pi.mediaPlayer.setVolume(0);
            pi.mediaPlayer.play();
            pi.isPlaying = true;
            double target = effectiveVolume(pi);
            pi.fadeOutAnim = new Timeline(new KeyFrame(Duration.millis(300),
                new KeyValue(pi.mediaPlayer.volumeProperty(), target, Interpolator.EASE_OUT)));
            pi.fadeOutAnim.setOnFinished(ev -> { pi.fadeOutAnim = null; applyVolumesToAll(); });
            pi.fadeOutAnim.play();
            if (pi == focusedPlayer) { addGlowEffect(); themeManager.updateDynamicColors(); }
            updatePlayPauseButton(); rebuildTabBar();
            if (pi.isMasterPartyPlayer && partyPanelBuilder != null && pi.song != null)
                partyPanelBuilder.masterBroadcastPlay(pi.song.getVideoId(), (long) pi.mediaPlayer.getCurrentTime().toMillis());
        } else {
            if (pi.isMasterPartyPlayer && partyPanelBuilder != null && pi.song != null)
                partyPanelBuilder.masterBroadcastPause(pi.song.getVideoId(), (long) pi.mediaPlayer.getCurrentTime().toMillis());
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
            if (pi == focusedPlayer || focusedPlayer == null) themeManager.updateDynamicColors();
        });
        pi.fadeOutAnim.play();
    }

    private void onSongEnded(PlayerInstance pi) {
        pi.isPlaying = false;
        applyVolumesToAll();
        startWaveDecay(pi);
        if (pi.panelPlayPause != null) pi.panelPlayPause.setText("▶");
        pickBestFocusedPlayer();
        themeManager.updateDynamicColors();
    }

    private void updateProgressUI(PlayerInstance pi, Duration current) {
        if (pi.mediaPlayer == null) return;
        Duration total = pi.mediaPlayer.getMedia().getDuration();
        if (total == null || !total.greaterThan(Duration.ZERO)) return;
        double pct = (current.toSeconds() / total.toSeconds()) * 100;
        if (pi.loopMarkersActive && !pi.stoppedAtLoopOut && !pi.seeking && pi.loopOutPct < 100.0 && pct >= pi.loopOutPct) {
            if (pi.looping) {
                Duration seekTo = total.multiply(pi.loopInPct / 100.0);
                pi.mediaPlayer.seek(seekTo);
                if (pi.isMasterPartyPlayer && partyPanelBuilder != null && pi.song != null)
                    partyPanelBuilder.masterBroadcastPlay(pi.song.getVideoId(), (long) seekTo.toMillis());
            } else {
                pi.stoppedAtLoopOut = true;
                fadeOutAndPause(pi);
            }
            return;
        }
        // El slider/tiempo del panel solo se ve con su pestaña activa; la barra inferior
        // (Now Playing) solo refleja al reproductor con foco. Para pestañas en segundo plano
        // sin foco no hay nada visible que actualizar — saltamos formatTime y los setters.
        boolean panelVisible  = pi.panelProgress != null && !pi.seeking
                                && pi.panel != null && pi.panel.isVisible();
        boolean bottomVisible = pi == focusedPlayer && !seekingByUser;
        if (!panelVisible && !bottomVisible) return;
        String elapsed = UIUtils.formatTime((int) current.toSeconds());
        if (panelVisible)  { pi.panelProgress.setValue(pct); pi.panelElapsed.setText(elapsed); }
        if (bottomVisible) { progressSlider.setValue(pct); timeElapsed.setText(elapsed); }
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
            UIUtils.toggleStyleClass(focusedPlayer.panelRepeat, "control-active-2", on);
    }

    private void drawWaveCanvas(javafx.scene.canvas.Canvas canvas, float[] smoothed, float[] peaks) {
        if (canvas == null) return;
        javafx.scene.canvas.GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        gc.clearRect(0, 0, w, h);
        if (smoothed == null || peaks == null) return;

        boolean isMini = (w <= 100);
        int n = smoothed.length;

        String hexA  = themeManager.currentTheme.get("bardo-accent");
        String hexA2 = themeManager.currentTheme.get("bardo-accent2");
        if (!Objects.equals(hexA, cachedWaveHexA) || !Objects.equals(hexA2, cachedWaveHexA2)) {
            cachedWaveHexA  = hexA;
            cachedWaveHexA2 = hexA2;
            cachedWaveCa    = hexA  != null ? Color.web(hexA)  : Color.web("#f4a7b9");
            cachedWaveCa2   = hexA2 != null ? Color.web(hexA2) : Color.web("#b39ddb");
        }
        Color ca  = cachedWaveCa;
        Color ca2 = cachedWaveCa2;

        if (isMini) {
            gc.setFill(Color.rgb(15, 5, 25, 0.35));
            gc.fillRect(0, 0, w, h);
        }

        double centerY = h / 2.0;
        double maxH    = centerY * 0.88;
        double minH    = isMini ? 1.5 : 2.0;

        // Layout espejo: graves en el centro, agudos en los extremos (simétrico)
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            double tPos  = (double) i / (n - 1);        // 0.0 → 1.0 (izq → der)
            double tBand = 2.0 * Math.abs(tPos - 0.5);  // 0.0 en centro → 1.0 en extremos
            xs[i] = tPos * (w - 1.0);
            double bandF = tBand * (n - 1);
            int    bLow  = Math.max(0, Math.min(n - 2, (int) bandF));
            float  frac  = (float)(bandF - bLow);
            float  val   = smoothed[bLow] * (1 - frac) + smoothed[bLow + 1] * frac;
            ys[i] = centerY - Math.max(minH, val * maxH);
        }

        double fillAlpha = isMini ? 0.45 : 0.28;
        double lineAlpha = isMini ? 0.85 : 0.92;
        double lineWidth = isMini ? 1.5 : 2.0;

        int slot = isMini ? 1 : 0;
        if (cachedFillGrad[slot] == null || cachedGradW[slot] != w
                || !Objects.equals(cachedGradHexA[slot], hexA) || !Objects.equals(cachedGradHexA2[slot], hexA2)) {
            cachedGradW[slot]     = w;
            cachedGradHexA[slot]  = hexA;
            cachedGradHexA2[slot] = hexA2;
            cachedFillGrad[slot] = new LinearGradient(0, 0, w, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(ca.getRed(), ca.getGreen(), ca.getBlue(), fillAlpha)),
                new Stop(1, Color.color(ca2.getRed(), ca2.getGreen(), ca2.getBlue(), fillAlpha)));
            cachedLineGrad[slot] = new LinearGradient(0, 0, w, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(ca.getRed(), ca.getGreen(), ca.getBlue(), lineAlpha)),
                new Stop(1, Color.color(ca2.getRed(), ca2.getGreen(), ca2.getBlue(), lineAlpha)));
        }
        LinearGradient fillGrad = cachedFillGrad[slot];
        LinearGradient lineGrad = cachedLineGrad[slot];

        // Relleno superior (entre curva y línea central)
        gc.setFill(fillGrad);
        gc.beginPath();
        gc.moveTo(xs[0], centerY);
        gc.lineTo(xs[0], ys[0]);
        for (int i = 0; i < n - 1; i++) {
            double midX = (xs[i] + xs[i + 1]) / 2.0;
            double midY = (ys[i] + ys[i + 1]) / 2.0;
            gc.quadraticCurveTo(xs[i], ys[i], midX, midY);
        }
        gc.lineTo(xs[n - 1], ys[n - 1]);
        gc.lineTo(xs[n - 1], centerY);
        gc.closePath();
        gc.fill();

        // Relleno inferior (espejo)
        gc.beginPath();
        gc.moveTo(xs[0], centerY);
        gc.lineTo(xs[0], h - ys[0]);
        for (int i = 0; i < n - 1; i++) {
            double midX = (xs[i] + xs[i + 1]) / 2.0;
            double midY = h - (ys[i] + ys[i + 1]) / 2.0;
            gc.quadraticCurveTo(xs[i], h - ys[i], midX, midY);
        }
        gc.lineTo(xs[n - 1], h - ys[n - 1]);
        gc.lineTo(xs[n - 1], centerY);
        gc.closePath();
        gc.fill();

        // Línea brillante superior
        gc.setStroke(lineGrad);
        gc.setLineWidth(lineWidth);
        gc.beginPath();
        gc.moveTo(xs[0], ys[0]);
        for (int i = 0; i < n - 1; i++) {
            double midX = (xs[i] + xs[i + 1]) / 2.0;
            double midY = (ys[i] + ys[i + 1]) / 2.0;
            gc.quadraticCurveTo(xs[i], ys[i], midX, midY);
        }
        gc.lineTo(xs[n - 1], ys[n - 1]);
        gc.stroke();

        // Línea brillante inferior (espejo)
        gc.beginPath();
        gc.moveTo(xs[0], h - ys[0]);
        for (int i = 0; i < n - 1; i++) {
            double midX = (xs[i] + xs[i + 1]) / 2.0;
            double midY = h - (ys[i] + ys[i + 1]) / 2.0;
            gc.quadraticCurveTo(xs[i], h - ys[i], midX, midY);
        }
        gc.lineTo(xs[n - 1], h - ys[n - 1]);
        gc.stroke();
    }

    private static double lerp(double a, double b, double t) { return a + t * (b - a); }

    private void startWaveDecay(PlayerInstance pi) {
        if (pi.waveDecayAnim != null) { pi.waveDecayAnim.stop(); pi.waveDecayAnim = null; }
        if (pi.waveSmoothed == null) return;
        // Si nadie ve esta onda (pestaña oculta y reproductor sin foco), no animamos el
        // decaimiento: aplanamos el array de una vez y dejamos el canvas plano con un solo
        // dibujado, evitando ~30fps de trabajo inútil en segundo plano.
        boolean panelVisible = pi.panel != null && pi.panel.isVisible();
        if (!panelVisible && pi != focusedPlayer) {
            Arrays.fill(pi.waveSmoothed, 0f);
            if (pi.panelWaveCanvas != null) drawWaveCanvas(pi.panelWaveCanvas, pi.waveSmoothed, pi.wavePeaks);
            return;
        }
        pi.waveDecayAnim = new Timeline(new KeyFrame(Duration.millis(33), e -> {
            if (pi.waveSmoothed == null) { pi.waveDecayAnim.stop(); return; }
            boolean allZero = true;
            for (int i = 0; i < pi.waveSmoothed.length; i++) {
                pi.waveSmoothed[i] *= 0.82f;
                if (pi.waveSmoothed[i] > 0.004f) allZero = false;
            }
            if (pi.panel != null && pi.panel.isVisible())
                drawWaveCanvas(pi.panelWaveCanvas, pi.waveSmoothed, pi.wavePeaks);
            if (pi == focusedPlayer) drawWaveCanvas(miniWaveCanvas, pi.waveSmoothed, miniWavePeaks);
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
    private void updatePartyListenerLock() {
        boolean lock = focusedPlayer != null && focusedPlayer.isPartyListener;
        for (Button b : new Button[]{btnPrev, btnPlayPause, btnNext, btnShuffle, btnRepeat}) {
            b.setVisible(!lock); b.setManaged(!lock);
        }
        progressSlider.setMouseTransparent(lock);
        volumeSlider.setVisible(!lock); volumeSlider.setManaged(!lock);
        volumeLabel.setVisible(!lock);  volumeLabel.setManaged(!lock);
    }

    private void setFocusedPlayer(PlayerInstance pi) {
        focusedPlayer = pi;
        updatePartyListenerLock();
        Song song = pi.song;
        if (song != null) {
            if (pi.isHiddenPartyTrack) {
                nowPlayingTitle.setText("???"); nowPlayingArtist.setText("");
            } else {
                nowPlayingTitle.setText(song.getTitle()); nowPlayingArtist.setText(song.getArtist());
                if (song.getThumbnailUrl() != null && !song.getThumbnailUrl().isBlank())
                    try { albumArt.setImage(new Image(song.getThumbnailUrl(), true)); } catch (Exception ignored) {}
            }
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
        Arrays.fill(miniWavePeaks, 0f);
        drawWaveCanvas(miniWaveCanvas, pi.waveSmoothed, miniWavePeaks);
        updatePlayPauseButton(); updateLoopButtons();
        if (pi.isPlaying) themeManager.updateDynamicColors();
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
            themeManager.fadeAllToBase();
            updatePartyListenerLock();
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

        downloadThen(song, target, () -> {
            if (group != null) group.incrementPlayCount();
            List<Song> locals = target.getSongs().stream().filter(Song::isLocal).collect(Collectors.toList());
            playSongInQueue(song, locals.isEmpty() ? List.of(song) : locals);
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
            libraryService, this::showToast, this::refreshHomePanel,
            partyPanelBuilder.isMasterProperty(), partyPanelBuilder::addSongToParty);
        openTab(tabId, group.isYoutubePlaylist() ? "📺" : "📋", group.getName(), panel, true, btnLibrary);
        themeManager.applyContrastStroke(panel, "bardo-text", "bardo-bg");
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
                // Update durations of songs already in the group
                Map<String, Song> fetchedMap = songs.stream()
                    .collect(Collectors.toMap(Song::getVideoId, s -> s, (a, b) -> a));
                group.getSongs().forEach(s -> {
                    Song f = fetchedMap.get(s.getVideoId());
                    if (f != null && !s.hasDuration())
                        s.setDuration(f.getDuration());
                });
                // Add truly new songs at the end
                Set<String> existing = group.getSongs().stream().map(Song::getVideoId).collect(Collectors.toSet());
                List<Song> newSongs = songs.stream().filter(s -> !existing.contains(s.getVideoId())).collect(Collectors.toList());
                group.getSongs().addAll(newSongs);
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
                item.setOnAction(ev -> {
                    if (g.getSongs().stream().noneMatch(s -> s.getVideoId().equals(song.getVideoId()))) {
                        song.setType(g.getType());
                        g.getSongs().add(0, song);
                        libraryService.save();
                    }
                    refreshLibraryPanel(); showToast("Añadido a «" + g.getName() + "»");
                });
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
        } else {
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

        if (quotaTracker.isEnabled()) homePanel.getChildren().add(buildQuotaWidget());
    }

    private VBox buildQuotaWidget() {
        int max  = quotaTracker.getDailyMax();
        int used = quotaTracker.getUsedToday();

        Label title = new Label("CUOTA YOUTUBE API (HOY)");
        title.getStyleClass().add("sidebar-section-label");

        ProgressBar bar = new ProgressBar();
        bar.progressProperty().bind(quotaTracker.usedTodayProperty().divide((double) max));
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("quota-progress-bar");
        if (quotaTracker.isExhausted() || used >= max * 0.8) bar.getStyleClass().add("quota-bar-critical");
        else if (used >= max * 0.5)                          bar.getStyleClass().add("quota-bar-warning");

        Label usedLbl = new Label();
        usedLbl.textProperty().bind(
            quotaTracker.usedTodayProperty().asString().concat(" / " + max + " unidades"));
        usedLbl.getStyleClass().add("quota-info-label");

        // Countdown: always present; visible only when quota exhausted
        Label countdownLbl = new Label();
        countdownLbl.getStyleClass().add("quota-countdown-label");
        countdownLbl.visibleProperty().bind(quotaTracker.exhaustedProperty());
        countdownLbl.managedProperty().bind(quotaTracker.exhaustedProperty());
        Runnable tick = () ->
            countdownLbl.setText("· reinicia en " + quotaTracker.getTimeUntilResetString());
        tick.run();
        Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick.run()));
        timer.setCycleCount(Animation.INDEFINITE);
        // Each home-panel refresh rebuilds this widget with a new Timeline; drop the
        // previous listener first so they don't accumulate on quotaTracker indefinitely.
        if (quotaExhaustedListener != null) quotaTracker.exhaustedProperty().removeListener(quotaExhaustedListener);
        quotaExhaustedListener = (obs, was, is) -> {
            if (Boolean.TRUE.equals(is)) timer.play(); else timer.stop();
        };
        quotaTracker.exhaustedProperty().addListener(quotaExhaustedListener);
        if (quotaTracker.isExhausted()) timer.play();
        homeCarouselTimelines.add(timer);

        HBox infoRow = new HBox(10, usedLbl, countdownLbl);
        infoRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6, title, bar, infoRow);
        box.setPadding(new javafx.geometry.Insets(12, 0, 4, 0));
        return box;
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
        imgView.setFitWidth(160); imgView.setFitHeight(90);
        imgView.setPreserveRatio(true);
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(160, 90);
        clip.setArcWidth(12); clip.setArcHeight(12);
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
        imgContainer.setPrefSize(160, 90); imgContainer.setMaxSize(160, 90);
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
        nameLbl.setMaxWidth(160);

        Label countLbl = new Label(group.getPlayCount() + " reproducciones");
        countLbl.getStyleClass().add("home-playlist-count");

        card.getChildren().addAll(imgContainer, nameLbl, countLbl);
        return card;
    }

    private VBox buildPinnedSongCard(Song song) {
        VBox card = new VBox(10);
        card.getStyleClass().add("home-playlist-card");

        ImageView imgView = new ImageView();
        imgView.setFitWidth(160); imgView.setFitHeight(90);
        imgView.setPreserveRatio(true);
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(160, 90);
        clip.setArcWidth(12); clip.setArcHeight(12);
        imgView.setClip(clip);
        imgView.setStyle("-fx-cursor: hand;");
        if (song.getThumbnailUrl() != null && !song.getThumbnailUrl().isBlank())
            CardBuilder.loadImage(imgView, song.getThumbnailUrl());

        Button unpinBtn = new Button("📌");
        unpinBtn.getStyleClass().add("home-overlay-btn");
        unpinBtn.setOpacity(0);
        StackPane.setAlignment(unpinBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(unpinBtn, new javafx.geometry.Insets(7, 7, 0, 0));
        unpinBtn.setOnAction(e -> { libraryService.unpinSong(song.getVideoId()); refreshHomePanel(); });

        StackPane imgContainer = new StackPane(imgView, unpinBtn);
        imgContainer.setPrefSize(160, 90); imgContainer.setMaxSize(160, 90);
        imgContainer.getStyleClass().add("home-playlist-thumb");
        imgContainer.setOnMouseEntered(e -> { FadeTransition ft = new FadeTransition(Duration.millis(150), unpinBtn); ft.setToValue(1); ft.play(); });
        imgContainer.setOnMouseExited(e  -> { FadeTransition ft = new FadeTransition(Duration.millis(150), unpinBtn); ft.setToValue(0); ft.play(); });
        imgContainer.setOnMouseClicked(e -> {
            if (e.getTarget() instanceof Button) return;
            if (e.getButton() == javafx.scene.input.MouseButton.MIDDLE) { openSongPaused(song, null); return; }
            downloadAndPlay(song, null);
        });

        Label titleLbl = new Label(song.getTitle());
        titleLbl.getStyleClass().add("home-playlist-name");
        titleLbl.setMaxWidth(160);

        card.getChildren().addAll(imgContainer, titleLbl);
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

    private void changeAudioDir(Path newBaseDir) {
        String saved = libraryService.loadAudioDir();
        Path oldBaseDir = saved.isBlank()
            ? PersistenceService.bardoBaseDir().resolve("audio")
            : Path.of(saved);

        if (oldBaseDir.equals(newBaseDir)) return;

        // Recopilar rutas reconocidas en el hilo FX antes de ir al hilo de fondo
        Set<String> recognizedPaths = new HashSet<>();
        for (LibraryGroup group : libraryService.getGroups())
            for (Song song : group.getSongs()) {
                String fp = song.getLocalFilePath();
                if (fp != null && !fp.isBlank()) recognizedPaths.add(fp);
            }
        for (Song pinned : libraryService.getPinnedSongs()) {
            String fp = pinned.getLocalFilePath();
            if (fp != null && !fp.isBlank()) recognizedPaths.add(fp);
        }

        // Solo mover archivos reconocidos que estén dentro de oldBaseDir
        List<Path> toMove = new ArrayList<>();
        for (String fp : recognizedPaths) {
            try {
                Path p = Path.of(fp);
                if (p.startsWith(oldBaseDir) && Files.isRegularFile(p)) toMove.add(p);
            } catch (Exception ignored) {}
        }

        // Detener reproductores con archivos en oldBaseDir para liberar handles (Windows)
        for (PlayerInstance pi : new ArrayList<>(activePlayers)) {
            if (pi.mediaPlayer == null || pi.song == null) continue;
            String fp = pi.song.getLocalFilePath();
            if (fp == null || fp.isBlank()) continue;
            try { if (!Path.of(fp).startsWith(oldBaseDir)) continue; }
            catch (Exception ignored) { continue; }
            if (pi.fadeOutAnim  != null) { pi.fadeOutAnim.stop();  pi.fadeOutAnim  = null; }
            if (pi.waveDecayAnim != null) { pi.waveDecayAnim.stop(); pi.waveDecayAnim = null; }
            pi.mediaPlayer.stop();
            pi.mediaPlayer.dispose();
            pi.mediaPlayer = null;
            pi.isPlaying   = false;
            if (pi.panelPlayPause != null) pi.panelPlayPause.setText("▶");
        }
        updatePlayPauseButton();
        pickBestFocusedPlayer();

        // Diálogo de progreso
        Dialog<Void> progressDlg = new Dialog<>();
        progressDlg.initOwner(stage());
        progressDlg.setTitle("Moviendo archivos");
        progressDlg.getDialogPane().getStylesheets().addAll(stage().getScene().getStylesheets());
        ProgressBar progressBar = new ProgressBar(toMove.isEmpty() ? ProgressBar.INDETERMINATE_PROGRESS : 0.0);
        progressBar.setPrefWidth(380);
        Label progressLbl = new Label(toMove.isEmpty() ? "Escaneando carpeta…"
                                                       : "0 de " + toMove.size() + " archivos");
        progressLbl.getStyleClass().add("greeting-sub");
        VBox dlgBox = new VBox(10, progressLbl, progressBar);
        dlgBox.setStyle("-fx-padding: 16 24;");
        progressDlg.getDialogPane().setContent(dlgBox);
        progressDlg.show();

        int total = toMove.size();

        CompletableFuture.runAsync(() -> {
            try {
                // 1. Copiar archivos reconocidos → destino; eliminar original tras copia exitosa
                if (!toMove.isEmpty()) {
                    Files.createDirectories(newBaseDir);
                    for (int i = 0; i < total; i++) {
                        Path src = toMove.get(i);
                        Path dst = newBaseDir.resolve(oldBaseDir.relativize(src));
                        try {
                            Files.createDirectories(dst.getParent());
                            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                            Files.delete(src);
                        } catch (IOException ignored) {}
                        final int done = i + 1;
                        Platform.runLater(() -> {
                            progressBar.setProgress((double) done / total);
                            progressLbl.setText(done + " de " + total + " archivos movidos");
                        });
                    }
                    // Eliminar subdirectorios vacíos creados por la app en oldBaseDir
                    if (Files.isDirectory(oldBaseDir)) {
                        try (var walk = Files.walk(oldBaseDir)) {
                            walk.sorted(Comparator.<Path>reverseOrder())
                                .filter(p -> Files.isDirectory(p) && !p.equals(oldBaseDir))
                                .forEach(p -> {
                                    try (var ls = Files.list(p)) {
                                        if (ls.findAny().isEmpty()) Files.delete(p);
                                    } catch (IOException ignored) {}
                                });
                        } catch (IOException ignored) {}
                    }
                }

                // 2. Guardar nueva ruta
                libraryService.saveAudioDir(newBaseDir.toString());

                // 3. Escanear carpeta destino (reconoce archivos preexistentes)
                Platform.runLater(() -> progressLbl.setText("Escaneando carpeta nueva…"));
                Map<String, Path> videoIdToFile = new HashMap<>();
                if (Files.isDirectory(newBaseDir)) {
                    try (var walk = Files.walk(newBaseDir)) {
                        walk.filter(Files::isRegularFile).forEach(p -> {
                            String name = p.getFileName().toString();
                            int dot = name.indexOf('.');
                            if (dot > 0) videoIdToFile.put(name.substring(0, dot), p);
                        });
                    }
                }

                // 4 & 5. Actualizar rutas y guardar en el hilo FX
                Platform.runLater(() -> {
                    progressBar.setProgress(1.0);
                    for (LibraryGroup group : libraryService.getGroups()) {
                        Path groupDir = newBaseDir.resolve(group.getId());
                        for (Song song : group.getSongs()) {
                            Path found = videoIdToFile.get(song.getVideoId());
                            if (found == null && Files.isDirectory(groupDir)) {
                                String vid = song.getVideoId();
                                try (var ls = Files.list(groupDir)) {
                                    found = ls.filter(p -> p.getFileName().toString().startsWith(vid + "."))
                                               .findFirst().orElse(null);
                                } catch (IOException ignored) {}
                            }
                            if (found != null) song.setLocalFilePath(found.toString());
                        }
                    }
                    for (Song pinned : libraryService.getPinnedSongs()) {
                        Path found = videoIdToFile.get(pinned.getVideoId());
                        if (found != null) pinned.setLocalFilePath(found.toString());
                    }
                    libraryService.save();
                    refreshLibraryPanel();
                    progressDlg.close();
                    showToast("Carpeta de música actualizada.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    progressDlg.close();
                    showToast("Error al mover archivos: " + e.getMessage());
                });
            }
        });
    }

    private void applyVolumesToAll() {
        for (PlayerInstance p : new ArrayList<>(activePlayers)) {
            if (p.isMashupLinked || p.mashupPartner != null) continue;
            if (p.mediaPlayer != null && p.fadeOutAnim == null)
                p.mediaPlayer.setVolume(effectiveVolume(p));
        }
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    void showToast(String message) {
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

    // ── Logo recolor ──────────────────────────────────────────────────────────

    private void recolorLogo() {
        if (logoPngSource == null || logoZoneMap == null) return;
        if (!logoRecolorBusy.compareAndSet(false, true)) return;
        Color accent  = ThemeManager.tryParseColor(themeManager.peekTargetColor("bardo-accent"));
        Color accent2 = ThemeManager.tryParseColor(themeManager.peekTargetColor("bardo-accent2"));
        Thread t = new Thread(() -> {
            Image result = applyHueShift(logoPngSource, logoZoneMap, accent, accent2);
            Platform.runLater(() -> { logoRecolorBusy.set(false); crossFadeLogo(result); });
        }, "bardo-logo-recolor");
        t.setDaemon(true);
        t.start();
    }

    private void crossFadeLogo(Image newImage) {
        if (logoFadeAnim != null) { logoFadeAnim.stop(); sidebarLogoNext.setOpacity(0); }
        sidebarLogoNext.setImage(newImage);
        javafx.animation.FadeTransition fadeIn = new javafx.animation.FadeTransition(javafx.util.Duration.millis(700), sidebarLogoNext);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);
        fadeIn.setOnFinished(e -> {
            sidebarLogo.setImage(newImage);
            sidebarLogoNext.setOpacity(0);
        });
        logoFadeAnim = fadeIn;
        logoFadeAnim.play();
    }

    private void setupLogoDrag() {
        final double[] pressScene  = {0, 0};
        final double[] prevScene   = {0, 0};
        final long[]   prevTime    = {0};
        final double[] angVelocity = {0};   // deg/ms
        final AnimationTimer[] inertia   = {null};
        final Timeline[]       resetAnim = {null};
        sidebarLogoStack.setOnMousePressed(e -> {
            if (inertia[0]   != null) { inertia[0].stop();   inertia[0]   = null; }
            if (resetAnim[0] != null) { resetAnim[0].stop(); resetAnim[0] = null; }
            pressScene[0]  = e.getSceneX();
            pressScene[1]  = e.getSceneY();
            prevScene[0]   = e.getSceneX();
            prevScene[1]   = e.getSceneY();
            prevTime[0]    = System.nanoTime();
            angVelocity[0] = 0;
            sidebarLogoStack.setCursor(javafx.scene.Cursor.CLOSED_HAND);
            e.consume();
        });

        sidebarLogoStack.setOnMouseDragged(e -> {
            long now = System.nanoTime();
            double dt = (now - prevTime[0]) / 1_000_000.0;
            double dx = e.getSceneX() - prevScene[0];
            double dy = e.getSceneY() - prevScene[1];
            prevScene[0] = e.getSceneX();
            prevScene[1] = e.getSceneY();
            prevTime[0]  = now;

            // Cursor relative to disc centre in scene coords (correct regardless of node rotation)
            javafx.geometry.Point2D centre = sidebarLogoStack.localToScene(
                sidebarLogoStack.getWidth() / 2.0, sidebarLogoStack.getHeight() / 2.0);
            double cx = e.getSceneX() - centre.getX();
            double cy = e.getSceneY() - centre.getY();
            double r2 = Math.max(cx * cx + cy * cy, 400); // min effective radius 20 px

            // 2-D cross product → tangential drag drives rotation
            double dRot = (cx * dy - cy * dx) / r2 * (180.0 / Math.PI);
            if (dt > 0) angVelocity[0] = 0.6 * angVelocity[0] + 0.4 * (dRot / dt);
            sidebarLogoStack.setRotate(sidebarLogoStack.getRotate() + dRot);
            e.consume();
        });

        sidebarLogoStack.setOnMouseReleased(e -> {
            sidebarLogoStack.setCursor(javafx.scene.Cursor.HAND);
            double totalDx = e.getSceneX() - pressScene[0];
            double totalDy = e.getSceneY() - pressScene[1];

            if (Math.hypot(totalDx, totalDy) < 5.0) {
                double cur = sidebarLogoStack.getRotate();
                double mod = ((cur % 360) + 360) % 360;
                double target = (mod <= 180) ? cur - mod : cur + (360 - mod);
                resetAnim[0] = new Timeline(new KeyFrame(Duration.millis(520),
                    new KeyValue(sidebarLogoStack.rotateProperty(), target, Interpolator.EASE_BOTH)
                ));
                resetAnim[0].play();
            } else {
                inertia[0] = new AnimationTimer() {
                    private long lastNano = 0;
                    @Override public void handle(long now) {
                        if (lastNano == 0) { lastNano = now; return; }
                        double dt = (now - lastNano) / 1_000_000.0;
                        lastNano = now;
                        if (dt <= 0 || dt > 100) return;
                        angVelocity[0] *= Math.pow(0.96, dt / 16.67);
                        if (Math.abs(angVelocity[0]) < 0.002) { stop(); inertia[0] = null; return; }
                        sidebarLogoStack.setRotate(sidebarLogoStack.getRotate() + angVelocity[0] * dt);
                    }
                };
                inertia[0].start();
            }
            e.consume();
        });
    }

    /**
     * Builds a per-pixel zone map from a PNG guide image.
     * Zones: -1 = exterior background (transparent), 0 = white inner oval (transparent),
     *         1 = zone1 (pink/warm hue), 2 = zone2 (blue/cool hue).
     * Exterior detection uses BFS flood-fill from all border pixels.
     */
    private static byte[] buildZoneMapFromPng(Image guide) {
        int w = (int) guide.getWidth(), h = (int) guide.getHeight();
        PixelReader pr = guide.getPixelReader();

        // Classify each pixel as white/low-saturation vs colored
        boolean[] isWhite = new boolean[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                Color c = pr.getColor(x, y);
                isWhite[y * w + x] = (c.getOpacity() < 0.1 || c.getSaturation() < 0.18);
            }

        // BFS flood-fill exterior from all border pixels that are white
        byte[] zone = new byte[w * h];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            if (isWhite[x]           && zone[x]           == 0) { zone[x]           = -1; queue.add(x); }
            if (isWhite[(h-1)*w + x] && zone[(h-1)*w + x] == 0) { zone[(h-1)*w + x] = -1; queue.add((h-1)*w + x); }
        }
        for (int y = 1; y < h - 1; y++) {
            if (isWhite[y*w]       && zone[y*w]       == 0) { zone[y*w]       = -1; queue.add(y*w); }
            if (isWhite[y*w + w-1] && zone[y*w + w-1] == 0) { zone[y*w + w-1] = -1; queue.add(y*w + w-1); }
        }
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            int x = idx % w, y = idx / w;
            int[][] nb = {{x-1,y},{x+1,y},{x,y-1},{x,y+1}};
            for (int[] n : nb) {
                if (n[0] < 0 || n[0] >= w || n[1] < 0 || n[1] >= h) continue;
                int ni = n[1] * w + n[0];
                if (zone[ni] != 0 || !isWhite[ni]) continue;
                zone[ni] = -1;
                queue.add(ni);
            }
        }

        // Assign zones by hue to all remaining colored pixels
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (zone[idx] != 0 || isWhite[idx]) continue; // exterior or white oval
                double hue = pr.getColor(x, y).getHue();
                if      (hue >= 300 || hue <= 60)  zone[idx] = 1; // pink → zone1
                else if (hue >= 175 && hue <= 265) zone[idx] = 2; // blue → zone2
                // else stays 0 → transparent
            }
        }
        return zone;
    }

    private static Image applyHueShift(Image src, byte[] zoneMap, Color accent, Color accent2) {
        int w = (int) src.getWidth(), h = (int) src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixelReader().getPixels(0, 0, w, h,
            javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);
        float hue1 = (float) accent.getHue(),  sat1 = (float) accent.getSaturation(), bri1 = (float) accent.getBrightness();
        float hue2 = (float) accent2.getHue(), sat2 = (float) accent2.getSaturation(), bri2 = (float) accent2.getBrightness();
        float[] hsb = new float[3];
        for (int i = 0; i < pixels.length; i++) {
            byte z = zoneMap[i];
            if (z <= 0) { pixels[i] = 0; continue; }
            int argb = pixels[i];
            rgbToHsb((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, hsb);
            float tH = (z == 1) ? hue1 : hue2;
            float tS = (z == 1) ? sat1 : sat2;
            float tB = (z == 1) ? bri1 : bri2;
            pixels[i] = hsbToArgb(tH, tS, Math.min(hsb[2], tB), argb >>> 24);
        }
        WritableImage out = new WritableImage(w, h);
        out.getPixelWriter().setPixels(0, 0, w, h,
            javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, w);
        return out;
    }

    private static void rgbToHsb(int r, int g, int b, float[] out) {
        float fr = r / 255f, fg = g / 255f, fb = b / 255f;
        float max = Math.max(fr, Math.max(fg, fb));
        float min = Math.min(fr, Math.min(fg, fb));
        float delta = max - min;
        out[2] = max;
        out[1] = max == 0f ? 0f : delta / max;
        if (delta == 0f) { out[0] = 0f; return; }
        float hue;
        if      (max == fr) hue = (fg - fb) / delta;
        else if (max == fg) hue = 2f + (fb - fr) / delta;
        else                hue = 4f + (fr - fg) / delta;
        hue *= 60f;
        if (hue < 0f) hue += 360f;
        out[0] = hue;
    }

    private static int hsbToArgb(float hue, float sat, float bri, int alpha) {
        if (sat == 0f) {
            int v = (int)(bri * 255f);
            return (alpha << 24) | (v << 16) | (v << 8) | v;
        }
        float h = hue / 60f;
        int   sector = (int) h % 6;
        float f = h - (int) h;
        float p = bri * (1f - sat);
        float q = bri * (1f - sat * f);
        float t = bri * (1f - sat * (1f - f));
        float r, g, b;
        switch (sector) {
            case 0:  r = bri; g = t;   b = p;   break;
            case 1:  r = q;   g = bri; b = p;   break;
            case 2:  r = p;   g = bri; b = t;   break;
            case 3:  r = p;   g = q;   b = bri; break;
            case 4:  r = t;   g = p;   b = bri; break;
            default: r = bri; g = p;   b = q;   break;
        }
        return (alpha << 24) | ((int)(r * 255f) << 16) | ((int)(g * 255f) << 8) | (int)(b * 255f);
    }

    // ── API interna para el sistema Party ─────────────────────────────────────

    PlayerInstance getFocusedPlayer() { return focusedPlayer; }

    PlayerInstance partyLoadSong(Song song, boolean hidden) {
        String tabId = "player:" + System.currentTimeMillis();
        PlayerInstance pi = new PlayerInstance(tabId);
        pi.isPartyListener      = true;
        pi.isHiddenPartyTrack   = hidden;
        pi.startPaused          = true;
        pi.volume = volumeSlider.getValue() / 100.0;
        pi.queue.add(song);
        buildPanel(pi); activePlayers.add(pi); loadSong(pi, song);
        return pi;
    }

    PlayerInstance openMasterPartyPlayer(Song song) {
        String tabId = "player:" + System.currentTimeMillis();
        PlayerInstance pi = new PlayerInstance(tabId);
        pi.isMasterPartyPlayer = true;
        pi.startPaused         = true;
        pi.volume = volumeSlider.getValue() / 100.0;
        pi.queue.add(song);
        buildPanel(pi); activePlayers.add(pi); loadSong(pi, song);
        return pi;
    }

    void partyPlay(PlayerInstance pi) {
        if (pi == null || pi.mediaPlayer == null) return;
        if (pi.fadeOutAnim != null) { pi.fadeOutAnim.stop(); pi.fadeOutAnim = null; }
        pi.mediaPlayer.setVolume(0);
        pi.mediaPlayer.play();
        pi.isPlaying = true;
        double target = effectiveVolume(pi);
        pi.fadeOutAnim = new Timeline(new KeyFrame(Duration.millis(300),
            new KeyValue(pi.mediaPlayer.volumeProperty(), target, Interpolator.EASE_OUT)));
        pi.fadeOutAnim.setOnFinished(ev -> { pi.fadeOutAnim = null; applyVolumesToAll(); });
        pi.fadeOutAnim.play();
        if (pi == focusedPlayer) { addGlowEffect(); themeManager.updateDynamicColors(); }
        updatePlayPauseButton();
        rebuildTabBar();
    }

    void revealPartyTrack(PlayerInstance pi) {
        Song song = pi.song;
        if (song == null) return;
        pi.isHiddenPartyTrack = false;
        if (pi.panelTitle  != null) pi.panelTitle.setText(song.getTitle());
        if (pi.panelArtist != null) pi.panelArtist.setText(song.getArtist());
        if (song.getThumbnailUrl() != null && !song.getThumbnailUrl().isBlank()) {
            try {
                Image thumb = new Image(song.getThumbnailUrl(), true);
                if (pi.artView != null) pi.artView.setImage(thumb);
                if (pi == focusedPlayer) albumArt.setImage(thumb);
            } catch (Exception ignored) {}
        }
        AppTab tab = findTab(pi.tabId);
        if (tab != null) { tab.title = song.getTitle(); rebuildTabBar(); }
        if (pi == focusedPlayer) {
            nowPlayingTitle.setText(song.getTitle());
            nowPlayingArtist.setText(song.getArtist());
        }
    }

    void partyPause(PlayerInstance pi, long seekMs) {
        if (pi == null || pi.mediaPlayer == null) return;
        pi.isPlaying = false;
        if (pi == focusedPlayer) removeGlowEffect();
        updatePlayPauseButton();
        if (pi.fadeOutAnim != null) pi.fadeOutAnim.stop();
        pi.fadeOutAnim = new Timeline(new KeyFrame(Duration.millis(700),
            new KeyValue(pi.mediaPlayer.volumeProperty(), 0.0, Interpolator.EASE_IN)));
        pi.fadeOutAnim.setOnFinished(ev -> {
            pi.mediaPlayer.pause();
            pi.mediaPlayer.seek(javafx.util.Duration.millis(seekMs));
            startWaveDecay(pi);
            pi.fadeOutAnim = null;
            applyVolumesToAll();
            rebuildTabBar();
            if (pi == focusedPlayer || focusedPlayer == null) themeManager.updateDynamicColors();
        });
        pi.fadeOutAnim.play();
    }

    private void openDebugPartyTabs() {
        // El master usa el partyPanelBuilder principal: así isMasterProperty() y addSongToParty()
        // funcionan correctamente y los botones 📢 de reproductores/playlists se activan.
        openTab("debug-party-master", "📡", "DEBUG Master", partyPanelBuilder.getPanel(), true, null);

        // El listener tiene su propio builder independiente
        PartyPanelBuilder listenerBuilder = new PartyPanelBuilder(this, downloadService);
        listenerBuilder.navigateToJoin();
        openTab("debug-party-listener", "🔗", "DEBUG Listener", listenerBuilder.getPanel(), true, null);
    }
}
