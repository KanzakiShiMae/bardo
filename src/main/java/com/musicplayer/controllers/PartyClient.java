package com.musicplayer.controllers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.musicplayer.models.Song;
import com.musicplayer.services.DownloadService;
import javafx.application.Platform;

import java.io.*;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Cliente TCP que conecta a un {@link PartyServer} remoto.
 * Descarga automáticamente la canción que el Master anuncia y reporta su estado.
 * Todos los callbacks se invocan en el hilo de JavaFX.
 */
class PartyClient {

    record MemberInfo(String name, String emoji, String color) {}

    /** Snapshot de reproducción del Master, para ponerse al día tras unirse o reconectar. */
    record SyncState(String videoId, boolean hidden, boolean playing, long positionMs, double volume,
                      boolean looping, boolean loopActive, double loopInPct, double loopOutPct) {}

    /** Snapshot del propio estado de reproducción de este listener, para que el Master pueda
     *  mostrarlo en vivo (ver {@link #setStatusSupplier}). */
    record PlaybackStatus(String videoId, long positionMs, boolean playing, double volume) {}

    interface Callbacks {
        void onConnected();
        /** La conexión se perdió inesperadamente; el cliente reintentará solo, sin salir de la sala. */
        void onConnectionLost(String reason);
        /** Reconectado y apariencia restaurada tras una pérdida de conexión — listo para resincronizar. */
        void onReconnected();
        void onTakenUpdate(java.util.Set<String> emojis, java.util.Set<String> colors);
        void onAppearanceAccepted();
        void onAppearanceRejected(String reason);
        void onTrackLoading(String title);
        void onTrackAnnounced(String videoId, String title, boolean hidden);
        void onTrackReady(Song song, Path localPath);
        void onOpen(String videoId, boolean hidden);
        void onPlay(String videoId, long positionMs);
        void onPause(String videoId, long positionMs);
        void onSeek(String videoId, long positionMs);
        void onVolume(String videoId, double volume);
        void onLoop(String videoId, boolean looping);
        void onLoopMarkers(String videoId, double inPct, double outPct, boolean active);
        void onSync(SyncState sync);
        /** Una canción concreta falló al descargarse (p.ej. vídeo no disponible) — no implica salir de la sala. */
        void onTrackError(String videoId, String message);
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
        /** Ping propio (ms de ida y vuelta contra el Master), medido cada pocos segundos. */
        void onPing(int ms);
        /** Fallo definitivo — no habrá más reintentos (falló la primera conexión, o motivo no recuperable). */
        void onDisconnected(String reason);
    }

    private final String host;
    private final int port;
    private final String listenerName;
    private final DownloadService downloadService;
    private final Callbacks callbacks;
    private final Gson gson = new Gson();

    /** Avatar/color elegidos vía {@link #chooseAppearance}; null hasta entonces. */
    private String emoji;
    private String color;

    private Socket socket;
    private PrintWriter out;
    private volatile boolean running;

    /** true una vez la primera conexión se completó con éxito al menos una vez. */
    private volatile boolean hasEverConnected = false;
    /** true si el usuario pidió desconectar voluntariamente (leave / cierre de la app) — no reintentar. */
    private volatile boolean stopRequested = false;
    /** true si el servidor nos dijo explícitamente que no volvamos (reject/kick/ban/roomClosed). */
    private volatile boolean fatalStop = false;
    /** true mientras se procesan los mensajes de un intento de reconexión (para no reabrir la UI de unión). */
    private volatile boolean isReconnectAttempt = false;

    /** Proveedor del estado de reproducción actual de este listener (posición/pausa/volumen),
     *  consultado en cada ciclo de ping — ver {@link #setStatusSupplier} y {@link #startPingLoop}. */
    private volatile java.util.function.Supplier<PlaybackStatus> statusSupplier;

    private static final long RECONNECT_DELAY_MS = 3_000;
    private static final long PING_INTERVAL_MS   = 4_000;

    PartyClient(String host, int port, String listenerName,
                DownloadService downloadService, Callbacks callbacks) {
        this.host = host;
        this.port = port;
        this.listenerName = listenerName;
        this.downloadService = downloadService;
        this.callbacks = callbacks;
    }

    void connect() {
        Thread t = new Thread(this::connectionLoop, "party-listener");
        t.setDaemon(true);
        t.start();
        startPingLoop();
    }

    /** Fija de dónde sacar el estado de reproducción propio (posición/pausa/volumen) que se
     *  reporta al Master en cada ciclo de ping — ver {@link #startPingLoop}. {@code null} (o que
     *  el propio supplier devuelva {@code null}, p.ej. sin canción abierta todavía) hace que ese
     *  ciclo no reporte nada, sin dejar de enviar el "ping" en sí. */
    void setStatusSupplier(java.util.function.Supplier<PlaybackStatus> supplier) {
        this.statusSupplier = supplier;
    }

    /** Envía un "ping" cada {@link #PING_INTERVAL_MS} mientras haya conexión activa; sigue vivo
     *  a través de reconexiones (se limita a no enviar nada mientras {@code running} sea falso).
     *  Reutiliza este mismo ciclo para reportar también, al Master, el estado de reproducción
     *  propio de este listener — así el Master puede mostrar en vivo (en el panel de su
     *  reproductor) la posición/pausa/volumen de cada oyente sin un temporizador aparte. */
    private void startPingLoop() {
        Thread t = new Thread(() -> {
            while (!stopRequested) {
                try { Thread.sleep(PING_INTERVAL_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                if (!running) continue;
                send("ping", j -> j.addProperty("ts", System.currentTimeMillis()));
                java.util.function.Supplier<PlaybackStatus> sup = statusSupplier;
                PlaybackStatus st = sup != null ? sup.get() : null;
                if (st != null) {
                    send("status", j -> {
                        j.addProperty("videoId", st.videoId());
                        j.addProperty("positionMs", st.positionMs());
                        j.addProperty("playing", st.playing());
                        j.addProperty("volume", st.volume());
                    });
                }
            }
        }, "party-ping");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Conecta y, si la conexión se cae de forma inesperada después de haber funcionado, reintenta
     * solo (sin sacar al usuario de la sala) hasta conseguirlo o hasta que {@link #disconnect()} lo
     * pare. Una reconexión con avatar/color ya elegidos los vuelve a reclamar automáticamente en
     * vez de mostrar otra vez la pantalla de "elige tu apariencia".
     */
    private void connectionLoop() {
        while (!stopRequested) {
            boolean reclaimingAppearance = hasEverConnected && emoji != null && color != null;
            isReconnectAttempt = reclaimingAppearance;

            try {
                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(host, port), 10_000);
                running = true;
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8), true);

                send("hello", j -> j.addProperty("name", listenerName));
                if (reclaimingAppearance) {
                    String e = emoji, c = color;
                    send("chooseAppearance", j -> { j.addProperty("emoji", e); j.addProperty("color", c); });
                }
                hasEverConnected = true;

                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                String line;
                while (running && (line = in.readLine()) != null) handleMessage(line);

            } catch (IOException ignored) {
                // se decide qué hacer más abajo, fuera del try — puede ser un intento de reconexión
            } finally {
                close();
            }

            if (stopRequested || fatalStop) return;

            if (!hasEverConnected) {
                // Nunca llegó a conectar ni una vez (código/host inválido, servidor caído) — no reintentar.
                String reason = "No se pudo conectar al servidor";
                Platform.runLater(() -> callbacks.onDisconnected(reason));
                return;
            }

            // Hubo una sesión funcionando y se cayó inesperadamente: se avisa y se reintenta solo.
            running = false;
            Platform.runLater(() -> callbacks.onConnectionLost("Conexión perdida — reintentando…"));
            try { Thread.sleep(RECONNECT_DELAY_MS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
    }

    void disconnect() {
        stopRequested = true;
        send("leave", null); // best-effort: si no hay conexión, send() no hace nada
        running = false;
        close();
    }

    /** Confirma avatar/color elegidos tras conectar. El servidor responde con
     *  appearanceAccepted (éxito) o appearanceRejected (otro listener lo tomó primero). */
    void chooseAppearance(String emoji, String color) {
        this.emoji = emoji;
        this.color = color;
        send("chooseAppearance", j -> { j.addProperty("emoji", emoji); j.addProperty("color", color); });
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
                case "welcome" -> { if (!isReconnectAttempt) Platform.runLater(callbacks::onConnected); }
                case "reject"  -> { running = false; fatalStop = true; String r = msg.get("reason").getAsString(); Platform.runLater(() -> callbacks.onRejected(r)); }
                case "taken" -> {
                    java.util.Set<String> emojis = new java.util.LinkedHashSet<>();
                    for (com.google.gson.JsonElement el : msg.getAsJsonArray("emojis")) emojis.add(el.getAsString());
                    java.util.Set<String> colors = new java.util.LinkedHashSet<>();
                    for (com.google.gson.JsonElement el : msg.getAsJsonArray("colors")) colors.add(el.getAsString());
                    Platform.runLater(() -> callbacks.onTakenUpdate(emojis, colors));
                }
                case "appearanceAccepted" -> {
                    if (isReconnectAttempt) { isReconnectAttempt = false; Platform.runLater(callbacks::onReconnected); }
                    else Platform.runLater(callbacks::onAppearanceAccepted);
                }
                case "appearanceRejected" -> {
                    String r = msg.get("reason").getAsString();
                    // Si esto llega durante un intento de RECONEXIÓN (reclamando el avatar/color
                    // de antes), significa que la ventana de gracia del servidor expiró mientras
                    // estábamos sin conexión y otro listener se quedó con esa apariencia — antes
                    // esto dejaba al cliente colgado en "reconectando" para siempre, porque solo
                    // "appearanceAccepted" limpiaba isReconnectAttempt/llamaba a onReconnected().
                    // La conexión TCP en sí está bien — se avisa igualmente como reconectado para
                    // soltar esa pantalla, y se delega en onAppearanceRejected para que la UI haga
                    // que el usuario elija una apariencia nueva.
                    if (isReconnectAttempt) { isReconnectAttempt = false; Platform.runLater(callbacks::onReconnected); }
                    Platform.runLater(() -> callbacks.onAppearanceRejected(r));
                }
                case "track"  -> handleTrack(msg);
                case "open"   -> { String vid = msg.get("videoId").getAsString(); boolean hid = msg.has("hidden") && msg.get("hidden").getAsBoolean(); Platform.runLater(() -> callbacks.onOpen(vid, hid)); }
                case "play"   -> { String vid = msg.get("videoId").getAsString(); long p = msg.get("positionMs").getAsLong(); Platform.runLater(() -> callbacks.onPlay(vid, p)); }
                case "pause"  -> { String vid = msg.get("videoId").getAsString(); long p = msg.get("positionMs").getAsLong(); Platform.runLater(() -> callbacks.onPause(vid, p)); }
                case "seek"   -> { String vid = msg.get("videoId").getAsString(); long p = msg.get("positionMs").getAsLong(); Platform.runLater(() -> callbacks.onSeek(vid, p)); }
                case "volume" -> { String vid = msg.get("videoId").getAsString(); double v = msg.get("volume").getAsDouble(); Platform.runLater(() -> callbacks.onVolume(vid, v)); }
                case "loop"       -> { String vid = msg.get("videoId").getAsString(); boolean l = msg.get("looping").getAsBoolean(); Platform.runLater(() -> callbacks.onLoop(vid, l)); }
                case "loopMarkers" -> {
                    String vid = msg.get("videoId").getAsString();
                    double inPct = msg.get("inPct").getAsDouble();
                    double outPct = msg.get("outPct").getAsDouble();
                    boolean active = msg.get("active").getAsBoolean();
                    Platform.runLater(() -> callbacks.onLoopMarkers(vid, inPct, outPct, active));
                }
                case "sync" -> {
                    com.google.gson.JsonElement vidEl = msg.get("videoId");
                    if (vidEl != null && !vidEl.isJsonNull()) {
                        String vid = vidEl.getAsString();
                        boolean hidden = msg.has("hidden") && msg.get("hidden").getAsBoolean();
                        boolean playing = msg.has("playing") && msg.get("playing").getAsBoolean();
                        long pos = msg.has("positionMs") ? msg.get("positionMs").getAsLong() : 0L;
                        double vol = msg.has("volume") ? msg.get("volume").getAsDouble() : 1.0;
                        boolean looping = msg.has("looping") && msg.get("looping").getAsBoolean();
                        boolean loopActive = msg.has("loopActive") && msg.get("loopActive").getAsBoolean();
                        double loopIn = msg.has("loopInPct") ? msg.get("loopInPct").getAsDouble() : 0.0;
                        double loopOut = msg.has("loopOutPct") ? msg.get("loopOutPct").getAsDouble() : 100.0;
                        SyncState sync = new SyncState(vid, hidden, playing, pos, vol, looping, loopActive, loopIn, loopOut);
                        Platform.runLater(() -> callbacks.onSync(sync));
                    }
                }
                case "closeTrack"  -> { String vid = msg.get("videoId").getAsString(); Platform.runLater(() -> callbacks.onCloseTrack(vid)); }
                case "roomClosed"  -> { running = false; fatalStop = true; Platform.runLater(() -> callbacks.onRoomClosed()); }
                case "kick"        -> { running = false; fatalStop = true; Platform.runLater(() -> callbacks.onKicked()); }
                case "ban"         -> { running = false; fatalStop = true; Platform.runLater(() -> callbacks.onBanned()); }
                case "redownload"  -> {
                    String vid = msg.get("videoId").getAsString();
                    // El Master pidió explícitamente volver a descargar esta canción — hay que
                    // olvidar que ya se pidió antes para que el "track" que sigue a este mensaje
                    // (ver PartyServer.broadcastRedownload) no se descarte por duplicado.
                    requestedDownloads.remove(vid);
                    Platform.runLater(() -> callbacks.onRedownload(vid));
                }
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
                case "pong" -> {
                    long ts = msg.get("ts").getAsLong();
                    int rtt = (int) Math.max(0, System.currentTimeMillis() - ts);
                    Platform.runLater(() -> callbacks.onPing(rtt));
                    send("pingReport", j -> j.addProperty("ms", rtt));
                }
            }
        } catch (Exception ignored) {}
    }

    /** videoId de cualquier canción para la que ya se ha lanzado (o terminado) una descarga en
     *  esta sesión del cliente — ver el comentario en {@link #handleTrack}. */
    private final java.util.Set<String> requestedDownloads = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void handleTrack(JsonObject msg) {
        String videoId      = msg.get("videoId").getAsString();
        String title        = msg.get("title").getAsString();
        String thumbnailUrl = msg.has("thumbnailUrl") ? msg.get("thumbnailUrl").getAsString() : "";
        boolean hidden      = msg.has("hidden") && msg.get("hidden").getAsBoolean();
        String displayTitle = hidden ? "???" : title;
        Platform.runLater(() -> callbacks.onTrackAnnounced(videoId, title, hidden));

        // El servidor reenvía TODO el historial de "sharedTracks" tras cada "hello" — tanto al
        // unirse como en cada reconexión (ver PartyServer.onMessage) — para que quien se una
        // tarde se ponga al día. Sin esta comprobación, cada reconexión relanzaba yt-dlp para
        // CADA canción ya compartida en la sala, aunque ya estuviera descargada o incluso
        // todavía descargándose de un intento anterior — con salas largas (muchas canciones) y
        // listeners con conexiones inestables (varias reconexiones seguidas), esto podía lanzar
        // una ráfaga de peticiones repetidas a YouTube en poco tiempo, suficiente para disparar
        // el bloqueo anti-bot de YouTube para esa IP — tras lo cual CUALQUIER descarga posterior
        // fallaba ("siempre en rojo"), no solo las repetidas. Una vez pedida una descarga para un
        // videoId en esta sesión del cliente, no se vuelve a pedir — si ya terminó con éxito el
        // archivo sigue en disco (downloadAudio lo detecta solo), y si seguía en curso, ese
        // intento original sigue su camino y acabará notificando el resultado igual.
        if (!requestedDownloads.add(videoId)) return;

        Platform.runLater(() -> callbacks.onTrackLoading(displayTitle));
        send("downloading", j -> j.addProperty("videoId", videoId));
        attemptDownload(new Song(videoId, title, "", "—", thumbnailUrl, ""), 1);
    }

    /** Número máximo de intentos (el primero + reintentos) antes de dar una descarga por fallida
     *  definitivamente. */
    private static final int DOWNLOAD_MAX_ATTEMPTS = 3;
    /** Espera antes del intento N — crece con cada reintento (4s, luego 9s) para no insistir
     *  inmediatamente si YouTube está aplicando un bloqueo anti-bot temporal por IP. */
    private static final long DOWNLOAD_RETRY_BASE_MS = 4_000;

    /** Descarga {@code song}, reintentando con espera creciente si falla — un fallo puntual
     *  (bloqueo anti-bot temporal de YouTube, corte de red breve…) ya no deja la canción en rojo
     *  para siempre a la primera, que era lo que pasaba antes de este método. */
    private void attemptDownload(Song song, int attempt) {
        String videoId = song.getVideoId();
        // Reporta el % de descarga al Master (para que vea el progreso de sus listeners),
        // sin exponerlo en la UI de este propio listener. Se limita el envío a cambios de
        // al menos 2 puntos para no saturar el socket con las decenas de ticks de yt-dlp.
        int[] lastSentPercent = {-1};
        downloadService.downloadAudio(song, "party", pct -> {
            if (pct - lastSentPercent[0] >= 2) {
                lastSentPercent[0] = pct;
                send("progress", j -> { j.addProperty("videoId", videoId); j.addProperty("percent", pct); });
            }
        })
            .thenAccept(path -> {
                song.setLocalFilePath(path.toString());
                send("ready", j -> j.addProperty("videoId", videoId));
                Platform.runLater(() -> callbacks.onTrackReady(song, path));
            })
            .exceptionally(ex -> {
                if (attempt < DOWNLOAD_MAX_ATTEMPTS) {
                    long delay = DOWNLOAD_RETRY_BASE_MS * attempt;
                    CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .execute(() -> attemptDownload(song, attempt + 1));
                    return null;
                }
                // Fallo al descargar ESTA canción tras agotar los reintentos (vídeo no
                // disponible, error persistente…) — se notifica al Master y se muestra al
                // propio listener, pero NO se sale de la sala: el resto de canciones y la
                // conexión siguen funcionando con normalidad.
                send("error", j -> { j.addProperty("message", ex.getMessage()); j.addProperty("videoId", videoId); });
                Platform.runLater(() -> callbacks.onTrackError(videoId, ex.getMessage()));
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
