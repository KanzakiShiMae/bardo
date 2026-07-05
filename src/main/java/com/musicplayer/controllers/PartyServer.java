package com.musicplayer.controllers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Servidor TCP embebido que gestiona los Listeners conectados a la sala Party del Master.
 * Escucha en el puerto indicado, acepta múltiples clientes y les retransmite comandos de
 * sincronización (track / play / pause). Los callbacks se llaman siempre en el hilo de JavaFX.
 */
class PartyServer {

    enum ListenerStatus { CONNECTING, DOWNLOADING, READY, ERROR }
    record ListenerState(String name, ListenerStatus status, String note, String emoji, String color) {}

    interface Callbacks {
        void onListenerUpdate(String name, ListenerStatus status, String note, String emoji, String color);
        void onListenerDisconnect(String name);
        void onListenerVideoStatus(String name, String videoId, ListenerStatus status);
        void onChatMessage(String name, String emoji, String color, String text, String songRefVideoId, String songRefTitle);
        void onReaction(String name, String emoji, String color, String reaction);
    }

    private record TrackRecord(String videoId, String title, String thumbnailUrl, boolean hidden) {}

    private final Callbacks callbacks;
    private final Gson gson = new Gson();
    private ServerSocket serverSocket;
    private final List<ClientHandler>   clients      = new CopyOnWriteArrayList<>();
    private final List<TrackRecord>     sharedTracks = new CopyOnWriteArrayList<>();
    private final Map<String, ListenerState> states  = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile boolean running;

    PartyServer(int port, Callbacks callbacks) throws IOException {
        this.callbacks = callbacks;
        this.serverSocket = new ServerSocket(port);
    }

    void start() {
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    ClientHandler h = new ClientHandler(socket);
                    clients.add(h);
                    h.start();
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        }, "party-accept");
        t.setDaemon(true);
        t.start();
    }

    // ── Broadcast ─────────────────────────────────────────────────────────────

    void broadcastTrack(String videoId, String title, String thumbnailUrl, boolean hidden) {
        sharedTracks.removeIf(t -> t.videoId().equals(videoId));
        sharedTracks.add(new TrackRecord(videoId, title, thumbnailUrl != null ? thumbnailUrl : "", hidden));
        JsonObject m = new JsonObject();
        m.addProperty("type", "track");
        m.addProperty("videoId", videoId);
        m.addProperty("title", title);
        m.addProperty("thumbnailUrl", thumbnailUrl != null ? thumbnailUrl : "");
        m.addProperty("hidden", hidden);
        broadcast(gson.toJson(m));
    }

    void broadcastOpen(String videoId, boolean hidden) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "open");
        m.addProperty("videoId", videoId);
        m.addProperty("hidden", hidden);
        broadcast(gson.toJson(m));
    }

    void broadcastPlay(String videoId, long positionMs) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "play");
        m.addProperty("videoId", videoId);
        m.addProperty("positionMs", positionMs);
        broadcast(gson.toJson(m));
    }

    void broadcastPause(String videoId, long positionMs) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "pause");
        m.addProperty("videoId", videoId);
        m.addProperty("positionMs", positionMs);
        broadcast(gson.toJson(m));
    }

    void broadcastSeek(String videoId, long positionMs) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "seek");
        m.addProperty("videoId", videoId);
        m.addProperty("positionMs", positionMs);
        broadcast(gson.toJson(m));
    }

    void broadcastVolume(String videoId, double volume) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "volume");
        m.addProperty("videoId", videoId);
        m.addProperty("volume", volume);
        broadcast(gson.toJson(m));
    }

    void broadcastLoop(String videoId, boolean looping) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "loop");
        m.addProperty("videoId", videoId);
        m.addProperty("looping", looping);
        broadcast(gson.toJson(m));
    }

    void broadcastCloseTrack(String videoId) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "closeTrack");
        m.addProperty("videoId", videoId);
        broadcast(gson.toJson(m));
    }

    void broadcastMasterChat(String text) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "chat");
        m.addProperty("name", "Master");
        m.addProperty("emoji", "📡");
        m.addProperty("color", "#54a0ff");
        m.addProperty("text", text);
        broadcast(gson.toJson(m));
    }

    void broadcastRoomClosed() {
        JsonObject m = new JsonObject();
        m.addProperty("type", "roomClosed");
        broadcast(gson.toJson(m));
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    int getPort() { return serverSocket.getLocalPort(); }

    /** Snapshot inmutable del estado de todos los listeners en este momento. */
    List<ListenerState> getStates() { return new ArrayList<>(states.values()); }

    void stop() {
        running = false;
        sharedTracks.clear();
        clients.forEach(ClientHandler::close);
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void broadcast(String json) { clients.forEach(c -> c.send(json)); }

    private void reject(ClientHandler handler, String reason) {
        JsonObject rej = new JsonObject();
        rej.addProperty("type", "reject");
        rej.addProperty("reason", reason);
        handler.send(gson.toJson(rej));
        handler.close();
    }

    private void broadcastMembers() {
        List<ListenerState> snapshot;
        synchronized (states) { snapshot = new ArrayList<>(states.values()); }
        JsonObject m = new JsonObject();
        m.addProperty("type", "members");
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (ListenerState s : snapshot) {
            JsonObject member = new JsonObject();
            member.addProperty("name", s.name());
            member.addProperty("emoji", s.emoji());
            member.addProperty("color", s.color());
            arr.add(member);
        }
        m.add("members", arr);
        broadcast(gson.toJson(m));
    }

    private void onMessage(ClientHandler handler, String json) {
        try {
            JsonObject msg = gson.fromJson(json, JsonObject.class);
            String type = msg.get("type").getAsString();

            if ("hello".equals(type)) {
                if (msg.has("name"))  handler.name  = msg.get("name").getAsString();
                if (msg.has("emoji")) handler.emoji = msg.get("emoji").getAsString();
                if (msg.has("color")) handler.color = msg.get("color").getAsString();

                String incomingEmoji = handler.emoji;
                String incomingColor = handler.color;
                synchronized (states) {
                    for (ListenerState s : states.values()) {
                        if (s.emoji().equals(incomingEmoji)) {
                            reject(handler, "El emoji " + incomingEmoji + " ya está en uso en esta sala");
                            return;
                        }
                        if (s.color().equals(incomingColor)) {
                            reject(handler, "El color elegido ya está en uso en esta sala");
                            return;
                        }
                    }
                }
            }

            String name  = handler.name  != null ? handler.name  : handler.socket.getInetAddress().getHostAddress();
            String emoji = handler.emoji != null ? handler.emoji : "🎵";

            if ("chat".equals(type)) {
                String text  = msg.has("text") ? msg.get("text").getAsString() : "";
                String svid  = msg.has("songRefVideoId") ? msg.get("songRefVideoId").getAsString() : null;
                String stitle = msg.has("songRefTitle")  ? msg.get("songRefTitle").getAsString()  : null;
                String senderColor = handler.color;
                JsonObject out = new JsonObject();
                out.addProperty("type", "chat"); out.addProperty("name", name); out.addProperty("emoji", emoji);
                out.addProperty("color", senderColor); out.addProperty("text", text);
                if (svid != null) { out.addProperty("songRefVideoId", svid); out.addProperty("songRefTitle", stitle != null ? stitle : ""); }
                broadcast(gson.toJson(out));
                Platform.runLater(() -> callbacks.onChatMessage(name, emoji, senderColor, text, svid, stitle));
                return;
            }

            if ("reaction".equals(type)) {
                String reaction = msg.get("reaction").getAsString();
                String senderColor = handler.color;
                JsonObject out = new JsonObject();
                out.addProperty("type", "reaction"); out.addProperty("name", name);
                out.addProperty("emoji", emoji); out.addProperty("color", senderColor); out.addProperty("reaction", reaction);
                broadcast(gson.toJson(out));
                Platform.runLater(() -> callbacks.onReaction(name, emoji, senderColor, reaction));
                return;
            }

            ListenerStatus status = switch (type) {
                case "hello"       -> ListenerStatus.CONNECTING;
                case "downloading" -> ListenerStatus.DOWNLOADING;
                case "ready"       -> ListenerStatus.READY;
                case "error"       -> ListenerStatus.ERROR;
                default            -> null;
            };
            if (status == null) return;

            String note = msg.has("message") ? msg.get("message").getAsString() : "";
            states.put(name, new ListenerState(name, status, note, emoji, handler.color));
            Platform.runLater(() -> callbacks.onListenerUpdate(name, status, note, emoji, handler.color));
            if ("hello".equals(type)) {
                JsonObject welcome = new JsonObject();
                welcome.addProperty("type", "welcome");
                handler.send(gson.toJson(welcome));
                for (TrackRecord t : sharedTracks) {
                    JsonObject tm = new JsonObject();
                    tm.addProperty("type", "track");
                    tm.addProperty("videoId", t.videoId());
                    tm.addProperty("title", t.title());
                    tm.addProperty("thumbnailUrl", t.thumbnailUrl());
                    tm.addProperty("hidden", t.hidden());
                    handler.send(gson.toJson(tm));
                }
                broadcastMembers();
            }

            if ((type.equals("downloading") || type.equals("ready") || type.equals("error")) && msg.has("videoId")) {
                String videoId = msg.get("videoId").getAsString();
                ListenerStatus vs = switch (type) {
                    case "ready" -> ListenerStatus.READY;
                    case "error" -> ListenerStatus.ERROR;
                    default      -> ListenerStatus.DOWNLOADING;
                };
                Platform.runLater(() -> callbacks.onListenerVideoStatus(name, videoId, vs));
            }

        } catch (Exception ignored) {}
    }

    private void onDisconnect(ClientHandler handler) {
        clients.remove(handler);
        String name = handler.name;
        if (name != null) {
            states.remove(name);
            broadcastMembers();
            Platform.runLater(() -> callbacks.onListenerDisconnect(name));
        }
    }

    private class ClientHandler {
        final Socket socket;
        String name;
        String emoji = "🎵";
        String color = "#a090b0";
        private PrintWriter out;

        ClientHandler(Socket socket) { this.socket = socket; }

        void start() {
            Thread t = new Thread(() -> {
                try {
                    out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                    String line;
                    while ((line = in.readLine()) != null) onMessage(this, line);
                } catch (IOException ignored) {
                } finally { close(); onDisconnect(this); }
            }, "party-client-" + socket.getInetAddress().getHostAddress());
            t.setDaemon(true);
            t.start();
        }

        void send(String json) { if (out != null) out.println(json); }
        void close() { try { socket.close(); } catch (IOException ignored) {} }
    }
}
