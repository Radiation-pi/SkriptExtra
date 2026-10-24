package re.imc.skriptextra.storage;

import ch.njol.skript.config.SectionNode;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.variables.VariablesStorage;
import org.bukkit.configuration.ConfigurationSection;
import re.imc.skriptextra.SkriptExtra;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class JdbcVariablesStorage extends VariablesStorage {
    private JdbcWriter writer;
    private SkriptExtra plugin;
    private boolean finished;

    public JdbcVariablesStorage(String type) { super(type); }

    @Override protected boolean load_i(SectionNode node) {
        plugin = SkriptExtra.getInstance();
        try {
            if (plugin == null || !plugin.getConfig().getBoolean("features.variable-storage.enabled", false))
                throw new IllegalArgumentException("Skript uses extra-jdbc but features.variable-storage.enabled is false");
            if (Boolean.parseBoolean(node.get("monitor changes", "false")))
                throw new IllegalArgumentException("extra-jdbc requires 'monitor changes: false'; cross-server polling is unsupported");
            ConfigurationSection config = plugin.getConfig().getConfigurationSection("features.variable-storage");
            if (config == null) throw new IllegalArgumentException("Missing variable-storage config");
            JdbcOptions options = new JdbcOptions(required(node, "host"), Integer.parseInt(required(node, "port")),
                    required(node, "database"), required(node, "table"), required(node, "user"), required(node, "password"),
                    node.get("ssl mode", "disable"), config.getInt("batch-size", 512), config.getInt("flush-interval-ms", 25),
                    config.getInt("retry-delay-ms", 250), config.getInt("connect-timeout-ms", 3000),
                    config.getInt("socket-timeout-ms", 5000), config.getInt("shutdown-timeout-ms", 30000),
                    config.getLong("maximum-journal-mib", 256) * 1024 * 1024);
            Path directory = plugin.getDataFolder().toPath().resolve("variable-journal");
            writer = new JdbcWriter(options, directory, plugin.getLogger(), message -> JdbcStorageIntegration.fatal(plugin, message));
            writer.initialize();
            record Loaded(String name, Object value) {}
            List<Loaded> loaded = new ArrayList<>();
            for (VariableChange change : writer.load()) {
                Object value = Classes.deserialize(change.type(), change.value());
                if (value == null) throw new IllegalStateException("Cannot deserialize a variable in " + options.table());
                loaded.add(new Loaded(change.name(), value));
            }
            // Validate the entire table before handing any values to Skript's migration machinery.
            for (Loaded value : loaded) SkriptStorageBridge.loaded(value.name(), value.value(), this);
            plugin.getLogger().info("JDBC 存储 " + getUserConfigurationName() + " 已加载 " + loaded.size()
                    + " 个变量；batch=" + options.batchSize() + ", flush=" + options.flushMillis() + "ms");
            return true;
        } catch (Exception | LinkageError e) {
            if (writer != null) try { writer.close(); } catch (IOException ignored) { }
            String reason = e instanceof SQLException sql ? "SQLState=" + sql.getSQLState() + ", code=" + sql.getErrorCode()
                    : e instanceof IllegalArgumentException ? e.getMessage() : e.getClass().getSimpleName();
            JdbcStorageIntegration.fatal(plugin, "JDBC 变量加载失败，禁止以空存储继续启动: " + reason);
            // Returning false would let Skript continue to another storage and redistribute partial data.
            throw new IllegalStateException("SkriptExtra JDBC startup aborted; inspect configuration and journal");
        }
    }

    private static String required(SectionNode node, String key) {
        String value = node.getValue(key);
        if (value == null) throw new IllegalArgumentException("Missing Skript database entry: " + key);
        return value;
    }

    @Override protected void allLoaded() { writer.start(); }
    @Override protected boolean requiresFile() { return false; }
    @Override protected File getFile(String name) { return new File(name); }
    @Override protected boolean connect() { return true; }
    @Override protected void disconnect() { /* Writer owns its JDBC connection. */ }

    @Override protected boolean save(String name, String type, byte[] value) {
        try {
            writer.append(new VariableChange(name, type, value));
            return true;
        } catch (IOException | RuntimeException e) {
            JdbcStorageIntegration.fatal(plugin, "JDBC 无法接收变量变更；停止服务器。请检查磁盘、日志容量及数据库错误");
            return false;
        }
    }

    @Override public synchronized void close() {
        if (finished) return;
        // Skript closes this after unloading scripts and draining its global save queue.
        super.close();
        try {
            SkriptStorageBridge.joinSaveThread(this);
            if (writer != null) writer.close();
        } catch (Exception e) {
            if (plugin != null) plugin.getLogger().severe("JDBC 关闭未完成；请保留 variable-journal 目录供重启恢复: "
                    + e.getClass().getSimpleName());
        }
        finished = true;
    }
}
