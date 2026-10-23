package re.imc.skriptextra.utils;

import ch.njol.skript.Skript;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorldEditUtils {
    public static Map<String, Clipboard> STRUCTURE_CACHE = new ConcurrentHashMap<>();

    public static Clipboard loadSchem(File file, String id) {
        ClipboardFormat format = ClipboardFormats.findByFile(file);

        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            Clipboard clipboard = reader.read();
            STRUCTURE_CACHE.put(id, clipboard);
            return clipboard;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void pasteSchem(Location loc, String clipboard, BlockFace face, boolean ignoreAir, boolean async) {
        if (async) {
            Bukkit.getScheduler().runTaskAsynchronously(Skript.getInstance(), () -> pasteSchematic(loc, STRUCTURE_CACHE.get(clipboard), face, ignoreAir));
        } else {
            Bukkit.getScheduler().runTask(Skript.getInstance(), () -> pasteSchematic(loc, STRUCTURE_CACHE.get(clipboard), face, ignoreAir));
        }
    }

    public static Clipboard pasteSchematic(Location loc, Clipboard clipboard, BlockFace face, boolean ignoreAir) {

        loc = loc.clone();
        int degree = DirectionUtils.faceToDegree(face);

        AffineTransform transform = new AffineTransform()
                .rotateY(-degree);
        switch (degree) {
            case 90 -> {
                loc.add(clipboard.getDimensions().getX() -1, 0, 0);
            }
            case 180 -> {
                loc.add(clipboard.getDimensions().getX() -1, 0, clipboard.getDimensions().getX() -1);
            }
            case 270 -> {
                loc.add(0, 0, clipboard.getDimensions().getX() -1);
            }
        }

        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(loc.getWorld()))) {
            ClipboardHolder holder = new ClipboardHolder(clipboard);
            holder.setTransform(transform);
            Operation operation = holder
                    .createPaste(editSession)
                    .ignoreAirBlocks(ignoreAir)
                    .to(BukkitAdapter.asBlockVector(loc))
                    .build();
            Operations.complete(operation);
        } catch (WorldEditException e) {
            throw new IllegalStateException("Unable to paste schematic", e);
        }
        return clipboard;
    }

    public static Clipboard createSchematic(Location start, Location end, Location origin, boolean copyEntities) {
        BukkitWorld bukkitWorld = new BukkitWorld(start.getWorld());
        CuboidRegion region = new CuboidRegion(bukkitWorld, BukkitAdapter.asBlockVector(start), BukkitAdapter.asBlockVector(end));
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);

        clipboard.setOrigin(BukkitAdapter.asBlockVector(origin));
        ForwardExtentCopy forwardExtentCopy = new ForwardExtentCopy(bukkitWorld, region, clipboard, region.getMinimumPoint());
        forwardExtentCopy.setCopyingEntities(copyEntities);
        try {
            Operations.complete(forwardExtentCopy);
        } catch (WorldEditException e) {
            throw new IllegalStateException("Unable to copy schematic", e);
        }


        return clipboard;
    }

    public static void replaceBlocks(Location start, Location end, Material type) {
        BukkitWorld bukkitWorld = new BukkitWorld(start.getWorld());
        CuboidRegion region = new CuboidRegion(bukkitWorld, BukkitAdapter.asBlockVector(start), BukkitAdapter.asBlockVector(end));

        try (EditSession editSession = WorldEdit.getInstance().newEditSession(bukkitWorld)) {
            editSession.setBlocks((Region) region, BukkitAdapter.asBlockState(new ItemStack(type)));
        } catch (WorldEditException e) {
            throw new IllegalStateException("Unable to replace blocks", e);
        }
    }
}
