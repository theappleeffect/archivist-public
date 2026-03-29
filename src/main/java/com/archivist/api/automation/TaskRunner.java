package com.archivist.api.automation;

import com.archivist.data.EventBus;
import com.archivist.data.LogEvent;
import com.archivist.detection.SessionConfidence;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * Tick-driven automation coordinator. Runs tasks sequentially from a queue.
 * Any player input immediately aborts all automation (kill switch).
 *
 * <p>NPC walking and menu navigation are LOBBY ONLY — refused when
 * session confidence is above the configured lobby threshold.</p>
 */
public final class TaskRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");

    public enum State {
        IDLE, RUNNING, PAUSED
    }

    private State state = State.IDLE;
    private final Deque<TickTask> taskQueue = new ArrayDeque<>();
    private TickTask currentTask;
    private final EventBus eventBus;
    private final Supplier<SessionConfidence> confidenceSupplier;
    private volatile double lobbyThreshold = 0.4;

    // Kill switch: tracks whether player pressed a key since last tick
    private volatile boolean killRequested = false;

    public TaskRunner(EventBus eventBus, Supplier<SessionConfidence> confidenceSupplier) {
        this.eventBus = eventBus;
        this.confidenceSupplier = confidenceSupplier;
    }

    /** Update the lobby threshold from config. */
    public void setLobbyThreshold(double threshold) {
        this.lobbyThreshold = threshold;
    }

    public State getState() { return state; }

    public void addTask(TickTask task) {
        taskQueue.addLast(task);
    }

    public void clearTasks() {
        taskQueue.clear();
        if (currentTask != null) {
            currentTask.abort();
            currentTask = null;
        }
    }

    /**
     * Start processing the task queue.
     */
    public void start() {
        if (taskQueue.isEmpty() && currentTask == null) return;
        killRequested = false;
        state = State.RUNNING;
        postEvent("Automation started (" + (taskQueue.size() + (currentTask != null ? 1 : 0)) + " tasks)");
    }

    /**
     * Immediately stop all automation. Safe to call from any thread.
     */
    public void stop() {
        killRequested = true;
    }

    public void pause() {
        if (state == State.RUNNING) {
            state = State.PAUSED;
            postEvent("Automation paused");
        }
    }

    public void resume() {
        if (state == State.PAUSED) {
            state = State.RUNNING;
            postEvent("Automation resumed");
        }
    }

    /**
     * Signal from input handler that user pressed a key — triggers kill switch.
     */
    public void onPlayerInput() {
        if (state == State.RUNNING) {
            killRequested = true;
        }
    }

    /**
     * Called every client tick from ArchivistMod.
     */
    public void tick() {
        if (state == State.IDLE || state == State.PAUSED) return;

        // Kill switch
        if (killRequested) {
            killRequested = false;
            if (currentTask != null) {
                currentTask.abort();
                currentTask = null;
            }
            taskQueue.clear();
            state = State.IDLE;
            postEvent("Automation stopped (user input)");
            return;
        }

        // Advance current task
        if (currentTask != null) {
            if (currentTask.isComplete()) {
                postEvent("Task complete: " + currentTask.getDescription());
                currentTask = null;
            } else {
                // Lobby check for physical interaction tasks
                if (currentTask.requiresLobby()) {
                    double conf = confidenceSupplier.get().getConfidence();
                    if (conf >= lobbyThreshold) {
                        postEvent("Skipping task (not in lobby, confidence " +
                                String.format("%.0f%%", conf * 100) + "): " + currentTask.getDescription());
                        currentTask.abort();
                        currentTask = null;
                        // Continue to next task
                    } else {
                        currentTask.tick(Minecraft.getInstance());
                        return;
                    }
                } else {
                    currentTask.tick(Minecraft.getInstance());
                    return;
                }
            }
        }

        // Pick next task
        if (!taskQueue.isEmpty()) {
            currentTask = taskQueue.pollFirst();
            if (currentTask != null) {
                postEvent("Starting task: " + currentTask.getDescription());
                currentTask.start(Minecraft.getInstance());
            }
        } else {
            // All tasks done
            state = State.IDLE;
            postEvent("Automation complete");
        }
    }

    public String getCurrentTaskDescription() {
        return currentTask != null ? currentTask.getDescription() : "idle";
    }

    public int getRemainingTasks() {
        return taskQueue.size() + (currentTask != null ? 1 : 0);
    }

    private void postEvent(String message) {
        LOGGER.info("[Archivist/Automation] {}", message);
        if (eventBus != null) {
            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "[Auto] " + message));
        }
    }
}
