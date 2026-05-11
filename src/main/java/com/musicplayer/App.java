package com.musicplayer;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

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

        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle("Bardo");
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
