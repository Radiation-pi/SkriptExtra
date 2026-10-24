package re.imc.skriptextra.elements.events;

import ch.njol.skript.Skript;
import ch.njol.skript.SkriptEventHandler;
import ch.njol.skript.events.bukkit.ScheduledNoWorldEvent;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.util.Timespan;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;
import re.imc.skriptextra.utils.PluginConfig;

public class EvtAsyncPeriodical
extends SkriptEvent {
    private Timespan period;
    private BukkitTask task;

    public boolean init(Literal<?>[] args, int matchedPattern, SkriptParser.ParseResult parseResult) {
        this.period = (Timespan)args[0].getSingle();
        return this.period != null;
    }

    public boolean postLoad() {
        long minimumTicks = Math.max(1L, PluginConfig.longValue("features.events.async-periodical.minimum-period-ticks", 1L));
        long ticks = Math.max(minimumTicks, this.period.getAs(Timespan.TimePeriod.TICK));
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously((Plugin)Skript.getInstance(), this::execute, ticks, ticks);
        return true;
    }

    public void unload() {
        if (this.task != null) {
            this.task.cancel();
        }
    }

    public boolean check(Event event) {
        throw new UnsupportedOperationException();
    }

    public boolean isEventPrioritySupported() {
        return false;
    }

    public String toString(@Nullable Event event, boolean debug) {
        return "on async every " + String.valueOf(this.period);
    }

    private void execute() {
        ScheduledNoWorldEvent event = new ScheduledNoWorldEvent();
        SkriptEventHandler.logEventStart((Event)event);
        SkriptEventHandler.logTriggerStart((Trigger)this.trigger);
        this.trigger.execute((Event)event);
        SkriptEventHandler.logTriggerEnd((Trigger)this.trigger);
        SkriptEventHandler.logEventEnd();
    }

    static {
        Skript.registerEvent((String)"*AsyncPeriodical", EvtAsyncPeriodical.class, ScheduledNoWorldEvent.class, (String[])new String[]{"on async every %timespan%", "every %timespan% async"}).description(new String[]{"调用于异步执行的周期性事件。"}).examples(new String[]{"on async every 5 seconds: # 在异步线程运行"}).since("1.0");
    }
}
