package dev.junxiex.mikuprotect.service;

import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.model.ContainerChange;
import dev.junxiex.mikuprotect.storage.Storage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small on-failure/shutdown write-ahead log. Activities are only written here when the database
 * write failed or the queue still holds records at shutdown. Replay happens before the writer
 * thread starts.
 *
 * <p>The file is a simple length-prefixed binary stream; partial trailing records are ignored on
 * replay so a crash in the middle of an append does not block startup.</p>
 */
public final class WalService {

    private static final int MAGIC = 0x4D505741;
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    private final Path file;
    private final Logger logger;
    private final boolean enabled;

    public WalService(Path directory, Logger logger, boolean enabled) {
        this.file = directory.resolve("pending.wal");
        this.logger = logger;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void append(List<ActivityRecord> records) {
        if (!enabled || records == null || records.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            boolean fresh = !Files.exists(file) || Files.size(file) == 0L;
            try (OutputStream output = Files.newOutputStream(file, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(output))) {
                if (fresh) {
                    out.writeInt(MAGIC);
                    out.writeByte(VERSION);
                }
                out.writeInt(records.size());
                for (ActivityRecord record : records) {
                    writeRecord(out, record);
                }
            }
            logger.warning("Database write failed or shutdown incomplete; wrote " + records.size()
                    + " records to " + file);
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Failed to append write-ahead log " + file, exception);
        }
    }

    public int replay(Storage storage) {
        if (!enabled || storage == null || !Files.exists(file)) {
            return 0;
        }
        List<ActivityRecord> records = read();
        if (records.isEmpty()) {
            cleanup();
            return 0;
        }
        try {
            storage.write(records);
            cleanup();
            logger.info("Replayed " + records.size() + " records from " + file);
            return records.size();
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "WAL replay failed; keeping " + file + " for the next start", exception);
            return 0;
        }
    }

    List<ActivityRecord> read() {
        List<ActivityRecord> records = new ArrayList<>();
        if (!Files.exists(file)) {
            return records;
        }
        try (InputStream input = Files.newInputStream(file);
                DataInputStream in = new DataInputStream(new BufferedInputStream(input))) {
            int magic = in.readInt();
            int version = in.readUnsignedByte();
            if (magic != MAGIC || version > VERSION) {
                logger.warning("Unsupported WAL header in " + file);
                return records;
            }
            while (true) {
                int count;
                try {
                    count = in.readInt();
                } catch (EOFException end) {
                    break;
                }
                if (count < 0 || count > 1_000_000) {
                    break;
                }
                for (int index = 0; index < count; index++) {
                    records.add(readRecord(in));
                }
            }
        } catch (EOFException end) {
            logger.warning("Truncated WAL record ignored: " + file);
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "Failed to read WAL " + file, exception);
        }
        return records;
    }

    private void cleanup() {
        try {
            java.nio.file.Path done = file.resolveSibling(file.getFileName() + ".done");
            Files.move(file, done, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(done);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // keep the file; duplicate replay is safer than losing records
            }
        }
    }

    private static void writeRecord(DataOutputStream out, ActivityRecord record) throws IOException {
        out.writeLong(record.time);
        writeString(out, record.worldUuid == null ? null : record.worldUuid.toString());
        writeString(out, record.worldName);
        out.writeInt(record.x);
        out.writeInt(record.y);
        out.writeInt(record.z);
        out.writeInt(record.action == null ? 0 : record.action.id());
        writeString(out, record.userName);
        writeString(out, record.userUuid == null ? null : record.userUuid.toString());
        writeString(out, record.material);
        writeString(out, record.blockData);
        writeString(out, record.oldBlockData);
        writeBytes(out, record.itemData);
        out.writeInt(record.amount);
        writeString(out, record.entityType);
        writeString(out, record.entityUuid == null ? null : record.entityUuid.toString());
        writeString(out, record.targetUser);
        writeString(out, record.message);
        writeBytes(out, record.meta);
        writeBytes(out, record.oldMeta);
        List<ContainerChange> containers = record.containersOrEmpty();
        out.writeInt(containers.size());
        for (ContainerChange change : containers) {
            out.writeInt(change.slot());
            writeBytes(out, change.beforeData());
            writeBytes(out, change.afterData());
            out.writeInt(change.beforeAmount());
            out.writeInt(change.afterAmount());
        }
    }

    private static ActivityRecord readRecord(DataInputStream in) throws IOException {
        long time = in.readLong();
        UUID worldUuid = parseUuid(readString(in));
        String worldName = readString(in);
        int x = in.readInt();
        int y = in.readInt();
        int z = in.readInt();
        ActionType action = ActionType.byId(in.readInt());
        String userName = readString(in);
        UUID userUuid = parseUuid(readString(in));
        String material = readString(in);
        String blockData = readString(in);
        String oldBlockData = readString(in);
        byte[] itemData = readBytes(in);
        int amount = in.readInt();
        String entityType = readString(in);
        UUID entityUuid = parseUuid(readString(in));
        String targetUser = readString(in);
        String message = readString(in);
        byte[] meta = readBytes(in);
        byte[] oldMeta = readBytes(in);
        int count = in.readInt();
        if (count < 0 || count > 4096) {
            throw new IOException("invalid container count: " + count);
        }
        List<ContainerChange> containers = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int slot = in.readInt();
            byte[] before = readBytes(in);
            byte[] after = readBytes(in);
            int beforeAmount = in.readInt();
            int afterAmount = in.readInt();
            containers.add(new ContainerChange(slot, before, after, beforeAmount, afterAmount));
        }
        if (action == null) {
            throw new IOException("unknown action id");
        }
        return ActivityRecord.restored(time, worldUuid, worldName, x, y, z, action, userName, userUuid, material,
                blockData, oldBlockData, itemData, amount, entityType, entityUuid, targetUser, message, meta,
                oldMeta, containers);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        writeBytes(out, value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readString(DataInputStream in) throws IOException {
        byte[] bytes = readBytes(in);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        if (bytes == null) {
            out.writeInt(-1);
            return;
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("WAL value too large: " + bytes.length);
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        if (length > MAX_BYTES) {
            throw new IOException("WAL value too large: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
