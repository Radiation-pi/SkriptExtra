package re.imc.skriptextra.utils;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerRegistryData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateSimulationDistance;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateViewDistance;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Settings are captured on startup; packet threads never read Bukkit configuration or permissions. */
public final class BiomeSendingListener extends PacketListenerAbstract {
    private static final String ROOT = "features.packet-events.";
    private final boolean viewEnabled, simulationEnabled, statusEnabled, biomeEnabled;
    private final int viewDistance, simulationDistance, status;
    private final String permission, registry, sourceNamespace, sourcePrefix, targetNamespace;
    private volatile Set<UUID> permittedPlayers = Set.of();
    private BukkitTask permissionTask;
    private boolean collisionWarned;

    public BiomeSendingListener(FileConfiguration config) {
        super(PacketListenerPriority.NORMAL);
        viewDistance = config.getInt(ROOT + "view-distance.value", 32);
        simulationDistance = config.getInt(ROOT + "simulation-distance.value", 32);
        status = config.getInt(ROOT + "entity-status-override.status", 28);
        viewEnabled = config.getBoolean(ROOT + "view-distance.enabled", true)
                && validRange("view-distance.value", viewDistance, 1, 32);
        simulationEnabled = config.getBoolean(ROOT + "simulation-distance.enabled", true)
                && validRange("simulation-distance.value", simulationDistance, 1, 32);
        statusEnabled = config.getBoolean(ROOT + "entity-status-override.enabled", true)
                && validRange("entity-status-override.status", status, -128, 127);
        permission = config.getString(ROOT + "entity-status-override.permission", "sicilia.admin");
        registry = config.getString(ROOT + "biome-namespace-rewrite.registry", "minecraft:worldgen/biome");
        sourceNamespace = config.getString(ROOT + "biome-namespace-rewrite.source-namespace", "terra");
        sourcePrefix = config.getString(ROOT + "biome-namespace-rewrite.source-path-prefix", "sicilia/sicilia/");
        targetNamespace = config.getString(ROOT + "biome-namespace-rewrite.target-namespace", "sicilia");
        boolean validBiome = registry.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                && sourceNamespace.matches("[a-z0-9_.-]+")
                && sourcePrefix.matches("[a-z0-9/._-]*")
                && targetNamespace.matches("[a-z0-9_.-]+");
        biomeEnabled = config.getBoolean(ROOT + "biome-namespace-rewrite.enabled", true) && validBiome;
        if (!validBiome) LogUtils.warn("群系命名空间配置无效，已禁用群系重写");
    }

    private static boolean validRange(String key, int value, int min, int max) {
        if (value >= min && value <= max) return true;
        LogUtils.warn(ROOT + key + " 超出范围 " + min + ".." + max + "，已禁用对应功能");
        return false;
    }

    public boolean hasEnabledFeatures() {
        return viewEnabled || simulationEnabled || statusEnabled || biomeEnabled;
    }

    public void startPermissionRefresh(JavaPlugin plugin) {
        if (!statusEnabled || permission.isBlank()) return;
        permissionTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Set<UUID> permitted = new HashSet<>();
            Bukkit.getOnlinePlayers().forEach(player -> {
                if (player.hasPermission(permission)) permitted.add(player.getUniqueId());
            });
            permittedPlayers = Set.copyOf(permitted);
        }, 0L, 20L);
    }

    public void close() {
        if (permissionTask != null) permissionTask.cancel();
        permittedPlayers = Set.of();
    }

    /** Null means leave the ID unchanged. Short and unrelated paths are preserved. */
    public ResourceLocation rewriteId(ResourceLocation id) {
        if (!sourceNamespace.equals(id.getNamespace()) || !id.getKey().startsWith(sourcePrefix)) return null;
        String suffix = id.getKey().substring(sourcePrefix.length());
        return suffix.isEmpty() ? null : new ResourceLocation(targetNamespace, suffix);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (viewEnabled && event.getPacketType() == PacketType.Play.Server.UPDATE_VIEW_DISTANCE) {
            new WrapperPlayServerUpdateViewDistance(event).setViewDistance(viewDistance);
            event.markForReEncode(true);
        } else if (simulationEnabled && event.getPacketType() == PacketType.Play.Server.UPDATE_SIMULATION_DISTANCE) {
            new WrapperPlayServerUpdateSimulationDistance(event).setSimulationDistance(simulationDistance);
            event.markForReEncode(true);
        } else if (statusEnabled && event.getPacketType() == PacketType.Play.Server.ENTITY_STATUS) {
            UUID recipient = event.getUser() == null ? null : event.getUser().getUUID();
            if (permission.isBlank() || (recipient != null && permittedPlayers.contains(recipient))) {
                new WrapperPlayServerEntityStatus(event).setStatus(status);
                event.markForReEncode(true);
            }
        } else if (biomeEnabled && event.getPacketType() == PacketType.Configuration.Server.REGISTRY_DATA) {
            WrapperConfigServerRegistryData wrapper = new WrapperConfigServerRegistryData(event);
            if (wrapper.getRegistryKey() == null || !registry.equals(wrapper.getRegistryKey().toString())) return;
            List<WrapperConfigServerRegistryData.RegistryElement> original = wrapper.getElements();
            if (original == null) return;
            List<WrapperConfigServerRegistryData.RegistryElement> rewritten = new ArrayList<>(original.size());
            Set<ResourceLocation> ids = new HashSet<>();
            boolean changed = false;
            for (WrapperConfigServerRegistryData.RegistryElement element : original) {
                ResourceLocation replacement = rewriteId(element.getId());
                ResourceLocation id = replacement == null ? element.getId() : replacement;
                if (!ids.add(id)) {
                    synchronized (this) {
                        if (!collisionWarned) {
                            LogUtils.warn("群系重写会产生重复 ID，保留原始注册表: " + id);
                            collisionWarned = true;
                        }
                    }
                    return;
                }
                rewritten.add(replacement == null ? element
                        : new WrapperConfigServerRegistryData.RegistryElement(id, element.getData()));
                changed |= replacement != null;
            }
            if (changed) {
                wrapper.setElements(rewritten);
                event.markForReEncode(true);
            }
        }
    }
}
