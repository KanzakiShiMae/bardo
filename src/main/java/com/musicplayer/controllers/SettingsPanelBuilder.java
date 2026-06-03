package com.musicplayer.controllers;

import com.musicplayer.services.ConfigLoader;
import com.musicplayer.services.LibraryService;
import com.musicplayer.services.YouTubeQuotaTracker;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Construye el contenido del panel de Configuración y lo inyecta en el {@code VBox}
 * destino proporcionado.
 *
 * <p>El panel se compone de tres secciones apiladas en un {@link ScrollPane}:
 * <ol>
 *   <li><b>Volumen de ambiente</b> — slider que controla el ratio de ducking para
 *       canciones de tipo Ambiente cuando otra canción está sonando.</li>
 *   <li><b>Clave de API de YouTube</b> — campo de texto para guardar la API key y
 *       reiniciar la aplicación.</li>
 *   <li><b>Apariencia</b> — checkbox de colores dinámicos, checkbox de contraste de
 *       texto, selectores de color con modo dinámico por variable y botón de reset
 *       individual, y botón global de restaurar valores predeterminados.</li>
 * </ol>
 *
 * <p>Toda la lógica de tema se delega en {@link ThemeManager}; los callbacks
 * de volumen de ambiente se resuelven mediante {@code onAmbientDuckChange}.
 */
public final class SettingsPanelBuilder {

    private SettingsPanelBuilder() {}

    /**
     * Rellena {@code settingsPanel} con todos los controles de configuración.
     *
     * @param settingsPanel       panel FXML destino (se reemplaza su contenido)
     * @param themeManager        gestor de temas activo de la sesión
     * @param libraryService      servicio de persistencia
     * @param onAmbientDuckChange callback que recibe el nuevo porcentaje de ducking
     *                            (0–100) y aplica el volumen a todos los reproductores
     */
    public static void build(VBox settingsPanel,
                             ThemeManager themeManager,
                             LibraryService libraryService,
                             YouTubeQuotaTracker quotaTracker,
                             DoubleConsumer onAmbientDuckChange) {
        int savedPct = libraryService.loadAmbientDuck();

        Label titleLbl    = new Label("Configuración");    titleLbl.getStyleClass().add("greeting");
        Label subtitleLbl = new Label("Ajustes de reproducción"); subtitleLbl.getStyleClass().add("greeting-sub");
        VBox header = new VBox(4, titleLbl, subtitleLbl);

        // ── Volumen de ambiente ───────────────────────────────────────────────
        Label sectionLbl = new Label("VOLUMEN DE AMBIENTE"); sectionLbl.getStyleClass().add("sidebar-section-label");
        Label descLbl = new Label(
            "Volumen al que suenan las canciones de tipo Ambiente cuando otra canción está reproduciendo.");
        descLbl.setWrapText(true); descLbl.getStyleClass().add("greeting-sub");

        Slider duckSlider = new Slider(0, 100, savedPct);
        duckSlider.setMajorTickUnit(25); duckSlider.setMinorTickCount(4);
        duckSlider.setShowTickMarks(true); duckSlider.setShowTickLabels(true);
        duckSlider.setSnapToTicks(false);
        duckSlider.getStyleClass().add("volume-slider"); duckSlider.setMaxWidth(340);

        Label duckValueLbl = new Label(savedPct + "%"); duckValueLbl.getStyleClass().add("volume-pct-label");
        HBox sliderRow = new HBox(12, duckSlider, duckValueLbl); sliderRow.setAlignment(Pos.CENTER_LEFT);

        PauseTransition duckSavePause = new PauseTransition(Duration.millis(600));
        duckSavePause.setOnFinished(e -> libraryService.saveAmbientDuck((int) Math.round(duckSlider.getValue())));
        duckSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int pct = (int) Math.round(newVal.doubleValue());
            duckValueLbl.setText(pct + "%");
            onAmbientDuckChange.accept(pct);
            duckSavePause.playFromStart();
        });

        VBox section = new VBox(8, sectionLbl, descLbl, sliderRow);

        // ── Clave de API de YouTube ───────────────────────────────────────────
        Label apiSectionLbl = new Label("CLAVE DE API DE YOUTUBE");
        apiSectionLbl.getStyleClass().add("sidebar-section-label");
        Label apiDescLbl = new Label(
            "Necesaria para buscar canciones y obtener playlists de YouTube. " +
            "Los cambios se aplican al reiniciar la aplicación.");
        apiDescLbl.setWrapText(true); apiDescLbl.getStyleClass().add("greeting-sub");

        String currentKey = libraryService.loadYouTubeApiKey();
        if (currentKey.isBlank()) currentKey = ConfigLoader.get("youtube.api.key");

        PasswordField apiField = new PasswordField();
        apiField.setText(currentKey);
        apiField.setPromptText("AIza…"); apiField.getStyleClass().add("detail-search-field");
        apiField.setPrefWidth(320);

        TextField apiVisible = new TextField();
        apiVisible.setPromptText("AIza…"); apiVisible.getStyleClass().add("detail-search-field");
        apiVisible.setPrefWidth(320);
        apiVisible.setVisible(false); apiVisible.setManaged(false);
        apiVisible.textProperty().bindBidirectional(apiField.textProperty());

        boolean[] revealing = {false};
        Button toggleVisBtn = new Button("👁");
        toggleVisBtn.getStyleClass().add("np-like-btn");
        toggleVisBtn.setOnAction(e -> {
            revealing[0] = !revealing[0];
            apiField.setVisible(!revealing[0]);   apiField.setManaged(!revealing[0]);
            apiVisible.setVisible(revealing[0]);  apiVisible.setManaged(revealing[0]);
            toggleVisBtn.setText(revealing[0] ? "🔒" : "👁");
            if (revealing[0]) apiVisible.requestFocus(); else apiField.requestFocus();
        });

        Label apiStatusLbl = new Label(""); apiStatusLbl.getStyleClass().add("greeting-sub");
        apiStatusLbl.setStyle("-fx-text-fill:#c0392b;");

        Button saveRestartBtn = new Button("💾  Guardar y reiniciar");
        saveRestartBtn.getStyleClass().add("btn-primary");
        saveRestartBtn.setOnAction(e -> {
            String key = apiField.getText().strip();
            if (key.isBlank()) { apiStatusLbl.setText("⚠ La clave no puede estar vacía."); return; }
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

        HBox apiRow = new HBox(8, apiField, apiVisible, toggleVisBtn, saveRestartBtn);
        apiRow.setAlignment(Pos.CENTER_LEFT);
        Separator settingsSep = new Separator(); settingsSep.setPadding(new Insets(8, 0, 8, 0));
        VBox apiSection = new VBox(8, apiSectionLbl, apiDescLbl, apiRow, apiStatusLbl);

        // ── Cuota de YouTube API ──────────────────────────────────────────────
        Label quotaSectionLbl = new Label("CUOTA DE YOUTUBE API");
        quotaSectionLbl.getStyleClass().add("sidebar-section-label");

        VBox quotaSection;
        if (quotaTracker.isEnforced()) {
            Label enforcedLbl = new Label(
                "🔒  Límite activo — API de desarrollo (no se puede desactivar)");
            enforcedLbl.getStyleClass().add("greeting-sub");
            Label enforcedMax = new Label(
                "Límite diario: " + YouTubeQuotaTracker.ENFORCED_MAX + " unidades");
            enforcedMax.getStyleClass().add("greeting-sub");
            quotaSection = new VBox(6, quotaSectionLbl, enforcedLbl, enforcedMax);
        } else {
            Label quotaDesc = new Label(
                "Registra el uso estimado de la API para evitar superar el límite diario de Google.");
            quotaDesc.setWrapText(true);
            quotaDesc.getStyleClass().add("greeting-sub");

            CheckBox enabledCheck = new CheckBox("Limitar cuota diaria");
            enabledCheck.setSelected(quotaTracker.isEnabled());
            enabledCheck.getStyleClass().add("greeting-sub");

            Label limitLbl = new Label("Límite diario:");
            limitLbl.getStyleClass().add("greeting-sub");
            TextField limitField = new TextField(String.valueOf(quotaTracker.getDailyMax()));
            limitField.setPrefWidth(90);
            limitField.getStyleClass().add("detail-search-field");
            limitField.setDisable(!quotaTracker.isEnabled());
            Label unitLbl = new Label("unidades");
            unitLbl.getStyleClass().add("greeting-sub");

            Button saveQuotaBtn = new Button("💾  Guardar");
            saveQuotaBtn.getStyleClass().add("btn-primary");
            saveQuotaBtn.setDisable(!quotaTracker.isEnabled());

            Label quotaStatusLbl = new Label("");
            quotaStatusLbl.getStyleClass().add("greeting-sub");

            enabledCheck.setOnAction(e -> {
                boolean on = enabledCheck.isSelected();
                quotaTracker.setEnabled(on);
                limitField.setDisable(!on);
                saveQuotaBtn.setDisable(!on);
            });

            PauseTransition clearStatus = new PauseTransition(Duration.seconds(2));
            clearStatus.setOnFinished(ev -> quotaStatusLbl.setText(""));
            saveQuotaBtn.setOnAction(e -> {
                try {
                    int val = Integer.parseInt(limitField.getText().strip());
                    quotaTracker.setDailyMax(val);
                    limitField.setText(String.valueOf(quotaTracker.getDailyMax()));
                    quotaStatusLbl.setText("✓ Guardado");
                    quotaStatusLbl.setStyle("-fx-text-fill: #27ae60;");
                    clearStatus.playFromStart();
                } catch (NumberFormatException ex) {
                    quotaStatusLbl.setText("⚠ Introduce un número entero.");
                    quotaStatusLbl.setStyle("-fx-text-fill: #c0392b;");
                }
            });

            HBox limitRow = new HBox(8, limitLbl, limitField, unitLbl, saveQuotaBtn);
            limitRow.setAlignment(Pos.CENTER_LEFT);
            quotaSection = new VBox(8, quotaSectionLbl, quotaDesc, enabledCheck, limitRow, quotaStatusLbl);
        }

        Separator quotaSep = new Separator(); quotaSep.setPadding(new Insets(8, 0, 8, 0));

        // ── Apariencia ────────────────────────────────────────────────────────
        Label appearanceSectionLbl = new Label("APARIENCIA");
        appearanceSectionLbl.getStyleClass().add("sidebar-section-label");
        Label appearanceDesc = new Label(
            "Personaliza los colores de la interfaz. Los cambios se aplican al instante.");
        appearanceDesc.setWrapText(true); appearanceDesc.getStyleClass().add("greeting-sub");

        CheckBox dynColorsCheck = new CheckBox("Colores dinámicos");
        dynColorsCheck.setSelected(themeManager.dynamicColorsEnabled);
        dynColorsCheck.getStyleClass().add("greeting-sub");
        Label dynColorsDesc = new Label(
            "Cuando está activo, los colores marcados con un selector de modo cambian " +
            "automáticamente con la miniatura de la canción en reproducción.");
        dynColorsDesc.setWrapText(true); dynColorsDesc.getStyleClass().add("greeting-sub");
        dynColorsCheck.setOnAction(e -> {
            themeManager.dynamicColorsEnabled = dynColorsCheck.isSelected();
            libraryService.saveDynamicColorsEnabled(themeManager.dynamicColorsEnabled);
            themeManager.updateDynamicColors();
        });

        CheckBox textContrastCheck = new CheckBox("Contraste de texto automático");
        textContrastCheck.setSelected(themeManager.textContrastEnabled);
        textContrastCheck.getStyleClass().add("greeting-sub");
        Label textContrastDesc = new Label(
            "Añade una sombra alrededor del texto cuando el color del texto y el fondo son " +
            "demasiado similares, para garantizar la legibilidad.");
        textContrastDesc.setWrapText(true); textContrastDesc.getStyleClass().add("greeting-sub");
        textContrastCheck.setOnAction(e -> {
            themeManager.textContrastEnabled = textContrastCheck.isSelected();
            libraryService.saveTextContrastEnabled(themeManager.textContrastEnabled);
            themeManager.applyTheme();
        });

        VBox colorRows = new VBox(10);
        colorRows.setPadding(new Insets(4, 0, 4, 0));

        List<ColorPicker> pickers = new ArrayList<>();
        List<ComboBox<String>> combos = new ArrayList<>();

        for (String[] tv : ThemeManager.THEME_VARS) {
            Label lbl = new Label(tv[2]); lbl.getStyleClass().add("greeting-sub"); lbl.setMinWidth(140);

            ColorPicker cp = new ColorPicker(
                ThemeManager.tryParseColor(themeManager.baseTheme.getOrDefault(tv[0], tv[1])));
            cp.getStyleClass().addAll("theme-color-picker", "button"); cp.setPrefWidth(130);
            pickers.add(cp);

            ComboBox<String> modeCombo = new ComboBox<>(FXCollections.observableArrayList(
                "Estático", "Color primario de canción", "Color secundario de canción"));
            String savedMode = themeManager.themeVarModes.getOrDefault(tv[0], ThemeManager.DYN_STATIC);
            modeCombo.setValue(
                ThemeManager.DYN_PRIMARY.equals(savedMode)   ? "Color primario de canción" :
                ThemeManager.DYN_SECONDARY.equals(savedMode) ? "Color secundario de canción" : "Estático");
            modeCombo.setPrefWidth(210);
            combos.add(modeCombo);

            final String varName = tv[0];
            final String defColor = tv[1];

            Button resetColorBtn = new Button();
            resetColorBtn.getStyleClass().add("color-reset-btn");
            resetColorBtn.setStyle("-fx-background-color: " + defColor + ";");
            resetColorBtn.setTooltip(new Tooltip("Restaurar color predeterminado"));
            resetColorBtn.setOnAction(e -> cp.setValue(ThemeManager.tryParseColor(defColor)));

            cp.valueProperty().addListener((obs, old, col) -> {
                String hex = ThemeManager.colorToHex(col);
                themeManager.baseTheme.put(varName, hex);
                if (ThemeManager.DYN_STATIC.equals(
                        themeManager.themeVarModes.getOrDefault(varName, ThemeManager.DYN_STATIC))) {
                    themeManager.currentTheme.put(varName, hex);
                    themeManager.applyTheme();
                }
                themeManager.debouncedSaveTheme();
            });

            modeCombo.setOnAction(e -> {
                String sel  = modeCombo.getValue();
                String mode = "Color primario de canción".equals(sel)   ? ThemeManager.DYN_PRIMARY :
                              "Color secundario de canción".equals(sel) ? ThemeManager.DYN_SECONDARY
                                                                        : ThemeManager.DYN_STATIC;
                themeManager.themeVarModes.put(varName, mode);
                libraryService.saveThemeVarModes(themeManager.themeVarModes);
                themeManager.updateDynamicColors();
            });

            HBox row = new HBox(12, lbl, cp, resetColorBtn, modeCombo);
            row.setAlignment(Pos.CENTER_LEFT);
            colorRows.getChildren().add(row);
        }

        Button resetThemeBtn = new Button("↺  Restaurar colores predeterminados");
        resetThemeBtn.getStyleClass().add("btn-secondary");
        resetThemeBtn.setOnAction(e -> {
            for (int i = 0; i < ThemeManager.THEME_VARS.length; i++) {
                String var = ThemeManager.THEME_VARS[i][0], def = ThemeManager.THEME_VARS[i][1];
                themeManager.baseTheme.put(var, def);
                themeManager.currentTheme.put(var, def);
                pickers.get(i).setValue(ThemeManager.tryParseColor(def));
                String defMode = ThemeManager.defaultModeFor(var);
                themeManager.themeVarModes.put(var, defMode);
                combos.get(i).setValue(
                    ThemeManager.DYN_PRIMARY.equals(defMode)   ? "Color primario de canción" :
                    ThemeManager.DYN_SECONDARY.equals(defMode) ? "Color secundario de canción" : "Estático");
            }
            themeManager.dynamicColorsEnabled = true; dynColorsCheck.setSelected(true);
            themeManager.textContrastEnabled  = true; textContrastCheck.setSelected(true);
            themeManager.applyTheme();
            themeManager.updateDynamicColors();
            themeManager.debouncedSaveTheme();
            libraryService.saveThemeVarModes(themeManager.themeVarModes);
            libraryService.saveDynamicColorsEnabled(true);
            libraryService.saveTextContrastEnabled(true);
        });

        VBox appearanceSection = new VBox(8, appearanceSectionLbl, appearanceDesc,
            dynColorsCheck, dynColorsDesc, textContrastCheck, textContrastDesc,
            colorRows, resetThemeBtn);

        Separator appearanceSep = new Separator(); appearanceSep.setPadding(new Insets(8, 0, 8, 0));

        VBox scrollContent = new VBox(24, header, section, settingsSep, apiSection,
            quotaSep, quotaSection, appearanceSep, appearanceSection);
        scrollContent.setPadding(new Insets(28, 28, 28, 28));

        ScrollPane settingsScroll = new ScrollPane(scrollContent);
        settingsScroll.setFitToWidth(true);
        settingsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        settingsScroll.getStyleClass().add("results-scroll");
        VBox.setVgrow(settingsScroll, Priority.ALWAYS);

        settingsPanel.getChildren().setAll(settingsScroll);
    }
}
