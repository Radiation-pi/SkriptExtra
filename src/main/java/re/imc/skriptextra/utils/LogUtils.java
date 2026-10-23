package re.imc.skriptextra.utils;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;
import java.util.logging.Logger;


public final class LogUtils {

    private static Logger logger;
    private static String version;

    public static void init(JavaPlugin p) {
        logger = p.getLogger();
        version = p.getDescription().getVersion();
    }

    public static void log(String message, Level level) {
        logger.log(level, version + " " + message);
    }

    public static void info(String message) {
        log(message, Level.INFO);
    }

    public static void warn(String message) {
        log(message, Level.WARNING);
    }

    public static void severe(String message) {
        log(message, Level.SEVERE);
    }
}
