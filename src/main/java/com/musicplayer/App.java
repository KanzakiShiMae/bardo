package com.musicplayer;

import com.musicplayer.controllers.UpdateChecker;
import com.musicplayer.services.ConfigLoader;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Punto de entrada JavaFX de Bardo.
 *
 * <p>Configura el {@link javafx.stage.Stage} principal: carga el layout desde
 * {@code main.fxml}, aplica la hoja de estilos, establece el icono de la aplicación
 * y fija el título a {@code "Bardo v{version}"}. La ventana usa
 * {@link javafx.stage.StageStyle#UNDECORATED}; el cromo personalizado y el
 * redimensionado están gestionados por {@link com.musicplayer.controllers.MainController}
 * y {@link com.musicplayer.controllers.ResizeHelper}.
 */
public class App extends Application {

    // Minimum dimensions derived from fixed UI components:
    //   sidebar 220 + song-row buttons ~300 + title min ~200 + scroll ~20 = 740 content
    //   now-playing: left-min 220 + slider-min 200 + right-min 200 = 620 → total 840
    //   → round up to 960 so the quota bar and playlist cards have breathing room
    private static final double MIN_W = 960;
    //   title-bar ~32 + tab-bar 42 + content ~450 + now-playing ~80 = 604
    //   → add margin for quota widget + home cards
    private static final double MIN_H = 660;

    @Override
    public void start(Stage stage) throws Exception {
        // ServiceLoader can't find IkonHandler providers in JPMS because ikonli-javafx
        // doesn't declare 'uses IkonHandler' — register them manually before any FontIcon is created
        try {
            var resolver = org.kordamp.ikonli.javafx.IkonResolver.getInstance();
            for (org.kordamp.ikonli.IkonHandler h : new org.kordamp.ikonli.IkonHandler[]{
                    new org.kordamp.ikonli.boxicons.BoxiconsSolidIkonHandler(),
                    new org.kordamp.ikonli.boxicons.BoxiconsRegularIkonHandler()}) {
                h.setFont(javafx.scene.text.Font.loadFont(h.getFontResource().openStream(), 16));
                resolver.registerHandler(h);
            }
        } catch (Exception ignored) {}

        FXMLLoader loader = new FXMLLoader(
            getClass().getResource("/com/musicplayer/views/main.fxml")
        );

        // Size to 80 % × 85 % of the primary screen, floored at the hard minimums
        javafx.geometry.Rectangle2D sb =
            javafx.stage.Screen.getPrimary().getVisualBounds();
        double initW = Math.max(MIN_W, sb.getWidth()  * 0.80);
        double initH = Math.max(MIN_H, sb.getHeight() * 0.85);
        // Never exceed the available screen area
        initW = Math.min(initW, sb.getWidth());
        initH = Math.min(initH, sb.getHeight());

        Scene scene = new Scene(loader.load(), initW, initH);
        scene.getStylesheets().add(
            getClass().getResource("/com/musicplayer/styles/main.css").toExternalForm()
        );

        java.net.URL icon = getClass().getResource("/com/musicplayer/icons/icon_full.png");
        if (icon != null) stage.getIcons().add(new Image(icon.toExternalForm()));

        String v = ConfigLoader.getVersion();
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle(v.isBlank() ? "Bardo" : "Bardo v" + v);
        stage.setFullScreenExitHint("");
        stage.setScene(scene);
        stage.setMinWidth(MIN_W);
        stage.setMinHeight(MIN_H);

        // Center on the primary screen (UNDECORATED stages don't self-center)
        stage.setX(sb.getMinX() + (sb.getWidth()  - initW) / 2.0);
        stage.setY(sb.getMinY() + (sb.getHeight() - initH) / 2.0);

        stage.show();

        UpdateChecker.checkAsync(v, stage);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
