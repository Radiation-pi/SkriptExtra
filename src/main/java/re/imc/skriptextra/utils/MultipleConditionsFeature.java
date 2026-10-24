package re.imc.skriptextra.utils;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Condition;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser;
import ch.njol.util.Kleenean;
import java.util.regex.MatchResult;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;
import re.imc.skriptextra.utils.LogUtils;
import re.imc.skriptextra.utils.PluginConfig;

public final class MultipleConditionsFeature {
    private MultipleConditionsFeature() {
    }

    public static void register() {
        if (!PluginConfig.enabled("features.conditions.multiple.enabled")) {
            return;
        }
        if (PluginConfig.bool("features.conditions.multiple.disable-when-vessel-present", true) && Bukkit.getPluginManager().getPlugin("Vessel") != null) {
            LogUtils.info("检测到 Vessel，已禁用多条件符号语法以避免冲突");
            return;
        }
        Skript.registerCondition(MultipleCondition.class, (String[])new String[]{PluginConfig.string("features.conditions.multiple.and-pattern", "<.+> && <.+>"), PluginConfig.string("features.conditions.multiple.or-pattern", "<.+> \\|\\| <.+>")});
        LogUtils.info("已启用 && 和 || 多条件语法");
    }

    public static final class MultipleCondition
    extends Condition {
        private Condition first;
        private Condition second;
        private boolean and;

        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean delay, SkriptParser.ParseResult parseResult) {
            String firstText = ((MatchResult)parseResult.regexes.get(0)).group();
            String secondText = ((MatchResult)parseResult.regexes.get(1)).group();
            this.first = Condition.parse((String)firstText, (String)("Can't understand this condition: " + firstText));
            this.second = Condition.parse((String)secondText, (String)("Can't understand this condition: " + secondText));
            this.and = matchedPattern == 0;
            return this.first != null && this.second != null;
        }

        public boolean check(Event event) {
            return this.and ? this.first.check(event) && this.second.check(event) : this.first.check(event) || this.second.check(event);
        }

        public String toString(@Nullable Event event, boolean debug) {
            return this.first.toString(event, debug) + (this.and ? " && " : " || ") + this.second.toString(event, debug);
        }
    }
}
