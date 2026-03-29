package com.archivist.command.commands;

import com.archivist.api.automation.TaskRunner;
import com.archivist.api.automation.EntityInspectTask;
import com.archivist.command.Command;
import com.archivist.detection.SessionConfidence;

import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Console command to start/stop/query NPC interaction automation.
 */
public final class NpcCommand implements Command {

    private final Supplier<TaskRunner> engineSupplier;
    private final Supplier<SessionConfidence> confidenceSupplier;

    public NpcCommand(Supplier<TaskRunner> engineSupplier,
                      Supplier<SessionConfidence> confidenceSupplier) {
        this.engineSupplier = engineSupplier;
        this.confidenceSupplier = confidenceSupplier;
    }

    @Override
    public String name() {
        return "npc";
    }

    @Override
    public String description() {
        return "NPC interaction automation (npc | npc stop | npc status)";
    }

    @Override
    public void execute(String[] args, Consumer<String> output) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player == null) {
            output.accept("Not connected to a server.");
            return;
        }

        TaskRunner engine = engineSupplier.get();
        if (engine == null) {
            output.accept("Automation engine not available.");
            return;
        }

        String subCommand = args.length > 0 ? args[0].toLowerCase() : "start";

        switch (subCommand) {
            case "stop" -> {
                engine.stop();
                output.accept("NPC automation stopped.");
            }
            case "status" -> {
                output.accept("State: " + engine.getState());
                output.accept("Current: " + engine.getCurrentTaskDescription());
                output.accept("Remaining tasks: " + engine.getRemainingTasks());
                double conf = confidenceSupplier.get().getConfidence();
                String label = conf > 0.7 ? "REAL SERVER" : conf > 0.4 ? "UNCERTAIN" : "LOBBY";
                output.accept("Confidence: " + String.format("%.0f%%", conf * 100) + " (" + label + ")");
            }
            case "force" -> {
                // Force NPC interaction regardless of lobby confidence
                engine.addTask(new EntityInspectTask(true));
                engine.start();
                output.accept("NPC interaction started (forced). Scanning for NPCs within 32 blocks...");
                output.accept("Lobby check bypassed. Press any key to abort.");
            }
            default -> {
                // Start NPC interaction
                double conf = confidenceSupplier.get().getConfidence();
                if (conf >= 0.4) {
                    String label = conf > 0.7 ? "REAL SERVER" : "UNCERTAIN";
                    output.accept("Warning: confidence is " + String.format("%.0f%%", conf * 100)
                            + " (" + label + "). NPC tasks are lobby-only and may be skipped.");
                    output.accept("Use \"npc force\" to bypass the lobby check.");
                }

                engine.addTask(new EntityInspectTask());
                engine.start();
                output.accept("NPC interaction started. Scanning for NPCs within 32 blocks...");
                output.accept("Press any key to abort.");
            }
        }
    }

    @Override
    public List<String> complete(String[] args) {
        if (args.length <= 1) {
            return List.of("start", "stop", "status", "force");
        }
        return List.of();
    }
}
