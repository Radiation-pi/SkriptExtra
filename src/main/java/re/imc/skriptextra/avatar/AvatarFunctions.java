package re.imc.skriptextra.avatar;

import ch.njol.skript.lang.function.Functions;
import ch.njol.skript.lang.function.Parameter;
import ch.njol.skript.lang.function.SimpleJavaFunction;
import ch.njol.skript.lang.util.SimpleLiteral;
import ch.njol.skript.registrations.DefaultClasses;
import org.bukkit.Bukkit;
import re.imc.skriptextra.utils.LogUtils;

import java.io.IOException;
import java.nio.file.Path;

public final class AvatarFunctions {
    private static final SkinAvatar SERVICE = new SkinAvatar();

    private AvatarFunctions() {}

    public static SkinAvatar service() { return SERVICE; }

    public static void register() {
        EffHeadImage.register();
        Functions.registerFunction(new SimpleJavaFunction<>("skinAvatar", new Parameter[]{
                new Parameter<>("texture", DefaultClasses.STRING, true, null),
                new Parameter<>("overlay", DefaultClasses.BOOLEAN, true, new SimpleLiteral<>(false, true)),
                new Parameter<>("character", DefaultClasses.STRING, true, new SimpleLiteral<>("█", true)),
                new Parameter<>("directory", DefaultClasses.STRING, true, new SimpleLiteral<>("plugins/Skript/skincache/", true)),
                new Parameter<>("lines", DefaultClasses.NUMBER, true, new SimpleLiteral<>(8L, true))
        }, DefaultClasses.STRING, false) {
            @Override public String[] executeSimple(Object[][] arguments) {
                if (Bukkit.isPrimaryThread()) {
                    LogUtils.warn("skinAvatar 需要在异步 section 中调用，已跳过主线程上的图片处理。");
                    return new String[0];
                }
                try {
                    return SERVICE.load((String) arguments[0][0], (boolean) arguments[1][0],
                            (String) arguments[2][0], Path.of((String) arguments[3][0]), ((Number) arguments[4][0]).intValue());
                } catch (IOException | RuntimeException e) {
                    LogUtils.warn("无法生成皮肤头像: " + e.getMessage());
                    return new String[0];
                }
            }
        });
    }
}
