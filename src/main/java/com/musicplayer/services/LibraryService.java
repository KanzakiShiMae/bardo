package com.musicplayer.services;

import com.musicplayer.models.LibraryGroup;
import com.musicplayer.models.Song;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.util.Duration;
import java.util.List;

public class LibraryService {

    private static LibraryService instance;

    private final ObservableList<LibraryGroup> groups      = FXCollections.observableArrayList();
    private final ObservableList<Song>         pinnedSongs = FXCollections.observableArrayList();
    private final PersistenceService persistence = new PersistenceService();
    private PauseTransition savePause;

    private LibraryService() {
        persistence.load().forEach(g -> { groups.add(g); watchGroup(g); });
        pinnedSongs.addAll(persistence.loadPinnedSongs());
        groups.addListener((ListChangeListener<LibraryGroup>) c -> debouncedSave());
        pinnedSongs.addListener((ListChangeListener<Song>) c -> debouncedSave());
    }

    public static LibraryService getInstance() {
        if (instance == null) instance = new LibraryService();
        return instance;
    }

    public ObservableList<LibraryGroup> getGroups() { return groups; }

    public LibraryGroup createGroup(String name) {
        LibraryGroup g = LibraryGroup.createCustom(name);
        watchGroup(g);
        groups.add(g);
        return g;
    }

    public void addGroup(LibraryGroup group) {
        if (groups.stream().noneMatch(g -> g.getId().equals(group.getId()))) {
            watchGroup(group);
            groups.add(group);
        }
    }

    public void removeGroup(LibraryGroup group) { groups.remove(group); }

    public LibraryGroup getOrCreateHistorial() {
        return groups.stream()
            .filter(g -> "Historial".equals(g.getName()) && !g.isYoutubePlaylist())
            .findFirst()
            .orElseGet(() -> createGroup("Historial"));
    }

    public void addSongToGroup(Song song, LibraryGroup group) { group.addSong(song); }

    public void save() { persistence.save(groups, pinnedSongs); }

    public ObservableList<Song> getPinnedSongs() { return pinnedSongs; }
    public boolean isSongPinned(String videoId)  { return pinnedSongs.stream().anyMatch(s -> s.getVideoId().equals(videoId)); }
    public void pinSong(Song song)               { if (!isSongPinned(song.getVideoId())) pinnedSongs.add(song); }
    public void unpinSong(String videoId)        { pinnedSongs.removeIf(s -> s.getVideoId().equals(videoId)); }

    public int  loadVolume()           { return persistence.loadVolume(); }
    public void saveVolume(int pct)    { persistence.saveVolume(pct); }
    public int  loadAmbientDuck()        { return persistence.loadAmbientDuck(); }
    public void saveAmbientDuck(int p)   { persistence.saveAmbientDuck(p); }
    public String loadYouTubeApiKey()    { return persistence.loadYouTubeApiKey(); }
    public void saveYouTubeApiKey(String k) { persistence.saveYouTubeApiKey(k); }

    /** Agrupa ráfagas de cambios en una sola escritura a disco tras 600 ms de inactividad. */
    private void debouncedSave() {
        if (savePause == null) {
            savePause = new PauseTransition(Duration.millis(600));
            savePause.setOnFinished(e -> persistence.save(groups, pinnedSongs));
        }
        savePause.playFromStart();
    }

    private void watchGroup(LibraryGroup group) {
        group.getSongs().addListener((ListChangeListener<Song>) c -> debouncedSave());
    }
}
