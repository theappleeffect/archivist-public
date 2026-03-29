package com.archivist.command.commands;

import com.archivist.command.Command;

import java.util.function.Consumer;

/**
 * Clears the console output.
 */
public final class ClearCommand implements Command {

    private final Runnable clearAction;

    public ClearCommand(Runnable clearAction) {
        this.clearAction = clearAction;
    }

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String description() {
        return "Clears the console output";
    }

    @Override
    public void execute(String[] args, Consumer<String> output) {
        clearAction.run();
    }
}
