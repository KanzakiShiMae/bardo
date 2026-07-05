package com.musicplayer.controllers;

import com.musicplayer.models.Song;
import com.musicplayer.services.DownloadService;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.net.*;
import java.util.*;
import java.util.prefs.Preferences;

class PartyPanelBuilder {

    private static final String BORE_ZIP_URL =
        "https://github.com/ekzhang/bore/releases/download/v0.5.0/bore-v0.5.0-x86_64-pc-windows-msvc.zip";

    private static final String[] COLORS = {
        "#ff6b6b", "#ff9f43", "#ffd32a", "#2ecc71",
        "#1abc9c", "#54a0ff", "#a29bfe", "#fd79a8",
        "#b2bec3", "#ffffff"
    };

    private static final String[] EMOJIS = {
        "🐱","🐶","🐺","🦊","🐻","🐼","🐨","🐯","🦁","🐸",
        "🐵","🦋","🌸","⭐","🎵","🎮","🎯","🚀","🌈","❤️",
        "😎","🤖","👾","🌙","🎸","🎤","🎧","🍕","🌮","🎃"
    };

    private final MainController mc;
    private final DownloadService downloadService;
    private final VBox root;

    // ── Estado Master ─────────────────────────────────────────────────────────
    private final SimpleBooleanProperty masterActive = new SimpleBooleanProperty(false);
    ReadOnlyBooleanProperty isMasterProperty() { return masterActive; }

    void addSongToParty(Song song) {
        if (partyServer == null || song == null) return;
        String videoId = song.getVideoId();
        if (sharedSongs.stream().anyMatch(s -> s.videoId.equals(videoId))) return;
        SharedSong shared = new SharedSong(song);
        sharedSongs.add(shared);
        songStatus.put(videoId, new LinkedHashMap<>());
        // Broadcast immediately so listeners start downloading before master hits play
        partyServer.broadcastTrack(shared.videoId, shared.title, shared.thumbnailUrl, shared.hidden);
        refreshSongList();
    }

    private PartyServer partyServer;
    private UPnPHelper  upnpHelper;
    private Process     sshTunnelProcess;
    private Label masterCodeLbl;
    private Label upnpStatusLbl;
    private VBox songListBox;

    private static class SharedSong {
        final Song song;
        final String videoId, title, thumbnailUrl;
        boolean hidden = false;
        SharedSong(Song s) {
            this.song = s;
            videoId      = s.getVideoId();
            title        = s.getTitle();
            thumbnailUrl = s.getThumbnailUrl() != null ? s.getThumbnailUrl() : "";
        }
    }
    private final List<SharedSong>                              sharedSongs  = new ArrayList<>();
    private final Map<String, String>                           listenerEmojis = new LinkedHashMap<>();
    private final Map<String, String>                           listenerColors = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String, PartyServer.ListenerStatus>> songStatus = new LinkedHashMap<>();

    // ── Estado Master (extras) ────────────────────────────────────────────────
    private VBox masterMemberListBox;
    private VBox masterChatBox;
    private ScrollPane masterChatScrollPane;
    private javafx.scene.layout.Pane reactionOverlay;

    // ── Estado Listener ───────────────────────────────────────────────────────
    private PartyClient partyClient;
    private final Map<String, PlayerInstance> partyPlayers = new LinkedHashMap<>();
    private Label listenerTrackLbl;
    private Label listenerSyncLbl;
    private Thread partyCleanupHook;
    private final Map<String, Song> pendingSongs = new LinkedHashMap<>();
    private final Set<String> hiddenListenerTracks = new java.util.HashSet<>();
    // Listener UI extras
    private VBox listenerMemberListBox;
    private VBox listenerChatBox;
    private ScrollPane listenerChatScrollPane;
    private final Map<String, String> knownSongs = new LinkedHashMap<>();
    private String pendingRefVideoId;
    private String pendingRefTitle;

    PartyPanelBuilder(MainController mc, DownloadService downloadService) {
        this.mc = mc;
        this.downloadService = downloadService;
        this.root = new VBox(16);
        root.setPadding(new Insets(24));
        showLanding();
    }

    VBox getPanel() { return root; }

    void navigateToJoin() { showJoinForm(); }

    // ── Landing ───────────────────────────────────────────────────────────────

    private void showLanding() {
        Preferences prefs = Preferences.userNodeForPackage(PartyPanelBuilder.class);

        Label title = new Label("🎧  Party");
        title.getStyleClass().add("section-title");

        Label desc = new Label("Crea una sala y comparte el código con tus amigos para escuchar juntos en tiempo real.");
        desc.setWrapText(true);
        desc.getStyleClass().add("greeting-sub");

        // ── Selector de rol ───────────────────────────────────────────────────
        ToggleGroup roleGroup = new ToggleGroup();
        ToggleButton createToggle = new ToggleButton("📡  Crear sala");
        ToggleButton joinToggle   = new ToggleButton("🔗  Unirse a sala");
        createToggle.setToggleGroup(roleGroup);
        joinToggle.setToggleGroup(roleGroup);
        createToggle.getStyleClass().add("btn-secondary");
        joinToggle.getStyleClass().add("btn-secondary");
        createToggle.setMaxWidth(Double.MAX_VALUE);
        joinToggle.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(createToggle, Priority.ALWAYS);
        HBox.setHgrow(joinToggle, Priority.ALWAYS);
        HBox roleBox = new HBox(8, createToggle, joinToggle);

        // ── Formulario Master (oculto hasta seleccionar "Crear sala") ─────────
        Label nameLabel = new Label("Nombre:");
        nameLabel.getStyleClass().add("greeting-sub");
        TextField nameFld = new TextField(prefs.get("party.master.name", ""));
        nameFld.setPromptText("Nombre de la sala");
        nameFld.getStyleClass().add("detail-search-field");
        HBox.setHgrow(nameFld, Priority.ALWAYS);

        Label portLabel = new Label("Puerto:");
        portLabel.getStyleClass().add("greeting-sub");
        TextField portFld = new TextField(prefs.get("party.master.port", "25565"));
        portFld.setPrefWidth(80);
        portFld.getStyleClass().add("detail-search-field");

        HBox masterForm = new HBox(10, nameLabel, nameFld, portLabel, portFld);
        masterForm.setAlignment(Pos.CENTER_LEFT);
        masterForm.setVisible(false);
        masterForm.setManaged(false);

        Label portHint = new Label("Configura este mismo puerto en el router para acceso externo.");
        portHint.setWrapText(true);
        portHint.getStyleClass().add("greeting-sub");
        portHint.setStyle("-fx-font-size: 11px;");
        portHint.setVisible(false);
        portHint.setManaged(false);

        Label portError = new Label();
        portError.getStyleClass().add("greeting-sub");
        portError.setStyle("-fx-text-fill: #c0392b; -fx-font-size: 11px;");
        portError.setManaged(false);
        portError.setVisible(false);

        Button startBtn = new Button("Iniciar sala");
        startBtn.getStyleClass().add("btn-primary");
        startBtn.setMaxWidth(Double.MAX_VALUE);
        startBtn.setVisible(false);
        startBtn.setManaged(false);
        startBtn.setOnAction(e -> {
            try {
                int port = Integer.parseInt(portFld.getText().trim());
                if (port < 1024 || port > 65535) throw new NumberFormatException();
                portError.setVisible(false);
                portError.setManaged(false);
                String roomName = nameFld.getText().trim();
                prefs.put("party.master.name", roomName);
                prefs.put("party.master.port", portFld.getText().trim());
                startMaster(port, roomName.isEmpty() ? "Sala Party" : roomName);
            } catch (NumberFormatException ex) {
                portError.setText("Puerto inválido (1024–65535)");
                portError.setVisible(true);
                portError.setManaged(true);
            }
        });

        createToggle.selectedProperty().addListener((obs, o, n) -> {
            masterForm.setVisible(n); masterForm.setManaged(n);
            portHint.setVisible(n);   portHint.setManaged(n);
            startBtn.setVisible(n);   startBtn.setManaged(n);
        });

        joinToggle.setOnAction(e -> {
            if (joinToggle.isSelected()) showJoinForm();
        });

        VBox box = new VBox(12, title, desc, roleBox, masterForm, portHint, portError, startBtn);
        box.setMaxWidth(380);
        root.getChildren().setAll(box);
    }

    // ── Master ────────────────────────────────────────────────────────────────

    private void startMaster(int requestedPort, String roomName) {
        sharedSongs.clear();
        listenerEmojis.clear();
        listenerColors.clear();
        songStatus.clear();

        try {
            partyServer = new PartyServer(requestedPort, new PartyServer.Callbacks() {
                @Override public void onListenerUpdate(String name, PartyServer.ListenerStatus status, String note, String emoji, String color) {
                    listenerEmojis.put(name, emoji);
                    listenerColors.put(name, color);
                    updateMasterMemberList();
                }
                @Override public void onListenerDisconnect(String name) {
                    listenerEmojis.remove(name);
                    listenerColors.remove(name);
                    songStatus.values().forEach(m -> m.remove(name));
                    updateMasterMemberList();
                    refreshSongList();
                }
                @Override public void onListenerVideoStatus(String name, String videoId, PartyServer.ListenerStatus status) {
                    LinkedHashMap<String, PartyServer.ListenerStatus> statuses = songStatus.get(videoId);
                    if (statuses == null) return;
                    statuses.put(name, status);
                    refreshSongList();
                }
                @Override public void onChatMessage(String name, String emoji, String color, String text, String songRefVideoId, String songRefTitle) {
                    PartyPanelBuilder.this.addMasterChatMessage(name, emoji, color, text, songRefVideoId, songRefTitle);
                }
                @Override public void onReaction(String name, String emoji, String color, String reaction) {
                    PartyPanelBuilder.this.addMasterReaction(name, emoji, color, reaction);
                    PartyPanelBuilder.this.showReactionAnimation(emoji, reaction);
                }
            });
            partyServer.start();
        } catch (Exception e) {
            showError("No se pudo iniciar el servidor: " + e.getMessage());
            return;
        }

        masterActive.set(true);
        int port = partyServer.getPort();
        showMasterPanel(RoomCode.encode(getLocalIp(), port), roomName);

        Preferences prefs = Preferences.userNodeForPackage(PartyPanelBuilder.class);
        String tunnelMode = prefs.get("party.tunnel.mode", "upnp");
        switch (tunnelMode) {
            case "serveo" -> {
                Platform.runLater(() -> { if (upnpStatusLbl != null) upnpStatusLbl.setText("🔄 Conectando con serveo.net…"); });
                Thread th = new Thread(() -> runServeoTunnel(port), "party-tunnel");
                th.setDaemon(true); th.start();
            }
            case "bore" -> {
                Platform.runLater(() -> { if (upnpStatusLbl != null) upnpStatusLbl.setText("🔄 Preparando bore.pub…"); });
                Thread th = new Thread(() -> runBoreTunnel(port), "party-tunnel");
                th.setDaemon(true); th.start();
            }
            default -> {
                Thread upnpThread = new Thread(() -> {
                    upnpHelper = new UPnPHelper();
                    String upnpResult = upnpHelper.mapPort(port);
                    boolean upnpOk = upnpResult != null;
                    Platform.runLater(() -> {
                        if (upnpStatusLbl == null) return;
                        if (upnpOk) {
                            String extIp = upnpResult.substring(0, upnpResult.lastIndexOf(':'));
                            masterCodeLbl.setText(RoomCode.encode(extIp, port));
                            upnpStatusLbl.setText("✅ Acceso remoto activo (UPnP)");
                        } else {
                            upnpStatusLbl.setText("⚠ UPnP no disponible — solo red local");
                        }
                    });
                }, "party-upnp");
                upnpThread.setDaemon(true);
                upnpThread.start();
            }
        }
    }

    private void updateMasterMemberList() {
        if (masterMemberListBox == null) return;
        masterMemberListBox.getChildren().clear();
        if (listenerEmojis.isEmpty()) {
            Label ph = new Label("Sin listeners conectados");
            ph.getStyleClass().add("greeting-sub");
            ph.setStyle("-fx-font-size: 11px;");
            masterMemberListBox.getChildren().add(ph);
        } else {
            for (Map.Entry<String, String> entry : listenerEmojis.entrySet()) {
                String memberColor = listenerColors.getOrDefault(entry.getKey(), "#a090b0");
                Label lbl = new Label(entry.getValue() + "  " + entry.getKey() + "  " + entry.getValue());
                lbl.getStyleClass().add("greeting-sub");
                lbl.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 13px; -fx-text-fill: " + memberColor + ";");
                masterMemberListBox.getChildren().add(lbl);
            }
        }
    }

    private void addMasterChatMessage(String name, String emoji, String nameColor, String text, String songRefVideoId, String songRefTitle) {
        if (masterChatBox == null) return;
        VBox bubble = new VBox(2);
        bubble.setPadding(new Insets(3, 8, 3, 8));
        Label header = new Label(emoji + "  " + name);
        header.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + nameColor + ";");
        bubble.getChildren().add(header);
        if (songRefVideoId != null && !songRefVideoId.isEmpty()) {
            Label ref = new Label("🎵  " + songRefTitle);
            ref.setStyle("-fx-background-color: -fx-background; -fx-padding: 2 6; -fx-background-radius: 4; -fx-font-size: 11px;");
            ref.getStyleClass().add("greeting-sub");
            ref.setWrapText(true);
            bubble.getChildren().add(ref);
        }
        if (text != null && !text.isEmpty()) {
            Label textLbl = new Label(text);
            textLbl.setWrapText(true);
            textLbl.getStyleClass().add("greeting-sub");
            bubble.getChildren().add(textLbl);
        }
        masterChatBox.getChildren().add(bubble);
        if (masterChatScrollPane != null)
            Platform.runLater(() -> masterChatScrollPane.setVvalue(1.0));
    }

    private void showReactionAnimation(String listenerEmoji, String reaction) {
        if (reactionOverlay == null) return;
        Label lbl = new Label(listenerEmoji + reaction);
        lbl.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 26px;");
        Platform.runLater(() -> {
            double w = reactionOverlay.getWidth();
            double h = reactionOverlay.getHeight();
            lbl.setLayoutX(Math.max(4, w - 70 + (Math.random() * 40 - 20)));
            lbl.setLayoutY(Math.max(10, h - 70));
            reactionOverlay.getChildren().add(lbl);
            TranslateTransition up = new TranslateTransition(Duration.seconds(2), lbl);
            up.setByY(-110);
            FadeTransition fade = new FadeTransition(Duration.seconds(2), lbl);
            fade.setFromValue(1.0); fade.setToValue(0.0);
            ParallelTransition anim = new ParallelTransition(up, fade);
            anim.setOnFinished(ev -> reactionOverlay.getChildren().remove(lbl));
            anim.play();
        });
    }

    private void addMasterReaction(String name, String emoji, String nameColor, String reaction) {
        if (masterChatBox == null) return;
        VBox bubble = new VBox(2);
        bubble.setPadding(new Insets(3, 8, 3, 8));
        Label header = new Label(emoji + "  " + name);
        header.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + nameColor + ";");
        Label reactionLbl = new Label(reaction);
        reactionLbl.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 22px; -fx-text-fill: #e74c3c;");
        bubble.getChildren().addAll(header, reactionLbl);
        masterChatBox.getChildren().add(bubble);
        if (masterChatScrollPane != null)
            Platform.runLater(() -> masterChatScrollPane.setVvalue(1.0));
    }

    private void addListenerReaction(String name, String emoji, String nameColor, String reaction) {
        if (listenerChatBox == null) return;
        VBox bubble = new VBox(2);
        bubble.setPadding(new Insets(3, 8, 3, 8));
        Label header = new Label(emoji + "  " + name);
        header.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + nameColor + ";");
        Label reactionLbl = new Label(reaction);
        reactionLbl.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 22px; -fx-text-fill: #e74c3c;");
        bubble.getChildren().addAll(header, reactionLbl);
        listenerChatBox.getChildren().add(bubble);
        if (listenerChatScrollPane != null)
            Platform.runLater(() -> listenerChatScrollPane.setVvalue(1.0));
    }

    private void addListenerChatMessage(String name, String emoji, String nameColor, String text, String songRefVideoId, String songRefTitle) {
        if (listenerChatBox == null) return;
        VBox bubble = new VBox(2);
        bubble.setPadding(new Insets(3, 8, 3, 8));
        Label header = new Label(emoji + "  " + name);
        header.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + nameColor + ";");
        bubble.getChildren().add(header);
        if (songRefVideoId != null && !songRefVideoId.isEmpty()) {
            Label ref = new Label("🎵  " + songRefTitle);
            ref.setStyle("-fx-background-color: -fx-background; -fx-padding: 2 6; -fx-background-radius: 4; -fx-font-size: 11px;");
            ref.getStyleClass().add("greeting-sub");
            ref.setWrapText(true);
            bubble.getChildren().add(ref);
        }
        if (text != null && !text.isEmpty()) {
            Label textLbl = new Label(text);
            textLbl.setWrapText(true);
            textLbl.getStyleClass().add("greeting-sub");
            bubble.getChildren().add(textLbl);
        }
        listenerChatBox.getChildren().add(bubble);
        if (listenerChatScrollPane != null)
            Platform.runLater(() -> listenerChatScrollPane.setVvalue(1.0));
    }

    private void refreshSongList() {
        if (songListBox == null) return;
        songListBox.getChildren().clear();

        if (sharedSongs.isEmpty()) {
            Label ph = new Label("Aún no has añadido ninguna canción.");
            ph.getStyleClass().add("greeting-sub");
            songListBox.getChildren().add(ph);
            return;
        }

        for (SharedSong shared : sharedSongs) {
            LinkedHashMap<String, PartyServer.ListenerStatus> statuses =
                songStatus.getOrDefault(shared.videoId, new LinkedHashMap<>());

            Label titleLbl = new Label(shared.title);
            titleLbl.getStyleClass().add("song-title");
            titleLbl.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(titleLbl, Priority.ALWAYS);

            HBox emojisBox = new HBox(4);
            emojisBox.setAlignment(Pos.CENTER_LEFT);
            boolean anyDownloading = false;

            for (Map.Entry<String, PartyServer.ListenerStatus> entry : statuses.entrySet()) {
                String em = listenerEmojis.getOrDefault(entry.getKey(), "🎵");
                PartyServer.ListenerStatus st = entry.getValue();
                if (st == PartyServer.ListenerStatus.DOWNLOADING) anyDownloading = true;
                String color = switch (st) {
                    case READY       -> "#27ae60";
                    case DOWNLOADING -> "#f39c12";
                    case ERROR       -> "#c0392b";
                    default          -> "#888888";
                };
                Label emojiLbl = new Label(em);
                emojiLbl.setStyle("-fx-font-size: 15px; -fx-underline: true; -fx-text-fill: " + color + ";");
                emojisBox.getChildren().add(emojiLbl);
            }

            Button hideToggleBtn = new Button(shared.hidden ? "🙈" : "👁");
            hideToggleBtn.getStyleClass().add("btn-secondary");
            hideToggleBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 6;");
            hideToggleBtn.setTooltip(new Tooltip(shared.hidden ? "Canción oculta para listeners" : "Canción visible para listeners"));
            hideToggleBtn.setOnAction(e -> {
                shared.hidden = !shared.hidden;
                hideToggleBtn.setText(shared.hidden ? "🙈" : "👁");
                hideToggleBtn.getTooltip().setText(shared.hidden ? "Canción oculta para listeners" : "Canción visible para listeners");
            });

            Button playPartyBtn = new Button("▶");
            playPartyBtn.getStyleClass().add("btn-secondary");
            playPartyBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 8;");
            playPartyBtn.setDisable(anyDownloading);
            playPartyBtn.setOnAction(e -> {
                if (partyServer != null) partyServer.broadcastOpen(shared.videoId, shared.hidden);
                mc.openMasterPartyPlayer(shared.song);
            });

            HBox row = new HBox(8, titleLbl, emojisBox, hideToggleBtn, playPartyBtn);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(4, 8, 4, 8));
            row.getStyleClass().add("detail-song-row");
            songListBox.getChildren().add(row);
        }
    }

    private void showMasterPanel(String initialCode, String roomName) {
        Label titleLbl = new Label("📡  " + roomName);
        titleLbl.getStyleClass().add("section-title");

        masterCodeLbl = new Label(initialCode);
        masterCodeLbl.setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");

        Button copyBtn = new Button("📋  Copiar");
        copyBtn.getStyleClass().add("btn-secondary");
        copyBtn.setOnAction(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(masterCodeLbl.getText());
            Clipboard.getSystemClipboard().setContent(c);
            copyBtn.setText("✅ Copiado");
            PauseTransition reset = new PauseTransition(Duration.seconds(2));
            reset.setOnFinished(ev -> copyBtn.setText("📋  Copiar"));
            reset.play();
        });

        HBox codeRow = new HBox(10, masterCodeLbl, copyBtn);
        codeRow.setAlignment(Pos.CENTER_LEFT);

        upnpStatusLbl = new Label("🔄 Conectando…");
        upnpStatusLbl.getStyleClass().add("greeting-sub");
        upnpStatusLbl.setStyle("-fx-font-size: 11px;");

        // ── Listeners conectados ──────────────────────────────────────────────
        Label membersHeader = new Label("LISTENERS");
        membersHeader.getStyleClass().add("sidebar-section-label");
        masterMemberListBox = new VBox(3);
        Label memberPh = new Label("Sin listeners conectados");
        memberPh.getStyleClass().add("greeting-sub");
        memberPh.setStyle("-fx-font-size: 11px;");
        masterMemberListBox.getChildren().add(memberPh);

        // ── Canciones ─────────────────────────────────────────────────────────
        Label songsHeader = new Label("CANCIONES");
        songsHeader.getStyleClass().add("sidebar-section-label");

        songListBox = new VBox(4);
        Label songPh = new Label("Aún no has añadido ninguna canción.");
        songPh.getStyleClass().add("greeting-sub");
        songListBox.getChildren().add(songPh);

        ScrollPane songsScroll = new ScrollPane(songListBox);
        songsScroll.setFitToWidth(true);
        songsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        songsScroll.getStyleClass().add("results-scroll");
        songsScroll.setPrefHeight(150);

        // ── Chat ─────────────────────────────────────────────────────────────
        Label chatHeader = new Label("CHAT");
        chatHeader.getStyleClass().add("sidebar-section-label");
        masterChatBox = new VBox(4);
        masterChatBox.setPadding(new Insets(4));
        masterChatScrollPane = new ScrollPane(masterChatBox);
        masterChatScrollPane.setFitToWidth(true);
        masterChatScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        masterChatScrollPane.getStyleClass().add("results-scroll");
        masterChatScrollPane.setPrefHeight(130);
        VBox.setVgrow(masterChatScrollPane, Priority.ALWAYS);

        TextField masterChatInput = new TextField();
        masterChatInput.setPromptText("Escribe un mensaje como Master…");
        masterChatInput.getStyleClass().add("detail-search-field");
        HBox.setHgrow(masterChatInput, Priority.ALWAYS);

        Button masterSendBtn = new Button("Enviar");
        masterSendBtn.getStyleClass().add("btn-secondary");

        Runnable masterSend = () -> {
            String text = masterChatInput.getText().trim();
            if (text.isEmpty()) return;
            if (partyServer != null) partyServer.broadcastMasterChat(text);
            addMasterChatMessage("Master", "📡", "#54a0ff", text, null, null);
            masterChatInput.clear();
        };
        masterChatInput.setOnAction(e -> masterSend.run());
        masterSendBtn.setOnAction(e -> masterSend.run());

        HBox masterInputRow = new HBox(6, masterChatInput, masterSendBtn);
        masterInputRow.setAlignment(Pos.CENTER_LEFT);

        // ── Cerrar sala ───────────────────────────────────────────────────────
        Button closeBtn = new Button("⏹  Cerrar sala");
        closeBtn.getStyleClass().add("btn-secondary");
        closeBtn.setMaxWidth(Double.MAX_VALUE);
        closeBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white;");
        closeBtn.setOnAction(e -> {
            if (partyServer != null) partyServer.broadcastRoomClosed();
            if (upnpHelper != null) {
                UPnPHelper h = upnpHelper; upnpHelper = null;
                Thread t = new Thread(h::removeMapping, "party-upnp-cleanup");
                t.setDaemon(true); t.start();
            }
            if (sshTunnelProcess != null) { sshTunnelProcess.destroyForcibly(); sshTunnelProcess = null; }
            if (partyServer != null) { partyServer.stop(); partyServer = null; }
            masterActive.set(false);
            masterCodeLbl = null; upnpStatusLbl = null; songListBox = null;
            masterMemberListBox = null; masterChatBox = null; masterChatScrollPane = null; reactionOverlay = null;
            sharedSongs.clear(); listenerEmojis.clear(); listenerColors.clear(); songStatus.clear();
            showLanding();
        });

        VBox content = new VBox(10,
            titleLbl, codeRow, upnpStatusLbl,
            new Separator(), membersHeader, masterMemberListBox,
            new Separator(), songsHeader, songsScroll,
            new Separator(), chatHeader, masterChatScrollPane, masterInputRow,
            new Separator(), closeBtn
        );
        content.setPadding(new Insets(4));

        reactionOverlay = new javafx.scene.layout.Pane();
        reactionOverlay.setMouseTransparent(true);
        reactionOverlay.setPickOnBounds(false);

        root.getChildren().setAll(new StackPane(content, reactionOverlay));
    }

    // ── Listener ──────────────────────────────────────────────────────────────

    private void showJoinForm() {
        Preferences prefs = Preferences.userNodeForPackage(PartyPanelBuilder.class);
        String savedNick  = prefs.get("party.nickname", "");
        String savedEmoji = prefs.get("party.emoji",    "🎵");

        Label titleLbl = new Label("🔗  Unirse a sala");
        titleLbl.getStyleClass().add("section-title");

        Label nickHeader = new Label("NICKNAME");
        nickHeader.getStyleClass().add("sidebar-section-label");
        TextField nickFld = new TextField(savedNick);
        nickFld.setPromptText("Tu nombre (máx. 20 caracteres)");
        nickFld.getStyleClass().add("detail-search-field");
        nickFld.textProperty().addListener((obs, o, n) -> {
            if (n.length() > 20) nickFld.setText(n.substring(0, 20));
        });

        Label emojiHeader = new Label("EMOJI");
        emojiHeader.getStyleClass().add("sidebar-section-label");

        String[] selectedEmoji = {savedEmoji};
        ToggleGroup emojiGroup = new ToggleGroup();
        FlowPane emojiPane = new FlowPane(4, 4);
        for (String em : EMOJIS) {
            ToggleButton btn = new ToggleButton(em);
            btn.setToggleGroup(emojiGroup);
            btn.setStyle("-fx-font-size: 18px; -fx-background-radius: 6; -fx-padding: 4 6;");
            if (em.equals(savedEmoji)) btn.setSelected(true);
            btn.selectedProperty().addListener((obs, o, n) -> { if (n) selectedEmoji[0] = em; });
            emojiPane.getChildren().add(btn);
        }

        Label colorHeader = new Label("COLOR");
        colorHeader.getStyleClass().add("sidebar-section-label");

        String savedColor = prefs.get("party.color", "#a29bfe");
        String[] selectedColor = {savedColor};
        ToggleGroup colorGroup = new ToggleGroup();
        FlowPane colorPane = new FlowPane(6, 6);
        for (String col : COLORS) {
            ToggleButton swatch = new ToggleButton("");
            swatch.setToggleGroup(colorGroup);
            swatch.setPrefSize(26, 26); swatch.setMinSize(26, 26); swatch.setMaxSize(26, 26);
            boolean isSelected = col.equals(savedColor);
            swatch.setSelected(isSelected);
            String baseStyle = "-fx-background-color: " + col + "; -fx-background-radius: 13; -fx-cursor: hand;";
            String selStyle  = baseStyle + " -fx-border-color: white; -fx-border-radius: 13; -fx-border-width: 2;";
            swatch.setStyle(isSelected ? selStyle : baseStyle);
            swatch.selectedProperty().addListener((obs, o, n) -> {
                if (n) {
                    selectedColor[0] = col;
                    swatch.setStyle(selStyle);
                } else {
                    swatch.setStyle(baseStyle);
                }
            });
            colorPane.getChildren().add(swatch);
        }

        Label codeHeader = new Label("CÓDIGO DE SALA");
        codeHeader.getStyleClass().add("sidebar-section-label");
        TextField codeField = new TextField();
        codeField.setPromptText("Pega aquí el código de sala");
        codeField.getStyleClass().add("detail-search-field");

        Label errorLbl = new Label();
        errorLbl.setWrapText(true);
        errorLbl.getStyleClass().add("greeting-sub");
        errorLbl.setStyle("-fx-text-fill: #c0392b;");
        errorLbl.setManaged(false); errorLbl.setVisible(false);

        Button connectBtn = new Button("Conectar");
        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setMaxWidth(Double.MAX_VALUE);
        connectBtn.setOnAction(e -> {
            String nick = nickFld.getText().trim();
            if (nick.isEmpty()) {
                errorLbl.setText("Introduce un nickname.");
                errorLbl.setVisible(true); errorLbl.setManaged(true); return;
            }
            try {
                String[] parts = RoomCode.decode(codeField.getText());
                prefs.put("party.nickname", nick);
                prefs.put("party.emoji",    selectedEmoji[0]);
                prefs.put("party.color",    selectedColor[0]);
                connectAsListener(parts[0], Integer.parseInt(parts[1]), nick, selectedEmoji[0], selectedColor[0]);
            } catch (IllegalArgumentException ex) {
                errorLbl.setText(ex.getMessage());
                errorLbl.setVisible(true); errorLbl.setManaged(true);
            }
        });

        Button backBtn = new Button("← Volver");
        backBtn.getStyleClass().add("btn-secondary");
        backBtn.setMaxWidth(Double.MAX_VALUE);
        backBtn.setOnAction(e -> showLanding());

        VBox form = new VBox(10,
            titleLbl,
            nickHeader, nickFld,
            emojiHeader, emojiPane,
            colorHeader, colorPane,
            codeHeader, codeField,
            errorLbl, connectBtn, backBtn);
        form.setMaxWidth(400);
        form.setPadding(new Insets(4, 4, 4, 4));

        ScrollPane formScroll = new ScrollPane(form);
        formScroll.setFitToWidth(true);
        formScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        formScroll.getStyleClass().add("results-scroll");
        VBox.setVgrow(formScroll, Priority.ALWAYS);

        root.getChildren().setAll(formScroll);
    }

    private void connectAsListener(String host, int port, String nickname, String emoji, String color) {
        Label connecting = new Label("🔗 Conectando…");
        connecting.getStyleClass().add("greeting-sub");
        root.getChildren().setAll(connecting);

        partyClient = new PartyClient(host, port, nickname, emoji, color, downloadService, new PartyClient.Callbacks() {
            @Override public void onConnected() { showListenerPanel(); }
            @Override public void onRejected(String reason) {
                partyClient = null;
                mc.showToast(reason);
                showJoinForm();
            }
            @Override public void onTrackLoading(String title) {
                if (listenerTrackLbl != null) listenerTrackLbl.setText("⬇ Descargando…");
            }
            @Override public void onTrackReady(Song song, java.nio.file.Path localPath) {
                boolean isHidden = hiddenListenerTracks.contains(song.getVideoId());
                if (listenerTrackLbl != null)
                    listenerTrackLbl.setText(isHidden ? "✅ Listo" : "✅ " + song.getTitle());
                pendingSongs.put(song.getVideoId(), song);
                // Player opens only when master broadcasts "open" — wait for onOpen
            }
            @Override public void onOpen(String videoId, boolean hidden) {
                Song song = pendingSongs.get(videoId);
                if (song == null) return;
                if (hidden) hiddenListenerTracks.add(videoId);
                else hiddenListenerTracks.remove(videoId);
                partyPlayers.put(videoId, mc.partyLoadSong(song, hidden));
            }
            @Override public void onPlay(String videoId, long positionMs) {
                if (listenerSyncLbl != null) listenerSyncLbl.setText("▶ Reproduciendo");
                PlayerInstance pi = partyPlayers.get(videoId);
                if (pi != null && pi.mediaPlayer != null) {
                    if (pi.isHiddenPartyTrack) {
                        hiddenListenerTracks.remove(videoId);
                        mc.revealPartyTrack(pi);
                        Song real = pendingSongs.get(videoId);
                        if (real != null && listenerTrackLbl != null)
                            listenerTrackLbl.setText("✅ " + real.getTitle());
                    }
                    pi.mediaPlayer.seek(javafx.util.Duration.millis(positionMs));
                    mc.partyPlay(pi);
                }
            }
            @Override public void onPause(String videoId, long positionMs) {
                if (listenerSyncLbl != null) listenerSyncLbl.setText("⏸ Pausado");
                PlayerInstance pi = partyPlayers.get(videoId);
                if (pi != null && pi.mediaPlayer != null)
                    mc.partyPause(pi, positionMs);
            }
            @Override public void onSeek(String videoId, long positionMs) {
                PlayerInstance pi = partyPlayers.get(videoId);
                if (pi != null && pi.mediaPlayer != null)
                    pi.mediaPlayer.seek(javafx.util.Duration.millis(positionMs));
            }
            @Override public void onVolume(String videoId, double volume) {
                PlayerInstance pi = partyPlayers.get(videoId);
                if (pi != null && pi.mediaPlayer != null) {
                    pi.volume = volume;
                    if (pi.fadeOutAnim == null) pi.mediaPlayer.setVolume(volume);
                }
            }
            @Override public void onLoop(String videoId, boolean looping) {
                PlayerInstance pi = partyPlayers.get(videoId);
                if (pi != null) pi.looping = looping;
            }
            @Override public void onCloseTrack(String videoId) {
                hiddenListenerTracks.remove(videoId);
                closePartyPlayerTab(videoId);
                // pendingSongs se conserva: el master puede volver a abrir la misma canción
            }
            @Override public void onRoomClosed() {
                mc.showToast("La sala ha sido cerrada por el master");
                closeAllPartyPlayerTabs();
                pendingSongs.clear();
                hiddenListenerTracks.clear();
                knownSongs.clear(); pendingRefVideoId = null; pendingRefTitle = null;
                if (partyClient != null) { partyClient.disconnect(); partyClient = null; }
                listenerTrackLbl = null; listenerSyncLbl = null;
                listenerMemberListBox = null; listenerChatBox = null; listenerChatScrollPane = null;
                PartyPanelBuilder.this.deletePartyDownloads();
                mc.closeTabForced("party");
                showLanding();
            }
            @Override public void onDisconnected(String reason) {
                closeAllPartyPlayerTabs();
                pendingSongs.clear();
                hiddenListenerTracks.clear();
                knownSongs.clear(); pendingRefVideoId = null; pendingRefTitle = null;
                partyClient = null;
                listenerTrackLbl = null; listenerSyncLbl = null;
                listenerMemberListBox = null; listenerChatBox = null; listenerChatScrollPane = null;
                PartyPanelBuilder.this.deletePartyDownloads();
                showError("Desconectado: " + reason);
            }
            @Override public void onTrackAnnounced(String videoId, String title, boolean hidden) {
                knownSongs.put(videoId, title);
                if (hidden) hiddenListenerTracks.add(videoId); else hiddenListenerTracks.remove(videoId);
            }
            @Override public void onChatMessage(String name, String emoji, String color, String text, String songRefVideoId, String songRefTitle) {
                PartyPanelBuilder.this.addListenerChatMessage(name, emoji, color, text, songRefVideoId, songRefTitle);
            }
            @Override public void onReaction(String name, String emoji, String color, String reaction) {
                PartyPanelBuilder.this.addListenerReaction(name, emoji, color, reaction);
            }
            @Override public void onMembersUpdate(java.util.List<PartyClient.MemberInfo> members) {
                if (listenerMemberListBox == null) return;
                listenerMemberListBox.getChildren().clear();
                if (members.isEmpty()) {
                    Label ph = new Label("Solo tú en la sala");
                    ph.getStyleClass().add("greeting-sub"); ph.setStyle("-fx-font-size: 11px;");
                    listenerMemberListBox.getChildren().add(ph);
                } else {
                    for (PartyClient.MemberInfo m : members) {
                        Label lbl = new Label(m.emoji() + "  " + m.name() + "  " + m.emoji());
                        lbl.getStyleClass().add("greeting-sub");
                        lbl.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 13px; -fx-text-fill: " + m.color() + ";");
                        listenerMemberListBox.getChildren().add(lbl);
                    }
                }
            }
        });
        Runnable cleanup = this::deletePartyDownloads;
        partyCleanupHook = new Thread(cleanup, "party-cleanup");
        Runtime.getRuntime().addShutdownHook(partyCleanupHook);
        partyClient.connect();
    }

    private void showListenerPanel() {
        Label titleLbl = new Label("🎧  En sala");
        titleLbl.getStyleClass().add("section-title");

        Label connLbl = new Label("✅ Conectado");
        connLbl.getStyleClass().add("greeting-sub");

        // ── Miembros ──────────────────────────────────────────────────────────
        Label membersHeader = new Label("MIEMBROS");
        membersHeader.getStyleClass().add("sidebar-section-label");
        listenerMemberListBox = new VBox(3);
        Label memberPh = new Label("Cargando miembros…");
        memberPh.getStyleClass().add("greeting-sub");
        memberPh.setStyle("-fx-font-size: 11px;");
        listenerMemberListBox.getChildren().add(memberPh);

        // ── Estado de reproducción ────────────────────────────────────────────
        Label syncHeader = new Label("ESTADO");
        syncHeader.getStyleClass().add("sidebar-section-label");
        listenerSyncLbl = new Label("⏸ Esperando…");
        listenerSyncLbl.getStyleClass().add("greeting-sub");

        // ── Chat ──────────────────────────────────────────────────────────────
        Label chatHeader = new Label("CHAT");
        chatHeader.getStyleClass().add("sidebar-section-label");

        listenerChatBox = new VBox(4);
        listenerChatBox.setPadding(new Insets(4));
        listenerChatScrollPane = new ScrollPane(listenerChatBox);
        listenerChatScrollPane.setFitToWidth(true);
        listenerChatScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        listenerChatScrollPane.getStyleClass().add("results-scroll");
        listenerChatScrollPane.setPrefHeight(140);

        // Chip que muestra la canción referenciada antes de enviar
        Label refChip = new Label();
        refChip.setStyle("-fx-background-color: #2a2a3a; -fx-padding: 2 8; -fx-background-radius: 4; -fx-font-size: 11px; -fx-cursor: hand;");
        refChip.setVisible(false); refChip.setManaged(false);
        refChip.setOnMouseClicked(ev -> {
            pendingRefVideoId = null; pendingRefTitle = null;
            refChip.setVisible(false); refChip.setManaged(false);
        });

        // Input row: [TextField] [📎] [❤️] [Enviar]
        TextField chatInput = new TextField();
        chatInput.setPromptText("Escribe un mensaje…");
        chatInput.getStyleClass().add("detail-search-field");
        HBox.setHgrow(chatInput, Priority.ALWAYS);

        Button refBtn = new Button("📎");
        refBtn.getStyleClass().add("btn-secondary");
        refBtn.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 12px; -fx-padding: 5 8;");
        refBtn.setVisible(false); refBtn.setManaged(false);
        refBtn.setOnAction(e -> {
            ContextMenu cm = new ContextMenu();
            for (Map.Entry<String, String> entry : knownSongs.entrySet()) {
                if (hiddenListenerTracks.contains(entry.getKey())) continue;
                MenuItem item = new MenuItem("🎵  " + entry.getValue());
                item.setOnAction(ev -> {
                    pendingRefVideoId = entry.getKey();
                    pendingRefTitle = entry.getValue();
                    refChip.setText("🎵  " + pendingRefTitle + "   ✕");
                    refChip.setVisible(true); refChip.setManaged(true);
                });
                cm.getItems().add(item);
            }
            if (cm.getItems().isEmpty()) {
                mc.showToast("Aún no hay canciones disponibles para referenciar");
                return;
            }
            // Platform.runLater evita que el ContextMenu se cierre inmediatamente
            // al ser mostrado desde dentro de un ActionEvent del mismo botón
            Platform.runLater(() -> cm.show(refBtn, javafx.geometry.Side.BOTTOM, 0, 0));
        });

        Button heartBtn = new Button("❤");
        heartBtn.getStyleClass().add("btn-secondary");
        heartBtn.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 16px; -fx-padding: 4 8; -fx-text-fill: #e74c3c;");
        heartBtn.setOnAction(e -> {
            if (partyClient != null) partyClient.sendReaction("❤");
        });

        Button sendBtn = new Button("Enviar");
        sendBtn.getStyleClass().add("btn-secondary");

        Runnable doSend = () -> {
            String text = chatInput.getText().trim();
            if (text.isEmpty() && pendingRefVideoId == null) return;
            if (partyClient != null) partyClient.sendChat(text, pendingRefVideoId, pendingRefTitle);
            chatInput.clear();
            pendingRefVideoId = null; pendingRefTitle = null;
            refChip.setVisible(false); refChip.setManaged(false);
        };
        chatInput.setOnAction(e -> doSend.run());
        sendBtn.setOnAction(e -> doSend.run());

        HBox inputRow = new HBox(6, chatInput, refBtn, heartBtn, sendBtn);
        inputRow.setAlignment(Pos.CENTER_LEFT);

        // ── Salir ─────────────────────────────────────────────────────────────
        Button leaveBtn = new Button("⏹  Salir de sala");
        leaveBtn.getStyleClass().add("btn-secondary");
        leaveBtn.setMaxWidth(Double.MAX_VALUE);
        leaveBtn.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white;");
        leaveBtn.setOnAction(e -> {
            if (partyClient != null) { partyClient.disconnect(); partyClient = null; }
            closeAllPartyPlayerTabs();
            pendingSongs.clear();
            knownSongs.clear(); pendingRefVideoId = null; pendingRefTitle = null;
            listenerTrackLbl = null; listenerSyncLbl = null;
            listenerMemberListBox = null; listenerChatBox = null; listenerChatScrollPane = null;
            deletePartyDownloads();
            showLanding();
        });

        VBox inner = new VBox(10,
            titleLbl, connLbl,
            new Separator(), membersHeader, listenerMemberListBox,
            new Separator(), syncHeader, listenerSyncLbl,
            new Separator(), chatHeader, listenerChatScrollPane,
            refChip, inputRow,
            new Separator(), leaveBtn
        );
        inner.setPadding(new Insets(4, 4, 8, 4));

        ScrollPane scroll = new ScrollPane(inner);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("results-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        root.getChildren().setAll(scroll);
    }

    // ── SSH / bore tunnel ─────────────────────────────────────────────────────

    /** Intenta crear un túnel via serveo.net. Devuelve true si el túnel llegó a estar activo. */
    private boolean runServeoTunnel(int port) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ssh", "-T",
                "-o", "BatchMode=yes",
                "-o", "StrictHostKeyChecking=no",
                "-o", "ConnectTimeout=20",
                "-o", "ServerAliveInterval=30",
                "-o", "ServerAliveCountMax=3",
                "-o", "LogLevel=ERROR",
                "-R", "0:localhost:" + port,
                "serveo.net"
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            sshTunnelProcess = proc;
            proc.getOutputStream().close();

            Thread killer = new Thread(() -> {
                try { Thread.sleep(25_000); if (proc.isAlive()) proc.destroyForcibly(); }
                catch (InterruptedException ignored) {}
            }, "party-serveo-kill");
            killer.setDaemon(true);
            killer.start();

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(proc.getInputStream()));

            boolean found = false;
            String line;
            while ((line = reader.readLine()) != null) {
                java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("Allocated port (\\d+)").matcher(line);
                if (!found && m.find()) {
                    found = true;
                    killer.interrupt();
                    int ap = Integer.parseInt(m.group(1));
                    updateTunnelUi("tcp.serveo.net", ap, "tcp.serveo.net");
                }
            }
            if (found) Platform.runLater(() -> {
                if (upnpStatusLbl != null) upnpStatusLbl.setText("⚠ Túnel desconectado");
            });
            return found;
        } catch (java.io.IOException ignored) {
            return false; // ssh.exe no encontrado
        }
    }

    private void runBoreTunnel(int port) {
        try {
            java.nio.file.Path boreExe = boreExePath();

            if (!java.nio.file.Files.exists(boreExe)) {
                Platform.runLater(() -> {
                    if (upnpStatusLbl != null) upnpStatusLbl.setText("⬇ Descargando bore (~3 MB)…");
                });
                downloadBore(boreExe);
            }

            Platform.runLater(() -> {
                if (upnpStatusLbl != null) upnpStatusLbl.setText("🔄 Conectando con bore.pub…");
            });

            ProcessBuilder pb = new ProcessBuilder(
                boreExe.toString(), "local", String.valueOf(port), "--to", "bore.pub"
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            sshTunnelProcess = proc;
            proc.getOutputStream().close();

            Thread killer = new Thread(() -> {
                try { Thread.sleep(30_000); if (proc.isAlive()) proc.destroyForcibly(); }
                catch (InterruptedException ignored) {}
            }, "party-bore-kill");
            killer.setDaemon(true);
            killer.start();

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(proc.getInputStream()));

            boolean found = false;
            String line;
            while ((line = reader.readLine()) != null) {
                java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("bore\\.pub:(\\d+)").matcher(line);
                if (!found && m.find()) {
                    found = true;
                    killer.interrupt();
                    int ap = Integer.parseInt(m.group(1));
                    updateTunnelUi("bore.pub", ap, "bore.pub");
                }
            }

            final boolean wasFound = found;
            Platform.runLater(() -> {
                if (upnpStatusLbl == null) return;
                if (wasFound) {
                    upnpStatusLbl.setText("⚠ Túnel desconectado");
                } else {
                    upnpStatusLbl.setText("❌ No se pudo establecer el túnel");
                }
            });
        } catch (Exception ex) {
            Platform.runLater(() -> {
                if (upnpStatusLbl != null)
                    upnpStatusLbl.setText("❌ Error: " + ex.getMessage());
            });
        }
    }

    private void updateTunnelUi(String host, int port, String label) {
        String code = RoomCode.encode(host, port);
        Platform.runLater(() -> {
            if (masterCodeLbl != null) masterCodeLbl.setText(code);
            if (upnpStatusLbl != null) upnpStatusLbl.setText("✅ Túnel activo — " + label + ":" + port);
        });
    }

    private java.nio.file.Path boreExePath() throws java.io.IOException {
        String base = System.getenv("APPDATA");
        if (base == null) base = System.getProperty("user.home");
        java.nio.file.Path dir = java.nio.file.Path.of(base, "Bardo");
        java.nio.file.Files.createDirectories(dir);
        return dir.resolve("bore.exe");
    }

    private void downloadBore(java.nio.file.Path dest) throws Exception {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.BufferedInputStream(
                    new java.net.URI(BORE_ZIP_URL).toURL().openStream()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(".exe") || entry.getName().equals("bore")) {
                    java.nio.file.Files.copy(zis, dest,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    break;
                }
            }
        }
    }

    // ── App shutdown ─────────────────────────────────────────────────────────

    /** Called when the application closes — kills tunnel/server/client processes. */
    void shutdown() {
        if (sshTunnelProcess != null) { sshTunnelProcess.destroyForcibly(); sshTunnelProcess = null; }
        if (partyServer  != null) { partyServer.stop();       partyServer  = null; }
        if (partyClient  != null) { partyClient.disconnect(); partyClient  = null; }
    }

    // ── Listener cleanup ──────────────────────────────────────────────────────

    private void deletePartyDownloads() {
        java.nio.file.Path dir = downloadService.getGroupDir("party");
        try {
            if (java.nio.file.Files.exists(dir)) {
                try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .map(java.nio.file.Path::toFile)
                        .forEach(java.io.File::delete);
                }
            }
        } catch (Exception ignored) {}
        if (partyCleanupHook != null) {
            try { Runtime.getRuntime().removeShutdownHook(partyCleanupHook); } catch (Exception ignored) {}
            partyCleanupHook = null;
        }
    }

    // ── Master broadcast helpers (called from MainController) ─────────────────

    void masterBroadcastPlay(String videoId, long positionMs) {
        if (partyServer != null) partyServer.broadcastPlay(videoId, positionMs);
    }

    void masterBroadcastPause(String videoId, long positionMs) {
        if (partyServer != null) partyServer.broadcastPause(videoId, positionMs);
    }

    void masterBroadcastSeek(String videoId, long positionMs) {
        if (partyServer != null) partyServer.broadcastSeek(videoId, positionMs);
    }

    void masterBroadcastVolume(String videoId, double volume) {
        if (partyServer != null) partyServer.broadcastVolume(videoId, volume);
    }

    void masterBroadcastLoop(String videoId, boolean looping) {
        if (partyServer != null) partyServer.broadcastLoop(videoId, looping);
    }

    void masterBroadcastCloseTrack(String videoId) {
        if (partyServer != null) partyServer.broadcastCloseTrack(videoId);
    }

    // ── Listener: cierre de pestañas de reproductor ───────────────────────────

    private void closeAllPartyPlayerTabs() {
        for (PlayerInstance pi : partyPlayers.values()) mc.closeTabForced(pi.tabId);
        partyPlayers.clear();
    }

    private void closePartyPlayerTab(String videoId) {
        PlayerInstance pi = partyPlayers.remove(videoId);
        if (pi != null) mc.closeTabForced(pi.tabId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String getLocalIp() {
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface ni = nets.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress())
                        return a.getHostAddress();
                }
            }
        } catch (SocketException ignored) {}
        return "127.0.0.1";
    }

    private void showError(String msg) {
        Label lbl = new Label("❌ " + msg);
        lbl.setWrapText(true);
        lbl.getStyleClass().add("greeting-sub");
        Button back = new Button("← Volver");
        back.getStyleClass().add("btn-secondary");
        back.setOnAction(e -> showLanding());
        root.getChildren().setAll(lbl, back);
    }
}
