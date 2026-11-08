package dev.junxiex.mikuprotect.storage;

import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.model.LookupQuery;
import dev.junxiex.mikuprotect.model.StoredActivity;
import java.util.Collection;
import java.util.List;

/**
 * Persistence contract. Implementations are expected to be thread-safe for a single writer plus
 * concurrent readers.
 */
public interface Storage extends AutoCloseable {

    StorageType type();

    void init() throws StorageException;

    boolean healthy();

    /** Last known name for a player UUID, or null when the UUID has never been written. */
    String userName(java.util.UUID uuid);

    void write(List<ActivityRecord> activities) throws StorageException;

    List<StoredActivity> query(LookupQuery query) throws StorageException;

    /**
     * Query with an explicit row cap. Used by the modification engine, which may legitimately
     * read far more than one lookup page, but still needs a hard ceiling.
     */
    List<StoredActivity> query(LookupQuery query, int rowCap) throws StorageException;

    long count(LookupQuery query) throws StorageException;

    void markRolledBack(Collection<Long> ids, boolean rolledBack) throws StorageException;

    int purge(long beforeTime, String worldName, String userName) throws StorageException;

    void flush();

    @Override
    void close();
}
