module com.musicplayer {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires javafx.media;
    requires okhttp3;
    requires com.google.gson;
    requires java.desktop;
    requires TarsosDSP.core;
    requires TarsosDSP.jvm;
    requires jave.core;
    requires weupnp;
    requires java.prefs;

    opens com.musicplayer to javafx.fxml;
    opens com.musicplayer.controllers to javafx.fxml;
    opens com.musicplayer.models to javafx.fxml;
    opens com.musicplayer.services to com.google.gson;

    exports com.musicplayer;
    exports com.musicplayer.controllers;
    exports com.musicplayer.models;
    exports com.musicplayer.services;
}
