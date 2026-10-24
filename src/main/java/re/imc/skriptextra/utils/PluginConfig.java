package re.imc.skriptextra.utils;

import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;
import re.imc.skriptextra.SkriptExtra;

public final class PluginConfig {
    private PluginConfig() {
    }

    private static FileConfiguration config() {
        return SkriptExtra.getInstance().getConfig();
    }

    public static boolean enabled(String path) {
        return PluginConfig.config().getBoolean(path, true);
    }

    public static boolean bool(String path, boolean defaultValue) {
        return PluginConfig.config().getBoolean(path, defaultValue);
    }

    public static int integer(String path, int defaultValue) {
        return PluginConfig.config().getInt(path, defaultValue);
    }

    public static long longValue(String path, long defaultValue) {
        return PluginConfig.config().getLong(path, defaultValue);
    }

    public static String string(String path, String defaultValue) {
        return PluginConfig.config().getString(path, defaultValue);
    }

    public static List<String> strings(String path) {
        return PluginConfig.config().getStringList(path);
    }
}
