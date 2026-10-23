package re.imc.skriptextra.utils;

import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.plugin.java.JavaPlugin;

/** Loaded only when the optional PacketEvents plugin is enabled. */
public final class PacketEventsIntegration {
    private PacketEventsIntegration() {}

    public static AutoCloseable register(JavaPlugin plugin) {
        BiomeSendingListener listener = new BiomeSendingListener(plugin.getConfig());
        if (!listener.hasEnabledFeatures()) {
            LogUtils.info("PacketEvents 子功能全部关闭，未注册监听器");
            return () -> {};
        }
        listener.startPermissionRefresh(plugin);
        try {
            PacketEvents.getAPI().getEventManager().registerListener(listener);
        } catch (RuntimeException | LinkageError e) {
            listener.close();
            throw e;
        }
        LogUtils.info("已启用 PacketEvents 发包修改功能");
        return () -> {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            listener.close();
        };
    }
}
