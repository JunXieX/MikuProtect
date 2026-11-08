package dev.junxiex.mikuprotect;

import dev.junxiex.mikuprotect.api.MikuProtectAPI;
import dev.junxiex.mikuprotect.api.MikuProtectAPIImpl;
import dev.junxiex.mikuprotect.command.CommandService;
import dev.junxiex.mikuprotect.config.MikuConfig;
import dev.junxiex.mikuprotect.listener.ListenerRegistrar;
import dev.junxiex.mikuprotect.service.AlertService;
import dev.junxiex.mikuprotect.service.InspectionService;
import dev.junxiex.mikuprotect.service.LookupService;
import dev.junxiex.mikuprotect.service.PurgeService;
import dev.junxiex.mikuprotect.service.RecordingService;
import dev.junxiex.mikuprotect.service.RollbackService;
import dev.junxiex.mikuprotect.service.UndoService;
import dev.junxiex.mikuprotect.storage.DuckDbStorage;
import dev.junxiex.mikuprotect.storage.MariaDbStorage;
import dev.junxiex.mikuprotect.storage.Storage;
import dev.junxiex.mikuprotect.storage.StorageException;
import dev.junxiex.mikuprotect.util.Scheduler;
import dev.junxiex.mikuprotect.web.WebService;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class MikuProtectPlugin extends JavaPlugin {

    private static MikuProtectPlugin instance;

    private MikuConfig config;
    private Storage storage;
    private RecordingService recording;
    private LookupService lookup;
    private InspectionService inspection;
    private PurgeService purge;
    private UndoService undo;
    private AlertService alerts;
    private RollbackService rollback;
    private CommandService commandService;
    private ListenerRegistrar listenerRegistrar;
    private Object worldEdit;
    private WebService web;
    private MikuProtectAPI api;

    public static MikuProtectPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        Scheduler.init(this);
        saveDefaultConfig();
        reloadConfig();
        try {
            this.config = new MikuConfig(getConfig(), getLogger());
        } catch (RuntimeException exception) {
            getLogger().severe("Invalid configuration: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            this.storage = switch (config.storageType) {
                case DUCKDB -> new DuckDbStorage(config, getDataFolder().toPath(), getLogger());
                case MARIADB -> new MariaDbStorage(config, getLogger());
            };
            this.storage.init();
        } catch (StorageException | RuntimeException exception) {
            getLogger().severe("Storage initialization failed: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.recording = new RecordingService(this, config, storage);
        this.lookup = new LookupService(this, storage);
        this.inspection = new InspectionService(this, config, lookup);
        this.purge = new PurgeService(this, storage);
        this.undo = new UndoService();
        this.alerts = new AlertService(this, config);
        this.rollback = new RollbackService(this, storage);
        this.commandService = new CommandService(this);

        this.recording.start();
        this.listenerRegistrar = new ListenerRegistrar(this, config, recording, alerts, storage, inspection);
        this.listenerRegistrar.register();
        this.commandService.registerCommands();
        Permissions.register(this);

        this.api = new MikuProtectAPIImpl(this);
        getServer().getServicesManager().register(MikuProtectAPI.class, api, this, ServicePriority.Normal);

        enableWorldEdit();
        this.web = new WebService(this, config);
        this.web.start();


        if (config.purgeAuto) {
            long period = 20L * 60L * 60L * 24L;
            Scheduler.timerGlobal(() -> purge.purge(
                    System.currentTimeMillis() - config.purgeDays * 24L * 60L * 60L * 1000L, null, null)
                    .whenComplete((removed, error) -> {
                        if (error == null) {
                            getLogger().info("Auto purge removed " + removed + " records");
                        }
                    }), 20L * 60L, period);
        }

        getLogger().info("MikuProtect enabled with " + storage.type() + "; Folia=" + Scheduler.folia());
    }

    @Override
    public void onDisable() {
        if (web != null) {
            web.stop();
            web = null;
        }
        disableWorldEdit();
        if (listenerRegistrar != null) {
            listenerRegistrar.unregister();
        }
        if (recording != null) {
            recording.stop();
        }
        if (storage != null) {
            storage.close();
        }
        Permissions.unregister(this);
        if (api != null) {
            getServer().getServicesManager().unregister(api);
        }
        instance = null;
    }

    /**
     * Reloads the configuration snapshot. Storage type changes need a restart.
     */
    public void reload() {
        reloadConfig();
        MikuConfig next = new MikuConfig(getConfig(), getLogger());
        if (next.storageType != config.storageType) {
            throw new IllegalStateException("storage.type 变更需要重启服务器");
        }
        this.config = next;
        this.alerts = new AlertService(this, next);
        if (recording != null) {
            recording.updateConfig(next);
        }
        if (listenerRegistrar != null) {
            listenerRegistrar.unregister();
            listenerRegistrar = new ListenerRegistrar(this, next, recording, alerts, storage, inspection);
            listenerRegistrar.register();
        }
        if (next.worldEditEnabled && worldEdit == null) {
            enableWorldEdit();
        } else if (!next.worldEditEnabled) {
            disableWorldEdit();
        }
        if (web != null) {
            web.stop();
        }
        this.web = new WebService(this, next);
        this.web.start();
    }

    /**
     * Loads the optional bridge by name so the plugin starts normally when WorldEdit is absent.
     */
    private void enableWorldEdit() {
        if (!config.worldEditEnabled || worldEdit != null) {
            return;
        }
        if (getServer().getPluginManager().getPlugin("WorldEdit") == null
                && getServer().getPluginManager().getPlugin("FastAsyncWorldEdit") == null) {
            return;
        }
        try {
            Class<?> type = Class.forName("dev.junxiex.mikuprotect.integration.WorldEditIntegration", true,
                    getClass().getClassLoader());
            Object instance = type.getConstructor(MikuProtectPlugin.class).newInstance(this);
            type.getMethod("register").invoke(instance);
            this.worldEdit = instance;
            getLogger().info("WorldEdit integration enabled");
        } catch (Throwable throwable) {
            getLogger().warning("WorldEdit integration unavailable: " + throwable.getMessage());
            this.worldEdit = null;
        }
    }

    private void disableWorldEdit() {
        Object instance = worldEdit;
        worldEdit = null;
        if (instance == null) {
            return;
        }
        try {
            instance.getClass().getMethod("unregister").invoke(instance);
        } catch (Throwable ignored) {
            // WorldEdit may already be disabled during shutdown
        }
    }

    public MikuConfig config() {
        return config;
    }

    public Storage storage() {
        return storage;
    }

    public RecordingService recording() {
        return recording;
    }

    public LookupService lookup() {
        return lookup;
    }

    public InspectionService inspection() {
        return inspection;
    }

    public PurgeService purge() {
        return purge;
    }

    public UndoService undo() {
        return undo;
    }

    public AlertService alerts() {
        return alerts;
    }

    public RollbackService rollback() {
        return rollback;
    }

    public MikuProtectAPI api() {
        return api;
    }
}
