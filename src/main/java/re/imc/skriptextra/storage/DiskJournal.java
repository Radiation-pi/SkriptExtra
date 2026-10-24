package re.imc.skriptextra.storage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Ordered disk spool. Only the writer acknowledges records, after SQL commit. */
public final class DiskJournal implements AutoCloseable {
    private static final int MAGIC = 0x53455831;
    static final int HEADER_BYTES = 68;
    private static final int MAX_RECORD = 16 * 1024 * 1024 + 8192;
    private final Path path;
    private final long limit;
    private final byte[] header;
    private final FileChannel lockChannel;
    private final FileLock fileLock;
    private FileChannel channel;
    private boolean dirty;

    public record Batch(List<VariableChange> changes, long end) {}

    public DiskJournal(Path directory, String identity, long limit) throws IOException {
        Files.createDirectories(directory);
        String hash = JdbcOptions.hash(identity);
        this.path = directory.resolve(hash + ".wal");
        this.limit = limit;
        header = ByteBuffer.allocate(HEADER_BYTES).putInt(MAGIC)
                .put(hash.getBytes(StandardCharsets.US_ASCII)).array();
        lockChannel = FileChannel.open(directory.resolve(hash + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("Journal already opened by another writer");
            fileLock = acquired;
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            if (channel.size() == 0) {
                writeFully(channel, ByteBuffer.wrap(header), 0);
                channel.force(true);
                forceDirectory();
            } else {
                ByteBuffer existing = ByteBuffer.allocate(HEADER_BYTES);
                readFully(channel, existing, 0);
                if (!Arrays.equals(existing.array(), header)) throw new IOException("Journal header/identity mismatch");
                validateAndRepairTail();
            }
        } catch (IOException | RuntimeException e) {
            if (channel != null) channel.close();
            if (acquired != null) acquired.close();
            lockChannel.close();
            throw e;
        }
    }

    private void validateAndRepairTail() throws IOException {
        long offset = HEADER_BYTES, size = channel.size();
        while (offset < size) {
            if (size - offset < 8) { repairTail(offset); return; }
            ByteBuffer frame = ByteBuffer.allocate(8);
            readFully(channel, frame, offset);
            frame.flip();
            int length = frame.getInt(), crc = frame.getInt();
            if (length <= 0 || length > MAX_RECORD) throw new IOException("Corrupt journal frame length");
            if (size - offset - 8 < length) { repairTail(offset); return; }
            byte[] payload = new byte[length];
            readFully(channel, ByteBuffer.wrap(payload), offset + 8);
            checkCrc(payload, crc);
            decode(payload);
            offset += 8L + length;
        }
    }

    private void repairTail(long end) throws IOException {
        // An incomplete last append is recoverable. A complete frame with bad CRC is not.
        channel.truncate(end);
        channel.force(true);
    }

    public synchronized void append(VariableChange change) throws IOException {
        byte[] payload = encode(change);
        long end = channel.size();
        if (end + 8L + payload.length > limit) throw new IOException("Journal disk budget exceeded");
        CRC32 crc = new CRC32(); crc.update(payload);
        ByteBuffer frame = ByteBuffer.allocate(payload.length + 8)
                .putInt(payload.length).putInt((int) crc.getValue()).put(payload);
        frame.flip();
        try {
            writeFully(channel, frame, end);
            dirty = true;
        } catch (IOException e) {
            channel.truncate(end);
            throw e;
        }
        notifyAll();
    }

    public synchronized Batch read(long offset, int maximum) throws IOException {
        long size = channel.size();
        List<VariableChange> changes = new ArrayList<>();
        while (offset < size && changes.size() < maximum) {
            ByteBuffer frame = ByteBuffer.allocate(8);
            readFully(channel, frame, offset); frame.flip();
            int length = frame.getInt(), crc = frame.getInt();
            if (length <= 0 || length > MAX_RECORD || offset + 8L + length > size)
                throw new IOException("Invalid journal record");
            byte[] payload = new byte[length];
            readFully(channel, ByteBuffer.wrap(payload), offset + 8);
            checkCrc(payload, crc);
            changes.add(decode(payload));
            offset += 8L + length;
        }
        return new Batch(changes, offset);
    }

    public synchronized void force() throws IOException {
        if (dirty) { channel.force(true); dirty = false; }
    }

    public synchronized long size() throws IOException { return channel.size(); }

    /** Caller passes a committed prefix. Replacement is atomic and fsynced. */
    public synchronized long compact(long committed) throws IOException {
        long size = channel.size();
        if (committed == HEADER_BYTES) return committed;
        if (committed == size) {
            channel.truncate(HEADER_BYTES);
            channel.force(true);
            dirty = false;
            return HEADER_BYTES;
        }
        if (committed - HEADER_BYTES < 4 * 1024 * 1024 || committed < size / 2) return committed;
        Path temporary = path.resolveSibling(path.getFileName() + ".compact");
        try (FileChannel replacement = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writeFully(replacement, ByteBuffer.wrap(header), 0);
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            long read = committed, write = HEADER_BYTES;
            while (read < size) {
                buffer.clear(); buffer.limit((int)Math.min(buffer.capacity(), size - read));
                readFully(channel, buffer, read); buffer.flip();
                int bytes = buffer.remaining();
                writeFully(replacement, buffer, write);
                read += bytes; write += bytes;
            }
            replacement.force(true);
        }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        channel.close();
        channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        forceDirectory();
        dirty = false;
        return HEADER_BYTES;
    }

    private void forceDirectory() throws IOException {
        // Directory fsync is supported on the deployment's Linux filesystem.
        // Other platforms may not expose directory handles through FileChannel.
        try (FileChannel directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
            directory.force(true);
        } catch (AccessDeniedException | UnsupportedOperationException ignored) { }
    }

    private static void checkCrc(byte[] payload, int expected) throws IOException {
        CRC32 crc = new CRC32(); crc.update(payload);
        if ((int)crc.getValue() != expected) throw new IOException("Journal checksum mismatch; restore/inspect before startup");
    }

    private static byte[] encode(VariableChange change) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writeString(out, change.name());
            writeString(out, change.type());
            byte[] value = change.value();
            out.writeInt(value == null ? -1 : value.length);
            if (value != null) out.write(value);
        }
        if (bytes.size() > MAX_RECORD) throw new IOException("Serialized variable exceeds journal record limit");
        return bytes.toByteArray();
    }

    private static VariableChange decode(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            String name = readString(in), type = readString(in);
            int length = in.readInt();
            if (length < -1 || length > in.available()) throw new IOException("Invalid journal value length");
            byte[] value = length == -1 ? null : in.readNBytes(length);
            if (in.available() != 0 || name == null || (value != null && type == null))
                throw new IOException("Invalid serialized journal variable");
            return new VariableChange(name, type, value);
        }
    }

    private static void writeString(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text == null ? null : text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes == null ? -1 : bytes.length);
        if (bytes != null) out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length == -1) return null;
        if (length < 0 || length > in.available()) throw new IOException("Invalid journal string length");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void readFully(FileChannel file, ByteBuffer buffer, long offset) throws IOException {
        while (buffer.hasRemaining()) {
            int read = file.read(buffer, offset);
            if (read <= 0) throw new EOFException("Truncated journal");
            offset += read;
        }
    }

    private static void writeFully(FileChannel file, ByteBuffer buffer, long offset) throws IOException {
        while (buffer.hasRemaining()) {
            int wrote = file.write(buffer, offset);
            if (wrote <= 0) throw new IOException("Cannot append journal");
            offset += wrote;
        }
    }

    @Override public synchronized void close() throws IOException {
        try { if (channel.isOpen()) { force(); channel.close(); } }
        finally { fileLock.close(); lockChannel.close(); }
    }
}
