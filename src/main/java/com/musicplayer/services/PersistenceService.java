package com.musicplayer.services;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.musicplayer.models.LibraryGroup;
import com.musicplayer.models.Song;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class PersistenceService {

    private static final Path SAVE_FILE;
    private static final Path SETTINGS_FILE;

    static {
        String appData = System.getenv("APPDATA");
        Path base = (appData != null)
            ? Path.of(appData, "Bardo")
            : Path.of(System.getProperty("user.home"), ".bardo");
        SAVE_FILE    = base.resolve("library.json");
        SETTINGS_FILE = base.resolve("settings.json");
    }

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // ── Settings ──────────────────────────────────────────────────────────────

    private static class SettingsDto {
        int    volume       = 70;
        int    ambientDuck  = 60;
        String youtubeApiKey = "";
    }

    private SettingsDto loadSettingsDto() {
        if (!Files.exists(SETTINGS_FILE)) return new SettingsDto();
        try {
            SettingsDto dto = gson.fromJson(Files.readString(SETTINGS_FILE), SettingsDto.class);
            return dto != null ? dto : new SettingsDto();
        } catch (Exception e) {
            return new SettingsDto();
        }
    }

    private void writeSettings(SettingsDto dto) {
        try {
            Files.createDirectories(SETTINGS_FILE.getParent());
            Files.writeString(SETTINGS_FILE, gson.toJson(dto));
        } catch (IOException e) {
            System.err.println("Error guardando configuración: " + e.getMessage());
        }
    }

    public void saveVolume(int pct) {
        SettingsDto dto = loadSettingsDto();
        dto.volume = pct;
        writeSettings(dto);
    }

    public int loadVolume() {
        return Math.max(0, Math.min(100, loadSettingsDto().volume));
    }

    public void saveAmbientDuck(int pct) {
        SettingsDto dto = loadSettingsDto();
        dto.ambientDuck = pct;
        writeSettings(dto);
    }

    public int loadAmbientDuck() {
        return Math.max(0, Math.min(100, loadSettingsDto().ambientDuck));
    }

    public void saveYouTubeApiKey(String key) {
        SettingsDto dto = loadSettingsDto();
        dto.youtubeApiKey = key != null ? key.strip() : "";
        writeSettings(dto);
    }

    public String loadYouTubeApiKey() {
        String key = loadSettingsDto().youtubeApiKey;
        return key != null ? key : "";
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    private static class SongDto {
        String videoId, title, artist, duration, thumbnailUrl, channelName, localFilePath;
    }

    private static class GroupDto {
        String  id, name, thumbnailUrl, youtubePlaylistId, description, type;
        boolean youtubePlaylist;
        int     playCount = 0;
        List<SongDto> songs = new ArrayList<>();
    }

    private static class LibraryDto {
        List<GroupDto> groups = new ArrayList<>();
        List<SongDto>  pinnedSongs = new ArrayList<>();
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    public void save(List<LibraryGroup> groups, List<Song> pinnedSongs) {
        LibraryDto dto = new LibraryDto();
        if (pinnedSongs != null) {
            for (Song s : pinnedSongs) {
                SongDto sd = new SongDto();
                sd.videoId       = s.getVideoId();
                sd.title         = s.getTitle();
                sd.artist        = s.getArtist();
                sd.duration      = s.getDuration();
                sd.thumbnailUrl  = s.getThumbnailUrl();
                sd.channelName   = s.getChannelName();
                sd.localFilePath = s.getLocalFilePath();
                dto.pinnedSongs.add(sd);
            }
        }
        for (LibraryGroup g : groups) {
            GroupDto gd = new GroupDto();
            gd.id               = g.getId();
            gd.name             = g.getName();
            gd.thumbnailUrl     = g.getThumbnailUrl();
            gd.youtubePlaylist  = g.isYoutubePlaylist();
            gd.youtubePlaylistId = g.getYoutubePlaylistId();
            gd.description      = g.getDescription();
            gd.type             = g.getType();
            gd.playCount        = g.getPlayCount();
            for (Song s : g.getSongs()) {
                SongDto sd = new SongDto();
                sd.videoId       = s.getVideoId();
                sd.title         = s.getTitle();
                sd.artist        = s.getArtist();
                sd.duration      = s.getDuration();
                sd.thumbnailUrl  = s.getThumbnailUrl();
                sd.channelName   = s.getChannelName();
                sd.localFilePath = s.getLocalFilePath();
                gd.songs.add(sd);
            }
            dto.groups.add(gd);
        }

        try {
            Files.createDirectories(SAVE_FILE.getParent());
            Files.writeString(SAVE_FILE, gson.toJson(dto));
        } catch (IOException e) {
            System.err.println("Error guardando biblioteca: " + e.getMessage());
        }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    public List<LibraryGroup> load() {
        List<LibraryGroup> result = new ArrayList<>();
        if (!Files.exists(SAVE_FILE)) return result;

        try {
            String json = Files.readString(SAVE_FILE);
            LibraryDto dto = gson.fromJson(json, LibraryDto.class);
            if (dto == null || dto.groups == null) return result;

            for (GroupDto gd : dto.groups) {
                LibraryGroup group = gd.youtubePlaylist
                    ? LibraryGroup.fromYouTubePlaylist(gd.id, gd.name,
                        gd.thumbnailUrl, orEmpty(gd.description))
                    : LibraryGroup.createCustomWithId(gd.id, gd.name);

                if (gd.songs != null) {
                    for (SongDto sd : gd.songs) {
                        Song song = new Song(
                            orEmpty(sd.videoId), orEmpty(sd.title), orEmpty(sd.artist),
                            orEmpty(sd.duration), sd.thumbnailUrl, orEmpty(sd.channelName),
                            sd.localFilePath
                        );
                        group.getSongs().add(song);
                    }
                }
                group.setType(gd.type != null ? gd.type : "Música");
                group.setPlayCount(gd.playCount);
                result.add(group);
            }
        } catch (Exception e) {
            System.err.println("Error cargando biblioteca: " + e.getMessage());
        }
        return result;
    }

    public List<Song> loadPinnedSongs() {
        if (!Files.exists(SAVE_FILE)) return new ArrayList<>();
        try {
            LibraryDto dto = gson.fromJson(Files.readString(SAVE_FILE), LibraryDto.class);
            if (dto == null || dto.pinnedSongs == null) return new ArrayList<>();
            List<Song> result = new ArrayList<>();
            for (SongDto sd : dto.pinnedSongs)
                result.add(new Song(orEmpty(sd.videoId), orEmpty(sd.title), orEmpty(sd.artist),
                    orEmpty(sd.duration), sd.thumbnailUrl, orEmpty(sd.channelName), sd.localFilePath));
            return result;
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private static String orEmpty(String s) { return s != null ? s : ""; }
}
