package dev.junxiex.mikuprotect.storage;

import dev.junxiex.mikuprotect.config.MikuConfig;
import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import dev.junxiex.mikuprotect.model.ContainerChange;
import dev.junxiex.mikuprotect.model.LookupQuery;
import dev.junxiex.mikuprotect.model.StoredActivity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JDBC 存储骨架：驱动加载、建表、主键分配、批量写入、查询、批量标记、分批清理与关闭。
 *
 * <p>子类只实现方言差异：驱动名、连接建立、DDL 选择与 mp_meta 的 upsert 语法。</p>
 *
 * <p>主键全部由应用侧分配（{@link IdRegistry}），插入时显式写 id，不使用数据库自增。
 * 分配水位只在启动时从库中读取，因此同一份数据库只能由一个服务端写入，多服共库不受支持。</p>
 */
public abstract class JdbcStorage implements Storage {

    /** 多行 VALUES 每条语句的行数，兼顾往返次数与占位符上限。 */
    protected static final int INSERT_ROWS_PER_STATEMENT = 200;

    /** 单条 INSERT 的估算负载上限，避免 MariaDB max_allowed_packet 被大 NBT 撑爆。 */
    private static final int MAX_INSERT_BYTES = 8 * 1024 * 1024;

    /** UPDATE/DELETE 的 IN 列表每批 id 数。 */
    protected static final int IDS_PER_STATEMENT = 500;

    /** 回填容器时 IN 查询每批 id 数。 */
    private static final int CONTAINER_IDS_PER_STATEMENT = 500;

    /** 大批量查询的硬上限，防止调用方误传过大的 rowCap。 */
    public static final int MAX_ROW_CAP = 1_000_000;

    /** 世界名缺失时的占位符，world_id 有 NOT NULL 约束。 */
    private static final String FALLBACK_WORLD = "unknown";

    private static final String META_SELECT = "SELECT v FROM mp_meta WHERE k = ?";

    private static final String ACTIVITY_SELECT = """
            SELECT a.id AS id, a.time AS time, w.name AS world_name, w.uuid AS world_uuid,
                   a.x AS x, a.y AS y, a.z AS z, a.action AS action,
                   u.name AS user_name, u.uuid AS user_uuid, m.name AS material_name,
                   a.block_data AS block_data, a.old_block_data AS old_block_data,
                   a.item_data AS item_data, a.amount AS amount,
                   a.entity_type AS entity_type, a.entity_uuid AS entity_uuid,
                   a.target_user AS target_user, a.message AS message,
                   a.meta AS meta, a.old_meta AS old_meta, a.rolled_back AS rolled_back
            FROM mp_activity a
            LEFT JOIN mp_world w ON w.id = a.world_id
            LEFT JOIN mp_user u ON u.id = a.user_id
            LEFT JOIN mp_material m ON m.id = a.material_id
            """;

    /** 条件只引用 a.*，因此计数不需要 join 维度表。 */
    private static final String ACTIVITY_COUNT = "SELECT COUNT(*) FROM mp_activity a";

    private static final String CONTAINER_SELECT =
            "SELECT activity_id, slot, before_data, after_data, before_amount, after_amount FROM mp_container";

    private static final String PURGE_SELECT = "SELECT id FROM mp_activity";

    private static final String ACTIVITY_DELETE = "DELETE FROM mp_activity WHERE id IN ";
    private static final String CONTAINER_DELETE = "DELETE FROM mp_container WHERE activity_id IN ";
    private static final String ACTIVITY_MARK = "UPDATE mp_activity SET rolled_back = ? WHERE id IN ";

    private static final String WORLD_INSERT = "INSERT INTO mp_world(id, name, uuid) VALUES ";
    private static final String USER_INSERT = "INSERT INTO mp_user(id, name, uuid, first_seen, last_seen) VALUES ";
    private static final String MATERIAL_INSERT = "INSERT INTO mp_material(id, name) VALUES ";
    private static final String ACTIVITY_INSERT = "INSERT INTO mp_activity(id, time, world_id, x, y, z, action,"
            + " user_id, material_id, block_data, old_block_data, item_data, amount, entity_type, entity_uuid,"
            + " target_user, message, old_message, meta, old_meta, rolled_back) VALUES ";
    private static final String CONTAINER_INSERT = "INSERT INTO mp_container(id, activity_id, slot, before_data,"
            + " after_data, before_amount, after_amount) VALUES ";
    private static final String USER_TOUCH = "UPDATE mp_user SET first_seen = COALESCE(first_seen, ?),"
            + " last_seen = GREATEST(COALESCE(last_seen, 0), ?) WHERE id = ?";

    private static final int WORLD_COLUMNS = 3;
    private static final int USER_COLUMNS = 5;
    private static final int MATERIAL_COLUMNS = 2;
    private static final int ACTIVITY_COLUMNS = 21;
    private static final int CONTAINER_COLUMNS = 7;

    private final StorageType type;
    private final IdRegistry registry = new IdRegistry();
    private final Map<UUID, String> userNames = new ConcurrentHashMap<>();
    private final ReentrantLock writeGuard = new ReentrantLock();

    protected volatile MikuConfig config;
    protected final Logger logger;

    private volatile boolean ready;
    private volatile boolean closed;

    protected JdbcStorage(StorageType type, MikuConfig config, Logger logger) {
        this.type = Objects.requireNonNull(type, "type");
        this.config = Objects.requireNonNull(config, "config");
        this.logger = logger == null ? Logger.getLogger("MikuProtect") : logger;
    }

    @Override
    public final StorageType type() {
        return type;
    }

    @Override
    public void updateConfig(MikuConfig next) {
        if (next != null) {
            this.config = next;
        }
    }

    @Override
    public final void init() throws StorageException {
        if (ready) {
            return;
        }
        if (closed) {
            throw new StorageException("存储已关闭，不能重新初始化");
        }
        try {
            loadDriver();
            connect();
        } catch (ClassNotFoundException failure) {
            throw new StorageException("缺少 JDBC 驱动: " + failure.getMessage(), failure);
        } catch (SQLException failure) {
            throw new StorageException("连接数据库失败: " + failure.getMessage(), failure);
        } catch (RuntimeException failure) {
            throw new StorageException("初始化存储失败: " + failure.getMessage(), failure);
        }
        try {
            createSchema();
            loadCaches();
        } catch (StorageException failure) {
            shutdownQuietly();
            throw failure;
        } catch (RuntimeException failure) {
            shutdownQuietly();
            throw new StorageException("初始化存储结构失败: " + failure.getMessage(), failure);
        }
        ready = true;
        logger.info("存储已就绪: " + type + "，schema 版本 " + Schema.VERSION
                + "，world " + registry.worlds().size() + " / user " + registry.users().size()
                + " / material " + registry.materials().size());
    }

    @Override
    public final boolean healthy() {
        return ready && !closed;
    }

    @Override
    public final void write(List<ActivityRecord> activities) throws StorageException {
        if (activities == null || activities.isEmpty()) {
            return;
        }
        ensureReady();
        writeGuard.lock();
        try {
            PersistedNames persisted = transaction(connection -> {
                prepareBatch(activities);
                PersistedNames names = insertDimensions(connection, activities);
                insertActivities(connection, activities);
                insertContainers(connection, activities);
                return names;
            });
            registry.worlds().markPersisted(persisted.worlds());
            registry.users().markPersisted(persisted.users());
            registry.materials().markPersisted(persisted.materials());
            rememberUserNames(activities);
        } finally {
            writeGuard.unlock();
        }
    }

    /** 分页查询：行数取 query.limit，并被配置的 maxPageSize 截断。 */
    @Override
    public final List<StoredActivity> query(LookupQuery lookup) throws StorageException {
        ensureReady();
        return execute(QuerySql.forLookup(lookup, registry, config.maxPageSize));
    }

    /**
     * 大批量查询：行数直接取 rowCap，供回滚等需要超过一页的场景使用。
     *
     * <p>截断规则：rowCap 在 [1, {@link #MAX_ROW_CAP}] 内按原值；大于硬上限取硬上限；
     * 非正数回落到配置的页大小。分页偏移仍按 query.page 计算，普通查询走另一个重载。</p>
     */
    @Override
    public final List<StoredActivity> query(LookupQuery lookup, int rowCap) throws StorageException {
        ensureReady();
        int fallback = Math.max(1, config.maxPageSize);
        int cap = rowCap <= 0 ? fallback : Math.min(rowCap, MAX_ROW_CAP);
        return execute(QuerySql.forScan(lookup, registry, cap));
    }

    /** 执行一条已拼好的查询，并一次性回填容器变更。 */
    private List<StoredActivity> execute(QuerySql sql) throws StorageException {
        String text = ACTIVITY_SELECT + sql.whereClause() + sql.orderClause() + sql.pageClause();
        return read(connection -> {
            List<StoredActivity> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(text)) {
                bindAll(statement, sql.params());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        StoredActivity activity = readActivity(resultSet);
                        if (activity != null) {
                            rows.add(activity);
                        }
                    }
                }
            }
            if (rows.isEmpty()) {
                return rows;
            }
            List<Long> ids = new ArrayList<>(rows.size());
            for (StoredActivity row : rows) {
                ids.add(row.id());
            }
            Map<Long, List<StoredActivity.StoredContainer>> containers = loadContainers(connection, ids);
            if (containers.isEmpty()) {
                return rows;
            }
            List<StoredActivity> result = new ArrayList<>(rows.size());
            for (StoredActivity row : rows) {
                List<StoredActivity.StoredContainer> changed = containers.get(row.id());
                result.add(changed == null || changed.isEmpty() ? row : withContainers(row, changed));
            }
            return result;
        });
    }

    @Override
    public final long count(LookupQuery lookup) throws StorageException {
        ensureReady();
        QuerySql sql = QuerySql.forCount(lookup, registry);
        String text = ACTIVITY_COUNT + sql.whereClause();
        Long total = read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(text)) {
                bindAll(statement, sql.params());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return Long.valueOf(resultSet.next() ? resultSet.getLong(1) : 0L);
                }
            }
        });
        return total == null ? 0L : total.longValue();
    }

    @Override
    public final void markRolledBack(Collection<Long> ids, boolean rolledBack) throws StorageException {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        ensureReady();
        Set<Long> deduped = new LinkedHashSet<>(ids.size());
        for (Long id : ids) {
            if (id != null) {
                deduped.add(id);
            }
        }
        if (deduped.isEmpty()) {
            return;
        }
        List<Long> unique = new ArrayList<>(deduped);
        writeGuard.lock();
        try {
            transaction(connection -> {
                byte flag = (byte) (rolledBack ? 1 : 0);
                for (int start = 0; start < unique.size(); start += IDS_PER_STATEMENT) {
                    int end = Math.min(unique.size(), start + IDS_PER_STATEMENT);
                    List<Long> chunk = unique.subList(start, end);
                    String text = ACTIVITY_MARK + placeholders(chunk.size());
                    try (PreparedStatement statement = connection.prepareStatement(text)) {
                        statement.setByte(1, flag);
                        int index = 2;
                        for (Long id : chunk) {
                            statement.setLong(index++, id);
                        }
                        statement.executeUpdate();
                    }
                }
                return null;
            });
        } finally {
            writeGuard.unlock();
        }
    }

    @Override
    public final int purge(long beforeTime, String worldName, String userName) throws StorageException {
        ensureReady();
        int batch = Math.max(1, config.purgeBatch);
        int removed = 0;
        while (true) {
            writeGuard.lock();
            Integer deleted;
            try {
                deleted = transaction(connection -> {
                    List<Long> ids = selectPurgeIds(connection, beforeTime, worldName, userName, batch);
                    if (ids.isEmpty()) {
                        return Integer.valueOf(0);
                    }
                    deleteByIds(connection, CONTAINER_DELETE, ids);
                    return Integer.valueOf(deleteByIds(connection, ACTIVITY_DELETE, ids));
                });
            } finally {
                writeGuard.unlock();
            }
            int current = deleted == null ? 0 : deleted.intValue();
            removed += current;
            if (current <= 0) {
                break;
            }
        }
        return removed;
    }

    @Override
    public final void flush() {
        // 每条写入都在事务提交时落盘，这里没有待刷写的数据
    }

    @Override
    public final void close() {
        if (closed) {
            return;
        }
        closed = true;
        ready = false;
        shutdownQuietly();
    }

    /** 在一次写事务中执行工作，失败回滚并抛 {@link StorageException}。 */
    protected final <T> T transaction(SqlWork<T> work) throws StorageException {
        Channel channel;
        try {
            channel = openWriteChannel();
        } catch (SQLException failure) {
            throw new StorageException("获取写连接失败: " + failure.getMessage(), failure);
        }
        try {
            Connection connection = channel.connection();
            boolean autoCommit = connection.getAutoCommit();
            try {
                if (autoCommit) {
                    connection.setAutoCommit(false);
                }
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection);
                throw new StorageException("数据库事务失败: " + failure.getMessage(), failure);
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException failure) {
            throw new StorageException("数据库事务失败: " + failure.getMessage(), failure);
        } finally {
            channel.close();
        }
    }

    /** 借一个读连接执行工作。 */
    protected final <T> T read(SqlWork<T> work) throws StorageException {
        Channel channel;
        try {
            channel = openReadChannel();
        } catch (SQLException failure) {
            throw new StorageException("获取读连接失败: " + failure.getMessage(), failure);
        }
        try {
            return work.apply(channel.connection());
        } catch (SQLException failure) {
            throw new StorageException("查询失败: " + failure.getMessage(), failure);
        } catch (RuntimeException failure) {
            throw new StorageException("查询失败: " + failure.getMessage(), failure);
        } finally {
            channel.close();
        }
    }

    protected static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 关闭失败不影响上层流程
        }
    }

    // ------------------------------------------------------------------
    // 方言钩子
    // ------------------------------------------------------------------

    protected abstract void loadDriver() throws ClassNotFoundException;

    /** 建立写/读连接或连接池。 */
    protected abstract void connect() throws SQLException;

    /** 占用写通道，释放由返回值的 close 负责。 */
    protected abstract Channel openWriteChannel() throws SQLException;

    /** 占用读通道，释放由返回值的 close 负责。 */
    protected abstract Channel openReadChannel() throws SQLException;

    /** 方言标识，用于选择 DDL。 */
    protected abstract String dialect();

    /** mp_meta 的 upsert 语句，两个参数依次是键与值。 */
    protected abstract String metaUpsertSql();

    /** 释放连接资源，不允许抛异常。 */
    protected abstract void shutdown();

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private void ensureReady() throws StorageException {
        if (!ready) {
            throw new StorageException("存储层尚未初始化");
        }
    }

    private void shutdownQuietly() {
        try {
            shutdown();
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING, "释放存储资源失败", failure);
        }
    }

    private void createSchema() throws StorageException {
        String dialect = dialect();
        transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String ddl : Schema.tables(dialect)) {
                    statement.execute(ddl);
                }
                for (String ddl : Schema.indexes(dialect)) {
                    try {
                        statement.execute(ddl);
                    } catch (SQLException indexFailure) {
                        // 索引不是正确性的必要条件：老库或引擎差异导致重复/不支持时继续启动
                        logger.warning("索引创建失败（不影响读写，查询可能变慢）: " + indexFailure.getMessage());
                    }
                }
            }
            verifyVersion(connection);
            writeVersion(connection);
            return null;
        });
    }

    private void verifyVersion(Connection connection) throws SQLException {
        Long version = null;
        try (PreparedStatement statement = connection.prepareStatement(META_SELECT)) {
            statement.setString(1, Schema.VERSION_KEY);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    version = parseLong(resultSet.getString(1));
                }
            }
        }
        if (version != null && version > Schema.VERSION) {
            throw new SQLException("数据库 schema 版本 " + version + " 高于插件支持的 " + Schema.VERSION);
        }
    }

    private void writeVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(metaUpsertSql())) {
            statement.setString(1, Schema.VERSION_KEY);
            statement.setString(2, Integer.toString(Schema.VERSION));
            statement.executeUpdate();
        }
    }

    private void loadCaches() throws StorageException {
        transaction(connection -> {
            loadNames(connection, "SELECT id, name FROM mp_world", registry.worlds());
            loadNames(connection, "SELECT id, name FROM mp_user", registry.users());
            loadNames(connection, "SELECT id, name FROM mp_material", registry.materials());
            loadUserNames(connection);
            registry.seedActivities(selectMaxId(connection, "SELECT COALESCE(MAX(id), 0) FROM mp_activity"));
            registry.seedContainers(selectMaxId(connection, "SELECT COALESCE(MAX(id), 0) FROM mp_container"));
            return null;
        });
    }

    private static void loadNames(Connection connection, String sql, IdRegistry.NameTable table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                table.load(resultSet.getString(2), resultSet.getLong(1));
            }
        }
    }

    private void loadUserNames(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT uuid, name FROM mp_user WHERE uuid IS NOT NULL")) {
            while (resultSet.next()) {
                UUID uuid = parseUuid(resultSet.getString(1));
                String name = resultSet.getString(2);
                if (uuid != null && name != null && !name.isBlank()) {
                    userNames.put(uuid, name);
                }
            }
        }
    }

    private void rememberUserNames(List<ActivityRecord> batch) {
        for (ActivityRecord record : batch) {
            if (record.userUuid != null && record.userName != null && !record.userName.isBlank()) {
                userNames.put(record.userUuid, record.userName);
            }
        }
    }

    @Override
    public final String userName(UUID uuid) {
        return uuid == null ? null : userNames.get(uuid);
    }

    private static long selectMaxId(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    private void prepareBatch(List<ActivityRecord> batch) {
        for (ActivityRecord record : batch) {
            record.id = registry.nextActivityId();
            registry.worlds().assign(worldName(record));
            if (record.userName != null && !record.userName.isBlank()) {
                registry.users().assign(record.userName);
            }
            if (record.material != null && !record.material.isBlank()) {
                registry.materials().assign(record.material);
            }
        }
    }

    private PersistedNames insertDimensions(Connection connection, List<ActivityRecord> batch) throws SQLException {
        Map<String, UUID> worlds = new LinkedHashMap<>();
        Map<String, UUID> users = new LinkedHashMap<>();
        Map<String, Long> firstSeen = new LinkedHashMap<>();
        Map<String, Long> lastSeen = new LinkedHashMap<>();
        Set<String> materials = new LinkedHashSet<>();
        for (ActivityRecord record : batch) {
            worlds.putIfAbsent(worldName(record), record.worldUuid);
            if (record.userName != null && !record.userName.isBlank()) {
                users.putIfAbsent(record.userName, record.userUuid);
                firstSeen.merge(record.userName, record.time, (left, right) -> Math.min(left, right));
                lastSeen.merge(record.userName, record.time, (left, right) -> Math.max(left, right));
            }
            if (record.material != null && !record.material.isBlank()) {
                materials.add(record.material);
            }
        }
        List<String> pendingWorlds = pendingOf(registry.worlds(), worlds.keySet());
        List<String> pendingUsers = pendingOf(registry.users(), users.keySet());
        List<String> pendingMaterials = pendingOf(registry.materials(), materials);

        insertRows(connection, WORLD_INSERT, WORLD_COLUMNS, pendingWorlds, (statement, index, name) -> {
            int cursor = bindLong(statement, index, requireId(registry.worlds(), name));
            cursor = bindString(statement, cursor, name);
            bindString(statement, cursor, uuidText(worlds.get(name)));
        });
        insertRows(connection, USER_INSERT, USER_COLUMNS, pendingUsers, (statement, index, name) -> {
            int cursor = bindLong(statement, index, requireId(registry.users(), name));
            cursor = bindString(statement, cursor, name);
            cursor = bindString(statement, cursor, uuidText(users.get(name)));
            cursor = bindLong(statement, cursor, firstSeen.getOrDefault(name, 0L));
            bindLong(statement, cursor, lastSeen.getOrDefault(name, 0L));
        });
        touchWorldUuids(connection, worlds);
        insertRows(connection, MATERIAL_INSERT, MATERIAL_COLUMNS, pendingMaterials, (statement, index, name) -> {
            int cursor = bindLong(statement, index, requireId(registry.materials(), name));
            bindString(statement, cursor, name);
        });
        touchUsers(connection, users.keySet(), firstSeen, lastSeen);
        return new PersistedNames(pendingWorlds, pendingUsers, pendingMaterials);
    }

    /**
     * Keep mp_world.uuid aligned with the latest record. A world name can survive a map reset
     * with a new UUID; rolls must resolve to the current Bukkit world, not a stale UUID.
     */
    private void touchWorldUuids(Connection connection, Map<String, UUID> worlds) throws SQLException {
        if (worlds.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("UPDATE mp_world SET uuid = ? WHERE id = ?")) {
            for (Map.Entry<String, UUID> entry : worlds.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                statement.setString(1, entry.getValue().toString());
                statement.setLong(2, requireId(registry.worlds(), entry.getKey()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void touchUsers(Connection connection, Set<String> names, Map<String, Long> firstSeen,
            Map<String, Long> lastSeen) throws SQLException {
        if (names.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(USER_TOUCH)) {
            for (String name : names) {
                statement.setLong(1, firstSeen.getOrDefault(name, 0L));
                statement.setLong(2, lastSeen.getOrDefault(name, 0L));
                statement.setLong(3, requireId(registry.users(), name));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertActivities(Connection connection, List<ActivityRecord> batch) throws SQLException {
        int start = 0;
        while (start < batch.size()) {
            int end = nextActivityEnd(batch, start);
            List<ActivityRecord> chunk = batch.subList(start, end);
            StringBuilder sql = new StringBuilder(ACTIVITY_INSERT.length() + chunk.size() * ACTIVITY_COLUMNS * 3);
            sql.append(ACTIVITY_INSERT);
            appendValues(sql, ACTIVITY_COLUMNS, chunk.size());
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                for (ActivityRecord record : chunk) {
                    index = bindActivity(statement, index, record);
                }
                statement.executeUpdate();
            }
            start = end;
        }
    }

    private void insertContainers(Connection connection, List<ActivityRecord> batch) throws SQLException {
        List<ContainerRow> rows = new ArrayList<>();
        for (ActivityRecord record : batch) {
            List<ContainerChange> changes = record.containersOrEmpty();
            for (ContainerChange change : changes) {
                if (change != null) {
                    rows.add(new ContainerRow(record.id, change));
                }
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        int start = 0;
        while (start < rows.size()) {
            int end = nextContainerEnd(rows, start);
            List<ContainerRow> chunk = rows.subList(start, end);
            StringBuilder sql = new StringBuilder(CONTAINER_INSERT.length() + chunk.size() * CONTAINER_COLUMNS * 3);
            sql.append(CONTAINER_INSERT);
            appendValues(sql, CONTAINER_COLUMNS, chunk.size());
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                for (ContainerRow row : chunk) {
                    ContainerChange change = row.change();
                    index = bindLong(statement, index, registry.nextContainerId());
                    index = bindLong(statement, index, row.activityId());
                    index = bindInt(statement, index, change.slot());
                    index = bindBytes(statement, index, change.beforeData());
                    index = bindBytes(statement, index, change.afterData());
                    index = bindInt(statement, index, change.beforeAmount());
                    index = bindInt(statement, index, change.afterAmount());
                }
                statement.executeUpdate();
            }
            start = end;
        }
    }

    private static int nextActivityEnd(List<ActivityRecord> batch, int start) {
        int end = start;
        long bytes = 0L;
        while (end < batch.size() && end - start < INSERT_ROWS_PER_STATEMENT) {
            int size = estimateActivity(batch.get(end));
            if (end > start && bytes + size > MAX_INSERT_BYTES) {
                break;
            }
            bytes += size;
            end++;
        }
        return end;
    }

    private static int nextContainerEnd(List<ContainerRow> rows, int start) {
        int end = start;
        long bytes = 0L;
        while (end < rows.size() && end - start < INSERT_ROWS_PER_STATEMENT) {
            ContainerChange change = rows.get(end).change();
            int size = 64 + length(change.beforeData()) + length(change.afterData());
            if (end > start && bytes + size > MAX_INSERT_BYTES) {
                break;
            }
            bytes += size;
            end++;
        }
        return end;
    }

    private static int estimateActivity(ActivityRecord record) {
        long bytes = 96L;
        bytes += length(record.blockData) + length(record.oldBlockData) + length(record.message)
                + length(record.material) + length(record.entityType) + length(record.targetUser)
                + length(record.userName) + length(record.worldName);
        bytes += length(record.itemData) + length(record.meta) + length(record.oldMeta);
        for (ContainerChange change : record.containersOrEmpty()) {
            bytes += 48L + length(change.beforeData()) + length(change.afterData());
        }
        return (int) Math.min(Integer.MAX_VALUE, bytes);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length() * 2;
    }

    private static int length(byte[] value) {
        return value == null ? 0 : value.length;
    }

    private int bindActivity(PreparedStatement statement, int index, ActivityRecord record) throws SQLException {
        int cursor = index;
        cursor = bindLong(statement, cursor, record.id);
        cursor = bindLong(statement, cursor, record.time);
        cursor = bindLong(statement, cursor, requireId(registry.worlds(), worldName(record)));
        cursor = bindInt(statement, cursor, record.x);
        cursor = bindInt(statement, cursor, record.y);
        cursor = bindInt(statement, cursor, record.z);
        cursor = bindShort(statement, cursor, (short) record.action.id());
        cursor = bindNullableLong(statement, cursor, nullableId(registry.users(), record.userName));
        cursor = bindNullableLong(statement, cursor, nullableId(registry.materials(), record.material));
        cursor = bindString(statement, cursor, record.blockData);
        cursor = bindString(statement, cursor, record.oldBlockData);
        cursor = bindBytes(statement, cursor, record.itemData);
        cursor = bindInt(statement, cursor, record.amount);
        cursor = bindString(statement, cursor, record.entityType);
        cursor = bindString(statement, cursor, record.entityUuid == null ? null : record.entityUuid.toString());
        cursor = bindString(statement, cursor, record.targetUser);
        cursor = bindString(statement, cursor, record.message);
        cursor = bindString(statement, cursor, null);
        cursor = bindBytes(statement, cursor, record.meta);
        cursor = bindBytes(statement, cursor, record.oldMeta);
        return bindByte(statement, cursor, (byte) 0);
    }

    private static StoredActivity readActivity(ResultSet resultSet) throws SQLException {
        ActionType action = ActionType.byId(resultSet.getInt("action"));
        if (action == null) {
            return null;
        }
        return new StoredActivity(
                resultSet.getLong("id"),
                resultSet.getLong("time"),
                parseUuid(resultSet.getString("world_uuid")),
                resultSet.getString("world_name"),
                resultSet.getInt("x"),
                resultSet.getInt("y"),
                resultSet.getInt("z"),
                action,
                resultSet.getString("user_name"),
                parseUuid(resultSet.getString("user_uuid")),
                resultSet.getString("material_name"),
                resultSet.getString("block_data"),
                resultSet.getString("old_block_data"),
                readBytes(resultSet, "item_data"),
                resultSet.getInt("amount"),
                resultSet.getString("entity_type"),
                parseUuid(resultSet.getString("entity_uuid")),
                resultSet.getString("target_user"),
                resultSet.getString("message"),
                readBytes(resultSet, "meta"),
                readBytes(resultSet, "old_meta"),
                resultSet.getInt("rolled_back") != 0,
                List.of());
    }

    private static byte[] readBytes(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        if (value instanceof java.sql.Blob blob) {
            return blob.getBytes(1, (int) blob.length());
        }
        if (value instanceof java.io.InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (java.io.IOException exception) {
                throw new SQLException("无法读取 BLOB 列 " + column, exception);
            }
        }
        return resultSet.getBytes(column);
    }

    private static StoredActivity withContainers(StoredActivity activity,
            List<StoredActivity.StoredContainer> containers) {
        return new StoredActivity(activity.id(), activity.time(), activity.worldUuid(), activity.worldName(),
                activity.x(), activity.y(), activity.z(), activity.action(), activity.userName(), activity.userUuid(),
                activity.material(), activity.blockData(), activity.oldBlockData(), activity.itemData(),
                activity.amount(), activity.entityType(), activity.entityUuid(), activity.targetUser(),
                activity.message(), activity.meta(), activity.oldMeta(), activity.rolledBack(),
                List.copyOf(containers));
    }

    private static Map<Long, List<StoredActivity.StoredContainer>> loadContainers(Connection connection,
            List<Long> ids) throws SQLException {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<StoredActivity.StoredContainer>> result = new LinkedHashMap<>();
        for (int start = 0; start < ids.size(); start += CONTAINER_IDS_PER_STATEMENT) {
            int end = Math.min(ids.size(), start + CONTAINER_IDS_PER_STATEMENT);
            List<Long> chunk = ids.subList(start, end);
            String text = CONTAINER_SELECT + " WHERE activity_id IN " + placeholders(chunk.size())
                    + " ORDER BY activity_id, slot";
            try (PreparedStatement statement = connection.prepareStatement(text)) {
                int index = 1;
                for (Long id : chunk) {
                    statement.setLong(index++, id);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        long activityId = resultSet.getLong("activity_id");
                        result.computeIfAbsent(activityId, key -> new ArrayList<>())
                                .add(new StoredActivity.StoredContainer(
                                        resultSet.getInt("slot"),
                                        readBytes(resultSet, "before_data"),
                                        readBytes(resultSet, "after_data"),
                                        resultSet.getInt("before_amount"),
                                        resultSet.getInt("after_amount")));
                    }
                }
            }
        }
        return result;
    }

    private List<Long> selectPurgeIds(Connection connection, long beforeTime, String worldName, String userName,
            int batch) throws SQLException {
        StringBuilder text = new StringBuilder(PURGE_SELECT);
        List<String> conditions = new ArrayList<>(3);
        List<Object> params = new ArrayList<>(3);
        if (beforeTime > 0L) {
            conditions.add("time < ?");
            params.add(beforeTime);
        }
        if (worldName != null && !worldName.isBlank()) {
            Long worldId = registry.worlds().id(worldName);
            if (worldId == null) {
                return List.of();
            }
            conditions.add("world_id = ?");
            params.add(worldId);
        }
        if (userName != null && !userName.isBlank()) {
            Long userId = registry.users().id(userName);
            if (userId == null) {
                return List.of();
            }
            conditions.add("user_id = ?");
            params.add(userId);
        }
        if (!conditions.isEmpty()) {
            text.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        text.append(" ORDER BY id LIMIT ").append(batch);
        List<Long> ids = new ArrayList<>(batch);
        try (PreparedStatement statement = connection.prepareStatement(text.toString())) {
            bindAll(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ids.add(resultSet.getLong(1));
                }
            }
        }
        return ids;
    }

    private static int deleteByIds(Connection connection, String sqlPrefix, List<Long> ids) throws SQLException {
        int deleted = 0;
        for (int start = 0; start < ids.size(); start += IDS_PER_STATEMENT) {
            int end = Math.min(ids.size(), start + IDS_PER_STATEMENT);
            List<Long> chunk = ids.subList(start, end);
            String text = sqlPrefix + placeholders(chunk.size());
            try (PreparedStatement statement = connection.prepareStatement(text)) {
                int index = 1;
                for (Long id : chunk) {
                    statement.setLong(index++, id);
                }
                deleted += statement.executeUpdate();
            }
        }
        return deleted;
    }

    private void insertRows(Connection connection, String insertSql, int columns, List<String> names,
            RowBinder binder) throws SQLException {
        for (int start = 0; start < names.size(); start += INSERT_ROWS_PER_STATEMENT) {
            int end = Math.min(names.size(), start + INSERT_ROWS_PER_STATEMENT);
            List<String> chunk = names.subList(start, end);
            StringBuilder sql = new StringBuilder(insertSql.length() + chunk.size() * columns * 3);
            sql.append(insertSql);
            appendValues(sql, columns, chunk.size());
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                for (String name : chunk) {
                    binder.bind(statement, index, name);
                    index += columns;
                }
                statement.executeUpdate();
            }
        }
    }

    private static List<String> pendingOf(IdRegistry.NameTable table, Collection<String> candidates) {
        List<String> result = new ArrayList<>();
        for (String name : table.pendingNames()) {
            if (candidates.contains(name)) {
                result.add(name);
            }
        }
        return result;
    }

    private static void appendValues(StringBuilder sql, int columns, int rows) {
        String single = "(" + "?,".repeat(columns - 1) + "?)";
        sql.append(single);
        for (int i = 1; i < rows; i++) {
            sql.append(',').append(single);
        }
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder(count * 2 + 1).append('(');
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('?');
        }
        return builder.append(')').toString();
    }

    private static void bindAll(PreparedStatement statement, List<Object> params) throws SQLException {
        int index = 1;
        for (Object value : params) {
            index = bindValue(statement, index, value);
        }
    }

    private static int bindValue(PreparedStatement statement, int index, Object value) throws SQLException {
        switch (value) {
            case null -> statement.setNull(index, Types.VARCHAR);
            case Long longValue -> statement.setLong(index, longValue);
            case Integer integerValue -> statement.setInt(index, integerValue);
            case Short shortValue -> statement.setShort(index, shortValue);
            case byte[] bytes -> statement.setBytes(index, bytes);
            case Boolean booleanValue -> statement.setInt(index, booleanValue ? 1 : 0);
            default -> statement.setString(index, String.valueOf(value));
        }
        return index + 1;
    }

    private static int bindLong(PreparedStatement statement, int index, long value) throws SQLException {
        statement.setLong(index, value);
        return index + 1;
    }

    private static int bindNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
        return index + 1;
    }

    private static int bindShort(PreparedStatement statement, int index, short value) throws SQLException {
        statement.setShort(index, value);
        return index + 1;
    }

    private static int bindByte(PreparedStatement statement, int index, byte value) throws SQLException {
        statement.setByte(index, value);
        return index + 1;
    }

    private static int bindInt(PreparedStatement statement, int index, int value) throws SQLException {
        statement.setInt(index, value);
        return index + 1;
    }

    private static int bindString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
        return index + 1;
    }

    private static int bindBytes(PreparedStatement statement, int index, byte[] value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BLOB);
        } else {
            statement.setBytes(index, value);
        }
        return index + 1;
    }

    private static String worldName(ActivityRecord record) {
        if (record.worldName != null && !record.worldName.isBlank()) {
            return record.worldName;
        }
        if (record.worldUuid != null) {
            return record.worldUuid.toString();
        }
        return FALLBACK_WORLD;
    }

    private static String uuidText(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }

    private static Long nullableId(IdRegistry.NameTable table, String name) {
        return name == null || name.isBlank() ? null : table.id(name);
    }

    private static long requireId(IdRegistry.NameTable table, String name) {
        Long id = table.id(name);
        if (id == null) {
            throw new IllegalStateException("维度表缺少 id: " + name);
        }
        return id;
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

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 回滚失败时保留原始异常
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            if (connection.getAutoCommit() != autoCommit) {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException ignored) {
            // 归还连接前恢复失败不影响已提交的结果
        }
    }

    /** 一次连接占用：嵌入式后端是固定连接加解锁，服务端后端是从池里借出再归还。 */
    protected static final class Channel implements AutoCloseable {

        private final Connection connection;
        private final Runnable release;

        private Channel(Connection connection, Runnable release) {
            this.connection = connection;
            this.release = release;
        }

        static Channel of(Connection connection, Runnable release) {
            return new Channel(Objects.requireNonNull(connection, "connection"),
                    Objects.requireNonNull(release, "release"));
        }

        Connection connection() {
            return connection;
        }

        @Override
        public void close() {
            release.run();
        }
    }

    /** 在连接上执行的工作单元。 */
    @FunctionalInterface
    protected interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** 维度行绑定器，index 是首个占位符下标，必须正好写入 columns 个参数。 */
    @FunctionalInterface
    private interface RowBinder {
        void bind(PreparedStatement statement, int index, String name) throws SQLException;
    }

    /** 本次事务内新插入的维度名字，提交成功后再清待落库标记。 */
    private record PersistedNames(List<String> worlds, List<String> users, List<String> materials) {
    }

    /** 展平后的容器行，activityId 是本批次刚分配的活动 id。 */
    private record ContainerRow(long activityId, ContainerChange change) {
    }
}
