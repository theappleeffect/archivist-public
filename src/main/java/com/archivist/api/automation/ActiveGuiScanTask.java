package com.archivist.api.automation;

import com.archivist.ArchivistMod;
import com.archivist.config.ArchivistConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

public class ActiveGuiScanTask implements TickTask {

    private enum Phase { SEND_COMMAND, WAIT_GUI, CLOSE_GUI, COOLDOWN, DONE }

    private static final int GUI_WAIT_TICKS = 40;
    private static final int CLOSE_DELAY_TICKS = 5;
    private static final int COOLDOWN_TICKS = 10;

    private final List<String> commands;
    private Phase phase = Phase.SEND_COMMAND;
    private int ticksInPhase = 0;
    private int commandIndex = 0;
    private boolean complete = false;

    public ActiveGuiScanTask(List<String> commands) {
        this.commands = List.copyOf(commands);
    }

    @Override
    public void start(Minecraft mc) {
        commandIndex = 0;
        phase = Phase.SEND_COMMAND;
        ticksInPhase = 0;
        complete = commands.isEmpty();
    }

    @Override
    public void tick(Minecraft mc) {
        if (complete) return;
        ticksInPhase++;
        LocalPlayer player = mc.player;
        if (player == null || mc.getConnection() == null) { complete = true; return; }

        switch (phase) {
            case SEND_COMMAND -> {
                ArchivistConfig mainConfig = ArchivistMod.getInstance().getConfig();
                mainConfig.passiveGuiDetection = true;

                String cmd = commands.get(commandIndex);
                mc.getConnection().sendCommand(cmd);
                phase = Phase.WAIT_GUI;
                ticksInPhase = 0;
            }
            case WAIT_GUI -> {
                if (mc.screen instanceof AbstractContainerScreen<?>) {
                    phase = Phase.CLOSE_GUI;
                    ticksInPhase = 0;
                } else if (ticksInPhase >= GUI_WAIT_TICKS) {
                    advanceCommand();
                }
            }
            case CLOSE_GUI -> {
                if (ticksInPhase >= CLOSE_DELAY_TICKS) {
                    if (player.containerMenu != player.inventoryMenu) {
                        player.closeContainer();
                    }
                    ArchivistConfig mainConfig = ArchivistMod.getInstance().getConfig();
                    mainConfig.passiveGuiDetection = false;
                    advanceCommand();
                }
            }
            case COOLDOWN -> {
                if (ticksInPhase >= COOLDOWN_TICKS) {
                    phase = Phase.SEND_COMMAND;
                    ticksInPhase = 0;
                }
            }
            case DONE -> complete = true;
        }
    }

    private void advanceCommand() {
        commandIndex++;
        if (commandIndex >= commands.size()) {
            ArchivistConfig mainConfig = ArchivistMod.getInstance().getConfig();
            mainConfig.passiveGuiDetection = false;
            phase = Phase.DONE;
            complete = true;
        } else {
            phase = Phase.COOLDOWN;
            ticksInPhase = 0;
        }
    }

    @Override
    public void abort() {
        ArchivistConfig mainConfig = ArchivistMod.getInstance().getConfig();
        mainConfig.passiveGuiDetection = false;
        complete = true;
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public String getDescription() {
        if (commandIndex < commands.size()) {
            return "GUI scan: /" + commands.get(commandIndex) + " (" + (commandIndex + 1) + "/" + commands.size() + ")";
        }
        return "GUI scan complete";
    }
}
