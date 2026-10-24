package re.imc.skriptextra.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** One ordered SQL writer, backed by a bounded disk spool rather than an unbounded heap queue. */
public final class JdbcWriter implements AutoCloseable {
    private final JdbcOptions options;
    private final DiskJournal journal;
    private final Logger logger;
    private final Consumer<String> fatal;
    private final String guid = UUID.randomUUID().toString();
    private final AtomicBoolean failed = new AtomicBoolean();
    private Connection connection;
    private IsolatedJdbcDriver driver;
    private PreparedStatement upsert, delete;
    private ScheduledExecutorService syncer;
    private Thread worker;
    private volatile boolean stopping;
    private volatile long closeDeadline = Long.MAX_VALUE;
    private long offset = DiskJournal.HEADER_BYTES;
    private long lastWarning;
    private final Object wakeSignal = new Object();

    public JdbcWriter(JdbcOptions options, Path directory, Logger logger, Consumer<String> fatal) throws IOException {
        this.options = options;
        this.logger = logger;
        this.fatal = fatal;
        journal = new DiskJournal(directory, options.identity(), options.maxJournalBytes());
    }

    /** Complete recovery before Skript is allowed to load any database values. */
    public void initialize() throws SQLException, IOException {
        openConnection();
        ensureSchema();
        int recovered = 0;
        while (true) {
            DiskJournal.Batch batch = journal.read(offset, options.batchSize());
            if (batch.changes().isEmpty()) break;
            journal.force();
            commit(batch.changes());
            recovered += batch.changes().size();
            offset = journal.compact(batch.end());
        }
        if (recovered != 0) logger.info("JDBC 已回放 " + recovered + " 条本地恢复日志: " + options.table());
    }

    public List<VariableChange> load() throws SQLException {
        List<VariableChange> values = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(512);
            try (ResultSet rows = statement.executeQuery("SELECT name,type,value FROM " + quotedTable())) {
                while (rows.next()) {
                    String name = rows.getString(1), type = rows.getString(2);
                    byte[] value = rows.getBytes(3);
                    if (name == null) throw new SQLException("Null variable name", "22000");
                    if (value == null) continue; // Native SQLibrary tombstone.
                    if (type == null) throw new SQLException("Missing serialized type", "22000");
                    values.add(new VariableChange(name, type, value));
                }
            }
        }
        return values;
    }

    public synchronized void start() {
        if (worker != null) return;
        syncer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "SkriptExtra JDBC journal " + options.table());
            thread.setDaemon(true); return thread;
        });
        syncer.scheduleWithFixedDelay(() -> {
            try { journal.force(); }
            catch (IOException e) { fail("无法同步 JDBC 恢复日志；停止服务器以免继续产生未保存变量"); }
        }, options.flushMillis(), options.flushMillis(), TimeUnit.MILLISECONDS);
        worker = new Thread(this::writeLoop, "SkriptExtra JDBC writer " + options.table());
        worker.setDaemon(true);
        worker.start();
    }

    public void append(VariableChange change) throws IOException {
        if (stopping || failed.get()) throw new IOException("JDBC storage is stopping or failed");
        try { journal.append(change); }
        catch (IOException e) {
            fail("JDBC 恢复日志无法追加（磁盘错误或已达容量上限）；停止服务器，保留已有日志");
            throw e;
        }
    }

    /** Syncs accepted changes locally; this is not a database commit acknowledgement. */
    public void flushJournal() throws IOException { journal.force(); }

    private void writeLoop() {
        int failures = 0;
        try {
            while (!failed.get()) {
                DiskJournal.Batch batch = journal.read(offset, options.batchSize());
                if (batch.changes().isEmpty()) {
                    if (stopping) break;
                    pause(options.flushMillis());
                    continue;
                }
                // Force the complete batch before a transaction can be submitted.
                journal.force();
                boolean committed = false;
                while (!committed && !failed.get()) {
                    if (stopping && System.nanoTime() >= closeDeadline) {
                        logger.warning("JDBC 关闭等待超时；未提交变更保留在本地恢复日志，下次启动回放: " + options.table());
                        return;
                    }
                    try {
                        if (connection == null) openConnection();
                        commit(batch.changes());
                        committed = true;
                        if (failures != 0) logger.info("JDBC 已恢复连接并提交积压批次: " + options.table());
                        failures = 0;
                    } catch (SQLException e) {
                        closeConnection();
                        String state = e.getSQLState();
                        if (!retryable(e)) {
                            fail("JDBC 写入被数据库拒绝；变更保留在恢复日志，停止服务器。SQLState="
                                    + state + ", code=" + e.getErrorCode());
                            return;
                        }
                        failures++;
                        long now = System.currentTimeMillis();
                        if (lastWarning == 0 || now - lastWarning >= 10000) {
                            logger.warning("JDBC 暂时不可用，已保留批次并重试；SQLState=" + state
                                    + ", code=" + e.getErrorCode() + ", journalBytes=" + journal.size());
                            lastWarning = now;
                        }
                        pause(Math.min(5000L, (long)options.retryMillis() << Math.min(failures - 1, 5)));
                    }
                }
                if (committed) offset = journal.compact(batch.end());
            }
        } catch (IOException | RuntimeException e) {
            fail("JDBC 写入器或恢复日志发生错误；停止服务器并保留日志供检查");
        } finally {
            closeConnection();
        }
    }

    private void openConnection() throws SQLException {
        if (driver == null) driver = new IsolatedJdbcDriver();
        Connection opened = driver.connect(options.url(), options.properties());
        if (opened == null) throw new SQLException("Driver rejected URL", "08001");
        try {
            // The lock is database-wide, regardless of which host alias reached it.
            String lockName = "skriptextra:" + JdbcOptions.hash(options.database() + "/" + options.table()).substring(0, 48);
            try (PreparedStatement lock = opened.prepareStatement("SELECT GET_LOCK(?,0)")) {
                lock.setString(1, lockName);
                try (ResultSet result = lock.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1)
                        throw new SQLException("Another writer owns this variable table", "55000");
                }
            }
            opened.setAutoCommit(false);
            connection = opened;
        } catch (SQLException e) { opened.close(); throw e; }
    }

    private void ensureSchema() throws SQLException {
        try (Statement q = connection.createStatement()) {
            q.executeUpdate("CREATE TABLE IF NOT EXISTS " + quotedTable() + " ("
                    + "rowid BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "name VARCHAR(380) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL UNIQUE,"
                    + "type VARCHAR(50),value MEDIUMBLOB,update_guid CHAR(36) NOT NULL) ENGINE=InnoDB");
        }
        Set<String> columns = new HashSet<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COLUMN_NAME,COLLATION_NAME,CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            query.setString(1, options.database()); query.setString(2, options.table());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String column = rows.getString(1);
                    columns.add(column);
                    if (column.equals("name")) {
                        String collation = rows.getString(2);
                        if (collation == null || !(collation.endsWith("_bin") || collation.endsWith("_as_cs"))
                                || rows.getLong(3) < 380)
                            throw new SQLException("Variable names need case-sensitive collation and length >= 380", "42000");
                    }
                }
            }
        }
        if (!columns.containsAll(Set.of("name", "type", "value", "update_guid")))
            throw new SQLException("Incompatible variable table columns; use a new table or migrate offline", "42000");
        boolean uniqueName = false;
        Map<String, List<String>> indices = new HashMap<>();
        try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("SHOW INDEX FROM " + quotedTable())) {
            while (rows.next()) {
                if (rows.getInt("Non_unique") == 0) {
                    String column = rows.getString("Column_name");
                    if (rows.getObject("Sub_part") != null) column = "prefix:" + column;
                    indices.computeIfAbsent(rows.getString("Key_name"), key -> new ArrayList<>()).add(column);
                }
            }
        }
        for (List<String> index : indices.values()) if (index.equals(List.of("name"))) uniqueName = true;
        if (!uniqueName) throw new SQLException("Variable name needs a full unique index", "42000");
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            query.setString(1, options.database()); query.setString(2, options.table());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || !"InnoDB".equalsIgnoreCase(rows.getString(1)))
                    throw new SQLException("Transactional InnoDB variable table required", "42000");
            }
        }
        connection.commit();
    }

    private void commit(List<VariableChange> changes) throws SQLException {
        if (upsert == null) {
            upsert = connection.prepareStatement("INSERT INTO " + quotedTable()
                    + " (name,type,value,update_guid) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE "
                    + "type=VALUES(type),value=VALUES(value),update_guid=VALUES(update_guid)");
            delete = connection.prepareStatement("DELETE FROM " + quotedTable() + " WHERE name=?");
        }
        // A variable is state, not an event stream; retain its final state in this batch.
        Map<String, VariableChange> latest = new LinkedHashMap<>();
        for (VariableChange change : changes) latest.put(change.name(), change);
        try {
            upsert.clearBatch(); delete.clearBatch();
            for (VariableChange change : latest.values()) {
                if (change.value() == null) {
                    delete.setString(1, change.name()); delete.addBatch();
                } else {
                    upsert.setString(1, change.name()); upsert.setString(2, change.type());
                    upsert.setBytes(3, change.value()); upsert.setString(4, guid); upsert.addBatch();
                }
            }
            upsert.executeBatch(); delete.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ignored) { }
            throw e;
        }
    }

    private static boolean retryable(SQLException e) {
        // BatchUpdateException may omit SQLState while wrapping a connection failure.
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.add(e);
        boolean transientFailure = false, hasState = false;
        while (!pending.isEmpty()) {
            Throwable cause = pending.removeFirst();
            if (!visited.add(cause)) continue;
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && !state.isEmpty()) {
                    hasState = true;
                    if (state.startsWith("22") || state.startsWith("23") || state.startsWith("28")
                            || state.startsWith("42") || state.equals("55000")) return false;
                    transientFailure |= state.startsWith("08") || state.startsWith("40") || state.startsWith("HYT");
                }
                transientFailure |= sql instanceof SQLTransientException || sql instanceof SQLRecoverableException
                        || sql instanceof SQLNonTransientConnectionException
                        || Set.of(1040, 1053, 1205, 1213, 1927, 2006, 2013).contains(sql.getErrorCode());
                if (sql.getNextException() != null) pending.add(sql.getNextException());
            }
            transientFailure |= cause instanceof java.io.IOException;
            if (cause.getCause() != null) pending.add(cause.getCause());
        }
        // Unknown connection/batch errors without any SQLState retain the log and retry.
        return transientFailure || !hasState;
    }

    private String quotedTable() { return "`" + options.table() + "`"; }

    private void fail(String message) {
        if (failed.compareAndSet(false, true)) fatal.accept(message);
    }

    private void pause(long millis) {
        if (stopping) millis = Math.min(millis, Math.max(0L,
                TimeUnit.NANOSECONDS.toMillis(closeDeadline - System.nanoTime())));
        if (millis <= 0) return;
        synchronized (wakeSignal) {
            try { wakeSignal.wait(millis); } catch (InterruptedException ignored) { }
        }
    }

    private void closeConnection() {
        if (upsert != null) try { upsert.close(); } catch (SQLException ignored) { }
        if (delete != null) try { delete.close(); } catch (SQLException ignored) { }
        upsert = null; delete = null;
        if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
        connection = null;
    }

    @Override public void close() throws IOException {
        stopping = true;
        closeDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.shutdownTimeoutMillis());
        if (worker != null) {
            // Interrupting a FileChannel writer can close the journal itself. Wake sleeps without interruption.
            synchronized (wakeSignal) { wakeSignal.notifyAll(); }
            try { worker.join((long)options.shutdownTimeoutMillis() + options.connectTimeoutMillis()
                    + options.socketTimeoutMillis() + 1000L); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (worker.isAlive()) {
                // Leave the journal/lock open: closing under an active writer would race recovery.
                throw new IOException("JDBC writer did not stop within its bounded shutdown deadline");
            }
        } else closeConnection();
        if (syncer != null) {
            syncer.shutdown();
            try { syncer.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        journal.close();
        if (driver != null) driver.close();
    }
}
