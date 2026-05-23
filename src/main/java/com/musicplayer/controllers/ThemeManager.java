package com.musicplayer.controllers;

import com.musicplayer.services.LibraryService;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.*;
import java.util.function.Supplier;

/**
 * Gestiona todo el sistema de temas y colores dinámicos de Bardo.
 *
 * <p>Mantiene dos mapas paralelos de variables CSS:
 * <ul>
 *   <li>{@link #baseTheme} — colores configurados manualmente por el usuario y
 *       persistidos entre sesiones.</li>
 *   <li>{@link #currentTheme} — colores aplicados actualmente en pantalla; pueden
 *       diferir de {@code baseTheme} cuando el modo dinámico extrae colores de la
 *       miniatura de la canción en reproducción.</li>
 * </ul>
 *
 * <p><b>Modos de color dinámico:</b> cada variable definida en {@link #THEME_VARS}
 * puede operar en tres modos ({@link #DYN_STATIC}, {@link #DYN_PRIMARY},
 * {@link #DYN_SECONDARY}). Los modos por defecto se asignan en
 * {@link #defaultModeFor(String)}: {@code bardo-accent} y {@code bardo-player-bg1}
 * usan {@code DYN_PRIMARY}; {@code bardo-accent2} y {@code bardo-player-bg2} usan
 * {@code DYN_SECONDARY}; el resto {@code DYN_STATIC}.
 *
 * <p><b>Transiciones de color:</b> {@link #fadeThemeVar(String, String)} anima el
 * cambio de color en ~700 ms (20 pasos lineales). {@link #fadeAllToBase()} revierte
 * todas las variables dinámicas a su valor base.
 *
 * <p><b>Contraste de texto (WCAG):</b> cuando {@link #textContrastEnabled} es
 * {@code true}, {@link #applyContrastStrokes()} añade la clase CSS
 * {@code bardo-contrast-dark} o {@code bardo-contrast-light} a cada contenedor
 * relevante. El umbral de ratio WCAG es {@link #CONTRAST_THRESHOLD} (3.0); si el
 * ratio es suficiente no se añade clase. Cuando sí se añade, la decisión entre
 * oscuro/claro usa {@code avgLum = (lumTexto + lumFondo) / 2 > 0.5}.
 */
public class ThemeManager {

    // ── Modos de color dinámico ───────────────────────────────────────────────

    /** La variable no sigue ningún color extraído; usa siempre el color base. */
    static final String DYN_STATIC    = "static";

    /** La variable sigue el color primario extraído de la miniatura. */
    static final String DYN_PRIMARY   = "primary";

    /** La variable sigue el color secundario extraído de la miniatura. */
    static final String DYN_SECONDARY = "secondary";

    /** Umbral de ratio de contraste WCAG por debajo del cual se activa el shadow. */
    static final double CONTRAST_THRESHOLD = 3.0;

    /**
     * Tabla maestra de variables de tema: {@code {nombreCSS, valorDefecto, etiqueta}}.
     * El orden determina el orden de visualización en el panel de Configuración.
     */
    static final String[][] THEME_VARS = {
        {"bardo-bg",         "#faf8fc", "Fondo principal"},
        {"bardo-sidebar-bg", "#f5f0fc", "Barra lateral"},
        {"bardo-accent",     "#f4a7b9", "Acento principal"},
        {"bardo-accent2",    "#9dc4e8", "Acento secundario"},
        {"bardo-text",       "#5a4a6a", "Texto principal"},
        {"bardo-text-muted", "#a090b0", "Texto secundario"},
        {"bardo-player-bg1", "#fdf6ff", "Reproductor (superior)"},
        {"bardo-player-bg2", "#f0e8ff", "Reproductor (inferior)"},
    };

    // ── Estado de tema ────────────────────────────────────────────────────────

    /** Colores configurados manualmente por el usuario (fuente de verdad persistida). */
    final Map<String, String> baseTheme     = new LinkedHashMap<>();

    /** Colores aplicados actualmente en escena (pueden diferir de base con dinámicos). */
    final Map<String, String> currentTheme  = new LinkedHashMap<>();

    /** Modo dinámico activo para cada variable CSS ({@code DYN_STATIC/PRIMARY/SECONDARY}). */
    final Map<String, String> themeVarModes = new LinkedHashMap<>();

    /** Si {@code false}, las variables dinámicas ignoran la miniatura y usan el color base. */
    boolean dynamicColorsEnabled = true;

    /** Si {@code false}, no se aplica ningún shadow de contraste al texto. */
    boolean textContrastEnabled  = true;

    private static final long FADE_DURATION_NS = 700_000_000L;

    /**
     * Estado activo de cada fade: {@code double[7] = {fromR, fromG, fromB, toR, toG, toB, startNanos}}.
     * {@code startNanos == -1} indica que aún no ha empezado (se inicializa en el primer frame).
     */
    private final Map<String, double[]> activeFades = new LinkedHashMap<>();
    private AnimationTimer colorFadeTimer;
    private PauseTransition themeSavePause;

    // ── Dependencias ──────────────────────────────────────────────────────────

    private final LibraryService           libraryService;
    private final Supplier<PlayerInstance> focusedPlayerSupplier;
    private final List<PlayerInstance>     activePlayers;
    private final Node                     sidebar;
    private final Node                     tabBarScroll;
    private final Node                     nowPlayingBar;
    private final StackPane                contentArea;

    /**
     * Crea el gestor de temas.
     *
     * @param libraryService        servicio de persistencia para guardar/cargar el tema
     * @param focusedPlayerSupplier supplier del reproductor actualmente enfocado
     * @param activePlayers         lista viva de reproductores activos
     * @param sidebar               nodo de la barra lateral (para contraste)
     * @param tabBarScroll          nodo del scroll de pestañas (para contraste)
     * @param nowPlayingBar         barra "Now Playing" inferior (para contraste)
     * @param contentArea           área central de contenido (para contraste por hijo)
     */
    ThemeManager(LibraryService libraryService,
                 Supplier<PlayerInstance> focusedPlayerSupplier,
                 List<PlayerInstance> activePlayers,
                 Node sidebar, Node tabBarScroll,
                 Node nowPlayingBar, StackPane contentArea) {
        this.libraryService        = libraryService;
        this.focusedPlayerSupplier = focusedPlayerSupplier;
        this.activePlayers         = activePlayers;
        this.sidebar               = sidebar;
        this.tabBarScroll          = tabBarScroll;
        this.nowPlayingBar         = nowPlayingBar;
        this.contentArea           = contentArea;
    }

    /**
     * Carga el tema y los modos guardados desde {@link LibraryService} y rellena
     * {@link #baseTheme}, {@link #currentTheme} y {@link #themeVarModes}.
     * Debe llamarse una sola vez al arrancar, justo después de crear la instancia.
     */
    void loadFromPersistence() {
        Map<String, String> savedTheme = libraryService.loadTheme();
        Map<String, String> savedModes = libraryService.loadThemeVarModes();
        dynamicColorsEnabled = libraryService.loadDynamicColorsEnabled();
        textContrastEnabled  = libraryService.loadTextContrastEnabled();
        for (String[] tv : THEME_VARS) {
            String c = savedTheme.getOrDefault(tv[0], tv[1]);
            baseTheme.put(tv[0], c);
            currentTheme.put(tv[0], c);
            themeVarModes.put(tv[0], savedModes.getOrDefault(tv[0], defaultModeFor(tv[0])));
        }
    }

    // ── Aplicación del tema ───────────────────────────────────────────────────

    /**
     * Aplica {@link #currentTheme} como estilo en línea en la raíz de la escena
     * y recalcula el contraste de texto en todos los contenedores.
     */
    void applyTheme(Scene sc) {
        applyThemeStyle(sc);
        applyContrastStrokes();
    }

    /** Versión sin parámetro; usa la escena del {@code contentArea}. */
    void applyTheme() {
        if (contentArea.getScene() != null) applyTheme(contentArea.getScene());
    }

    /**
     * Actualiza solo las variables CSS en la raíz de la escena sin recalcular el
     * contraste de texto. Se usa dentro de las animaciones de fade para evitar
     * disparar un layout pass completo en cada fotograma.
     */
    private void applyThemeStyle() {
        Scene sc = contentArea.getScene();
        if (sc != null) applyThemeStyle(sc);
    }

    private void applyThemeStyle(Scene sc) {
        StringBuilder sb = new StringBuilder();
        currentTheme.forEach((k, v) -> sb.append(k).append(": ").append(v).append("; "));
        sc.getRoot().setStyle(sb.toString());
    }

    /**
     * Recorre todos los contenedores relevantes y asigna la clase CSS de contraste
     * ({@code bardo-contrast-dark} o {@code bardo-contrast-light}) según el ratio WCAG
     * entre el color de texto y el color de fondo de cada zona.
     *
     * <p>Los paneles de reproductor ({@link PlayerInstance#panel}) usan
     * {@code bardo-player-bg1} como fondo; el resto usa {@code bardo-bg}.
     */
    void applyContrastStrokes() {
        applyContrastStroke(sidebar,       "bardo-text", "bardo-sidebar-bg");
        applyContrastStroke(tabBarScroll,  "bardo-text", "bardo-sidebar-bg");
        applyContrastStroke(nowPlayingBar, "bardo-text", "bardo-player-bg1");
        Set<Node> playerPanels = new HashSet<>();
        for (PlayerInstance pi : activePlayers) {
            if (pi.panel != null) playerPanels.add(pi.panel);
        }
        for (Node child : contentArea.getChildren()) {
            if (playerPanels.contains(child))
                applyContrastStroke(child, "bardo-text", "bardo-player-bg1");
            else
                applyContrastStroke(child, "bardo-text", "bardo-bg");
        }
    }

    /**
     * Elimina ambas clases de contraste del {@code container} y, si el contraste WCAG
     * entre {@code textVar} y {@code bgVar} está por debajo de {@link #CONTRAST_THRESHOLD},
     * añade la clase apropiada ({@code dark} cuando el fondo es claro, {@code light} si es oscuro).
     *
     * @param container nodo al que aplicar/quitar la clase; ignorado si es {@code null}
     * @param textVar   nombre de la variable CSS del color de texto (ej. {@code "bardo-text"})
     * @param bgVar     nombre de la variable CSS del color de fondo
     */
    void applyContrastStroke(Node container, String textVar, String bgVar) {
        if (container == null) return;
        container.getStyleClass().removeAll("bardo-contrast-dark", "bardo-contrast-light");
        if (!textContrastEnabled) return;
        Color tc = tryParseColor(currentTheme.getOrDefault(textVar, "#5a4a6a"));
        Color bc = tryParseColor(currentTheme.getOrDefault(bgVar,   "#faf8fc"));
        if (contrastRatio(tc, bc) >= CONTRAST_THRESHOLD) return;
        double avgLum = (relativeLuminance(tc) + relativeLuminance(bc)) / 2.0;
        container.getStyleClass().add(avgLum > 0.5 ? "bardo-contrast-dark" : "bardo-contrast-light");
    }

    // ── Persistencia debounced ────────────────────────────────────────────────

    /**
     * Programa un guardado del tema base en 500 ms; reinicia el temporizador si se
     * llama de nuevo antes de que venza (patrón debounce).
     */
    void debouncedSaveTheme() {
        if (themeSavePause == null) {
            themeSavePause = new PauseTransition(Duration.millis(500));
            themeSavePause.setOnFinished(e -> libraryService.saveTheme(baseTheme));
        }
        themeSavePause.playFromStart();
    }

    // ── Color dinámico ────────────────────────────────────────────────────────

    /**
     * Actualiza los colores dinámicos según el estado actual: si los dinámicos están
     * desactivados o no hay reproductor con miniatura, revierte a base; de lo contrario
     * extrae los colores dominantes de la miniatura en un hilo daemon y los aplica.
     */
    void updateDynamicColors() {
        if (!dynamicColorsEnabled) { fadeAllToBase(); return; }
        boolean anyDynamic = themeVarModes.values().stream().anyMatch(m -> !DYN_STATIC.equals(m));
        if (!anyDynamic) return;
        PlayerInstance pi = focusedPlayerSupplier.get();
        if (pi == null || !pi.isPlaying || pi.song == null ||
                pi.song.getThumbnailUrl() == null || pi.song.getThumbnailUrl().isBlank()) {
            fadeAllToBase(); return;
        }
        final String thumbUrl = pi.song.getThumbnailUrl();
        final PlayerInstance captured = pi;
        Thread extractor = new Thread(() -> {
            try {
                Image img = new Image(thumbUrl, 128, 128, false, true);
                List<Color> colors = img.isError() ? List.of() : extractDominantColors(img, 2);
                Platform.runLater(() -> {
                    if (focusedPlayerSupplier.get() != captured) return;
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

    /**
     * Revierte todas las variables en modo dinámico a su color base mediante fade.
     * Las variables en modo {@link #DYN_STATIC} no se tocan.
     */
    void fadeAllToBase() {
        for (String[] tv : THEME_VARS) {
            String varName = tv[0];
            if (DYN_STATIC.equals(themeVarModes.getOrDefault(varName, DYN_STATIC))) continue;
            fadeThemeVar(varName, baseTheme.getOrDefault(varName, tv[1]));
        }
    }

    /**
     * Registra una transición de color para {@code varName} hacia {@code toHex}.
     * Todas las transiciones activas se animan juntas en un único {@link AnimationTimer}
     * que llama a {@link #applyThemeStyle()} una sola vez por frame nativo (~60 fps).
     * Si ya había una transición en curso para la misma variable, se sobreescribe
     * partiendo del color interpolado en ese instante.
     */
    void fadeThemeVar(String varName, String toHex) {
        String fromHex = currentTheme.getOrDefault(varName, toHex);
        if (fromHex.equalsIgnoreCase(toHex)) {
            activeFades.remove(varName);
            currentTheme.put(varName, toHex);
            applyThemeStyle();
            applyContrastStrokes();
            return;
        }
        try {
            double fr = Integer.parseInt(fromHex.substring(1, 3), 16);
            double fg = Integer.parseInt(fromHex.substring(3, 5), 16);
            double fb = Integer.parseInt(fromHex.substring(5, 7), 16);
            double tr = Integer.parseInt(toHex.substring(1, 3), 16);
            double tg = Integer.parseInt(toHex.substring(3, 5), 16);
            double tb = Integer.parseInt(toHex.substring(5, 7), 16);
            activeFades.put(varName, new double[]{fr, fg, fb, tr, tg, tb, -1});
            ensureFadeTimerRunning();
        } catch (Exception ex) {
            currentTheme.put(varName, toHex); applyTheme();
        }
    }

    private void ensureFadeTimerRunning() {
        if (colorFadeTimer != null) return;
        colorFadeTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                boolean anyActive = false;
                var it = activeFades.entrySet().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    double[] s = entry.getValue();
                    if (s[6] < 0) s[6] = now;
                    double t = Math.min(1.0, (now - s[6]) / (double) FADE_DURATION_NS);
                    int r = (int) Math.round(s[0] + (s[3] - s[0]) * t);
                    int g = (int) Math.round(s[1] + (s[4] - s[1]) * t);
                    int b = (int) Math.round(s[2] + (s[5] - s[2]) * t);
                    currentTheme.put(entry.getKey(), String.format("#%02x%02x%02x", r, g, b));
                    if (t >= 1.0) it.remove(); else anyActive = true;
                }
                applyThemeStyle();
                if (!anyActive) {
                    stop();
                    colorFadeTimer = null;
                    applyContrastStrokes();
                }
            }
        };
        colorFadeTimer.start();
    }

    private void applyDynamicColors(List<Color> colors) {
        for (String[] tv : THEME_VARS) {
            String varName = tv[0];
            String mode = themeVarModes.getOrDefault(varName, DYN_STATIC);
            if (DYN_STATIC.equals(mode)) continue;
            int idx = DYN_PRIMARY.equals(mode) ? 0 : 1;
            if (idx >= colors.size()) idx = 0;
            fadeThemeVar(varName, colorToHex(colors.get(idx)));
        }
    }

    // ── Utilidades estáticas ──────────────────────────────────────────────────

    /**
     * Devuelve el modo dinámico por defecto para una variable de tema.
     * {@code bardo-accent} y {@code bardo-player-bg1} → {@link #DYN_PRIMARY};
     * {@code bardo-accent2} y {@code bardo-player-bg2} → {@link #DYN_SECONDARY};
     * el resto → {@link #DYN_STATIC}.
     */
    static String defaultModeFor(String varName) {
        return switch (varName) {
            case "bardo-accent", "bardo-player-bg1"  -> DYN_PRIMARY;
            case "bardo-accent2", "bardo-player-bg2" -> DYN_SECONDARY;
            default -> DYN_STATIC;
        };
    }

    /** Parsea un color CSS hexadecimal; devuelve blanco si el formato no es válido. */
    static Color tryParseColor(String hex) {
        try { return Color.web(hex); }
        catch (Exception e) { return Color.web("#ffffff"); }
    }

    /** Convierte un {@link Color} a cadena hexadecimal {@code #rrggbb}. */
    static String colorToHex(Color c) {
        return String.format("#%02x%02x%02x",
            (int) Math.round(c.getRed()   * 255),
            (int) Math.round(c.getGreen() * 255),
            (int) Math.round(c.getBlue()  * 255));
    }

    /** Calcula el ratio de contraste WCAG entre dos colores (rango 1–21). */
    static double contrastRatio(Color a, Color b) {
        double la = relativeLuminance(a) + 0.05, lb = relativeLuminance(b) + 0.05;
        return la > lb ? la / lb : lb / la;
    }

    /** Calcula la luminancia relativa WCAG de un color. */
    static double relativeLuminance(Color c) {
        return 0.2126 * linearize(c.getRed())
             + 0.7152 * linearize(c.getGreen())
             + 0.0722 * linearize(c.getBlue());
    }

    private static double linearize(double ch) {
        return ch <= 0.04045 ? ch / 12.92 : Math.pow((ch + 0.055) / 1.055, 2.4);
    }

    /**
     * Extrae hasta {@code count} colores dominantes de {@code img} usando cuantización
     * por tono (18 cubos de 20°). Filtra píxeles con poca saturación o brillo extremo.
     * Garantiza diversidad angular mínima de 60° entre colores devueltos.
     *
     * @param img   imagen fuente (puede ser de cualquier tamaño; se recomienda 128×128)
     * @param count número máximo de colores a extraer
     * @return lista de colores ordenada por frecuencia, vacía si no hay suficientes píxeles
     */
    static List<Color> extractDominantColors(Image img, int count) {
        if (img == null || img.isError() || img.getWidth() == 0) return List.of();
        var pr = img.getPixelReader();
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        Map<Integer, double[]> buckets = new HashMap<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Color c = pr.getColor(x, y);
                if (c.getSaturation() < 0.2 || c.getBrightness() < 0.2 || c.getBrightness() > 0.92) continue;
                int hb = (int) (c.getHue() / 20);
                buckets.computeIfAbsent(hb, k -> new double[4]);
                double[] acc = buckets.get(hb);
                acc[0] += c.getRed(); acc[1] += c.getGreen(); acc[2] += c.getBlue(); acc[3]++;
            }
        }
        if (buckets.isEmpty()) return List.of();
        List<Map.Entry<Integer, double[]>> entries = new ArrayList<>(buckets.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue()[3], a.getValue()[3]));
        List<Color> result = new ArrayList<>();
        List<Integer> usedHueBuckets = new ArrayList<>();
        for (Map.Entry<Integer, double[]> e : entries) {
            int hb = e.getKey();
            boolean tooClose = usedHueBuckets.stream().anyMatch(u -> {
                int d = Math.abs(hb - u); return Math.min(d, 18 - d) < 3;
            });
            if (tooClose) continue;
            usedHueBuckets.add(hb);
            double[] acc = e.getValue(); double n = acc[3];
            result.add(Color.color(
                Math.min(1, acc[0] / n), Math.min(1, acc[1] / n), Math.min(1, acc[2] / n)));
            if (result.size() >= count) break;
        }
        return result;
    }
}
