package com.archivist.api.automation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * Rotates through a list of /server targets. For each:
 * sends the command, waits for transfer, waits for detection to settle,
 * then moves to next.
 *
 * <p>Does NOT require lobby — server rotation is just sending /server commands.</p>
 */
public class EndpointSwitchTask implements TickTask {

    private enum Phase { SENDING, WAITING_TRANSFER, WAITING_SETTLE, DELAY, DONE }

    private final List<String> servers;
    private int currentIndex = 0;
    private Phase phase = Phase.SENDING;
    private int ticksInPhase = 0;
    private boolean complete = false;
    private final Runnable onServerScanned; // callback after each server settles

    // Timing (in ticks)
    private static final int TRANSFER_TIMEOUT = 200;  // 10 seconds
    private static final int SETTLE_TIME = 300;        // 15 seconds for confidence
    private final int delayMin;
    private final int delayMax;
    private int currentDelay;

    public EndpointSwitchTask(List<String> servers, int delayMinMs, int delayMaxMs, Runnable onServerScanned) {
        this.servers = List.copyOf(servers);
        this.delayMin = JitteredTimer.msToTicks(delayMinMs);
        this.delayMax = JitteredTimer.msToTicks(delayMaxMs);
        this.onServerScanned = onServerScanned;
    }

    @Override
    public void start(Minecraft mc) {
        if (servers.isEmpty()) {
            complete = true;
            return;
        }
        currentIndex = 0;
        phase = Phase.SENDING;
        ticksInPhase = 0;
    }

    @Override
    public void tick(Minecraft mc) {
        if (complete) return;
        ticksInPhase++;

        switch (phase) {
            case SENDING -> {
                LocalPlayer player = mc.player;
                if (player != null && currentIndex < servers.size()) {
                    String target = servers.get(currentIndex);
                    player.connection.sendChat("/server " + target);
                    phase = Phase.WAITING_TRANSFER;
                    ticksInPhase = 0;
                } else {
                    complete = true;
                }
            }
            case WAITING_TRANSFER -> {
                // Wait for command tree (detected externally) or timeout
                if (ticksInPhase >= TRANSFER_TIMEOUT) {
                    // Timeout — move to settle phase anyway
                    phase = Phase.WAITING_SETTLE;
                    ticksInPhase = 0;
                }
                // Note: externally, the pipeline resets command tree on transfer.
                // We just wait a fixed time here for simplicity.
            }
            case WAITING_SETTLE -> {
                if (ticksInPhase >= SETTLE_TIME) {
                    // Server scanned — trigger callback
                    if (onServerScanned != null) {
                        onServerScanned.run();
                    }
                    // Move to delay before next server
                    currentDelay = JitteredTimer.nextDelay(delayMin * 50, delayMax * 50) / 50;
                    if (currentDelay < 1) currentDelay = delayMin;
                    phase = Phase.DELAY;
                    ticksInPhase = 0;
                }
            }
            case DELAY -> {
                if (ticksInPhase >= currentDelay) {
                    currentIndex++;
                    if (currentIndex >= servers.size()) {
                        complete = true;
                    } else {
                        phase = Phase.SENDING;
                        ticksInPhase = 0;
                    }
                }
            }
            case DONE -> complete = true;
        }
    }

    @Override
    public void abort() { complete = true; }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public String getDescription() {
        if (currentIndex < servers.size()) {
            return "Server rotation " + (currentIndex + 1) + "/" + servers.size() + ": " + servers.get(currentIndex);
        }
        return "Server rotation complete";
    }

    @Override
    public boolean requiresLobby() { return false; }
}
