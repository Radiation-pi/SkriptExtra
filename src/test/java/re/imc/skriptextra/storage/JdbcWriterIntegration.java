package re.imc.skriptextra.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/** Explicitly opt-in: run only against a disposable database, never a player variable table. */
public final class JdbcWriterIntegration {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path directory = Path.of(args[1]);
        Files.createDirectories(directory);
        JdbcOptions options = new JdbcOptions(System.getProperty("jdbc.test.host", "127.0.0.1"), Integer.getInteger("jdbc.test.port", 33307),
                "skript_bench", "extra_jdbc_integration", "root", "benchmark-only", "disable",
                128, 10, 100, 1000, 1000, 10000, 32 * 1024 * 1024);
        Logger log = Logger.getLogger("jdbc-integration");
        try (JdbcWriter writer = new JdbcWriter(options, directory.resolve("journal"), log,
                message -> { throw new AssertionError(message); })) {
            writer.initialize();
            if (mode.equals("crash")) {
                for (int i = 0; i < 2000; i++) writer.append(change(i));
                for (int i = 0; i < 500; i++) writer.append(new VariableChange("key" + i, null, null));
                writer.append(new VariableChange("key1", "string", "restored".getBytes(StandardCharsets.UTF_8)));
                writer.flushJournal();
                System.out.println("CRASH_READY: accepted journal records synced before forced JVM exit");
                Runtime.getRuntime().halt(0);
            }
            if (mode.equals("recover")) {
                Map<String, byte[]> values = new HashMap<>();
                for (VariableChange change : writer.load()) values.put(change.name(), change.value());
                require(values.size() == 1501, "recovered row count");
                for (int i = 500; i < 2000; i++) require(Arrays.equals(values.get("key" + i), change(i).value()), "recovered value " + i);
                require(Arrays.equals(values.get("key1"), "restored".getBytes(StandardCharsets.UTF_8)), "delete then reinsert");
                for (int i = 0; i < 500; i++) if (i != 1) require(!values.containsKey("key" + i), "recovered deletion " + i);
                System.out.println("PASS forced JVM exit and WAL replay: all values/deletions verified");
                // A different journal cannot evade the database-level single-writer lock.
                boolean locked = false;
                try (JdbcWriter second = new JdbcWriter(options, directory.resolve("second"), log, message -> { })) {
                    second.initialize();
                } catch (SQLException expected) { locked = "55000".equals(expected.getSQLState()); }
                require(locked, "database advisory single-writer lock");
                System.out.println("PASS database single-writer lock");
            }
            if (mode.startsWith("outage")) {
                writer.start();
                Files.writeString(directory.resolve("ready"), "ready");
                await(directory.resolve("database-stopped"));
                for (int i = 0; i < 3000; i++) writer.append(change(i));
                if (mode.equals("outage-stop"))
                    writer.append(new VariableChange("deferred-marker", "string", new byte[]{11, 22, 33}));
                writer.flushJournal();
                Files.writeString(directory.resolve("staged"), "staged");
                await(directory.resolve("database-started"));
            }
            if (mode.equals("recover-outage")) {
                Map<String, byte[]> values = new HashMap<>();
                for (VariableChange change : writer.load()) values.put(change.name(), change.value());
                require(values.size() == 3001 && Arrays.equals(values.get("deferred-marker"), new byte[]{11,22,33}),
                        "shutdown deadline journal replay");
                for (int i = 0; i < 3000; i++) require(Arrays.equals(values.get("key" + i), change(i).value()), "deferred value " + i);
                System.out.println("PASS bounded offline shutdown and next-start replay: 3001 values");
            }
        }
        if (mode.equals("outage")) {
            try (JdbcWriter reader = new JdbcWriter(options, directory.resolve("journal"), log, message -> { throw new AssertionError(message); })) {
                reader.initialize();
                Map<String, byte[]> values = new HashMap<>();
                for (VariableChange change : reader.load()) values.put(change.name(), change.value());
                require(values.size() == 3000, "outage row count");
                for (int i = 0; i < 3000; i++) require(Arrays.equals(values.get("key" + i), change(i).value()), "outage value " + i);
            }
            System.out.println("PASS database outage, reconnection and drain: 3000/3000 values");
        }
        if (mode.equals("outage-stop")) System.out.println("PASS offline shutdown returned with journal retained");
    }

    private static VariableChange change(int i) {
        return new VariableChange("key" + i, "string", ("值," + i + "\n\"").getBytes(StandardCharsets.UTF_8));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void await(Path marker) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
        while (!Files.exists(marker)) {
            require(System.nanoTime() < deadline, "timeout waiting for " + marker.getFileName());
            Thread.sleep(20);
        }
    }
}
