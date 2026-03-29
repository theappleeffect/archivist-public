package com.archivist.command.commands;

import com.archivist.command.Command;

import java.util.function.Consumer;

/**
 * Toggles GUI inspector mode.
 */
public final class InspectorCommand implements Command {

    private final Runnable toggleAction;

    public InspectorCommand(Runnable toggleAction) {
        this.toggleAction = toggleAction;
    }

    @Override
    public String name() {
        return "inspector";
    }

    @Override
    public String description() {
        return "Toggles GUI inspector mode";
    }

    @Override
    public void execute(String[] args, Consumer<String> output) {
        if (toggleAction == null) {
            output.accept("Inspector not available.");
            return;
        }
        toggleAction.run();
        output.accept("Inspector toggled.");
    }
}
