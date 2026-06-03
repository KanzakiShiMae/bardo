package com.musicplayer;

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

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(
            getClass().getResource("/com/musicplayer/views/main.fxml")
        );

        Scene scene = new Scene(loader.load(), 1100, 700);
        scene.getStylesheets().add(
            getClass().getResource("/com/musicplayer/styles/main.css").toExternalForm()
        );

        java.net.URL icon = getClass().getResource("/com/musicplayer/icons/icon_full.png");
        if (icon != null) stage.getIcons().add(new Image(icon.toExternalForm()));

        String v = ConfigLoader.getVersion();
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle(v.isBlank() ? "Bardo" : "Bardo v" + v);
        stage.setFullScreenExitHint("");           // oculta el aviso nativo "Press ESC"
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
