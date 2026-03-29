package com.archivist.command.commands;

import com.archivist.command.Command;
import com.archivist.data.ServerSession;
import com.archivist.detection.DetectionPipeline;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.Minecraft;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Triggers a re-scan of the current server using cached data.
 */
public final class ScanCommand implements Command {

    private final Supplier<DetectionPipeline> pipelineSupplier;
    private final Supplier<ServerSession> sessionSupplier;

    public ScanCommand(Supplier<DetectionPipeline> pipelineSupplier,
                       Supplier<ServerSession> sessionSupplier) {
        this.pipelineSupplier = pipelineSupplier;
        this.sessionSupplier = sessionSupplier;
    }

    @Override
    public String name() {
        return "scan";
    }

    @Override
    public String description() {
        return "Triggers a re-scan of the current server";
    }

    @Override
    public void execute(String[] args, Consumer<String> output) {
        ServerSession session = sessionSupplier.get();
        if (session == null) {
            output.accept("Not connected to any server.");
            return;
        }

        DetectionPipeline pipeline = pipelineSupplier.get();
        if (pipeline == null) {
            output.accept("Detection pipeline not available.");
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        CommandDispatcher<?> dispatcher = null;
        if (mc.player != null && mc.player.connection != null) {
            dispatcher = mc.player.connection.getCommands();
        }

        if (dispatcher == null) {
            output.accept("No command tree available — cannot re-scan.");
            return;
        }

        output.accept("Re-scanning with cached data...");
        pipeline.rescan(session, dispatcher);
        output.accept("Re-scan complete. Results updated in the console.");
    }
}
