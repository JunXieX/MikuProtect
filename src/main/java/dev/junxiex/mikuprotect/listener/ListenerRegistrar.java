package dev.junxiex.mikuprotect.listener;

import dev.junxiex.mikuprotect.config.MikuConfig;
import dev.junxiex.mikuprotect.service.AlertService;
import dev.junxiex.mikuprotect.service.InspectionService;
import dev.junxiex.mikuprotect.service.RecordingService;
import dev.junxiex.mikuprotect.storage.Storage;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * 监听器的统一注册入口。插件启用时调用 {@link #register()}，重载或停用时调用 {@link #unregister()}。
 *
 * <p>监听器只依赖 {@link RecordingService}（以及告示牌/聊天需要的配置快照），不持有任何存储对象。</p>
 */
public final class ListenerRegistrar {

    private final Plugin plugin;
    private final MikuConfig config;
    private final RecordingService recording;
    private final AlertService alerts;
    private final Storage storage;
    private final InspectionService inspection;
    private final List<Listener> listeners = new ArrayList<>(5);

    public ListenerRegistrar(Plugin plugin, MikuConfig config, RecordingService recording) {
        this(plugin, config, recording, null);
    }

    public ListenerRegistrar(Plugin plugin, MikuConfig config, RecordingService recording, AlertService alerts) {
        this(plugin, config, recording, alerts, null, null);
    }

    public ListenerRegistrar(Plugin plugin, MikuConfig config, RecordingService recording, AlertService alerts,
            Storage storage) {
        this(plugin, config, recording, alerts, storage, null);
    }

    public ListenerRegistrar(Plugin plugin, MikuConfig config, RecordingService recording, AlertService alerts,
            Storage storage, InspectionService inspection) {
        this.plugin = plugin;
        this.config = config;
        this.recording = recording;
        this.alerts = alerts;
        this.storage = storage;
        this.inspection = inspection;
    }

    /** 注册全部监听器；重复调用不会重复注册。 */
    public synchronized void register() {
        if (!listeners.isEmpty() || plugin == null || recording == null) {
            return;
        }
        listeners.add(new BlockListener(recording, alerts));
        listeners.add(new ContainerListener(recording));
        listeners.add(new EntityListener(recording));
        listeners.add(new PlayerListener(recording, config, storage, inspection));
        listeners.add(new WorldListener(recording));
        PluginManager manager = plugin.getServer().getPluginManager();
        for (Listener listener : listeners) {
            manager.registerEvents(listener, plugin);
        }
    }

    /** 注销全部监听器，供重载使用。 */
    public synchronized void unregister() {
        for (Listener listener : listeners) {
            HandlerList.unregisterAll(listener);
        }
        listeners.clear();
    }

    public boolean registered() {
        return !listeners.isEmpty();
    }
}
