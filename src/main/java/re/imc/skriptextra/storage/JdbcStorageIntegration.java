package re.imc.skriptextra.storage;

import ch.njol.skript.SkriptConfig;
import ch.njol.skript.config.Config;
import ch.njol.skript.config.Node;
import ch.njol.skript.config.SectionNode;
import ch.njol.skript.variables.Variables;
import org.bukkit.Bukkit;
import re.imc.skriptextra.SkriptExtra;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JdbcStorageIntegration {
    private static final AtomicBoolean STOP_REQUESTED = new AtomicBoolean();
    private JdbcStorageIntegration() {}

    public static void register(SkriptExtra plugin) {
        // Register even with the feature off, so an inconsistent config fails explicitly.
        if (!Variables.registerStorage(JdbcVariablesStorage.class, "extra-jdbc"))
            throw new IllegalStateException("Cannot register extra-jdbc storage; restart instead of plugin reload");
        boolean usesJdbc = configuredTypes().stream().anyMatch(type -> type.equalsIgnoreCase("extra-jdbc"));
        if (usesJdbc || plugin.getConfig().getBoolean("features.variable-storage.enabled", false)) {
            try { SkriptStorageBridge.verify(); }
            catch (LinkageError e) {
                fatal(plugin, "当前 Skript 的变量加载接口不兼容；JDBC 无法安全启动");
                throw e;
            }
        }
        if (usesJdbc && (!plugin.getConfig().getBoolean("features.variable-storage.enabled", false)
                || !plugin.getConfig().getBoolean("general.enabled", true))) {
            fatal(plugin, "Skript 配置使用 extra-jdbc，但 SkriptExtra 或变量存储开关已关闭；请同时修改两处配置");
            throw new IllegalStateException("Inconsistent extra-jdbc configuration");
        }
        if (plugin.getConfig().getBoolean("features.variable-storage.warn-legacy-sqlibrary", true)) warnLegacy(plugin);
        if (plugin.getConfig().getBoolean("features.variable-storage.enabled", false) && !usesJdbc)
            plugin.getLogger().warning("JDBC 开关已启用，但 Skript/config.sk 中没有 type: extra-jdbc；当前变量存储不会自动切换");
    }

    private static List<String> configuredTypes() {
        Config config = SkriptConfig.getConfig();
        if (config == null) throw new IllegalStateException("Skript configuration is not loaded");
        Node databases = config.getMainNode().get("databases");
        List<String> types = new ArrayList<>();
        if (databases instanceof SectionNode section)
            for (Node node : section) if (node instanceof SectionNode database)
                types.add(database.get("type", "disabled"));
        return types;
    }

    private static void warnLegacy(SkriptExtra plugin) {
        boolean mysql = configuredTypes().stream().anyMatch(type -> type.equalsIgnoreCase("mysql"));
        boolean sqlite = configuredTypes().stream().anyMatch(type -> type.equalsIgnoreCase("sqlite"));
        if (mysql) {
            plugin.getLogger().warning("检测到 Skript 仍配置原生 MySQL/SQLibrary 变量存储：该实现逐条写入，批量更新性能较慢。"
                    + "建议备份后启用 SkriptExtra JDBC，并在 Skript/config.sk 改用 type: extra-jdbc。"
                    + "确认所有 MySQL/SQLite 存储及其他插件均不再依赖后，再禁用 SQLibrary。");
        } else if (sqlite && Bukkit.getPluginManager().isPluginEnabled("SQLibrary")) {
            plugin.getLogger().warning("Skript 的 SQLite 存储仍依赖 SQLibrary；完成迁移前请保留该插件。");
        }
    }

    static void fatal(SkriptExtra plugin, String message) {
        if (plugin != null) plugin.getLogger().severe(message);
        else Bukkit.getLogger().severe("[SkriptExtra] " + message);
        if (STOP_REQUESTED.compareAndSet(false, true)) {
            if (Bukkit.isPrimaryThread()) Bukkit.shutdown();
            else Bukkit.getScheduler().runTask(ch.njol.skript.Skript.getInstance(), Bukkit::shutdown);
        }
    }
}
