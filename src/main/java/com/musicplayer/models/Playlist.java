package com.musicplayer.models;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class Playlist {

    private String name;
    private String description;
    private final ObservableList<Song> songs = FXCollections.observableArrayList();

    public Playlist(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public ObservableList<Song> getSongs() { return songs; }

    public void addSong(Song song) { songs.add(song); }
    public void removeSong(Song song) { songs.remove(song); }

    public int size() { return songs.size(); }
}
