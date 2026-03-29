package com.archivist.api.automation;

import net.minecraft.client.Minecraft;

/**
 * A single automation task that runs over multiple ticks.
 */
public interface TickTask {

    /** Called once when the task begins. */
    void start(Minecraft mc);

    /** Called every client tick while this task is active. */
    void tick(Minecraft mc);

    /** Called when the task should abort immediately. */
    void abort();

    /** Returns true when the task has finished (success or failure). */
    boolean isComplete();

    /** Human-readable description for GUI display. */
    String getDescription();

    /**
     * Returns true if this task requires lobby environment (confidence < 0.4).
     * Physical interaction tasks (NPC walk, menu nav) return true.
     * Server rotation returns false (it just sends commands).
     */
    default boolean requiresLobby() { return false; }
}
