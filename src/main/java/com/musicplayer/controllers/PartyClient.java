package com.musicplayer.controllers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.musicplayer.models.Song;
import com.musicplayer.services.DownloadService;
import javafx.application.Platform;

import java.io.*;
import java.net.Socket;
import java.nio.file.Path;

/**
 * Cliente TCP que conecta a un {@link PartyServer} remoto.
 * Descarga automáticamente la canción que el Master anuncia y reporta su estado.
 * Todos los callbacks se invocan en el hilo de JavaFX.
 */
class PartyClient {

    record MemberInfo(String name, String emoji, String color) {}

    interface Callbacks {
        void onConnected();
        void onTrackLoading(String title);
        void onTrackAnnounced(String videoId, String title, boolean hidden);
        void onTrackReady(Song song, Path localPath);
        void onOpen(String videoId, boolean hidden);
        void onPlay(String videoId, long positionMs);
        void onPause(String videoId, long positionMs);
        void onSeek(String videoId, long positionMs);
        void onVolume(String videoId, double volume);
        void onLoop(String videoId, boolean looping);
        void onCloseTrack(String videoId);
        void onRoomClosed();
        void onRejected(String reason);
        void onKicked();
        void onBanned();
        void onRedownload(String videoId);
        void onRemoveTrack(String videoId);
        void onChatMessage(String name, String emoji, String color, String text, String songRefVideoId, String songRefTitle);
        void onReaction(String name, String emoji, String color, String reaction, String songRefVideoId, String songRefTitle);
        void onMembersUpdate(java.util.List<MemberInfo> members);
        void onDisconnected(String reason);
    }

    private final String host;
    private final int port;
    private final String listenerName;
    private final String emoji;
    private final String color;
    private final DownloadService downloadService;
    private final Callbacks callbacks;
    private final Gson gson = new Gson();

    private Socket socket;
    private PrintWriter out;
    private volatile boolean running;
    PartyClient(String host, int port, String listenerName, String emoji, String color,
                DownloadService downloadService, Callbacks callbacks) {
        this.host = host;
        this.port = port;
        this.listenerName = listenerName;
        this.emoji = emoji;
        this.color = color;
        this.downloadService = downloadService;
        this.callbacks = callbacks;
    }

    void connect() {
        Thread t = new Thread(() -> {
            boolean everConnected = false;
            try {
                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(host, port), 10_000);
                running = true;
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8), true);

                send("hello", j -> { j.addProperty("name", listenerName); j.addProperty("emoji", emoji); j.addProperty("color", color); });
                everConnected = true;

                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                String line;
                while (running && (line = in.readLine()) != null) handleMessage(line);

            } catch (IOException e) {
                // Notify only on real failure: either mid-session drop (running=true)
                // or initial connect failure (everConnected=false).
                // Skip if user called disconnect() intentionally (running=false, everConnected=true).
                if (running || !everConnected) {
                    String reason = e.getMessage() != null ? e.getMessage() : "No se pudo conectar al servidor";
                    Platform.runLater(() -> callbacks.onDisconnected(reason));
                }
            } finally {
                close();
            }
        }, "party-listener");
        t.setDaemon(true);
        t.start();
    }

    void disconnect() {
        running = false;
        close();
    }

    void sendChat(String text, String songRefVideoId, String songRefTitle) {
        send("chat", j -> {
            j.addProperty("text", text);
            j.addProperty("color", color);
            if (songRefVideoId != null) { j.addProperty("songRefVideoId", songRefVideoId); j.addProperty("songRefTitle", songRefTitle != null ? songRefTitle : ""); }
        });
    }

    void sendReaction(String reaction, String songRefVideoId, String songRefTitle) {
        send("reaction", j -> {
            j.addProperty("reaction", reaction);
            j.addProperty("color", color);
            if (songRefVideoId != null) {
                j.addProperty("songRefVideoId", songRefVideoId);
                j.addProperty("songRefTitle", songRefTitle != null ? songRefTitle : "");
            }
        });
    }

    // ── Message handling ──────────────────────────────────────────────────────

    private void handleMessage(String json) {
        try {
            JsonObject msg = gson.fromJson(json, JsonObject.class);
            switch (msg.get("type").getAsString()) {
                case "welcome" -> Platform.runLater(callbacks::onConnected);
                case "reject"  -> { running = false; String r = msg.get("reason").getAsString(); Platform.runLater(() -> callbacks.onRejected(r)); }
                case "track"  -> handleTrack(msg);
                case "open"   -> { String vid = msg.get("videoId").getAsString(); boolean hid = msg.has("hidden") && msg.get("hidden").getAsBoolean(); Platform.runLater(() -> callbacks.onOpen(vid, hid)); }
                case "play"   -> { String vid = msg.get("videoId").getAsString(); long p = msg.get("positionMs").getAsLong(); Platform.runLater(() -> callbacks.onPlay(vid, p)); }
                case "pause"  -> { String vid = msg.get("videoId").getAsString(); long p = msg.get("positionMs").getAsLong(); Platform.runLater(() -> callbacks.onPause(vid, p)); }
                case "seek"   -> { String vid = msg.get("videoId").getAsString(); long p = msg.get("positionMs").getAsLong(); Platform.runLater(() -> callbacks.onSeek(vid, p)); }
                case "volume" -> { String vid = msg.get("videoId").getAsString(); double v = msg.get("volume").getAsDouble(); Platform.runLater(() -> callbacks.onVolume(vid, v)); }
                case "loop"       -> { String vid = msg.get("videoId").getAsString(); boolean l = msg.get("looping").getAsBoolean(); Platform.runLater(() -> callbacks.onLoop(vid, l)); }
                case "closeTrack"  -> { String vid = msg.get("videoId").getAsString(); Platform.runLater(() -> callbacks.onCloseTrack(vid)); }
                case "roomClosed"  -> { running = false; Platform.runLater(() -> callbacks.onRoomClosed()); }
                case "kick"        -> { running = false; Platform.runLater(() -> callbacks.onKicked()); }
                case "ban"         -> { running = false; Platform.runLater(() -> callbacks.onBanned()); }
                case "redownload"  -> { String vid = msg.get("videoId").getAsString(); Platform.runLater(() -> callbacks.onRedownload(vid)); }
                case "removeTrack" -> { String vid = msg.get("videoId").getAsString(); Platform.runLater(() -> callbacks.onRemoveTrack(vid)); }
                case "chat" -> {
                    String n = msg.get("name").getAsString(); String em = msg.has("emoji") ? msg.get("emoji").getAsString() : "bxs-music";
                    String c = msg.has("color") ? msg.get("color").getAsString() : "#a090b0";
                    String t = msg.has("text") ? msg.get("text").getAsString() : "";
                    String svid = msg.has("songRefVideoId") ? msg.get("songRefVideoId").getAsString() : null;
                    String st = msg.has("songRefTitle") ? msg.get("songRefTitle").getAsString() : null;
                    Platform.runLater(() -> callbacks.onChatMessage(n, em, c, t, svid, st));
                }
                case "reaction" -> {
                    String n = msg.get("name").getAsString(); String em = msg.has("emoji") ? msg.get("emoji").getAsString() : "bxs-music";
                    String c = msg.has("color") ? msg.get("color").getAsString() : "#a090b0";
                    String r = msg.get("reaction").getAsString();
                    String svid = msg.has("songRefVideoId") ? msg.get("songRefVideoId").getAsString() : null;
                    String st   = msg.has("songRefTitle")   ? msg.get("songRefTitle").getAsString()   : null;
                    Platform.runLater(() -> callbacks.onReaction(n, em, c, r, svid, st));
                }
                case "members" -> {
                    com.google.gson.JsonArray arr = msg.getAsJsonArray("members");
                    java.util.List<MemberInfo> list = new java.util.ArrayList<>();
                    for (com.google.gson.JsonElement el : arr) {
                        com.google.gson.JsonObject mo = el.getAsJsonObject();
                        String c = mo.has("color") ? mo.get("color").getAsString() : "#a090b0";
                        list.add(new MemberInfo(mo.get("name").getAsString(), mo.get("emoji").getAsString(), c));
                    }
                    Platform.runLater(() -> callbacks.onMembersUpdate(list));
                }
            }
        } catch (Exception ignored) {}
    }

    private void handleTrack(JsonObject msg) {
        String videoId      = msg.get("videoId").getAsString();
        String title        = msg.get("title").getAsString();
        String thumbnailUrl = msg.has("thumbnailUrl") ? msg.get("thumbnailUrl").getAsString() : "";
        boolean hidden      = msg.has("hidden") && msg.get("hidden").getAsBoolean();
        String displayTitle = hidden ? "???" : title;
        Platform.runLater(() -> callbacks.onTrackLoading(displayTitle));
        Platform.runLater(() -> callbacks.onTrackAnnounced(videoId, title, hidden));
        send("downloading", j -> j.addProperty("videoId", videoId));

        Song song = new Song(videoId, title, "", "—", thumbnailUrl, "");
        downloadService.downloadAudio(song, "party")
            .thenAccept(path -> {
                song.setLocalFilePath(path.toString());
                send("ready", j -> j.addProperty("videoId", videoId));
                Platform.runLater(() -> callbacks.onTrackReady(song, path));
            })
            .exceptionally(ex -> {
                send("error", j -> { j.addProperty("message", ex.getMessage()); j.addProperty("videoId", videoId); });
                Platform.runLater(() -> callbacks.onDisconnected("Error al descargar: " + ex.getMessage()));
                return null;
            });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface JsonBuilder { void build(JsonObject j); }

    private void send(String type, JsonBuilder builder) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        if (builder != null) builder.build(msg);
        if (out != null) out.println(gson.toJson(msg));
    }

    private void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
}
