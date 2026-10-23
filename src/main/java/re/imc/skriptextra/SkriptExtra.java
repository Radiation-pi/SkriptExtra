package re.imc.skriptextra;

import ch.njol.skript.Skript;
import ch.njol.skript.SkriptAddon;
import ch.njol.skript.lang.function.Functions;
import ch.njol.skript.lang.function.Parameter;
import ch.njol.skript.lang.function.SimpleJavaFunction;
import ch.njol.skript.lang.util.SimpleLiteral;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.registrations.DefaultClasses;
import ch.njol.skript.util.Version;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import re.imc.skriptextra.elements.ExtraClasses;
import re.imc.skriptextra.utils.placeholderAPI.PlaceholderPlugin;
import re.imc.skriptextra.utils.placeholderAPI.PlaceholderRegistry;
import re.imc.skriptextra.utils.PacketEventsIntegration;
import re.imc.skriptextra.utils.FileHash;
import re.imc.skriptextra.utils.WorldEditUtils;
import re.imc.skriptextra.utils.LogUtils;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class SkriptExtra extends JavaPlugin {
    
    private static SkriptExtra instance;
    private PlaceholderRegistry registry;
    private AutoCloseable packetEventsIntegration;

    public static SkriptExtra getInstance() {
        return instance;
    }

    public PlaceholderRegistry getRegistry() {
        return registry;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        // 初始化日志 + PacketEvents
        LogUtils.init(this);
        LogUtils.info("启动中...");
        
        // 检查 Skript 版本
        if (Skript.getVersion().isSmallerThan(new Version(2, 7, 3))) {
            LogUtils.severe("需要 Skript 2.7.3 或更高版本！");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // 初始化 PlaceholderRegistry（如果有可用的placeholder插件）
        if (!PlaceholderPlugin.getInstalledPlugins().isEmpty()) {
            registry = new PlaceholderRegistry(this);
            LogUtils.info("检测到 Placeholder 插件，已启用 Placeholder 功能");
        }
        
        if (getConfig().getBoolean("features.packet-events.enabled", true)) {
            if (Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
                try {
                    packetEventsIntegration = PacketEventsIntegration.register(this);
                } catch (RuntimeException | LinkageError e) {
                    LogUtils.severe("PacketEvents 集成加载失败，已跳过发包修改功能: " + e);
                }
            } else {
                LogUtils.warn("未检测到已启用的 PacketEvents，已跳过发包修改功能");
            }
        }

        // 注册 Skript 语法 (Events, Expressions, Effects, Sections, Conditions, Structures)
        SkriptAddon addon = Skript.registerAddon(this);
        LogUtils.info("正在加载 Skript 语法...");
        ExtraClasses.load();
        try {
            addon.loadClasses("re.imc.skriptextra.elements", "events", "expressions", "effects", "sections", "conditions", "structures");
        } catch (IOException e) {
            LogUtils.severe("An error occurred while loading classes: " + e.getMessage());
        }

        // 注册 Skript Functions
        Functions.registerFunction(new SimpleJavaFunction<>("fileSha1",
            new Parameter[]{new Parameter<>("file", DefaultClasses.OBJECT, true, null)},
            DefaultClasses.STRING, true) {
            @Override
            public String @Nullable [] executeSimple(Object[][] objects) {
                Object input = objects[0][0];
                File file = input instanceof File ? (File) input : input instanceof String ? new File((String) input) : null;
                if (file != null) {
                    try {
                        return new String[]{FileHash.sha1(file)};
                    } catch (Exception e) {
                        LogUtils.severe("Error calculating SHA-1 hash: " + e.getMessage());
                    }
                }
                return null;
            }
        });

        // WorldEdit 相关函数
        if (Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
            Functions.registerFunction(new SimpleJavaFunction<>("loadSchem", new Parameter[]{new Parameter<>("file", DefaultClasses.OBJECT, true, null), new Parameter<>("id", DefaultClasses.STRING, true, null)}, DefaultClasses.OBJECT, true) {
                @Override
                public Object @Nullable [] executeSimple(Object[][] objects) {
                    String id = objects[1][0].toString();
                    File file = null;
                    if (objects[0][0] instanceof File f) {
                        file = f;
                    }
                    if (objects[0][0] instanceof String str) {
                        file = new File(str);
                    }
                    return new Clipboard[]{WorldEditUtils.loadSchem(file, id)};
                }
            });

            Functions.registerFunction(new SimpleJavaFunction<>("placeSchem", new Parameter[]{
                    new Parameter<>("id", DefaultClasses.STRING, true, null),
                    new Parameter<>("loc", DefaultClasses.LOCATION, true, null),
                    new Parameter<>("direction", DefaultClasses.STRING, true, null),
                    new Parameter<>("ignoreair", DefaultClasses.BOOLEAN, true, new SimpleLiteral<>(false, true)),
                    new Parameter<>("async", DefaultClasses.BOOLEAN, true, new SimpleLiteral<>(true, true))

            }, DefaultClasses.OBJECT, true) {
                @Override
                public Object @Nullable [] executeSimple(Object[][] objects) {
                    String id = objects[0][0].toString();
                    Location loc = (Location) objects[1][0];
                    BlockFace face = BlockFace.valueOf(objects[2][0].toString().toUpperCase());
                    boolean ignoreAir = (boolean) objects[3][0];
                    boolean async = (boolean) objects[4][0];
                    WorldEditUtils.pasteSchem(loc, id, face, ignoreAir, async);
                    return null;
                }
            });
        }

        // 注册 sendMultiBlockChange 更改函数
        Functions.registerFunction(new SimpleJavaFunction<>("sendMultiBlockChange", new Parameter[]{
                new Parameter("players", DefaultClasses.PLAYER, false, null),
                new Parameter("blockdata", Classes.getExactClassInfo(BlockData.class), true, null),
                new Parameter("locations", DefaultClasses.LOCATION, false, null)
        }, DefaultClasses.OBJECT, true) {
            @Override
            public @Nullable Object[] executeSimple(Object[][] objects) {
                Player[] players = (Player[]) objects[0];
                BlockData blockData = (BlockData) objects[1][0];
                Location[] locations = (Location[]) objects[2];
                Map<Position, BlockData> blocks = new HashMap<>();
                for (Location location : locations) {
                    blocks.put(Position.block(location), blockData);
                }
                for (Player player : players) {
                    player.sendMultiBlockChange(blocks);
                }
                return new Object[0];
            }
        });
        LogUtils.info("已完成加载...");
    }

    @Override
    public void onDisable() {
        LogUtils.info("正在卸载...");
        if (packetEventsIntegration != null) {
            try {
                packetEventsIntegration.close();
            } catch (Exception e) {
                LogUtils.warn("注销 PacketEvents 监听器失败: " + e.getMessage());
            }
            packetEventsIntegration = null;
        }
        instance = null;
        registry = null;
    }
}
