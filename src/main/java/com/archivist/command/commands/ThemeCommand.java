package com.archivist.command.commands;

import com.archivist.command.Command;
import com.archivist.config.ArchivistConfig;
import com.archivist.config.ConfigManager;
import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.ThemeManager;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Switches the active color theme.
 */
public final class ThemeCommand implements Command {

    private final Supplier<ArchivistConfig> configSupplier;

    public ThemeCommand(Supplier<ArchivistConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    @Override
    public String name() {
        return "theme";
    }

    @Override
    public String description() {
        return "Switches color theme (theme <name> | theme list)";
    }

    @Override
    public void execute(String[] args, Consumer<String> output) {
        ThemeManager manager = ThemeManager.getInstance();

        if (args.length == 0) {
            output.accept("Current theme: " + ColorScheme.get().name());
            output.accept("Usage: theme <name> | theme list");
            return;
        }

        String arg = args[0].toLowerCase();

        if ("list".equals(arg)) {
            output.accept("=== Available Themes ===");
            for (String themeName : manager.getThemes().keySet()) {
                String marker = themeName.equalsIgnoreCase(ColorScheme.get().name()) ? " (active)" : "";
                output.accept("  - " + themeName + marker);
            }
            return;
        }

        ColorScheme theme = manager.getTheme(arg);
        if (theme == null) {
            output.accept("Unknown theme: " + arg + ". Use 'theme list' to see available themes.");
            return;
        }

        ColorScheme.setActive(theme);

        ArchivistConfig config = configSupplier.get();
        if (config != null) {
            config.activeTheme = theme.name();
            ConfigManager.save(config);
        }

        output.accept("Theme switched to: " + theme.name());
    }

    @Override
    public List<String> complete(String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 1 ? args[0].toLowerCase() : "";
            ThemeManager manager = ThemeManager.getInstance();
            List<String> names = new java.util.ArrayList<>(manager.getThemes().keySet().stream()
                    .filter(n -> n.startsWith(prefix))
                    .toList());
            if ("list".startsWith(prefix)) {
                names.add("list");
            }
            return names;
        }
        return List.of();
    }
}
