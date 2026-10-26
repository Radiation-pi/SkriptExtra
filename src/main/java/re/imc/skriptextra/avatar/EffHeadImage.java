package re.imc.skriptextra.avatar;

import ch.njol.skript.Skript;
import ch.njol.skript.effects.Delay;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.Variable;
import ch.njol.skript.lang.parser.ParserInstance;
import ch.njol.skript.variables.Variables;
import ch.njol.util.Kleenean;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import re.imc.skriptextra.SkriptExtra;
import re.imc.skriptextra.utils.LogUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** Assignment syntax can suspend on a cold image; an ordinary expression cannot. */
public final class EffHeadImage extends Effect {
    private Expression<?> source;
    private Expression<Number> lines;
    private Expression<String> character;
    private Variable<?> destination;
    private boolean overlay;

    public static void register() {
        String options = " [overlay:with overlay] [with %-integer% lines] [(with character|using) %-string%]";
        Skript.registerEffect(EffHeadImage.class,
                "set %objects% to [the] head image of %player/uuid%" + options,
                "set %objects% to %player/uuid%'[s] head image" + options,
                "store [the] head string of %player/uuid%" + options + " inside %objects%");
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] expressions, int pattern, Kleenean delayed, SkriptParser.ParseResult result) {
        int sourceIndex = pattern == 2 ? 0 : 1;
        int destinationIndex = pattern == 2 ? 3 : 0;
        Expression<?> target = expressions[destinationIndex].getSource();
        if (!(target instanceof Variable<?> variable) || !variable.isList()) {
            Skript.error("头像结果需要保存到列表变量，例如 {_head::*}。");
            return false;
        }
        destination = variable;
        source = expressions[sourceIndex];
        lines = (Expression<Number>) expressions[sourceIndex + 1];
        character = (Expression<String>) expressions[sourceIndex + 2];
        overlay = result.hasTag("overlay");
        ParserInstance.get().setHasDelayBefore(Kleenean.TRUE);
        return true;
    }

    @Override
    protected TriggerItem walk(Event event) {
        debug(event, true);
        if (Bukkit.isPrimaryThread()) return start(event);
        // Read player profiles and evaluate event expressions on the main thread.
        Delay.addDelayedEvent(event);
        Object locals = Variables.removeLocals(event);
        Bukkit.getScheduler().runTask(SkriptExtra.getInstance(), () -> {
            if (locals != null) Variables.setLocalVariables(event, locals);
            try {
                TriggerItem next = start(event);
                if (next != null) TriggerItem.walk(next, event);
            } finally {
                Variables.removeLocals(event);
            }
        });
        return null;
    }

    private TriggerItem start(Event event) {
        String variableName = destination.getName().toString(event);
        boolean local = destination.isLocal();
        Object value = source.getSingle(event);
        Player player = value instanceof Player p ? p : value instanceof UUID uuid ? Bukkit.getPlayer(uuid) : null;
        Number requestedLines = lines == null ? 8 : lines.getSingle(event);
        String requestedCharacter = character == null ? "█" : character.getSingle(event);
        if (requestedLines == null || requestedLines.longValue() < 1 || requestedLines.longValue() > 64
                || requestedCharacter == null || requestedCharacter.isEmpty()) {
            LogUtils.warn("头像参数无效：行数需为 1–64，字符不能为空。");
            assign(event, variableName, local, new String[0]);
            return getNext();
        }
        String texture = player == null ? null : player.getPlayerProfile().getProperties().stream()
                .filter(property -> property.getName().equals("textures"))
                .map(property -> property.getValue()).findFirst().orElse(null);
        if (texture == null) {
            assign(event, variableName, local, new String[0]);
            return getNext();
        }
        int count = requestedLines.intValue();
        try {
            String[] cached = AvatarFunctions.service().cached(texture, overlay, requestedCharacter, count);
            if (cached != null) {
                assign(event, variableName, local, cached);
                return getNext();
            }
        } catch (IOException e) {
            LogUtils.warn("无法生成皮肤头像: " + e.getMessage());
            assign(event, variableName, local, new String[0]);
            return getNext();
        }
        Delay.addDelayedEvent(event);
        Object locals = Variables.removeLocals(event);
        SkriptExtra plugin = SkriptExtra.getInstance();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String[] rows;
            try {
                rows = AvatarFunctions.service().load(texture, overlay, requestedCharacter,
                        Path.of("plugins/Skript/skincache/"), count);
            } catch (IOException | RuntimeException e) {
                LogUtils.warn("无法生成皮肤头像: " + e.getMessage());
                rows = new String[0];
            }
            if (!plugin.isEnabled()) return;
            String[] completed = rows;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (getTrigger() != null && getTrigger().getScript() != null && !getTrigger().getScript().valid()) return;
                if (locals != null) Variables.setLocalVariables(event, locals);
                try {
                    assign(event, variableName, local, completed);
                    if (getNext() != null) TriggerItem.walk(getNext(), event);
                } finally {
                    Variables.removeLocals(event);
                }
            });
        });
        return null;
    }

    private static void assign(Event event, String name, boolean local, String[] rows) {
        Variables.setVariable(name, null, event, local);
        String prefix = name.substring(0, name.length() - 1);
        for (int i = 0; i < rows.length; i++) Variables.setVariable(prefix + (i + 1), rows[i], event, local);
    }

    @Override protected void execute(Event event) { throw new UnsupportedOperationException(); }
    @Override public String toString(Event event, boolean debug) { return "store head image"; }
}
