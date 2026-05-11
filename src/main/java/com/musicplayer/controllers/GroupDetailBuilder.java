package com.musicplayer.controllers;

import com.musicplayer.models.LibraryGroup;
import com.musicplayer.models.Song;
import com.musicplayer.services.LibraryService;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.text.Normalizer;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.collections.transformation.FilteredList;

/**
 * Construye los paneles de la biblioteca: sección de grupo (fila en la lista)
 * y panel de detalle (lista de canciones con drag-to-reorder y checklist de playlists).
 */
public final class GroupDetailBuilder {

    private GroupDetailBuilder() {}

    /** Tipos de lista disponibles en el Dropdown. */
    private static final String[] TYPES = {"Música", "Ambiente", "Mashup"};

    // ── groupSection ─────────────────────────────────────────────────────────

    public static VBox groupSection(LibraryGroup group, Runnable onRemove,
                                    Consumer<Button> onRefreshYt, Runnable onClick) {
        VBox card = new VBox(0); card.getStyleClass().add("group-section");
        HBox row  = new HBox(12); row.getStyleClass().add("group-header"); row.setAlignment(Pos.CENTER_LEFT);

        String thumbUrl = group.getThumbnailUrl();
        if (thumbUrl != null && !thumbUrl.isBlank()) {
            ImageView iv = new ImageView(); iv.setFitWidth(56); iv.setFitHeight(32); iv.setPreserveRatio(false);
            try { iv.setImage(new Image(thumbUrl, 56, 32, false, true, true)); } catch (Exception ignored) {}
            row.getChildren().add(iv);
        } else {
            Label icon = new Label(group.isYoutubePlaylist() ? "📺" : "🎵");
            icon.setStyle("-fx-font-size: 20px; -fx-min-width: 48px; -fx-alignment: center;");
            row.getChildren().add(icon);
        }

        Label nameLbl = new Label(group.getName()); nameLbl.getStyleClass().add("group-name");
        Label cntLbl  = new Label();
        cntLbl.textProperty().bind(Bindings.createStringBinding(() -> group.size() + " canciones", group.getSongs()));
        cntLbl.getStyleClass().add("group-count");
        VBox meta = new VBox(2, nameLbl, cntLbl); HBox.setHgrow(meta, Priority.ALWAYS);

        ComboBox<String> typeCombo = buildTypeCombo(group);

        Label chevron = new Label("›"); chevron.setStyle("-fx-font-size: 20px; -fx-text-fill: #c0b0d0;");
        Button removeBtn = new Button("✕"); removeBtn.getStyleClass().add("group-remove-btn"); removeBtn.setOnAction(e -> onRemove.run());

        if (group.isYoutubePlaylist() && onRefreshYt != null) {
            Button refreshBtn = new Button("🔄"); refreshBtn.getStyleClass().add("group-refresh-btn");
            refreshBtn.setOnAction(e -> { e.consume(); onRefreshYt.accept(refreshBtn); });
            row.getChildren().addAll(meta, typeCombo, chevron, refreshBtn, removeBtn);
        } else {
            row.getChildren().addAll(meta, typeCombo, chevron, removeBtn);
        }
        row.setOnMouseClicked(e -> { if (!(e.getTarget() instanceof Button) && !(e.getTarget() instanceof ComboBox)) onClick.run(); });
        card.getChildren().add(row);
        return card;
    }

    // ── detailPanel ───────────────────────────────────────────────────────────

    public static VBox detailPanel(LibraryGroup group, Runnable onBack, Runnable onPlayAll,
                                   Runnable onImportFolder, Consumer<Button> onRefreshYt,
                                   Runnable onDownloadAll, Consumer<String> onBrowser,
                                   BiConsumer<Song, LibraryGroup> onPlaySong,
                                   BiConsumer<Song, LibraryGroup> onOpenPaused,
                                   Consumer<List<Song>> onOpenMashup,
                                   LibraryService libraryService, Consumer<String> onToast) {
        VBox panel = new VBox(0); panel.getStyleClass().add("panel"); panel.setPadding(Insets.EMPTY);

        // Top bar
        Button backBtn = new Button("←"); backBtn.getStyleClass().add("back-btn"); backBtn.setOnAction(e -> onBack.run());
        Label iconLbl  = new Label(group.isYoutubePlaylist() ? "📺" : "🎵"); iconLbl.setStyle("-fx-font-size: 22px;");

        Label titleLbl = new Label(group.getName()); titleLbl.getStyleClass().add("greeting");
        Label cntLbl   = new Label();
        cntLbl.textProperty().bind(Bindings.createStringBinding(() -> group.size() + " canciones", group.getSongs()));
        cntLbl.getStyleClass().add("greeting-sub");
        VBox titleBox = new VBox(3, titleLbl, cntLbl); HBox.setHgrow(titleBox, Priority.ALWAYS);

        ComboBox<String> typeCombo = buildTypeCombo(group);

        Button playAllBtn = new Button("▶"); playAllBtn.getStyleClass().add("btn-icon"); playAllBtn.setOnAction(e -> onPlayAll.run());
        Button importBtn  = new Button("📁"); importBtn.getStyleClass().add("btn-icon");  importBtn.setOnAction(e -> onImportFolder.run());
        HBox actionBtns = new HBox(6, typeCombo, playAllBtn, importBtn); actionBtns.setAlignment(Pos.CENTER_RIGHT);
        // Mashup selection state
        Song[]    mashupSel     = {null, null};
        Button[]  mashupPlayBtn = {new Button("🎧  Reproducir Mashup")};
        mashupPlayBtn[0].getStyleClass().add("btn-primary");
        mashupPlayBtn[0].setDisable(true);
        mashupPlayBtn[0].setOnAction(e -> {
            if (onOpenMashup != null && mashupSel[0] != null && mashupSel[1] != null)
                onOpenMashup.accept(List.of(mashupSel[0], mashupSel[1]));
        });
        Label mashupHint = new Label("Selecciona dos canciones (① y ②) y pulsa el botón para reproducirlas juntas.");
        mashupHint.setStyle("-fx-font-size:11px; -fx-text-fill:#a090b8;"); mashupHint.setWrapText(true);
        HBox mashupBar = new HBox(12, mashupHint, mashupPlayBtn[0]);
        mashupBar.setAlignment(Pos.CENTER_LEFT);
        mashupBar.setStyle("-fx-background-color:#f8f0ff; -fx-border-color:#e8d8f4; -fx-border-width:1 0 1 0; -fx-padding:10 28;");
        mashupBar.setVisible("Mashup".equals(group.getType()));
        mashupBar.setManaged("Mashup".equals(group.getType()));
        group.typeProperty().addListener((obs, old, t) -> {
            boolean m = "Mashup".equals(t);
            mashupBar.setVisible(m); mashupBar.setManaged(m);
            if (!m) { mashupSel[0] = null; mashupSel[1] = null; mashupPlayBtn[0].setDisable(true); }
        });

        if (group.isYoutubePlaylist()) {
            Button refreshBtn = new Button("🔄"); refreshBtn.getStyleClass().add("btn-icon");
            refreshBtn.setOnAction(e -> { if (onRefreshYt != null) onRefreshYt.accept(refreshBtn); });
            Button dlBtn = new Button("⬇"); dlBtn.getStyleClass().add("btn-icon");
            dlBtn.setOnAction(e -> { if (onDownloadAll != null) onDownloadAll.run(); });
            actionBtns.getChildren().addAll(refreshBtn, dlBtn);
        }

        HBox topBar = new HBox(12, backBtn, iconLbl, titleBox, actionBtns);
        topBar.setAlignment(Pos.CENTER_LEFT); topBar.setPadding(new Insets(22, 28, 14, 28));
        Separator sep = new Separator(); sep.setStyle("-fx-background-color: #ece8f4;");

        // ── Search ───────────────────────────────────────────────────────────
        FilteredList<Song> filteredSongs = new FilteredList<>(group.getSongs(), p -> true);

        TextField searchField = new TextField();
        searchField.getStyleClass().add("detail-search-field");
        searchField.setPromptText("Buscar canción…");
        searchField.setPrefWidth(190);
        searchField.textProperty().addListener((obs, old, query) -> {
            String q = normalize(query);
            filteredSongs.setPredicate(q.isEmpty() ? null
                : s -> normalize(s.getTitle()).contains(q) || normalize(s.getArtist()).contains(q));
        });

        Label searchIcon = new Label("🔍");
        searchIcon.setStyle("-fx-font-size:13px;");
        HBox searchRow = new HBox(6, searchIcon, searchField);
        searchRow.setAlignment(Pos.CENTER_RIGHT);
        searchRow.setPadding(new Insets(8, 28, 6, 28));

        ListView<Song> songList = new ListView<>(filteredSongs);
        songList.getStyleClass().add("detail-listview");
        songList.setFixedCellSize(56);
        songList.setFocusTraversable(false);
        VBox.setVgrow(songList, Priority.ALWAYS);

        // ── Drag-to-reorder state ─────────────────────────────────────────────
        Song[]     dragging     = {null};
        boolean[]  dragActive   = {false};
        boolean[]  justDragged  = {false};
        boolean[]  scrollDir    = {false};
        double[]   pressScreenY = {0};
        Timeline[] scrollTl     = {null};

        songList.setCellFactory(lv -> {
            ListCell<Song> cell = new ListCell<>() {
                @Override protected void updateItem(Song song, boolean empty) {
                    super.updateItem(song, empty);
                    setPadding(Insets.EMPTY);
                    setStyle("-fx-background-color: transparent; -fx-border-width: 0;");
                    if (empty || song == null) { setGraphic(null); return; }
                    if ("Mashup".equals(group.getType())) {
                        String badge = song == mashupSel[0] ? "①" : song == mashupSel[1] ? "②" : null;
                        setGraphic(detailSongRowMashup(song, group, badge, onBrowser, () -> {
                            if (justDragged[0]) { justDragged[0] = false; return; }
                            if      (song == mashupSel[0]) mashupSel[0] = null;
                            else if (song == mashupSel[1]) mashupSel[1] = null;
                            else if (mashupSel[0] == null) mashupSel[0] = song;
                            else                           mashupSel[1] = song;
                            mashupPlayBtn[0].setDisable(mashupSel[0] == null || mashupSel[1] == null);
                            songList.refresh();
                        }, moved -> Platform.runLater(() -> songList.scrollTo(filteredSongs.indexOf(moved))),
                        libraryService, onToast));
                    } else {
                        setGraphic(detailSongRow(song, group, onBrowser,
                            () -> { if (!justDragged[0]) onPlaySong.accept(song, group); justDragged[0] = false; },
                            () -> onOpenPaused.accept(song, group),
                            moved -> Platform.runLater(() -> songList.scrollTo(filteredSongs.indexOf(moved))),
                            libraryService, onToast));
                    }
                }
            };

            cell.setOnMousePressed(e -> {
                if (e.getTarget() instanceof Button || e.getTarget() instanceof ComboBox || cell.isEmpty()) return;
                if (!searchField.getText().isEmpty()) return;
                dragging[0] = cell.getItem(); pressScreenY[0] = e.getScreenY(); dragActive[0] = false;
            });

            cell.setOnMouseDragged(e -> {
                if (dragging[0] == null) return;
                if (!dragActive[0]) {
                    if (Math.abs(e.getScreenY() - pressScreenY[0]) < 8) return;
                    dragActive[0] = true; justDragged[0] = false; songList.setCursor(Cursor.CLOSED_HAND);
                }

                Bounds  lb     = songList.localToScene(songList.getBoundsInLocal());
                double  mouseY = e.getSceneY(), top = lb.getMinY(), bot = lb.getMaxY();
                boolean goDown = mouseY > bot - 60, goUp = mouseY < top + 60;

                if (goDown || goUp) {
                    if (scrollTl[0] == null || goDown != scrollDir[0]) {
                        if (scrollTl[0] != null) scrollTl[0].stop();
                        scrollDir[0] = goDown;
                        ScrollBar vsb = verticalScrollBar(songList);
                        if (vsb != null) {
                            double step = goDown ? 0.04 : -0.04;
                            scrollTl[0] = new Timeline(new KeyFrame(Duration.millis(80),
                                ev -> vsb.setValue(Math.max(0, Math.min(1, vsb.getValue() + step)))));
                            scrollTl[0].setCycleCount(Animation.INDEFINITE);
                            scrollTl[0].play();
                        }
                    }
                } else if (scrollTl[0] != null) { scrollTl[0].stop(); scrollTl[0] = null; }

                ScrollBar vsb2  = verticalScrollBar(songList);
                int   total     = filteredSongs.size();
                double visible  = songList.getHeight() / 56.0;
                double scroll   = vsb2 != null ? vsb2.getValue() : 0;
                double firstVis = scroll * Math.max(0, total - visible);
                int targetIdx   = (int) Math.max(0, Math.min(total - 1, firstVis + Math.max(0, mouseY - top) / 56.0));
                int curIdx      = group.getSongs().indexOf(dragging[0]);
                if (targetIdx != curIdx && curIdx >= 0) {
                    group.getSongs().remove(curIdx);
                    group.getSongs().add(targetIdx, dragging[0]);
                }
                e.consume();
            });

            cell.setOnMouseReleased(e -> {
                if (dragActive[0]) {
                    justDragged[0] = true; dragActive[0] = false; songList.setCursor(Cursor.DEFAULT);
                    if (scrollTl[0] != null) { scrollTl[0].stop(); scrollTl[0] = null; }
                }
                dragging[0] = null;
            });

            return cell;
        });

        panel.getChildren().addAll(topBar, sep, searchRow, mashupBar, songList);
        return panel;
    }

    // ── Song row ──────────────────────────────────────────────────────────────

    private static HBox detailSongRow(Song song, LibraryGroup group,
                                      Consumer<String> onBrowser, Runnable onPlay,
                                      Runnable onOpenPaused, Consumer<Song> onMoved,
                                      LibraryService libraryService, Consumer<String> onToast) {
        HBox row = new HBox(12); row.getStyleClass().add("detail-song-row");
        row.setAlignment(Pos.CENTER_LEFT); row.setPadding(new Insets(0, 16, 0, 16));

        boolean hasThumb = song.getThumbnailUrl() != null && !song.getThumbnailUrl().isBlank();
        if (hasThumb) {
            ImageView iv = new ImageView(); iv.getStyleClass().add("detail-thumb");
            iv.setFitWidth(56); iv.setFitHeight(32); iv.setPreserveRatio(false);
            try { iv.setImage(new Image(song.getThumbnailUrl(), 56, 32, false, true, true)); } catch (Exception ignored) {}
            row.getChildren().add(iv);
        } else {
            Label ph = new Label("🎵"); ph.setStyle("-fx-font-size: 18px; -fx-min-width: 56px; -fx-alignment: center;");
            row.getChildren().add(ph);
        }

        Label titleLbl  = new Label(song.getTitle());  titleLbl.getStyleClass().add("song-title");  titleLbl.setMaxWidth(300);
        Label artistLbl = new Label(song.getArtist()); artistLbl.getStyleClass().add("song-artist");
        VBox info = new VBox(2, titleLbl, artistLbl); HBox.setHgrow(info, Priority.ALWAYS);

        Label durLbl = new Label(song.getDuration()); durLbl.getStyleClass().add("song-duration"); durLbl.setMinWidth(40);
        row.getChildren().addAll(info, durLbl);

        if (hasThumb) {
            Button linkBtn = new Button("🔗"); linkBtn.getStyleClass().add("row-link-btn");
            linkBtn.setOnAction(e -> onBrowser.accept(song.getVideoId()));
            row.getChildren().add(linkBtn);
        }

        Button openBtn = new Button("▷"); openBtn.getStyleClass().add("row-open-btn");
        openBtn.setTooltip(new Tooltip("Abrir en reproductor (pausado)"));
        openBtn.setOnAction(e -> onOpenPaused.run());
        row.getChildren().add(openBtn);

        // Playlist checklist button
        Button checklistBtn = new Button("≡"); checklistBtn.getStyleClass().add("row-checklist-btn");
        ContextMenu[] menuRef = {null};
        // MOUSE_PRESSED filter runs before the popup's auto-hide, so isShowing() is still true here
        checklistBtn.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (menuRef[0] != null && menuRef[0].isShowing()) {
                menuRef[0].hide();
                e.consume(); // stop MOUSE_RELEASED → onAction from firing
            }
        });
        checklistBtn.setOnAction(e -> {
            menuRef[0] = buildPlaylistChecklist(song, group, libraryService, onToast);
            menuRef[0].show(checklistBtn, Side.BOTTOM, 0, 0);
        });
        row.getChildren().add(checklistBtn);

        // Move arrows (±10 positions)
        Button upBtn   = new Button("↑"); upBtn.getStyleClass().add("row-move-btn");
        Button downBtn = new Button("↓"); downBtn.getStyleClass().add("row-move-btn");
        upBtn.setOnAction(ev -> {
            moveInList(group.getSongs(), song, -10);
            if (onMoved != null) onMoved.accept(song);
        });
        downBtn.setOnAction(ev -> {
            moveInList(group.getSongs(), song, 10);
            if (onMoved != null) onMoved.accept(song);
        });
        VBox moveBox = new VBox(0, upBtn, downBtn); moveBox.setAlignment(Pos.CENTER);

        Button removeBtn = new Button("✕"); removeBtn.getStyleClass().add("row-remove-btn");
        removeBtn.setOnAction(e -> group.removeSong(song));
        row.getChildren().addAll(moveBox, removeBtn);

        row.setOnMouseClicked(e -> { if (!(e.getTarget() instanceof Button)) onPlay.run(); });
        return row;
    }

    // ── Mashup song row (selection mode) ─────────────────────────────────────

    private static HBox detailSongRowMashup(Song song, LibraryGroup group,
                                            String selBadge, Consumer<String> onBrowser,
                                            Runnable onSelect, Consumer<Song> onMoved,
                                            LibraryService libraryService, Consumer<String> onToast) {
        HBox row = new HBox(12); row.getStyleClass().add("detail-song-row");
        row.setAlignment(Pos.CENTER_LEFT); row.setPadding(new Insets(0, 16, 0, 16));
        if (selBadge != null)
            row.setStyle("-fx-background-color:" + ("①".equals(selBadge) ? "#fff0f5" : "#f0f5ff") + ";");

        boolean hasThumb = song.getThumbnailUrl() != null && !song.getThumbnailUrl().isBlank();
        if (hasThumb) {
            ImageView iv = new ImageView(); iv.getStyleClass().add("detail-thumb");
            iv.setFitWidth(56); iv.setFitHeight(32); iv.setPreserveRatio(false);
            try { iv.setImage(new Image(song.getThumbnailUrl(), 56, 32, false, true, true)); } catch (Exception ignored) {}
            row.getChildren().add(iv);
        } else {
            Label ph = new Label("🎵"); ph.setStyle("-fx-font-size:18px; -fx-min-width:56px; -fx-alignment:center;");
            row.getChildren().add(ph);
        }

        Label titleLbl  = new Label(song.getTitle());  titleLbl.getStyleClass().add("song-title");  titleLbl.setMaxWidth(300);
        Label artistLbl = new Label(song.getArtist()); artistLbl.getStyleClass().add("song-artist");
        VBox info = new VBox(2, titleLbl, artistLbl); HBox.setHgrow(info, Priority.ALWAYS);
        Label durLbl = new Label(song.getDuration()); durLbl.getStyleClass().add("song-duration"); durLbl.setMinWidth(40);
        row.getChildren().addAll(info, durLbl);

        if (hasThumb) {
            Button linkBtn = new Button("🔗"); linkBtn.getStyleClass().add("row-link-btn");
            linkBtn.setOnAction(e -> onBrowser.accept(song.getVideoId()));
            row.getChildren().add(linkBtn);
        }

        // Selection badge button
        String btnText = selBadge != null ? selBadge : "○";
        Button selBtn = new Button(btnText); selBtn.getStyleClass().add("mashup-sel-btn");
        if (selBadge != null) selBtn.setStyle("-fx-opacity:1.0; -fx-text-fill:" + ("①".equals(selBadge) ? "#e8729a" : "#6ba3d6") + ";");
        selBtn.setOnAction(e -> onSelect.run());
        row.getChildren().add(selBtn);

        // Playlist checklist
        Button checklistBtn = new Button("≡"); checklistBtn.getStyleClass().add("row-checklist-btn");
        ContextMenu[] menuRef = {null};
        checklistBtn.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (menuRef[0] != null && menuRef[0].isShowing()) { menuRef[0].hide(); e.consume(); }
        });
        checklistBtn.setOnAction(e -> {
            menuRef[0] = buildPlaylistChecklist(song, group, libraryService, onToast);
            menuRef[0].show(checklistBtn, Side.BOTTOM, 0, 0);
        });
        row.getChildren().add(checklistBtn);

        // Move arrows
        Button upBtn   = new Button("↑"); upBtn.getStyleClass().add("row-move-btn");
        Button downBtn = new Button("↓"); downBtn.getStyleClass().add("row-move-btn");
        upBtn.setOnAction(ev   -> { moveInList(group.getSongs(), song, -10); if (onMoved != null) onMoved.accept(song); });
        downBtn.setOnAction(ev -> { moveInList(group.getSongs(), song,  10); if (onMoved != null) onMoved.accept(song); });
        VBox moveBox = new VBox(0, upBtn, downBtn); moveBox.setAlignment(Pos.CENTER);

        Button removeBtn = new Button("✕"); removeBtn.getStyleClass().add("row-remove-btn");
        removeBtn.setOnAction(e -> group.removeSong(song));
        row.getChildren().addAll(moveBox, removeBtn);

        row.setOnMouseClicked(e -> { if (!(e.getTarget() instanceof Button)) onSelect.run(); });
        return row;
    }

    // ── Playlist checklist popup ──────────────────────────────────────────────

    private static ContextMenu buildPlaylistChecklist(Song song, LibraryGroup currentGroup,
                                                      LibraryService libraryService, Consumer<String> onToast) {
        ContextMenu menu = new ContextMenu();
        for (LibraryGroup g : libraryService.getGroups()) {
            boolean inGroup = g.getSongs().stream().anyMatch(s -> s.getVideoId().equals(song.getVideoId()));
            CheckMenuItem item = new CheckMenuItem((g.isYoutubePlaylist() ? "📺 " : "🎵 ") + g.getName());
            item.setSelected(inGroup);
            item.setOnAction(ev -> {
                if (item.isSelected()) {
                    // Add to beginning of this playlist
                    if (!g.getSongs().stream().anyMatch(s -> s.getVideoId().equals(song.getVideoId()))) {
                        g.getSongs().add(0, song);
                        song.setType(g.getType());
                        onToast.accept("Añadido a «" + g.getName() + "»");
                    }
                } else {
                    // Remove from this playlist
                    if (g == currentGroup) {
                        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                        alert.setTitle("Eliminar canción");
                        alert.setHeaderText(null);
                        alert.setContentText("¿Eliminar «" + song.getTitle() + "» de la lista actual «" + g.getName() + "»?");
                        alert.showAndWait()
                            .filter(r -> r == ButtonType.OK)
                            .ifPresent(r -> {
                                g.removeSong(song);
                                onToast.accept("Eliminado de «" + g.getName() + "»");
                            });
                    } else {
                        g.removeSong(song);
                        onToast.accept("Eliminado de «" + g.getName() + "»");
                    }
                }
            });
            menu.getItems().add(item);
        }
        if (menu.getItems().isEmpty()) {
            MenuItem empty = new MenuItem("(Sin listas de reproducción)");
            empty.setDisable(true);
            menu.getItems().add(empty);
        }
        return menu;
    }

    // ── Type ComboBox ─────────────────────────────────────────────────────────

    private static ComboBox<String> buildTypeCombo(LibraryGroup group) {
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().addAll(TYPES);
        combo.setValue(group.getType());
        combo.getStyleClass().add("type-combo");
        combo.setPrefWidth(110);
        combo.setOnAction(e -> group.setType(combo.getValue()));
        // Prevent click on combo from propagating as row click
        combo.setOnMouseClicked(javafx.event.Event::consume);
        return combo;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
            .toLowerCase();
    }

    private static ScrollBar verticalScrollBar(ListView<?> list) {
        for (javafx.scene.Node n : list.lookupAll(".scroll-bar"))
            if (n instanceof ScrollBar sb && sb.getOrientation() == Orientation.VERTICAL) return sb;
        return null;
    }

    private static void moveInList(ObservableList<Song> list, Song song, int delta) {
        int i = list.indexOf(song);
        int j = Math.max(0, Math.min(list.size() - 1, i + delta));
        if (j != i) { list.remove(i); list.add(j, song); }
    }
}
