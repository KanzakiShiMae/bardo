package com.musicplayer.models;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.io.File;

/**
 * Modelo inmutable (salvo {@code localFilePath}, {@code duration} y {@code type}) que
 * representa una canción, ya sea de YouTube o de disco local.
 *
 * <p>Para canciones de YouTube, {@code videoId} es el ID de 11 caracteres del vídeo y
 * {@code localFilePath} estará vacío hasta que se descargue el audio. Para archivos locales
 * creados con {@link #fromLocalFile}, el {@code videoId} contiene la ruta absoluta del
 * archivo (actúa como identificador único).
 *
 * <p>Los campos están respaldados por {@link javafx.beans.property.StringProperty} para
 * permitir binding reactivo en la UI (p. ej. actualizar duración cuando el {@code MediaPlayer}
 * obtiene los metadatos).
 */
public class Song {

    private final StringProperty videoId;
    private final StringProperty title;
    private final StringProperty artist;
    private final StringProperty duration;
    private final StringProperty thumbnailUrl;
    private final StringProperty channelName;
    private final StringProperty localFilePath;
    private String type = "Música";

    /** Constructor para canciones de YouTube. */
    public Song(String videoId, String title, String artist, String duration,
                String thumbnailUrl, String channelName) {
        this(videoId, title, artist, duration, thumbnailUrl, channelName, null);
    }

    /** Constructor completo. */
    public Song(String videoId, String title, String artist, String duration,
                String thumbnailUrl, String channelName, String localFilePath) {
        this.videoId      = new SimpleStringProperty(videoId);
        this.title        = new SimpleStringProperty(title);
        this.artist       = new SimpleStringProperty(artist);
        this.duration     = new SimpleStringProperty(duration != null ? duration : "—");
        this.thumbnailUrl = new SimpleStringProperty(thumbnailUrl);
        this.channelName  = new SimpleStringProperty(channelName);
        this.localFilePath = new SimpleStringProperty(localFilePath);
    }

    /** Crea una Song a partir de un archivo local, parseando "Artista - Título" del nombre. */
    public static Song fromLocalFile(File file) {
        String name  = file.getName().replaceAll("\\.[^.]+$", "");
        String title = name, artist = "Desconocido";
        if (name.contains(" - ")) {
            String[] parts = name.split(" - ", 2);
            artist = parts[0].trim();
            title  = parts[1].trim();
        }
        String path = file.getAbsolutePath();
        return new Song(path, title, artist, "—", null, artist, path);
    }

    public boolean isLocal() {
        String p = localFilePath.get();
        return p != null && !p.isBlank();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getVideoId()       { return videoId.get(); }
    public StringProperty videoIdProperty() { return videoId; }

    public String getTitle()         { return title.get(); }
    public StringProperty titleProperty()   { return title; }

    public String getArtist()        { return artist.get(); }
    public StringProperty artistProperty()  { return artist; }

    public String getDuration()      { return duration.get(); }
    public StringProperty durationProperty() { return duration; }
    public void setDuration(String d) { duration.set(d); }

    public String getThumbnailUrl()  { return thumbnailUrl.get(); }
    public StringProperty thumbnailUrlProperty() { return thumbnailUrl; }

    public String getChannelName()   { return channelName.get(); }
    public StringProperty channelNameProperty() { return channelName; }

    public String getLocalFilePath() { return localFilePath.get(); }
    public void setLocalFilePath(String path) { localFilePath.set(path); }
    public StringProperty localFilePathProperty() { return localFilePath; }

    public String getType()          { return type; }
    public void   setType(String t)  { this.type = t != null ? t : "Música"; }
}
