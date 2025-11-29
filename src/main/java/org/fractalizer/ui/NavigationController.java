package org.fractalizer.ui;

import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import org.fractalizer.engine.Camera;
import org.fractalizer.ui.panels.RenderCallback;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Handles keyboard and mouse navigation for fractal exploration.
 * Separates input handling from main application logic.
 */
public class NavigationController {

    private final StackPane imageContainer;
    private final Supplier<Camera> cameraSupplier;
    private final RenderCallback renderCallback;
    private final Runnable renderFullCallback;
    private final Runnable resetCameraCallback;
    private final Consumer<Float> speedChangeCallback;

    // Input state
    private final Set<KeyCode> pressedKeys = new HashSet<>();
    private boolean isDragging = false;
    private double dragStartX, dragStartY;

    public NavigationController(StackPane imageContainer,
                                 Supplier<Camera> cameraSupplier,
                                 RenderCallback renderCallback,
                                 Runnable renderFullCallback,
                                 Runnable resetCameraCallback,
                                 Consumer<Float> speedChangeCallback) {
        this.imageContainer = imageContainer;
        this.cameraSupplier = cameraSupplier;
        this.renderCallback = renderCallback;
        this.renderFullCallback = renderFullCallback;
        this.resetCameraCallback = resetCameraCallback;
        this.speedChangeCallback = speedChangeCallback;

        setupKeyboardControls();
        setupMouseControls();
    }

    private void setupKeyboardControls() {
        imageContainer.setOnKeyPressed(e -> {
            // Track navigation keys
            if (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN ||
                e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT ||
                e.getCode() == KeyCode.PAGE_UP || e.getCode() == KeyCode.PAGE_DOWN) {
                pressedKeys.add(e.getCode());
                e.consume();
            }

            // R to reset camera
            if (e.getCode() == KeyCode.R) {
                resetCameraCallback.run();
                e.consume();
            }

            // Space to render full quality
            if (e.getCode() == KeyCode.SPACE) {
                renderFullCallback.run();
                e.consume();
            }

            // Q/E for roll
            if (e.getCode() == KeyCode.Q) {
                getCamera().roll(-1);
                renderCallback.requestRender();
                e.consume();
            }
            if (e.getCode() == KeyCode.E) {
                getCamera().roll(1);
                renderCallback.requestRender();
                e.consume();
            }
        });

        imageContainer.setOnKeyReleased(e -> {
            pressedKeys.remove(e.getCode());
        });

        // Clear pressed keys when focus is lost to prevent stuck keys
        imageContainer.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                pressedKeys.clear();
            }
        });
    }

    private void setupMouseControls() {
        // Start drag on mouse press
        imageContainer.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                isDragging = true;
                dragStartX = e.getScreenX();
                dragStartY = e.getScreenY();
                imageContainer.setCursor(Cursor.CLOSED_HAND);
            }
        });

        // Handle drag movement
        imageContainer.setOnMouseDragged(e -> {
            if (isDragging) {
                double deltaX = e.getScreenX() - dragStartX;
                double deltaY = e.getScreenY() - dragStartY;

                // Apply rotation
                getCamera().rotate((float) deltaX, (float) deltaY);
                renderCallback.requestRender();

                // Update start position for next delta
                dragStartX = e.getScreenX();
                dragStartY = e.getScreenY();
            }
        });

        // End drag on mouse release
        imageContainer.setOnMouseReleased(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                isDragging = false;
                imageContainer.setCursor(Cursor.OPEN_HAND);
            }
        });

        // Change cursor when entering/leaving image area
        imageContainer.setOnMouseEntered(e -> {
            if (!isDragging) {
                imageContainer.setCursor(Cursor.OPEN_HAND);
            }
            imageContainer.requestFocus();
        });

        imageContainer.setOnMouseExited(e -> {
            if (!isDragging) {
                imageContainer.setCursor(Cursor.DEFAULT);
            }
        });

        // Scroll for movement speed adjustment
        imageContainer.setOnScroll(e -> {
            double delta = e.getDeltaY() > 0 ? 1.1 : 0.9;
            float newSpeed = getCamera().getMoveSpeed() * (float) delta;
            newSpeed = Math.max(0.001f, Math.min(1.0f, newSpeed));
            getCamera().setMoveSpeed(newSpeed);
            speedChangeCallback.accept(newSpeed);
        });
    }

    /**
     * Process keyboard input - should be called from animation timer.
     * @return true if camera moved
     */
    public boolean processKeyboardInput() {
        boolean moved = false;
        Camera camera = getCamera();

        // Arrow keys for movement
        if (pressedKeys.contains(KeyCode.UP)) {
            camera.moveForward(1);
            moved = true;
        }
        if (pressedKeys.contains(KeyCode.DOWN)) {
            camera.moveForward(-1);
            moved = true;
        }
        if (pressedKeys.contains(KeyCode.LEFT)) {
            camera.strafe(-1);
            moved = true;
        }
        if (pressedKeys.contains(KeyCode.RIGHT)) {
            camera.strafe(1);
            moved = true;
        }

        // Page Up/Down for vertical movement
        if (pressedKeys.contains(KeyCode.PAGE_UP)) {
            camera.moveUp(1);
            moved = true;
        }
        if (pressedKeys.contains(KeyCode.PAGE_DOWN)) {
            camera.moveUp(-1);
            moved = true;
        }

        return moved;
    }

    private Camera getCamera() {
        return cameraSupplier.get();
    }
}