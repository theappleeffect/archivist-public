package com.archivist.command;

import java.util.List;
import java.util.function.Consumer;

/**
 * Interface for console commands in the Archivist GUI.
 */
public interface Command {
    String name();
    String description();
    void execute(String[] args, Consumer<String> output);
    default List<String> complete(String[] args) { return List.of(); }
}
