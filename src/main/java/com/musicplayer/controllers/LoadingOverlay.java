package com.musicplayer.controllers;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Full-window loading overlay shown at startup.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Constructor — builds the overlay UI and starts loading icon pieces in background.</li>
 *   <li>{@link #start()} — starts the progress-bar timer and the minimum-display timer.</li>
 *   <li>When icon pieces finish loading → mandatory intro animation (pieces fly from screen
 *       corners to centre). This animation cannot be interrupted.</li>
 *   <li>After intro: if all tasks are done AND min time has passed → go straight to
 *       {@link #finishLoading()}; otherwise start the idle loop animation.</li>
 *   <li>{@link #completeTask()} — call once per async task that finishes; triggers finish
 *       when all tasks are done AND intro has played AND min time has elapsed.</li>
 * </ol>
 */
class LoadingOverlay {

    /** Minimum ms the overlay stays visible. */
    private static final int MIN_DISPLAY_MS = 2400;

    private final StackPane parent;
    private final StackPane overlay;
    private final StackPane iconPane;

    private final ImageView piece1 = new ImageView();
    private final ImageView piece2 = new ImageView();
    private final ImageView piece3 = new ImageView();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label("Cargando…");

    private final int totalTasks;
    private int  completedTasks = 0;
    private boolean minTimePassed = false;
    private boolean allTasksDone  = false;
    private boolean introPlayed   = false;

    private SequentialTransition loopAnim;
    private Node targetNode;

    LoadingOverlay(StackPane parent, int totalTasks) {
        this.parent     = parent;
        this.totalTasks = totalTasks;

        for (ImageView iv : new ImageView[]{piece1, piece2, piece3}) {
            iv.setFitWidth(180);
            iv.setFitHeight(180);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
        }

        iconPane = new StackPane(piece1, piece2, piece3);
        iconPane.setMaxSize(180, 180);
        iconPane.setViewOrder(-1.0); // render on top of label and progress bar

        progressBar.setPrefWidth(240);
        progressBar.getStyleClass().add("loading-progress-bar");

        statusLabel.getStyleClass().add("loading-status-label");

        VBox content = new VBox(30, iconPane, statusLabel, progressBar);
        content.setAlignment(Pos.CENTER);
        content.setMaxSize(VBox.USE_PREF_SIZE, VBox.USE_PREF_SIZE);

        overlay = new StackPane(content);
        overlay.getStyleClass().add("loading-overlay");
        parent.getChildren().add(overlay);

        // Load icon pieces in background; on completion trigger the mandatory intro
        Thread loader = new Thread(() -> {
            Image i1 = loadImage("/com/musicplayer/icons/icon_full1.png");
            Image i2 = loadImage("/com/musicplayer/icons/icon_full2.png");
            Image i3 = loadImage("/com/musicplayer/icons/icon_full3.png");
            Platform.runLater(() -> {
                if (i3 != null) piece1.setImage(i3);
                if (i2 != null) piece2.setImage(i2);
                if (i1 != null) piece3.setImage(i1);
                startIntroWhenReady();
            });
        }, "bardo-loading-icons");
        loader.setDaemon(true);
        loader.start();
    }

    private Image loadImage(String path) {
        try (java.io.InputStream in = getClass().getResourceAsStream(path)) {
            return in != null ? new Image(in) : null;
        } catch (Exception ignored) { return null; }
    }

    /** Start the progress-bar fill and the minimum-display timer. Call once from initialize(). */
    void start() {
        Timeline barAnim = new Timeline(
            new KeyFrame(Duration.ZERO,
                new KeyValue(progressBar.progressProperty(), 0)),
            new KeyFrame(Duration.millis(MIN_DISPLAY_MS),
                new KeyValue(progressBar.progressProperty(), 1.0, Interpolator.EASE_BOTH))
        );
        barAnim.play();

        PauseTransition minTimer = new PauseTransition(Duration.millis(MIN_DISPLAY_MS));
        minTimer.setOnFinished(e -> { minTimePassed = true; checkFinish(); });
        minTimer.play();
    }

    // ── Intro animation ───────────────────────────────────────────────────────

    /** Waits for the overlay to be laid out, then positions pieces and starts intro. */
    private void startIntroWhenReady() {
        if (overlay.getWidth() > 0 && overlay.getHeight() > 0) {
            positionAtCornersAndWait();
            return;
        }
        java.util.concurrent.atomic.AtomicReference<ChangeListener<Number>> ref =
            new java.util.concurrent.atomic.AtomicReference<>();
        ChangeListener<Number> listener = (obs, old, w) -> {
            if (overlay.getWidth() > 0 && overlay.getHeight() > 0) {
                overlay.widthProperty().removeListener(ref.get());
                positionAtCornersAndWait();
            }
        };
        ref.set(listener);
        overlay.widthProperty().addListener(listener);
    }

    /**
     * Places each piece at its starting screen corner and waits 300 ms so the images
     * are fully rendered before the fly-in begins.
     */
    private void positionAtCornersAndWait() {
        Bounds iconBounds = iconPane.localToScene(iconPane.getBoundsInLocal());
        double cx = iconBounds.getCenterX();
        double cy = iconBounds.getCenterY();
        double sw = overlay.getScene().getWidth();
        double sh = overlay.getScene().getHeight();
        double half = 90; // half the icon size — centre placed outside viewport so piece is fully hidden

        // piece1 (iconfull3) → off-screen top-left, piece2 (iconfull2) → off-screen top-right,
        // piece3 (iconfull1) → off-screen bottom-centre
        piece1.setTranslateX(-half - cx);       piece1.setTranslateY(-half - cy);
        piece2.setTranslateX(sw + half - cx);   piece2.setTranslateY(-half - cy);
        piece3.setTranslateX(sw / 2 - cx);      piece3.setTranslateY(sh + half - cy);

        // Hold at corners long enough for images to render fully, then fly in
        PauseTransition hold = new PauseTransition(Duration.millis(300));
        hold.setOnFinished(e -> playIntroAnimation());
        hold.play();
    }

    /** Animates pieces from their current corner positions to the centre. */
    private void playIntroAnimation() {
        ParallelTransition flyIn = new ParallelTransition();
        for (ImageView piece : new ImageView[]{piece1, piece2, piece3}) {
            TranslateTransition t = new TranslateTransition(Duration.millis(750), piece);
            t.setToX(0);
            t.setToY(0);
            t.setInterpolator(Interpolator.EASE_IN);
            flyIn.getChildren().add(t);
        }
        flyIn.setOnFinished(e -> {
            introPlayed = true;
            if (allTasksDone && minTimePassed) {
                finishLoading();
            } else {
                buildAndPlayLoop();
            }
        });
        flyIn.play();
    }

    // ── Idle loop animation ───────────────────────────────────────────────────

    private ParallelTransition buildSeparate(ImageView[] pieces, double[][] off) {
        ParallelTransition pt = new ParallelTransition();
        for (int i = 0; i < 3; i++) {
            TranslateTransition t = new TranslateTransition(Duration.millis(380), pieces[i]);
            t.setToX(off[i][0]);
            t.setToY(off[i][1]);
            t.setInterpolator(Interpolator.EASE_OUT);
            pt.getChildren().add(t);
        }
        return pt;
    }

    private ParallelTransition buildReunite(ImageView[] pieces) {
        ParallelTransition pt = new ParallelTransition();
        for (ImageView piece : pieces) {
            TranslateTransition t = new TranslateTransition(Duration.millis(380), piece);
            t.setToX(0);
            t.setToY(0);
            t.setInterpolator(Interpolator.EASE_IN);
            pt.getChildren().add(t);
        }
        return pt;
    }

    private void buildAndPlayLoop() {
        double[][] off = {{-19, -15}, {19, -15}, {0, 24}};
        ImageView[] pieces = {piece1, piece2, piece3};

        RotateTransition spinCW  = new RotateTransition(Duration.millis(680), iconPane);
        spinCW.setByAngle(360);
        spinCW.setInterpolator(Interpolator.EASE_BOTH);

        RotateTransition spinCCW = new RotateTransition(Duration.millis(680), iconPane);
        spinCCW.setByAngle(-360);
        spinCCW.setInterpolator(Interpolator.EASE_BOTH);

        loopAnim = new SequentialTransition(
            buildSeparate(pieces, off),
            new PauseTransition(Duration.millis(110)),
            buildReunite(pieces),
            new PauseTransition(Duration.millis(200)),
            spinCW,
            new PauseTransition(Duration.millis(180)),
            buildSeparate(pieces, off),
            new PauseTransition(Duration.millis(110)),
            buildReunite(pieces),
            new PauseTransition(Duration.millis(200)),
            spinCCW,
            new PauseTransition(Duration.millis(220))
        );
        loopAnim.setCycleCount(Animation.INDEFINITE);
        loopAnim.play();
    }

    // ── Task completion ───────────────────────────────────────────────────────

    /**
     * Signal that one async loading task has finished.
     * Thread-safe — may be called from any thread.
     */
    void completeTask() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::completeTask);
            return;
        }
        completedTasks = Math.min(completedTasks + 1, totalTasks);
        if (completedTasks >= totalTasks) {
            allTasksDone = true;
            checkFinish();
        }
    }

    private void checkFinish() {
        if (!introPlayed || !allTasksDone || !minTimePassed) return;
        finishLoading();
    }

    /** Nodo de destino al que el icono se desplazará antes de desaparecer. */
    void setTargetNode(Node node) {
        this.targetNode = node;
    }

    // ── Finish sequence ───────────────────────────────────────────────────────

    private void finishLoading() {
        if (loopAnim != null) loopAnim.stop();

        // Normalize rotation to [0,360) so the reset travels at most 180°
        double cur = ((iconPane.getRotate() % 360) + 360) % 360;
        iconPane.setRotate(cur);
        double rotTarget = cur > 180 ? 360 : 0;

        // Animate pieces back to centre and rotation to 0° simultaneously
        ParallelTransition resetToNeutral = new ParallelTransition();
        for (ImageView p : new ImageView[]{piece1, piece2, piece3}) {
            TranslateTransition t = new TranslateTransition(Duration.millis(300), p);
            t.setToX(0);
            t.setToY(0);
            t.setInterpolator(Interpolator.EASE_BOTH);
            resetToNeutral.getChildren().add(t);
        }
        Timeline resetRot = new Timeline(new KeyFrame(Duration.millis(300),
            new KeyValue(iconPane.rotateProperty(), rotTarget, Interpolator.EASE_BOTH)));
        resetToNeutral.getChildren().add(resetRot);

        // Then fade out label and progress bar
        FadeTransition fadeLabel = new FadeTransition(Duration.millis(200), statusLabel);
        fadeLabel.setToValue(0);
        FadeTransition fadeBar   = new FadeTransition(Duration.millis(200), progressBar);
        fadeBar.setToValue(0);

        PauseTransition brief = new PauseTransition(Duration.millis(100));
        brief.setOnFinished(e -> flyToTargetAndFade());

        new SequentialTransition(
            resetToNeutral,
            new ParallelTransition(fadeLabel, fadeBar),
            brief
        ).play();
    }

    private void flyToTargetAndFade() {
        if (targetNode != null && targetNode.getScene() != null) {
            Bounds src = iconPane.localToScene(iconPane.getBoundsInLocal());
            Bounds dst = targetNode.localToScene(targetNode.getBoundsInLocal());

            TranslateTransition move = new TranslateTransition(Duration.millis(420), iconPane);
            move.setByX(dst.getCenterX() - src.getCenterX());
            move.setByY(dst.getCenterY() - src.getCenterY());
            move.setInterpolator(Interpolator.EASE_BOTH);

            FadeTransition fadeOut = new FadeTransition(Duration.millis(420), overlay);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(ev -> parent.getChildren().remove(overlay));

            new SequentialTransition(move, fadeOut).play();
        } else {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(420), overlay);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(e -> parent.getChildren().remove(overlay));
            fadeOut.play();
        }
    }
}
