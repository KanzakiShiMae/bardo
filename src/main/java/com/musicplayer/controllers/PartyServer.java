package com.musicplayer.controllers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.application.Platform;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
        /** Porcentaje (0-100) de descarga de {@code videoId} reportado por el listener {@code name}. */
        void onListenerVideoProgress(String name, String videoId, int percent);
        /** La conexión de {@code name} se cayó inesperadamente; tiene una ventana de gracia para reconectar. */
        void onListenerReconnecting(String name);
        /** {@code name} reconectó dentro de la ventana de gracia — ya no hace falta seguir mostrando el aviso. */
        void onListenerReconnected(String name);
        /** Ping (ms de ida y vuelta) que {@code name} acaba de medir contra este servidor. */
        void onListenerPing(String name, int ms);
        void onChatMessage(String name, String emoji, String color, String text, String songRefVideoId, String songRefTitle);
        void onReaction(String name, String emoji, String color, String reaction, String songRefVideoId, String songRefTitle);
    }

    private record TrackRecord(String videoId, String title, String thumbnailUrl, boolean hidden) {}

    private final Callbacks callbacks;
    private final Gson gson = new Gson();
    private ServerSocket serverSocket;
    private final List<ClientHandler>   clients      = new CopyOnWriteArrayList<>();
    private final List<TrackRecord>     sharedTracks = new CopyOnWriteArrayList<>();
    private final Map<String, ListenerState> states  = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Set<String> bannedNames = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean running;

    // ── Reconexión de listeners ──────────────────────────────────────────────
    /** Cuánto se espera a que un listener con la conexión caída vuelva antes de darlo por perdido. */
    private static final long RECONNECT_GRACE_MS = 45_000;
    /** name -> token de la ventana de gracia activa; se invalida (se quita) si reconecta a tiempo. */
    private final Map<String, Object> reconnectTokens = new ConcurrentHashMap<>();

    // ── Estado en vivo de reproducción del Master (para resincronizar a quien se una/reconecte) ──
    private volatile String  liveVideoId;
    private volatile boolean liveHidden;
    private volatile boolean livePlaying;
    private volatile long    livePositionMs;
    private volatile long    livePositionAtMs;
    private volatile double  liveVolume = 1.0;
    private volatile boolean liveLooping;
    private volatile boolean liveLoopActive;
    private volatile double  liveLoopInPct  = 0.0;
    private volatile double  liveLoopOutPct = 100.0;

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
        liveVideoId = videoId; liveHidden = hidden;
        livePlaying = false; livePositionMs = 0; livePositionAtMs = System.currentTimeMillis();
        liveVolume = 1.0; liveLooping = false;
        liveLoopActive = false; liveLoopInPct = 0.0; liveLoopOutPct = 100.0;
        JsonObject m = new JsonObject();
        m.addProperty("type", "open");
        m.addProperty("videoId", videoId);
        m.addProperty("hidden", hidden);
        broadcast(gson.toJson(m));
    }

    void broadcastPlay(String videoId, long positionMs) {
        if (videoId.equals(liveVideoId)) { livePlaying = true; livePositionMs = positionMs; livePositionAtMs = System.currentTimeMillis(); }
        JsonObject m = new JsonObject();
        m.addProperty("type", "play");
        m.addProperty("videoId", videoId);
        m.addProperty("positionMs", positionMs);
        broadcast(gson.toJson(m));
    }

    void broadcastPause(String videoId, long positionMs) {
        if (videoId.equals(liveVideoId)) { livePlaying = false; livePositionMs = positionMs; livePositionAtMs = System.currentTimeMillis(); }
        JsonObject m = new JsonObject();
        m.addProperty("type", "pause");
        m.addProperty("videoId", videoId);
        m.addProperty("positionMs", positionMs);
        broadcast(gson.toJson(m));
    }

    void broadcastSeek(String videoId, long positionMs) {
        if (videoId.equals(liveVideoId)) { livePositionMs = positionMs; livePositionAtMs = System.currentTimeMillis(); }
        JsonObject m = new JsonObject();
        m.addProperty("type", "seek");
        m.addProperty("videoId", videoId);
        m.addProperty("positionMs", positionMs);
        broadcast(gson.toJson(m));
    }

    void broadcastVolume(String videoId, double volume) {
        if (videoId.equals(liveVideoId)) liveVolume = volume;
        JsonObject m = new JsonObject();
        m.addProperty("type", "volume");
        m.addProperty("videoId", videoId);
        m.addProperty("volume", volume);
        broadcast(gson.toJson(m));
    }

    void broadcastLoop(String videoId, boolean looping) {
        if (videoId.equals(liveVideoId)) liveLooping = looping;
        JsonObject m = new JsonObject();
        m.addProperty("type", "loop");
        m.addProperty("videoId", videoId);
        m.addProperty("looping", looping);
        broadcast(gson.toJson(m));
    }

    /** Marcadores de loop A/B (en % de la duración) — se replican en vivo y se cachean para resync. */
    void broadcastLoopMarkers(String videoId, double inPct, double outPct, boolean active) {
        if (videoId.equals(liveVideoId)) { liveLoopInPct = inPct; liveLoopOutPct = outPct; liveLoopActive = active; }
        JsonObject m = new JsonObject();
        m.addProperty("type", "loopMarkers");
        m.addProperty("videoId", videoId);
        m.addProperty("inPct", inPct);
        m.addProperty("outPct", outPct);
        m.addProperty("active", active);
        broadcast(gson.toJson(m));
    }

    void broadcastCloseTrack(String videoId) {
        if (videoId.equals(liveVideoId)) { liveVideoId = null; livePlaying = false; }
        JsonObject m = new JsonObject();
        m.addProperty("type", "closeTrack");
        m.addProperty("videoId", videoId);
        broadcast(gson.toJson(m));
    }

    void broadcastMasterChat(String text) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "chat");
        m.addProperty("name", "Master");
        m.addProperty("emoji", "bx-broadcast");
        m.addProperty("color", "#54a0ff");
        m.addProperty("text", text);
        broadcast(gson.toJson(m));
    }

    void broadcastRoomClosed() {
        JsonObject m = new JsonObject();
        m.addProperty("type", "roomClosed");
        broadcast(gson.toJson(m));
    }

    void kickClient(String name) {
        clients.stream().filter(c -> name.equals(c.name)).findFirst().ifPresent(c -> {
            c.skipReconnectGrace = true; // un expulsado no debe reaparecer como "reconectando"
            JsonObject m = new JsonObject();
            m.addProperty("type", "kick");
            c.send(gson.toJson(m));
            c.close();
        });
    }

    void banClient(String name) {
        bannedNames.add(name);
        clients.stream().filter(c -> name.equals(c.name)).findFirst().ifPresent(c -> {
            c.skipReconnectGrace = true;
            JsonObject m = new JsonObject();
            m.addProperty("type", "ban");
            c.send(gson.toJson(m));
            c.close();
        });
    }

    void broadcastRemoveTrack(String videoId) {
        sharedTracks.removeIf(t -> t.videoId().equals(videoId));
        JsonObject m = new JsonObject();
        m.addProperty("type", "removeTrack");
        m.addProperty("videoId", videoId);
        broadcast(gson.toJson(m));
    }

    void broadcastRedownload(String videoId) {
        JsonObject del = new JsonObject();
        del.addProperty("type", "redownload");
        del.addProperty("videoId", videoId);
        broadcast(gson.toJson(del));
        // Re-send the track message so listeners start a fresh download
        sharedTracks.stream().filter(t -> t.videoId().equals(videoId)).findFirst().ifPresent(t -> {
            JsonObject tm = new JsonObject();
            tm.addProperty("type", "track");
            tm.addProperty("videoId", t.videoId());
            tm.addProperty("title", t.title());
            tm.addProperty("thumbnailUrl", t.thumbnailUrl());
            tm.addProperty("hidden", t.hidden());
            broadcast(gson.toJson(tm));
        });
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    int getPort() { return serverSocket.getLocalPort(); }

    /** Snapshot inmutable del estado de todos los listeners en este momento. */
    List<ListenerState> getStates() { return new ArrayList<>(states.values()); }

    void stop() {
        running = false;
        sharedTracks.clear();
        bannedNames.clear();
        reconnectTokens.clear();
        clients.forEach(c -> c.skipReconnectGrace = true); // la sala se cierra entera, nadie debe "reconectar"
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
        JsonArray arr = new JsonArray();
        for (ListenerState s : snapshot) {
            // Los listeners que aún no han elegido avatar/color no se incluyen en la
            // lista que ven los demás listeners — solo el Master los ve (por nombre,
            // vía onListenerUpdate/onListenerUpdate con emoji/color null).
            if (s.emoji() == null || s.color() == null) continue;
            JsonObject member = new JsonObject();
            member.addProperty("name", s.name());
            member.addProperty("emoji", s.emoji());
            member.addProperty("color", s.color());
            arr.add(member);
        }
        m.add("members", arr);
        broadcast(gson.toJson(m));
    }

    /** Construye el mensaje "taken" con los avatares/colores ya elegidos por listeners confirmados. */
    private JsonObject buildTakenMessage() {
        Set<String> emojis = new LinkedHashSet<>();
        Set<String> colors = new LinkedHashSet<>();
        synchronized (states) {
            for (ListenerState s : states.values()) {
                if (s.emoji() != null) emojis.add(s.emoji());
                if (s.color() != null) colors.add(s.color());
            }
        }
        JsonObject m = new JsonObject();
        m.addProperty("type", "taken");
        JsonArray emojiArr = new JsonArray(); emojis.forEach(emojiArr::add);
        JsonArray colorArr = new JsonArray(); colors.forEach(colorArr::add);
        m.add("emojis", emojiArr);
        m.add("colors", colorArr);
        return m;
    }

    /** Notifica a todos los clientes qué avatares/colores están ocupados — para que los que
     *  aún están eligiendo apariencia vean tachado en tiempo real lo que otro acaba de confirmar. */
    private void broadcastTaken() { broadcast(gson.toJson(buildTakenMessage())); }

    /**
     * Snapshot del estado de reproducción actual del Master (canción abierta, posición estimada
     * ajustando el tiempo transcurrido si está sonando, volumen, loop y marcadores A/B). Se envía
     * a cada listener justo tras el "hello" — tanto en una unión nueva como en una reconexión —
     * para que pueda ponerse exactamente en el mismo punto que el resto de la sala.
     */
    private JsonObject buildSyncMessage() {
        JsonObject m = new JsonObject();
        m.addProperty("type", "sync");
        String vid = liveVideoId;
        if (vid == null) {
            m.add("videoId", com.google.gson.JsonNull.INSTANCE);
            return m;
        }
        long pos = livePositionMs;
        if (livePlaying) pos += System.currentTimeMillis() - livePositionAtMs;
        m.addProperty("videoId", vid);
        m.addProperty("hidden", liveHidden);
        m.addProperty("playing", livePlaying);
        m.addProperty("positionMs", pos);
        m.addProperty("volume", liveVolume);
        m.addProperty("looping", liveLooping);
        m.addProperty("loopActive", liveLoopActive);
        m.addProperty("loopInPct", liveLoopInPct);
        m.addProperty("loopOutPct", liveLoopOutPct);
        return m;
    }

    private void onMessage(ClientHandler handler, String json) {
        try {
            JsonObject msg = gson.fromJson(json, JsonObject.class);
            String type = msg.get("type").getAsString();

            if ("hello".equals(type)) {
                if (msg.has("name")) handler.name = msg.get("name").getAsString();

                if (handler.name != null && bannedNames.contains(handler.name)) {
                    reject(handler, "Has sido baneado de esta sala");
                    return;
                }
                // El avatar/color se eligen en un paso posterior (chooseAppearance),
                // una vez el listener ya está dentro de la sala — no se validan aquí.

                // ¿Es una reconexión dentro de la ventana de gracia? Si es así, su entrada en
                // "states" (con su avatar/color) se conservó intacta — se restauran en el nuevo
                // handler para que no tenga que volver a elegir apariencia.
                if (handler.name != null && reconnectTokens.remove(handler.name) != null) {
                    ListenerState prev = states.get(handler.name);
                    if (prev != null) { handler.emoji = prev.emoji(); handler.color = prev.color(); }
                    String rname = handler.name;
                    Platform.runLater(() -> callbacks.onListenerReconnected(rname));
                }
            }

            if ("leave".equals(type)) {
                handler.skipReconnectGrace = true; // salida voluntaria — no dar ventana de gracia
                return;
            }

            String name = handler.name != null ? handler.name : handler.socket.getInetAddress().getHostAddress();
            // Fallback solo para mostrar chat/reacciones si llegaran sin apariencia elegida
            // (no debería ocurrir: el cliente bloquea esas acciones hasta confirmar apariencia).
            String displayEmoji = handler.emoji != null ? handler.emoji : "bxs-music";

            if ("chat".equals(type)) {
                String text  = msg.has("text") ? msg.get("text").getAsString() : "";
                String svid  = msg.has("songRefVideoId") ? msg.get("songRefVideoId").getAsString() : null;
                String stitle = msg.has("songRefTitle")  ? msg.get("songRefTitle").getAsString()  : null;
                String senderColor = handler.color;
                JsonObject out = new JsonObject();
                out.addProperty("type", "chat"); out.addProperty("name", name); out.addProperty("emoji", displayEmoji);
                out.addProperty("color", senderColor); out.addProperty("text", text);
                if (svid != null) { out.addProperty("songRefVideoId", svid); out.addProperty("songRefTitle", stitle != null ? stitle : ""); }
                broadcast(gson.toJson(out));
                Platform.runLater(() -> callbacks.onChatMessage(name, displayEmoji, senderColor, text, svid, stitle));
                return;
            }

            if ("reaction".equals(type)) {
                String reaction    = msg.get("reaction").getAsString();
                String senderColor = handler.color;
                String svid        = msg.has("songRefVideoId") ? msg.get("songRefVideoId").getAsString() : null;
                String stitle      = msg.has("songRefTitle")   ? msg.get("songRefTitle").getAsString()   : null;
                JsonObject out = new JsonObject();
                out.addProperty("type", "reaction"); out.addProperty("name", name);
                out.addProperty("emoji", displayEmoji); out.addProperty("color", senderColor); out.addProperty("reaction", reaction);
                if (svid != null) { out.addProperty("songRefVideoId", svid); out.addProperty("songRefTitle", stitle != null ? stitle : ""); }
                broadcast(gson.toJson(out));
                Platform.runLater(() -> callbacks.onReaction(name, displayEmoji, senderColor, reaction, svid, stitle));
                return;
            }

            if ("chooseAppearance".equals(type)) {
                if (handler.name == null || !msg.has("emoji") || !msg.has("color")) return;
                String wantEmoji = msg.get("emoji").getAsString();
                String wantColor = msg.get("color").getAsString();

                boolean conflict;
                synchronized (states) {
                    conflict = states.values().stream().anyMatch(s ->
                        !s.name().equals(handler.name) &&
                        (wantEmoji.equals(s.emoji()) || wantColor.equals(s.color())));
                    if (!conflict) {
                        handler.emoji = wantEmoji;
                        handler.color = wantColor;
                        ListenerState prev = states.get(handler.name);
                        ListenerStatus st = prev != null ? prev.status() : ListenerStatus.CONNECTING;
                        String nt = prev != null ? prev.note() : "";
                        states.put(handler.name, new ListenerState(handler.name, st, nt, wantEmoji, wantColor));
                    }
                }

                if (conflict) {
                    JsonObject rej = new JsonObject();
                    rej.addProperty("type", "appearanceRejected");
                    rej.addProperty("reason", "Ese avatar o color ya no está disponible — elige otro");
                    handler.send(gson.toJson(rej));
                    // Puede que su copia de "taken" estuviera desactualizada; se la reenviamos.
                    handler.send(gson.toJson(buildTakenMessage()));
                } else {
                    JsonObject acc = new JsonObject();
                    acc.addProperty("type", "appearanceAccepted");
                    handler.send(gson.toJson(acc));
                    String finalName = handler.name;
                    Platform.runLater(() -> callbacks.onListenerUpdate(finalName, ListenerStatus.CONNECTING, "", wantEmoji, wantColor));
                    broadcastMembers();
                    broadcastTaken();
                }
                return;
            }

            if ("progress".equals(type)) {
                if (handler.name == null || !msg.has("videoId") || !msg.has("percent")) return;
                String pvid = msg.get("videoId").getAsString();
                int percent = msg.get("percent").getAsInt();
                String pname = handler.name;
                Platform.runLater(() -> callbacks.onListenerVideoProgress(pname, pvid, percent));
                return;
            }

            if ("ping".equals(type)) {
                // Eco inmediato — el cliente mide su propio RTT contra la respuesta.
                if (msg.has("ts")) {
                    JsonObject pong = new JsonObject();
                    pong.addProperty("type", "pong");
                    pong.addProperty("ts", msg.get("ts").getAsLong());
                    handler.send(gson.toJson(pong));
                }
                return;
            }

            if ("pingReport".equals(type)) {
                if (handler.name != null && msg.has("ms")) {
                    int ms = msg.get("ms").getAsInt();
                    String pname = handler.name;
                    Platform.runLater(() -> callbacks.onListenerPing(pname, ms));
                }
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
            // handler.emoji/color (no el fallback) para que "no elegido aún" siga siendo null.
            states.put(name, new ListenerState(name, status, note, handler.emoji, handler.color));
            Platform.runLater(() -> callbacks.onListenerUpdate(name, status, note, handler.emoji, handler.color));
            if ("hello".equals(type)) {
                JsonObject welcome = new JsonObject();
                welcome.addProperty("type", "welcome");
                handler.send(gson.toJson(welcome));
                handler.send(gson.toJson(buildTakenMessage()));
                handler.send(gson.toJson(buildSyncMessage()));
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
        if (name == null) return;

        if (handler.skipReconnectGrace) {
            finalizeDisconnect(name);
            return;
        }

        // Caída inesperada: se le da una ventana de gracia para reconectar antes de darlo por
        // perdido de verdad. Mientras tanto su entrada en "states" (avatar/color incluidos)
        // se conserva intacta, así que nadie más puede robarle su apariencia.
        Object token = new Object();
        reconnectTokens.put(name, token);
        Platform.runLater(() -> callbacks.onListenerReconnecting(name));
        Thread timeout = new Thread(() -> {
            try { Thread.sleep(RECONNECT_GRACE_MS); } catch (InterruptedException ignored) { return; }
            if (reconnectTokens.remove(name, token)) finalizeDisconnect(name);
        }, "party-reconnect-timeout-" + name);
        timeout.setDaemon(true);
        timeout.start();
    }

    private void finalizeDisconnect(String name) {
        states.remove(name);
        broadcastMembers();
        broadcastTaken(); // libera su avatar/color para quien siga eligiendo apariencia
        Platform.runLater(() -> callbacks.onListenerDisconnect(name));
    }

    private class ClientHandler {
        final Socket socket;
        String name;
        String emoji; // null hasta que el listener confirma su apariencia (chooseAppearance)
        String color; // null hasta que el listener confirma su apariencia (chooseAppearance)
        /** true si la desconexión es voluntaria (leave/kick/ban) o la sala se está cerrando entera. */
        volatile boolean skipReconnectGrace = false;
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
