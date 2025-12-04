package org.fractalizer.ui.timeline;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import org.fractalizer.animation.AnimationTrack;
import org.fractalizer.animation.Easing;
import org.fractalizer.animation.Keyframe;
import org.fractalizer.animation.Timeline;

import java.util.*;
import java.util.function.Consumer;

/**
 * Professional visual timeline widget with:
 * - Time ruler with click-to-seek
 * - Track rows with keyframe diamonds
 * - Drag & drop keyframes
 * - Playhead indicator
 * - Context menu for keyframe operations
 */
public class TimelineWidget extends VBox {

    // Timeline data
    private final Timeline timeline;
    private final List<TrackInfo> visibleTracks = new ArrayList<>();

    // UI Components
    private final Canvas canvas;
    private HBox transportBar;
    private Label timeLabel;
    private Label frameLabel;
    private Button playButton;
    private ComboBox<Easing> easingCombo;
    private Spinner<Double> durationSpinner;
    private ScrollBar hScrollBar;
    private ScrollBar vScrollBar;

    // Layout constants
    private static final double RULER_HEIGHT = 25;
    private static final double TRACK_HEIGHT = 24;
    private static final double TRACK_LABEL_WIDTH = 80;
    private static final double KEYFRAME_SIZE = 10;
    private static final double MIN_PIXELS_PER_SECOND = 30;
    private static final double MAX_PIXELS_PER_SECOND = 200;

    // Visual state
    private double pixelsPerSecond = 60;
    private double scrollOffsetX = 0;  // Horizontal scroll
    private double scrollOffsetY = 0;  // Vertical scroll
    private KeyframeHandle selectedKeyframe = null;
    private KeyframeHandle hoveredKeyframe = null;
    private boolean isDragging = false;
    private double dragStartX;
    private double dragStartTime;

    // Colors
    private static final Color BG_COLOR = Color.rgb(30, 30, 40);
    private static final Color RULER_COLOR = Color.rgb(45, 45, 55);
    private static final Color TRACK_COLOR_1 = Color.rgb(35, 35, 45);
    private static final Color TRACK_COLOR_2 = Color.rgb(40, 40, 50);
    private static final Color PLAYHEAD_COLOR = Color.rgb(255, 100, 100);
    private static final Color KEYFRAME_COLOR = Color.rgb(255, 200, 100);
    private static final Color KEYFRAME_SELECTED = Color.rgb(100, 200, 255);
    private static final Color KEYFRAME_HOVER = Color.rgb(255, 255, 150);
    private static final Color TEXT_COLOR = Color.rgb(200, 200, 200);
    private static final Color GRID_COLOR = Color.rgb(60, 60, 70);

    // Callbacks
    private Runnable onRenderRequest;
    private Consumer<Double> onTimeChange;
    private Runnable onKeyframeAdded;
    private Runnable onKeyframeUpdated;

    public TimelineWidget(Timeline timeline) {
        this.timeline = timeline;

        // Initialize visible tracks
        initializeTrackInfo();

        // Create canvas for timeline drawing
        canvas = new Canvas(800, 200);
        canvas.setOnMousePressed(this::handleMousePressed);
        canvas.setOnMouseDragged(this::handleMouseDragged);
        canvas.setOnMouseReleased(this::handleMouseReleased);
        canvas.setOnMouseMoved(this::handleMouseMoved);
        canvas.setOnScroll(e -> {
            if (e.isControlDown()) {
                // Zoom
                double factor = e.getDeltaY() > 0 ? 1.1 : 0.9;
                pixelsPerSecond = Math.max(MIN_PIXELS_PER_SECOND,
                    Math.min(MAX_PIXELS_PER_SECOND, pixelsPerSecond * factor));
                updateScrollBars();
                redraw();
            } else if (e.isShiftDown()) {
                // Scroll horizontally
                scrollOffsetX = Math.max(0, scrollOffsetX - e.getDeltaY() * 0.5);
                updateScrollBars();
                redraw();
            } else {
                // Scroll vertically
                scrollOffsetY = Math.max(0, scrollOffsetY - e.getDeltaY() * 0.3);
                updateScrollBars();
                redraw();
            }
            e.consume();
        });

        // Horizontal scrollbar
        hScrollBar = new ScrollBar();
        hScrollBar.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        hScrollBar.valueProperty().addListener((obs, old, val) -> {
            scrollOffsetX = val.doubleValue();
            redraw();
        });

        // Vertical scrollbar
        vScrollBar = new ScrollBar();
        vScrollBar.setOrientation(javafx.geometry.Orientation.VERTICAL);
        vScrollBar.valueProperty().addListener((obs, old, val) -> {
            scrollOffsetY = val.doubleValue();
            redraw();
        });

        // Wrap canvas in a pane that tracks size
        Pane canvasPane = new Pane(canvas);
        canvasPane.setMinHeight(100);
        canvasPane.setStyle("-fx-background-color: #1e1e28;");

        // Make canvas resize with parent
        canvasPane.widthProperty().addListener((obs, old, val) -> {
            canvas.setWidth(val.doubleValue());
            updateScrollBars();
            redraw();
        });
        canvasPane.heightProperty().addListener((obs, old, val) -> {
            canvas.setHeight(val.doubleValue());
            updateScrollBars();
            redraw();
        });

        // Layout: canvas + scrollbars with proper corner handling
        // Use GridPane to avoid scrollbar overlap
        GridPane canvasWithScrollbars = new GridPane();
        canvasWithScrollbars.add(canvasPane, 0, 0);
        canvasWithScrollbars.add(vScrollBar, 1, 0);
        canvasWithScrollbars.add(hScrollBar, 0, 1);
        // Corner filler (empty region where scrollbars meet)
        Region corner = new Region();
        corner.setStyle("-fx-background-color: #1e1e28;");
        canvasWithScrollbars.add(corner, 1, 1);

        // Make canvas expand
        GridPane.setHgrow(canvasPane, Priority.ALWAYS);
        GridPane.setVgrow(canvasPane, Priority.ALWAYS);
        VBox.setVgrow(canvasWithScrollbars, Priority.ALWAYS);

        // Transport bar
        transportBar = createTransportBar();
        timeLabel = new Label("0.000s");
        timeLabel.setStyle("-fx-font-family: monospace; -fx-text-fill: white;");
        frameLabel = new Label("Frame 0/0");
        frameLabel.setStyle("-fx-font-family: monospace; -fx-text-fill: #aaa;");

        // Easing combo
        easingCombo = new ComboBox<>();
        easingCombo.getItems().addAll(Easing.values());
        easingCombo.setValue(Easing.LINEAR);
        easingCombo.setMaxWidth(150);
        easingCombo.setTooltip(new Tooltip("Easing for new keyframes.\nChange to update selected keyframe."));

        // Update selected keyframe's easing when combo changes
        easingCombo.setOnAction(e -> {
            if (selectedKeyframe != null) {
                updateSelectedKeyframeEasing(easingCombo.getValue());
            }
        });

        // Duration spinner
        durationSpinner = new Spinner<>(1.0, 300.0, timeline.getDuration(), 1.0);
        durationSpinner.setEditable(true);
        durationSpinner.setPrefWidth(70);
        durationSpinner.valueProperty().addListener((obs, old, val) -> {
            timeline.setDuration(val);
            updateScrollBars();
            redraw();
        });

        // Top bar with transport and info
        HBox topBar = new HBox(10);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(5, 10, 5, 10));
        topBar.setStyle("-fx-background-color: #252530;");

        Button addKeyBtn = new Button("+ Key");
        addKeyBtn.setTooltip(new Tooltip("Add keyframe (selected track or all if none selected)"));
        addKeyBtn.setOnAction(e -> {
            if (onKeyframeAdded != null) onKeyframeAdded.run();
            redraw();
        });

        Button updateKeyBtn = new Button("Update");
        updateKeyBtn.setTooltip(new Tooltip("Update selected track only"));
        updateKeyBtn.setOnAction(e -> {
            if (onKeyframeUpdated != null) onKeyframeUpdated.run();
            redraw();
        });

        Button deleteKeyBtn = new Button("Delete");
        deleteKeyBtn.setOnAction(e -> deleteSelectedKeyframe());

        Label durationLabel = new Label("Duration:");
        durationLabel.setStyle("-fx-text-fill: #aaa;");

        Label easingLabel = new Label("Easing:");
        easingLabel.setStyle("-fx-text-fill: #aaa;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        topBar.getChildren().addAll(
            transportBar,
            new Separator(javafx.geometry.Orientation.VERTICAL),
            timeLabel, frameLabel,
            new Separator(javafx.geometry.Orientation.VERTICAL),
            addKeyBtn, updateKeyBtn, deleteKeyBtn,
            new Separator(javafx.geometry.Orientation.VERTICAL),
            easingLabel, easingCombo,
            spacer,
            durationLabel, durationSpinner,
            new Label("s")
        );
        topBar.getChildren().get(topBar.getChildren().size() - 1).setStyle("-fx-text-fill: #aaa;");

        // Setup context menu
        setupContextMenu();

        // Build layout
        setStyle("-fx-background-color: #1e1e28; -fx-border-color: #444; -fx-border-width: 1 0 0 0;");
        getChildren().addAll(topBar, canvasWithScrollbars);

        // Initial draw
        Platform.runLater(() -> {
            updateScrollBars();
            redraw();
        });
    }

    private void updateScrollBars() {
        double canvasWidth = canvas.getWidth();
        double canvasHeight = canvas.getHeight();

        // Horizontal scrollbar: total timeline width
        double totalTimelineWidth = timeline.getDuration() * pixelsPerSecond + TRACK_LABEL_WIDTH;
        double visibleWidth = canvasWidth - TRACK_LABEL_WIDTH;
        double maxScrollX = Math.max(0, totalTimelineWidth - canvasWidth);

        hScrollBar.setMin(0);
        hScrollBar.setMax(maxScrollX);
        hScrollBar.setVisibleAmount(visibleWidth);
        hScrollBar.setBlockIncrement(visibleWidth * 0.9);
        hScrollBar.setUnitIncrement(pixelsPerSecond);
        if (scrollOffsetX > maxScrollX) scrollOffsetX = maxScrollX;
        hScrollBar.setValue(scrollOffsetX);

        // Vertical scrollbar: total tracks height
        double totalTracksHeight = RULER_HEIGHT + visibleTracks.size() * TRACK_HEIGHT;
        double visibleHeight = canvasHeight;
        double maxScrollY = Math.max(0, totalTracksHeight - canvasHeight);

        vScrollBar.setMin(0);
        vScrollBar.setMax(maxScrollY);
        vScrollBar.setVisibleAmount(visibleHeight);
        vScrollBar.setBlockIncrement(TRACK_HEIGHT * 3);
        vScrollBar.setUnitIncrement(TRACK_HEIGHT);
        if (scrollOffsetY > maxScrollY) scrollOffsetY = maxScrollY;
        vScrollBar.setValue(scrollOffsetY);
    }

    private void initializeTrackInfo() {
        // Define which tracks to show and their display names
        visibleTracks.clear();
        visibleTracks.add(new TrackInfo("camPos", "Camera Pos", Color.rgb(100, 200, 255)));
        visibleTracks.add(new TrackInfo("camQuat", "Camera Rot", Color.rgb(150, 200, 255)));
        visibleTracks.add(new TrackInfo("fov", "FOV", Color.rgb(200, 150, 255)));
        visibleTracks.add(new TrackInfo("focalDistance", "Focal Dist", Color.rgb(255, 200, 100)));
        visibleTracks.add(new TrackInfo("aperture", "Aperture", Color.rgb(255, 180, 100)));
        visibleTracks.add(new TrackInfo("lightDir", "Light Dir", Color.rgb(255, 255, 150)));
        visibleTracks.add(new TrackInfo("baseHue", "Base Hue", Color.rgb(255, 150, 200)));
    }

    private HBox createTransportBar() {
        HBox transport = new HBox(2);
        transport.setAlignment(Pos.CENTER);

        Button startBtn = new Button("\u23EE");
        startBtn.setTooltip(new Tooltip("Go to start"));
        startBtn.setOnAction(e -> { timeline.goToStart(); updateAndRender(); });

        Button prevKeyBtn = new Button("\u23EA");
        prevKeyBtn.setTooltip(new Tooltip("Previous keyframe"));
        prevKeyBtn.setOnAction(e -> goToPreviousKeyframe());

        Button prevBtn = new Button("\u23F4");
        prevBtn.setTooltip(new Tooltip("Previous frame"));
        prevBtn.setOnAction(e -> { timeline.previousFrame(); updateAndRender(); });

        playButton = new Button("\u25B6");
        playButton.setPrefWidth(40);
        playButton.setTooltip(new Tooltip("Play/Pause"));
        playButton.setOnAction(e -> {
            if (timeline.isPlaying()) {
                timeline.pause();
            } else {
                timeline.play();
            }
            updatePlayButton();
        });

        Button stopBtn = new Button("\u23F9");
        stopBtn.setTooltip(new Tooltip("Stop"));
        stopBtn.setOnAction(e -> { timeline.stop(); updatePlayButton(); updateAndRender(); });

        Button nextBtn = new Button("\u23F5");
        nextBtn.setTooltip(new Tooltip("Next frame"));
        nextBtn.setOnAction(e -> { timeline.nextFrame(); updateAndRender(); });

        Button nextKeyBtn = new Button("\u23E9");
        nextKeyBtn.setTooltip(new Tooltip("Next keyframe"));
        nextKeyBtn.setOnAction(e -> goToNextKeyframe());

        Button endBtn = new Button("\u23ED");
        endBtn.setTooltip(new Tooltip("Go to end"));
        endBtn.setOnAction(e -> { timeline.goToEnd(); updateAndRender(); });

        transport.getChildren().addAll(startBtn, prevKeyBtn, prevBtn, playButton, stopBtn, nextBtn, nextKeyBtn, endBtn);
        return transport;
    }

    private void goToPreviousKeyframe() {
        double currentTime = timeline.getCurrentTime();
        double prevTime = -1;

        // Find the closest keyframe before current time
        for (String trackName : timeline.getTrackNames()) {
            AnimationTrack<?> track = timeline.getTrack(trackName);
            for (Keyframe<?> kf : track.getKeyframes()) {
                double kfTime = kf.getTime();
                if (kfTime < currentTime - 0.001 && kfTime > prevTime) {
                    prevTime = kfTime;
                }
            }
        }

        if (prevTime >= 0) {
            timeline.setCurrentTime(prevTime);
            updateAndRender();
        }
    }

    private void goToNextKeyframe() {
        double currentTime = timeline.getCurrentTime();
        double nextTime = Double.MAX_VALUE;

        // Find the closest keyframe after current time
        for (String trackName : timeline.getTrackNames()) {
            AnimationTrack<?> track = timeline.getTrack(trackName);
            for (Keyframe<?> kf : track.getKeyframes()) {
                double kfTime = kf.getTime();
                if (kfTime > currentTime + 0.001 && kfTime < nextTime) {
                    nextTime = kfTime;
                }
            }
        }

        if (nextTime < Double.MAX_VALUE) {
            timeline.setCurrentTime(nextTime);
            updateAndRender();
        }
    }

    private void setupContextMenu() {
        ContextMenu contextMenu = new ContextMenu();

        MenuItem deleteItem = new MenuItem("Delete Keyframe");
        deleteItem.setOnAction(e -> deleteSelectedKeyframe());

        MenuItem jumpItem = new MenuItem("Jump to Keyframe");
        jumpItem.setOnAction(e -> {
            if (selectedKeyframe != null) {
                timeline.setCurrentTime(selectedKeyframe.time);
                updateAndRender();
            }
        });

        contextMenu.getItems().addAll(jumpItem, new SeparatorMenuItem(), deleteItem);

        canvas.setOnContextMenuRequested(e -> {
            if (selectedKeyframe != null) {
                contextMenu.show(canvas, e.getScreenX(), e.getScreenY());
            }
        });
    }

    // ========================================================================
    // Drawing
    // ========================================================================

    public void redraw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        // Skip drawing if canvas is too small (prevents crashes)
        if (w < 10 || h < 10) return;

        // Clear
        gc.setFill(BG_COLOR);
        gc.fillRect(0, 0, w, h);

        // Save state and clip to tracks area (below ruler)
        gc.save();
        gc.beginPath();
        gc.rect(0, RULER_HEIGHT, w, h - RULER_HEIGHT);
        gc.clip();

        // Draw tracks (with scroll offset)
        drawTracks(gc, w, h);

        // Draw playhead line (only in track area)
        drawPlayheadLine(gc, h);

        gc.restore();

        // Draw ruler on top (fixed, no scrolling)
        drawRuler(gc, w);

        // Draw playhead triangle on top of ruler (not clipped)
        drawPlayheadTriangle(gc);

        // Update labels
        updateTimeLabels();
    }

    private void drawRuler(GraphicsContext gc, double width) {
        gc.setFill(RULER_COLOR);
        gc.fillRect(0, 0, width, RULER_HEIGHT);

        gc.setStroke(GRID_COLOR);
        gc.setLineWidth(1);
        gc.setFill(TEXT_COLOR);
        gc.setFont(Font.font("Monospace", 10));
        gc.setTextAlign(TextAlignment.CENTER);

        // Calculate tick interval based on zoom
        double tickInterval = calculateTickInterval();
        double startTime = Math.floor(scrollOffsetX / pixelsPerSecond / tickInterval) * tickInterval;

        for (double time = startTime; time <= timeline.getDuration(); time += tickInterval) {
            double x = TRACK_LABEL_WIDTH + (time * pixelsPerSecond) - scrollOffsetX;
            if (x < TRACK_LABEL_WIDTH || x > width) continue;

            // Major tick
            gc.strokeLine(x, RULER_HEIGHT - 10, x, RULER_HEIGHT);

            // Time label
            String label = formatTime(time);
            gc.fillText(label, x, RULER_HEIGHT - 12);

            // Minor ticks
            double minorInterval = tickInterval / 4;
            for (int i = 1; i < 4; i++) {
                double minorX = x + (i * minorInterval * pixelsPerSecond);
                if (minorX < width) {
                    gc.strokeLine(minorX, RULER_HEIGHT - 5, minorX, RULER_HEIGHT);
                }
            }
        }

        // Track label area
        gc.setFill(Color.rgb(40, 40, 50));
        gc.fillRect(0, 0, TRACK_LABEL_WIDTH, RULER_HEIGHT);
        gc.setFill(TEXT_COLOR);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("Tracks", 5, RULER_HEIGHT - 8);
    }

    private void drawTracks(GraphicsContext gc, double width, double height) {
        double y = RULER_HEIGHT - scrollOffsetY;

        for (int i = 0; i < visibleTracks.size(); i++) {
            TrackInfo info = visibleTracks.get(i);
            AnimationTrack<?> track = timeline.getTrack(info.trackName);

            // Skip tracks that are completely off-screen
            if (y + TRACK_HEIGHT < RULER_HEIGHT || y > height) {
                y += TRACK_HEIGHT;
                continue;
            }

            // Track background
            gc.setFill(i % 2 == 0 ? TRACK_COLOR_1 : TRACK_COLOR_2);
            gc.fillRect(0, y, width, TRACK_HEIGHT);

            // Track label background
            gc.setFill(Color.rgb(50, 50, 60));
            gc.fillRect(0, y, TRACK_LABEL_WIDTH, TRACK_HEIGHT);

            // Track label
            gc.setFill(info.color);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFont(Font.font("System", 11));
            gc.fillText(info.displayName, 5, y + TRACK_HEIGHT - 7);

            // Draw keyframes
            if (track != null) {
                drawKeyframes(gc, track, info, y, width);
            }

            // Grid lines
            gc.setStroke(GRID_COLOR.deriveColor(0, 1, 1, 0.3));
            double tickInterval = calculateTickInterval();
            double startTime = Math.floor(scrollOffsetX / pixelsPerSecond / tickInterval) * tickInterval;
            for (double time = startTime; time <= timeline.getDuration(); time += tickInterval) {
                double x = TRACK_LABEL_WIDTH + (time * pixelsPerSecond) - scrollOffsetX;
                if (x >= TRACK_LABEL_WIDTH && x <= width) {
                    gc.strokeLine(x, y, x, y + TRACK_HEIGHT);
                }
            }

            y += TRACK_HEIGHT;
        }
    }

    private void drawKeyframes(GraphicsContext gc, AnimationTrack<?> track, TrackInfo info, double trackY, double width) {
        double centerY = trackY + TRACK_HEIGHT / 2;

        for (Keyframe<?> kf : track.getKeyframes()) {
            double x = TRACK_LABEL_WIDTH + (kf.getTime() * pixelsPerSecond) - scrollOffsetX;
            if (x < TRACK_LABEL_WIDTH - KEYFRAME_SIZE || x > width + KEYFRAME_SIZE) continue;

            // Determine color
            Color color = info.color;
            if (selectedKeyframe != null && selectedKeyframe.trackName.equals(info.trackName)
                && Math.abs(selectedKeyframe.time - kf.getTime()) < 0.001) {
                color = KEYFRAME_SELECTED;
            } else if (hoveredKeyframe != null && hoveredKeyframe.trackName.equals(info.trackName)
                && Math.abs(hoveredKeyframe.time - kf.getTime()) < 0.001) {
                color = KEYFRAME_HOVER;
            }

            // Draw diamond
            drawDiamond(gc, x, centerY, KEYFRAME_SIZE / 2, color);
        }

        // Draw connections between keyframes
        List<Keyframe<?>> keyframes = new ArrayList<>(track.getKeyframes());
        if (keyframes.size() > 1) {
            gc.setStroke(info.color.deriveColor(0, 1, 1, 0.4));
            gc.setLineWidth(1);
            for (int i = 0; i < keyframes.size() - 1; i++) {
                double x1 = TRACK_LABEL_WIDTH + (keyframes.get(i).getTime() * pixelsPerSecond) - scrollOffsetX;
                double x2 = TRACK_LABEL_WIDTH + (keyframes.get(i + 1).getTime() * pixelsPerSecond) - scrollOffsetX;
                if (x2 >= TRACK_LABEL_WIDTH && x1 <= width) {
                    gc.strokeLine(Math.max(x1, TRACK_LABEL_WIDTH), centerY, Math.min(x2, width), centerY);
                }
            }
        }
    }

    private void drawDiamond(GraphicsContext gc, double cx, double cy, double radius, Color color) {
        double[] xPoints = {cx, cx + radius, cx, cx - radius};
        double[] yPoints = {cy - radius, cy, cy + radius, cy};

        gc.setFill(color);
        gc.fillPolygon(xPoints, yPoints, 4);

        gc.setStroke(color.brighter());
        gc.setLineWidth(1);
        gc.strokePolygon(xPoints, yPoints, 4);
    }

    private void drawPlayheadLine(GraphicsContext gc, double height) {
        double x = TRACK_LABEL_WIDTH + (timeline.getCurrentTime() * pixelsPerSecond) - scrollOffsetX;

        if (x >= TRACK_LABEL_WIDTH && x <= canvas.getWidth()) {
            gc.setStroke(PLAYHEAD_COLOR);
            gc.setLineWidth(2);
            gc.strokeLine(x, RULER_HEIGHT, x, height);
        }
    }

    private void drawPlayheadTriangle(GraphicsContext gc) {
        double x = TRACK_LABEL_WIDTH + (timeline.getCurrentTime() * pixelsPerSecond) - scrollOffsetX;

        if (x >= TRACK_LABEL_WIDTH && x <= canvas.getWidth()) {
            // Line in ruler area
            gc.setStroke(PLAYHEAD_COLOR);
            gc.setLineWidth(2);
            gc.strokeLine(x, 0, x, RULER_HEIGHT);

            // Triangle at top
            gc.setFill(PLAYHEAD_COLOR);
            double[] xPoints = {x - 6, x + 6, x};
            double[] yPoints = {0, 0, 10};
            gc.fillPolygon(xPoints, yPoints, 3);
        }
    }

    // ========================================================================
    // Mouse Handling
    // ========================================================================

    private void handleMousePressed(MouseEvent e) {
        double x = e.getX();
        double y = e.getY();

        if (e.getButton() == MouseButton.PRIMARY) {
            // Check if clicking on a keyframe
            KeyframeHandle handle = findKeyframeAt(x, y);

            if (handle != null) {
                selectedKeyframe = handle;
                isDragging = true;
                dragStartX = x;
                dragStartTime = handle.time;
                updateEasingComboFromSelection();
                redraw();
            } else if (y < RULER_HEIGHT || x < TRACK_LABEL_WIDTH) {
                // Click on ruler - seek to time
                if (x >= TRACK_LABEL_WIDTH) {
                    double time = (x - TRACK_LABEL_WIDTH + scrollOffsetX) / pixelsPerSecond;
                    time = Math.max(0, Math.min(timeline.getDuration(), time));
                    timeline.setCurrentTime(time);
                    updateAndRender();
                }
            } else {
                // Click on empty track area - seek and deselect
                selectedKeyframe = null;
                double time = (x - TRACK_LABEL_WIDTH + scrollOffsetX) / pixelsPerSecond;
                time = Math.max(0, Math.min(timeline.getDuration(), time));
                timeline.setCurrentTime(time);
                updateAndRender();
            }
        }
    }

    private void handleMouseDragged(MouseEvent e) {
        if (isDragging && selectedKeyframe != null) {
            double deltaX = e.getX() - dragStartX;
            double deltaTime = deltaX / pixelsPerSecond;
            double newTime = Math.max(0, Math.min(timeline.getDuration(), dragStartTime + deltaTime));

            // Move the keyframe
            AnimationTrack<Object> track = timeline.getTrack(selectedKeyframe.trackName);
            if (track != null) {
                Keyframe<?> kf = track.getKeyframeAt(selectedKeyframe.time);
                if (kf != null && Math.abs(newTime - selectedKeyframe.time) > 0.001) {
                    // Remove old and add at new time
                    Object value = kf.getValue();
                    Easing easing = kf.getEasing();
                    track.removeKeyframe(selectedKeyframe.time);
                    track.setKeyframe(newTime, value, easing);
                    selectedKeyframe = new KeyframeHandle(selectedKeyframe.trackName, newTime);
                    // Apply changes and re-render the scene
                    updateAndRender();
                }
            }
        } else if (e.getY() < RULER_HEIGHT && e.getX() >= TRACK_LABEL_WIDTH) {
            // Drag on ruler - scrub
            double time = (e.getX() - TRACK_LABEL_WIDTH + scrollOffsetX) / pixelsPerSecond;
            time = Math.max(0, Math.min(timeline.getDuration(), time));
            timeline.setCurrentTime(time);
            updateAndRender();
        }
    }

    private void handleMouseReleased(MouseEvent e) {
        if (isDragging) {
            isDragging = false;
            // Snap to nearest frame if close enough
            if (selectedKeyframe != null) {
                double frameTime = Math.round(selectedKeyframe.time * timeline.getFrameRate()) / timeline.getFrameRate();
                if (Math.abs(frameTime - selectedKeyframe.time) < 0.02) {
                    // Snap
                    AnimationTrack<Object> track = timeline.getTrack(selectedKeyframe.trackName);
                    if (track != null) {
                        Keyframe<?> kf = track.getKeyframeAt(selectedKeyframe.time);
                        if (kf != null) {
                            Object value = kf.getValue();
                            Easing easing = kf.getEasing();
                            track.removeKeyframe(selectedKeyframe.time);
                            track.setKeyframe(frameTime, value, easing);
                            selectedKeyframe = new KeyframeHandle(selectedKeyframe.trackName, frameTime);
                        }
                    }
                }
            }
            // Apply changes and re-render the scene
            updateAndRender();
        }
    }

    private void handleMouseMoved(MouseEvent e) {
        KeyframeHandle newHover = findKeyframeAt(e.getX(), e.getY());
        if (!Objects.equals(newHover, hoveredKeyframe)) {
            hoveredKeyframe = newHover;
            redraw();
        }

        // Update cursor
        if (newHover != null || e.getY() < RULER_HEIGHT) {
            canvas.setCursor(javafx.scene.Cursor.HAND);
        } else {
            canvas.setCursor(javafx.scene.Cursor.DEFAULT);
        }
    }

    private KeyframeHandle findKeyframeAt(double mouseX, double mouseY) {
        double y = RULER_HEIGHT - scrollOffsetY;

        for (TrackInfo info : visibleTracks) {
            AnimationTrack<?> track = timeline.getTrack(info.trackName);
            if (track != null && mouseY >= y && mouseY < y + TRACK_HEIGHT && mouseY >= RULER_HEIGHT) {
                double centerY = y + TRACK_HEIGHT / 2;

                for (Keyframe<?> kf : track.getKeyframes()) {
                    double kfX = TRACK_LABEL_WIDTH + (kf.getTime() * pixelsPerSecond) - scrollOffsetX;

                    // Check if mouse is within diamond bounds
                    double dx = Math.abs(mouseX - kfX);
                    double dy = Math.abs(mouseY - centerY);
                    if (dx + dy <= KEYFRAME_SIZE) {
                        return new KeyframeHandle(info.trackName, kf.getTime());
                    }
                }
            }
            y += TRACK_HEIGHT;
        }
        return null;
    }

    private void deleteSelectedKeyframe() {
        if (selectedKeyframe != null) {
            AnimationTrack<?> track = timeline.getTrack(selectedKeyframe.trackName);
            if (track != null) {
                // Find and remove keyframe with tolerance for floating point comparison
                double targetTime = selectedKeyframe.time;
                Double keyToRemove = null;
                for (Keyframe<?> kf : track.getKeyframes()) {
                    if (Math.abs(kf.getTime() - targetTime) < 0.001) {
                        keyToRemove = kf.getTime();
                        break;
                    }
                }
                if (keyToRemove != null) {
                    track.removeKeyframe(keyToRemove);
                }
                selectedKeyframe = null;
                // Apply changes and re-render the scene
                updateAndRender();
            }
        }
    }

    /**
     * Update the easing of the currently selected keyframe.
     */
    @SuppressWarnings("unchecked")
    private void updateSelectedKeyframeEasing(Easing newEasing) {
        if (selectedKeyframe == null || newEasing == null) return;

        AnimationTrack<Object> track = timeline.getTrack(selectedKeyframe.trackName);
        if (track != null) {
            Keyframe<?> kf = track.getKeyframeAt(selectedKeyframe.time);
            if (kf != null) {
                // Re-add keyframe with new easing
                Object value = kf.getValue();
                track.removeKeyframe(selectedKeyframe.time);
                track.setKeyframe(selectedKeyframe.time, value, newEasing);
                redraw();
                // Trigger re-render to show updated interpolation
                if (onRenderRequest != null) {
                    onRenderRequest.run();
                }
            }
        }
    }

    /**
     * Update the easing combo to show the selected keyframe's easing.
     */
    private void updateEasingComboFromSelection() {
        if (selectedKeyframe != null) {
            AnimationTrack<?> track = timeline.getTrack(selectedKeyframe.trackName);
            if (track != null) {
                Keyframe<?> kf = track.getKeyframeAt(selectedKeyframe.time);
                if (kf != null) {
                    // Temporarily disable the action to avoid triggering update
                    var handler = easingCombo.getOnAction();
                    easingCombo.setOnAction(null);
                    easingCombo.setValue(kf.getEasing());
                    easingCombo.setOnAction(handler);
                }
            }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private double calculateTickInterval() {
        // Aim for ticks every ~50-100 pixels
        double targetPixels = 80;
        double rawInterval = targetPixels / pixelsPerSecond;

        // Snap to nice values: 0.1, 0.25, 0.5, 1, 2, 5, 10, etc.
        double[] niceValues = {0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60};
        for (double nice : niceValues) {
            if (rawInterval <= nice) return nice;
        }
        return 60;
    }

    private String formatTime(double time) {
        if (time < 60) {
            return String.format("%.1fs", time);
        } else {
            int min = (int) (time / 60);
            double sec = time % 60;
            return String.format("%d:%04.1f", min, sec);
        }
    }

    private void updateTimeLabels() {
        timeLabel.setText(String.format("%.3fs", timeline.getCurrentTime()));
        frameLabel.setText(String.format("Frame %d/%d", timeline.getCurrentFrame(), timeline.getTotalFrames()));
    }

    private void updatePlayButton() {
        playButton.setText(timeline.isPlaying() ? "\u23F8" : "\u25B6");
    }

    private void updateAndRender() {
        redraw();
        if (onTimeChange != null) {
            onTimeChange.accept(timeline.getCurrentTime());
        }
        if (onRenderRequest != null) {
            onRenderRequest.run();
        }
    }

    // ========================================================================
    // Public API
    // ========================================================================

    public void setOnRenderRequest(Runnable callback) {
        this.onRenderRequest = callback;
    }

    public void setOnTimeChange(Consumer<Double> callback) {
        this.onTimeChange = callback;
    }

    public void setOnKeyframeAdded(Runnable callback) {
        this.onKeyframeAdded = callback;
    }

    public void setOnKeyframeUpdated(Runnable callback) {
        this.onKeyframeUpdated = callback;
    }

    public Timeline getTimeline() {
        return timeline;
    }

    public Easing getSelectedEasing() {
        return easingCombo.getValue();
    }

    /**
     * Get the currently selected track name, or null if no keyframe is selected.
     */
    public String getSelectedTrackName() {
        return selectedKeyframe != null ? selectedKeyframe.trackName : null;
    }

    /**
     * Get the time of the currently selected keyframe, or -1 if none selected.
     */
    public double getSelectedKeyframeTime() {
        return selectedKeyframe != null ? selectedKeyframe.time : -1;
    }

    public void refresh() {
        redraw();
    }

    // ========================================================================
    // Inner classes
    // ========================================================================

    private static class TrackInfo {
        final String trackName;
        final String displayName;
        final Color color;

        TrackInfo(String trackName, String displayName, Color color) {
            this.trackName = trackName;
            this.displayName = displayName;
            this.color = color;
        }
    }

    private static class KeyframeHandle {
        final String trackName;
        final double time;

        KeyframeHandle(String trackName, double time) {
            this.trackName = trackName;
            this.time = time;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            KeyframeHandle that = (KeyframeHandle) o;
            return Math.abs(that.time - time) < 0.001 && trackName.equals(that.trackName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(trackName, Math.round(time * 1000));
        }
    }
}
