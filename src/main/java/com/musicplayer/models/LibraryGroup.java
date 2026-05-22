package com.musicplayer.models;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.util.UUID;

/**
 * Colección de canciones que puede ser una lista personalizada del usuario
 * o una playlist importada de YouTube.
 *
 * <p>Cada grupo tiene un identificador estable ({@link #getId()}):
 * para playlists de YouTube es el {@code playlistId} de la API; para colecciones
 * locales es un UUID generado en la creación.
 *
 * <p>{@link #playCount} acumula el número de canciones de este grupo que han
 * sido reproducidas; se usa para determinar las "colecciones más escuchadas"
 * en la pantalla de inicio. Al eliminar una colección del inicio se resetea a 0.
 *
 * <p>El campo {@link #type} ("Música", "Ambiente" o "Mashup") se propaga
 * automáticamente a todas las canciones del grupo cuando se cambia.
 */
public class LibraryGroup {

    private final StringProperty id;
    private final StringProperty name;
    private final StringProperty thumbnailUrl;
    private final BooleanProperty youtubePlaylist;
    private final StringProperty youtubePlaylistId;
    private final StringProperty description;
    private final StringProperty type = new SimpleStringProperty("Música");
    private final ObservableList<Song> songs = FXCollections.observableArrayList();
    private int playCount = 0;

    public static LibraryGroup createCustom(String name) {
        return new LibraryGroup(UUID.randomUUID().toString(), name, null, false, null, "");
    }

    /** Restaura un grupo personalizado con su ID original (desde persistencia). */
    public static LibraryGroup createCustomWithId(String id, String name) {
        return new LibraryGroup(id, name, null, false, null, "");
    }

    public static LibraryGroup fromYouTubePlaylist(String playlistId, String name,
                                                    String thumbnailUrl, String description) {
        return new LibraryGroup(playlistId, name, thumbnailUrl, true, playlistId, description);
    }

    private LibraryGroup(String id, String name, String thumbnailUrl,
                         boolean isYoutube, String youtubePlaylistId, String description) {
        this.id = new SimpleStringProperty(id);
        this.name = new SimpleStringProperty(name);
        this.thumbnailUrl = new SimpleStringProperty(thumbnailUrl);
        this.youtubePlaylist = new SimpleBooleanProperty(isYoutube);
        this.youtubePlaylistId = new SimpleStringProperty(youtubePlaylistId);
        this.description = new SimpleStringProperty(description);
    }

    public String getId() { return id.get(); }
    public String getName() { return name.get(); }
    public void setName(String v) { name.set(v); }
    public StringProperty nameProperty() { return name; }
    public String getThumbnailUrl() { return thumbnailUrl.get(); }
    public boolean isYoutubePlaylist() { return youtubePlaylist.get(); }
    public String getYoutubePlaylistId() { return youtubePlaylistId.get(); }
    public String getDescription() { return description.get(); }
    public ObservableList<Song> getSongs() { return songs; }

    public String getType()         { return type.get(); }
    public StringProperty typeProperty() { return type; }
    public void setType(String t) {
        type.set(t != null ? t : "Música");
        songs.forEach(s -> s.setType(type.get()));
    }

    public int  getPlayCount()       { return playCount; }
    public void setPlayCount(int c)  { this.playCount = c; }
    public void incrementPlayCount() { this.playCount++; }

    public void addSong(Song s) {
        if (songs.stream().noneMatch(x -> x.getVideoId().equals(s.getVideoId()))) {
            s.setType(getType());
            songs.add(s);
        }
    }
    public void removeSong(Song s) { songs.remove(s); }
    public int size() { return songs.size(); }
}