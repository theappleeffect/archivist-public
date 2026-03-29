package com.archivist.api.automation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scans for NPC-like entities in lobby areas, walks toward them, and right-clicks.
 * LOBBY ONLY — refuses to run when session confidence >= 0.4.
 *
 * <p>Walk logic: simple straight-line approach. Rotate to face entity, hold forward
 * key, stop when in range. No Baritone dependency.</p>
 */
public class EntityInspectTask implements TickTask {

    private enum Phase { SCANNING, ROTATING, WALKING, CLICKING, WAITING_GUI, COOLDOWN, DONE }

    private final boolean force;
    private Phase phase = Phase.SCANNING;
    private int ticksInPhase = 0;
    private boolean complete = false;
    private final List<Entity> targetEntities = new ArrayList<>();
    private int currentTargetIndex = 0;
    private Entity currentTarget;

    // Walking
    private Vec3 lastPlayerPos;
    private int stuckTicks = 0;
    private static final int STUCK_THRESHOLD = 40;
    private static final double INTERACT_RANGE = 3.5;
    private static final double INITIAL_SCAN_RANGE = 32.0;
    private static final double MAX_SCAN_RANGE = 128.0;
    private double currentScanRange = INITIAL_SCAN_RANGE;

    public EntityInspectTask() { this(false); }
    public EntityInspectTask(boolean force) { this.force = force; }

    // Timing
    private static final int ROTATE_TICKS = 5;
    private static final int GUI_WAIT_TICKS = 15;
    private int cooldownTicks;

    @Override
    public void start(Minecraft mc) {
        phase = Phase.SCANNING;
        ticksInPhase = 0;
        targetEntities.clear();
        currentTargetIndex = 0;
        currentScanRange = INITIAL_SCAN_RANGE;
    }

    @Override
    public void tick(Minecraft mc) {
        if (complete) return;
        ticksInPhase++;
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) { complete = true; return; }

        switch (phase) {
            case SCANNING -> {
                // Find named non-player entities in range
                targetEntities.clear();
                for (Entity entity : mc.level.entitiesForRendering()) {
                    if (entity == player) continue;
                    if (entity instanceof Player) continue; // skip real players
                    if (entity.distanceTo(player) > currentScanRange) continue;

                    boolean isNamedLiving = entity instanceof LivingEntity && entity.hasCustomName();
                    boolean isHologram = entity instanceof ArmorStand as && as.hasCustomName() && as.isInvisible();

                    if (isNamedLiving && !isHologram) {
                        targetEntities.add(entity);
                    }
                }

                // Sort by distance (closest first)
                targetEntities.sort(Comparator.comparingDouble(e -> e.distanceTo(player)));

                if (targetEntities.isEmpty()) {
                    if (currentScanRange < MAX_SCAN_RANGE) {
                        currentScanRange *= 2; // 32 → 64 → 128
                        ticksInPhase = 0;      // re-scan at wider range
                    } else {
                        complete = true;       // nothing within 128 blocks
                    }
                } else {
                    currentTargetIndex = 0;
                    advanceToNextTarget(player);
                }
            }
            case ROTATING -> {
                // Smoothly rotate to face target
                if (currentTarget == null || !currentTarget.isAlive()) {
                    skipToNext(player);
                    return;
                }
                lookToward(player, currentTarget, ticksInPhase, ROTATE_TICKS);

                if (ticksInPhase >= ROTATE_TICKS) {
                    double dist = player.distanceTo(currentTarget);
                    if (dist <= INTERACT_RANGE) {
                        phase = Phase.CLICKING;
                    } else {
                        phase = Phase.WALKING;
                        lastPlayerPos = player.position();
                        stuckTicks = 0;
                    }
                    ticksInPhase = 0;
                }
            }
            case WALKING -> {
                if (currentTarget == null || !currentTarget.isAlive()) {
                    stopWalking(mc);
                    skipToNext(player);
                    return;
                }

                // Face target
                lookToward(player, currentTarget, 1, 1);

                // Check distance
                double dist = player.distanceTo(currentTarget);
                if (dist <= INTERACT_RANGE) {
                    stopWalking(mc);
                    phase = Phase.CLICKING;
                    ticksInPhase = 0;
                    return;
                }

                // Walk forward
                mc.options.keyUp.setDown(true);

                // Stuck detection
                Vec3 currentPos = player.position();
                if (lastPlayerPos != null && currentPos.distanceTo(lastPlayerPos) < 0.05) {
                    stuckTicks++;
                    if (stuckTicks >= STUCK_THRESHOLD) {
                        stopWalking(mc);
                        skipToNext(player);
                        return;
                    }
                } else {
                    stuckTicks = 0;
                }
                lastPlayerPos = currentPos;

                // Add occasional jitter
                int pause = JitteredTimer.nextMovementPause();
                if (pause > 0) {
                    player.setYRot(player.getYRot() + JitteredTimer.nextLookJitter());
                }
            }
            case CLICKING -> {
                if (currentTarget == null) { skipToNext(player); return; }

                // Right-click the entity
                if (mc.gameMode != null) {
                    mc.gameMode.interact(player, currentTarget, InteractionHand.MAIN_HAND);
                }
                phase = Phase.WAITING_GUI;
                ticksInPhase = 0;
            }
            case WAITING_GUI -> {
                // Wait for GUI to open (detected by screen change)
                if (mc.screen != null && !(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) {
                    // GUI opened — leave it open for ContainerWalkTask to scan
                    complete = true;
                    return;
                }
                if (ticksInPhase >= GUI_WAIT_TICKS) {
                    // No GUI opened — move to next NPC
                    cooldownTicks = JitteredTimer.msToTicks(JitteredTimer.nextDelay(200, 500));
                    phase = Phase.COOLDOWN;
                    ticksInPhase = 0;
                }
            }
            case COOLDOWN -> {
                if (ticksInPhase >= cooldownTicks) {
                    currentTargetIndex++;
                    advanceToNextTarget(player);
                }
            }
            case DONE -> complete = true;
        }
    }

    private void advanceToNextTarget(LocalPlayer player) {
        if (currentTargetIndex >= targetEntities.size()) {
            complete = true;
            return;
        }
        currentTarget = targetEntities.get(currentTargetIndex);
        phase = Phase.ROTATING;
        ticksInPhase = 0;
    }

    private void skipToNext(LocalPlayer player) {
        currentTargetIndex++;
        advanceToNextTarget(player);
    }

    private void stopWalking(Minecraft mc) {
        mc.options.keyUp.setDown(false);
    }

    private void lookToward(LocalPlayer player, Entity target, int tick, int totalTicks) {
        Vec3 eyes = player.getEyePosition();
        Vec3 targetPos = target.position().add(0, target.getEyeHeight() * 0.5, 0);
        Vec3 delta = targetPos.subtract(eyes);

        double dist = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(delta.y, dist)));

        // Smooth interpolation
        float progress = Math.min(1f, (float) tick / totalTicks);
        player.setYRot(lerpAngle(player.getYRot(), targetYaw, progress));
        player.setXRot(lerp(player.getXRot(), targetPitch, progress));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float lerpAngle(float a, float b, float t) {
        float diff = b - a;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        return a + diff * t;
    }

    @Override
    public void abort() {
        complete = true;
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyUp.setDown(false);
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public String getDescription() {
        String target = currentTarget != null && currentTarget.hasCustomName()
                ? currentTarget.getCustomName().getString() : "?";
        return "NPC interaction " + (currentTargetIndex + 1) + "/" + targetEntities.size() + ": " + target;
    }

    @Override
    public boolean requiresLobby() { return !force; }
}
