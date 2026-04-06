package com.archivist.meteor;

import com.archivist.ArchivistMod;
import com.archivist.data.ServerSession;
import com.archivist.data.LogExporter;
import com.archivist.account.AccountManager;
import com.archivist.account.AccountEntry;
import com.archivist.api.automation.SequenceHandler;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.List;

public class ArchivistMeteorAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    private static final Category CATEGORY = new Category("Archivist");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Archivist Meteor Addon");

        Modules.get().registerCategory(CATEGORY);

        Modules.get().add(new InfoModule());
        Modules.get().add(new PluginsModule());
        Modules.get().add(new ExportModule());
        Modules.get().add(new AccountsModule());
        Modules.get().add(new AutomationModule());

        LOG.info("Archivist Meteor Addon initialized");
    }

    @Override
    public String getPackage() {
        return "com.archivist.meteor";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("ArchivistMod", "archivist-public");
    }

    public static class InfoModule extends Module {
        public InfoModule() {
            super(CATEGORY, "info", "Display Archivist server detection info");
        }

        @Override
        public void onActivate() {
            ArchivistMod mod = ArchivistMod.getInstance();
            if (mod == null) return;
            ServerSession session = mod.getSession();
            if (session == null) return;

            info("Server: " + session.getDomain() + ":" + session.getPort());
            info("Brand: " + (session.getServerSoftware() != null ? session.getServerSoftware() : session.getBrand()));
            info("Plugins: " + session.getPlugins().size());
        }
    }

    public static class PluginsModule extends Module {
        public PluginsModule() {
            super(CATEGORY, "plugins", "List detected plugins");
        }

        @Override
        public void onActivate() {
            ArchivistMod mod = ArchivistMod.getInstance();
            if (mod == null) return;
            ServerSession session = mod.getSession();
            if (session == null) return;

            info("Detected " + session.getPlugins().size() + " plugins:");
            for (String plugin : session.getPlugins()) {
                info("  - " + plugin);
            }
        }
    }

    public static class ExportModule extends Module {
        public ExportModule() {
            super(CATEGORY, "export", "Export server data to JSON");
        }

        @Override
        public void onActivate() {
            ArchivistMod mod = ArchivistMod.getInstance();
            if (mod == null) {
                error("Archivist not initialized");
                return;
            }
            ServerSession session = mod.getSession();
            if (session == null) {
                error("No active server session");
                return;
            }

            try {
                var logData = session.toLogData();
                String json = LogExporter.exportJson(logData, List.of());
                var path = LogExporter.saveToFile(json, "json");
                info("Exported to: " + path.getFileName());

                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    mc.keyboardHandler.setClipboard(json);
                    info("Copied to clipboard");
                }
            } catch (Exception e) {
                error("Export failed: " + e.getMessage());
            }
        }
    }

    public static class AccountsModule extends Module {
        public AccountsModule() {
            super(CATEGORY, "accounts", "List saved accounts");
        }

        @Override
        public void onActivate() {
            ArchivistMod mod = ArchivistMod.getInstance();
            if (mod == null || mod.getAccountManager() == null) {
                error("Account manager not available");
                return;
            }

            AccountManager manager = mod.getAccountManager();
            List<AccountEntry> accounts = manager.getAccounts();
            String activeId = manager.getActiveAccountId();

            info("Accounts (" + accounts.size() + "):");
            for (AccountEntry account : accounts) {
                String prefix = account.id.equals(activeId) ? ">" : " ";
                info(prefix + " " + account.username + " (" + account.type + ")");
            }
        }
    }

    public static class AutomationModule extends Module {
        public AutomationModule() {
            super(CATEGORY, "automation", "Show automation status");
        }

        @Override
        public void onActivate() {
            ArchivistMod mod = ArchivistMod.getInstance();
            if (mod == null || mod.getSequenceHandler() == null) {
                error("Automation not available");
                return;
            }

            SequenceHandler handler = mod.getSequenceHandler();
            SequenceHandler.Phase phase = handler.getPhase();

            String status = switch (phase) {
                case IDLE -> "Idle";
                case CONNECTING -> "Connecting: " + handler.getCurrentServerDisplay();
                case SCANNING -> "Scanning: " + handler.getCurrentServerDisplay();
                case DISCONNECTING -> "Disconnecting";
                case DELAY -> "Delay between servers";
                case COMPLETED -> "Completed";
                case ERROR -> "Error";
            };

            info("Automation: " + status);
        }
    }
}
