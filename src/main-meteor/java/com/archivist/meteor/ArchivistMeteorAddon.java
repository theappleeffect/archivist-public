package com.archivist.meteor;

import com.archivist.ArchivistMod;
import com.archivist.config.ArchivistConfig;
import com.archivist.data.ServerSession;
import com.archivist.data.EventBus;
import com.archivist.data.LogExporter;
import com.archivist.account.AccountManager;
import com.archivist.account.AccountEntry;
import com.archivist.api.automation.SequenceHandler;
import com.archivist.api.ApiSyncManager;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;

public class ArchivistMeteorAddon {
    private static final Logger LOG = LogUtils.getLogger();
    private static boolean initialized = false;
    private static boolean meteorPresent = false;

    public static boolean isMeteorPresent() {
        return meteorPresent;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void onInitialize() {
        if (initialized) return;
        initialized = true;

        meteorPresent = FabricLoader.getInstance().isModLoaded("meteor-client");
        
        if (!meteorPresent) {
            LOG.info("Meteor Client not detected - Archivist HUD integration disabled");
            return;
        }

        try {
            LOG.info("Meteor Client detected - initializing Archivist HUD");
            registerWithMeteor();
        } catch (Throwable e) {
            LOG.error("Failed to initialize Archivist Meteor integration", e);
            meteorPresent = false;
        }
    }

    private static void registerWithMeteor() throws Throwable {
        Class<?> hudClass = Class.forName("meteordevelopment.meteorclient.systems.hud.Hud");
        Class<?> hudElementClass = Class.forName("meteordevelopment.meteorclient.systems.hud.HudElement");
        Class<?> modulesClass = Class.forName("meteordevelopment.meteorclient.systems.modules.Modules");
        Class<?> moduleClass = Class.forName("meteordevelopment.meteorclient.systems.modules.Module");
        Class<?> categoryClass = Class.forName("meteordevelopment.meteorclient.addons.Category");
        Class<?> commandsClass = Class.forName("meteordevelopment.meteorclient.commands.Commands");

        Object category = categoryClass.getConstructor(String.class).newInstance("Archivist");
        modulesClass.getMethod("registerCategory", categoryClass).invoke(null, category);

        Object hud = hudClass.getMethod("get").invoke(null);
        Object hudElement = createHudElement(hudElementClass, hudClass);
        hudClass.getMethod("register", hudElementClass).invoke(hud, hudElement);

        registerCommands(commandsClass);

        LOG.info("Archivist HUD registered with Meteor Client");
    }

    private static Object createHudElement(Class<?> hudElementClass, Class<?> hudClass) throws Throwable {
        Class<?>[] innerClasses = hudElementClass.getDeclaredClasses();
        Class<?> infoClass = null;
        for (Class<?> c : hudElementClass.getDeclaredClasses()) {
            if (c.getSimpleName().equals("Info")) {
                infoClass = c;
                break;
            }
        }
        
        if (infoClass == null) {
            LOG.warn("Could not find HudElement.Info class");
            return null;
        }

        final Class<?> finalInfoClass = infoClass;
        final Class<?> fhudElementClass = hudElementClass;
        
        Object info = java.lang.reflect.Proxy.newProxyInstance(
            finalInfoClass.getClassLoader(),
            new Class<?>[] { finalInfoClass },
            (proxy, method, args) -> {
                if (method.getName().equals("group")) {
                    return Class.forName("meteordevelopment.meteorclient.systems.hud.HudGroup")
                        .getField("TOP_LEFT").get(null);
                }
                if (method.getName().equals("name")) return "archivist-info";
                if (method.getName().equals("description")) return "Display detected server information";
                if (method.getName().equals("module")) return null;
                if (method.getName().equals("renderer")) return null;
                return null;
            }
        );

        return fhudElementClass.getDeclaredConstructor(finalInfoClass).newInstance(info);
    }

    private static void registerCommands(Class<?> commandsClass) throws Throwable {
        commandsClass.getMethod("add", Class.forName("meteordevelopment.meteorclient.commands.Command"))
            .invoke(null, createInfoCommand());
        commandsClass.getMethod("add", Class.forName("meteordevelopment.meteorclient.commands.Command"))
            .invoke(null, createPluginsCommand());
        commandsClass.getMethod("add", Class.forName("meteordevelopment.meteorclient.commands.Command"))
            .invoke(null, createExportCommand());
        commandsClass.getMethod("add", Class.forName("meteordevelopment.meteorclient.commands.Command"))
            .invoke(null, createAccountCommand());
        commandsClass.getMethod("add", Class.forName("meteordevelopment.meteorclient.commands.Command"))
            .invoke(null, createAutomationCommand());
    }

    private static Object createInfoCommand() throws Throwable {
        return java.lang.reflect.Proxy.newProxyInstance(
            Thread.currentThread().getContextClassLoader(),
            new Class<?>[] { Class.forName("meteordevelopment.meteorclient.commands.Command") },
            (proxy, method, args) -> {
                if (method.getName().equals("getName")) return "archivist";
                if (method.getName().equals("getDescription")) return "Archivist server detection info";
                if (method.getName().equals("build")) {
                    Object builder = args[0];
                    builder.getClass().getMethod("executes", Runnable.class)
                        .invoke(builder, (Runnable) () -> {
                            ArchivistMod mod = ArchivistMod.getInstance();
                            if (mod == null) return;
                            ServerSession session = mod.getSession();
                            if (session == null) return;
                            
                            String info = String.format(
                                "Server: %s:%d | Brand: %s | Plugins: %d",
                                session.getDomain(),
                                session.getPort(),
                                session.getServerSoftware(),
                                session.getPlugins().size()
                            );
                            sendInfo(info);
                        });
                    return null;
                }
                return null;
            }
        );
    }

    private static Object createPluginsCommand() throws Throwable {
        return java.lang.reflect.Proxy.newProxyInstance(
            Thread.currentThread().getContextClassLoader(),
            new Class<?>[] { Class.forName("meteordevelopment.meteorclient.commands.Command") },
            (proxy, method, args) -> {
                if (method.getName().equals("getName")) return "archivist-plugins";
                if (method.getName().equals("getDescription")) return "List detected plugins";
                if (method.getName().equals("build")) {
                    Object builder = args[0];
                    builder.getClass().getMethod("executes", Runnable.class)
                        .invoke(builder, (Runnable) () -> {
                            ArchivistMod mod = ArchivistMod.getInstance();
                            if (mod == null) return;
                            ServerSession session = mod.getSession();
                            if (session == null) return;
                            
                            Set<String> plugins = session.getPlugins();
                            sendInfo("Detected " + plugins.size() + " plugins:");
                            for (String plugin : plugins) {
                                sendInfo("  - " + plugin);
                            }
                        });
                    return null;
                }
                return null;
            }
        );
    }

    private static Object createExportCommand() throws Throwable {
        return java.lang.reflect.Proxy.newProxyInstance(
            Thread.currentThread().getContextClassLoader(),
            new Class<?>[] { Class.forName("meteordevelopment.meteorclient.commands.Command") },
            (proxy, method, args) -> {
                if (method.getName().equals("getName")) return "archivist-export";
                if (method.getName().equals("getDescription")) return "Export server data";
                if (method.getName().equals("build")) {
                    Object builder = args[0];
                    builder.getClass().getMethod("executes", Runnable.class)
                        .invoke(builder, (Runnable) () -> {
                            ArchivistMod mod = ArchivistMod.getInstance();
                            if (mod == null) return;
                            ServerSession session = mod.getSession();
                            if (session == null) return;
                            
                            var logData = session.toLogData();
                            try {
                                String json = LogExporter.exportJson(logData, List.of());
                                var path = LogExporter.saveToFile(json, "json");
                                sendInfo("Exported to: " + path.getFileName());
                                copyToClipboard(json);
                            } catch (Exception e) {
                                sendError("Export failed: " + e.getMessage());
                            }
                        });
                    return null;
                }
                return null;
            }
        );
    }

    private static Object createAccountCommand() throws Throwable {
        return java.lang.reflect.Proxy.newProxyInstance(
            Thread.currentThread().getContextClassLoader(),
            new Class<?>[] { Class.forName("meteordevelopment.meteorclient.commands.Command") },
            (proxy, method, args) -> {
                if (method.getName().equals("getName")) return "archivist-account";
                if (method.getName().equals("getDescription")) return "Manage accounts";
                if (method.getName().equals("build")) {
                    Object builder = args[0];
                    builder.getClass().getMethod("executes", Runnable.class)
                        .invoke(builder, (Runnable) () -> {
                            ArchivistMod mod = ArchivistMod.getInstance();
                            if (mod == null || mod.getAccountManager() == null) return;
                            
                            AccountManager manager = mod.getAccountManager();
                            List<AccountEntry> accounts = manager.getAccounts();
                            sendInfo("Accounts (" + accounts.size() + "):");
                            for (AccountEntry account : accounts) {
                                String prefix = account.id.equals(manager.getActiveAccountId()) ? ">" : " ";
                                sendInfo(prefix + " " + account.username + " (" + account.type + ")");
                            }
                        });
                    return null;
                }
                return null;
            }
        );
    }

    private static Object createAutomationCommand() throws Throwable {
        return java.lang.reflect.Proxy.newProxyInstance(
            Thread.currentThread().getContextClassLoader(),
            new Class<?>[] { Class.forName("meteordevelopment.meteorclient.commands.Command") },
            (proxy, method, args) -> {
                if (method.getName().equals("getName")) return "archivist-auto";
                if (method.getName().equals("getDescription")) return "Automation controls";
                if (method.getName().equals("build")) {
                    Object builder = args[0];
                    builder.getClass().getMethod("executes", Runnable.class)
                        .invoke(builder, (Runnable) () -> {
                            ArchivistMod mod = ArchivistMod.getInstance();
                            if (mod == null || mod.getSequenceHandler() == null) return;
                            
                            SequenceHandler handler = mod.getSequenceHandler();
                            SequenceHandler.Phase phase = handler.getPhase();
                            String status = phase == SequenceHandler.Phase.IDLE ? "Idle" :
                                          phase == SequenceHandler.Phase.CONNECTING ? "Connecting: " + handler.getCurrentServerDisplay() :
                                          phase == SequenceHandler.Phase.SCANNING ? "Scanning: " + handler.getCurrentServerDisplay() :
                                          phase == SequenceHandler.Phase.DELAY ? "Delay between servers" :
                                          phase.name();
                            sendInfo("Automation: " + status);
                        });
                    return null;
                }
                return null;
            }
        );
    }

    private static void sendInfo(String message) {
        try {
            Class<?> chatUtilsClass = Class.forName("meteordevelopment.meteorclient.utils.player.ChatUtils");
            chatUtilsClass.getMethod("info", String.class, Object[].class).invoke(null, message, new Object[0]);
        } catch (Exception e) {
            LOG.info("[Archivist] " + message);
        }
    }

    private static void sendError(String message) {
        try {
            Class<?> chatUtilsClass = Class.forName("meteordevelopment.meteorclient.utils.player.ChatUtils");
            chatUtilsClass.getMethod("error", String.class, Object[].class).invoke(null, message, new Object[0]);
        } catch (Exception e) {
            LOG.error("[Archivist] " + message);
        }
    }

    private static void copyToClipboard(String text) {
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
        } catch (Exception e) {
            LOG.warn("Failed to copy to clipboard", e);
        }
    }
}
