package re.imc.skriptextra.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.*;

/** Standalone checks: no Minecraft server or database required. */
public final class JournalTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory(Path.of("build"), "journal-test-");
        String identity = "test/table";
        Path wal = directory.resolve(JdbcOptions.hash(identity) + ".wal");
        VariableChange first = new VariableChange("中文::key", "string", new byte[]{0, 1, -1});
        try (DiskJournal journal = new DiskJournal(directory, identity, 1024 * 1024)) {
            journal.append(first);
            journal.append(new VariableChange("中文::key", null, null));
            journal.force();
            var changes = journal.read(DiskJournal.HEADER_BYTES, 10).changes();
            require(changes.size() == 2 && Arrays.equals(changes.getFirst().value(), first.value()), "binary round trip");
            require(changes.getLast().value() == null, "deletion round trip");
            boolean locked = false;
            try (DiskJournal ignored = new DiskJournal(directory, identity, 1024 * 1024)) { }
            catch (IOException | OverlappingFileLockException expected) { locked = true; }
            require(locked, "exclusive writer lock");
        }
        long length = Files.size(wal);
        Files.write(wal, new byte[]{0, 0, 0}, StandardOpenOption.APPEND);
        try (DiskJournal journal = new DiskJournal(directory, identity, 1024 * 1024)) {
            require(journal.size() == length, "repair incomplete frame header");
        }
        Files.write(wal, ByteBuffer.allocate(10).putInt(20).putInt(1234).putShort((short)1).array(), StandardOpenOption.APPEND);
        try (DiskJournal journal = new DiskJournal(directory, identity, 1024 * 1024)) {
            require(journal.size() == length, "repair incomplete frame payload");
            long offset = journal.compact(journal.size());
            require(offset == DiskJournal.HEADER_BYTES && journal.size() == offset, "acknowledge committed journal");
            journal.append(first); journal.force();
        }
        try (FileChannel file = FileChannel.open(wal, StandardOpenOption.WRITE)) {
            file.write(ByteBuffer.wrap(new byte[]{42}), DiskJournal.HEADER_BYTES + 8);
        }
        boolean rejected = false;
        try (DiskJournal ignored = new DiskJournal(directory, identity, 1024 * 1024)) { }
        catch (IOException expected) { rejected = true; }
        require(rejected, "reject complete corrupt record instead of silently truncating");
        // Exercise atomic compaction while an uncommitted suffix remains.
        try (DiskJournal journal = new DiskJournal(directory, "compaction", 16 * 1024 * 1024)) {
            byte[] bytes = new byte[8192];
            for (int i = 0; i < 700; i++) journal.append(new VariableChange("k" + i, "string", bytes));
            var committed = journal.read(DiskJournal.HEADER_BYTES, 600);
            long compacted = journal.compact(committed.end());
            var remaining = journal.read(compacted, 200).changes();
            require(compacted == DiskJournal.HEADER_BYTES && remaining.size() == 100
                    && remaining.getFirst().name().equals("k600"), "atomic prefix compaction preserves suffix");
        }
        // Passwords must never appear in diagnostic toString output.
        JdbcOptions options = new JdbcOptions("localhost", 3306, "test", "vars", "user", "sensitive",
                "disable", 512, 25, 250, 1000, 1000, 5000, 1024 * 1024);
        require(!options.toString().contains("sensitive"), "credential-safe diagnostics");
        System.out.println("PASS journal: round-trip, deletes, lock, torn tail, CRC, atomic compaction, diagnostics");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
