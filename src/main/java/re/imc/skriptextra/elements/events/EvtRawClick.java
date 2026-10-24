package re.imc.skriptextra.elements.events;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptParser;
import ch.njol.util.coll.CollectionUtils;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.Nullable;
import re.imc.skriptextra.utils.PluginConfig;

public class EvtRawClick
extends SkriptEvent {
    public boolean init(Literal<?>[] literals, int i, SkriptParser.ParseResult parseResult) {
        return true;
    }

    public boolean check(Event event) {
        if (event instanceof PlayerInteractEvent) {
            PlayerInteractEvent interactEvent = (PlayerInteractEvent)event;
            return PluginConfig.bool("features.events.raw-click.player-interact", true) && (PluginConfig.bool("features.events.raw-click.off-hand", true) || interactEvent.getHand() != EquipmentSlot.OFF_HAND);
        }
        if (event instanceof PlayerInteractEntityEvent) {
            PlayerInteractEntityEvent interactEvent = (PlayerInteractEntityEvent)event;
            return PluginConfig.bool("features.events.raw-click.entity-interact", true) && (PluginConfig.bool("features.events.raw-click.off-hand", true) || interactEvent.getHand() != EquipmentSlot.OFF_HAND);
        }
        return false;
    }

    public String toString(@Nullable Event event, boolean b) {
        return "raw click";
    }

    static {
        Class[] eventTypes = (Class[])CollectionUtils.array((Object[])new Class[]{PlayerInteractEvent.class, PlayerInteractEntityEvent.class, PlayerInteractAtEntityEvent.class});
        Skript.registerEvent((String)"Raw Click", EvtRawClick.class, (Class[])eventTypes, (String[])new String[]{"raw click"}).description(new String[]{"Purely click event"}).examples(new String[]{"on raw click:"}).since("1.0");
    }
}
