package dev.junxiex.mikuprotect.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.junxiex.mikuprotect.config.MikuConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 可选后端：MariaDB，连接由连接池管理，池大小取配置。
 *
 * <p>写事务从池里借一条连接并显式提交或回滚，读写各自借还，因此不需要额外的全局锁。
 * 主键由应用侧分配，多服共库不受支持。</p>
 */
public final class MariaDbStorage extends JdbcStorage {

    private static final String DRIVER = "org.mariadb.jdbc.Driver";
    private static final long CONNECTION_TIMEOUT_MS = 10_000L;
    private static final long VALIDATION_TIMEOUT_MS = 5_000L;
    private static final long MAX_LIFETIME_MS = 1_800_000L;
    private static final long IDLE_TIMEOUT_MS = 600_000L;

    private final String lockOwner = UUID.randomUUID().toString();
    private volatile HikariDataSource dataSource;
    private volatile ScheduledExecutorService heartbeat;

    /**
     * @param config 配置快照
     * @param logger 日志
     */
    public MariaDbStorage(MikuConfig config, Logger logger) {
        super(StorageType.MARIADB, config, logger);
    }

    @Override
    protected void loadDriver() throws ClassNotFoundException {
        Class.forName(DRIVER, true, getClass().getClassLoader());
    }

    @Override
    protected void connect() throws SQLException {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("MikuProtect-Storage");
        hikari.setDriverClassName(DRIVER);
        hikari.setJdbcUrl("jdbc:mariadb://" + config.mariadbHost + ":" + config.mariadbPort + "/"
                + config.mariadbDatabase);
        hikari.setUsername(config.mariadbUsername);
        hikari.setPassword(config.mariadbPassword);
        hikari.setMaximumPoolSize(config.mariadbPoolSize);
        hikari.setMinimumIdle(Math.min(2, config.mariadbPoolSize));
        hikari.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        hikari.setValidationTimeout(VALIDATION_TIMEOUT_MS);
        hikari.setMaxLifetime(MAX_LIFETIME_MS);
        hikari.setIdleTimeout(IDLE_TIMEOUT_MS);
        hikari.setAutoCommit(true);
        hikari.addDataSourceProperty("useServerPrepStmts", "true");
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "256");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        HikariDataSource created = new HikariDataSource(hikari);
        try (Connection connection = created.getConnection()) {
            if (!connection.isValid(5)) {
                throw new SQLException("MariaDB 连接校验失败");
            }
        } catch (SQLException failure) {
            created.close();
            throw failure;
        }
        this.dataSource = created;
    }

    @Override
    protected void afterSchema() throws StorageException {
        if (!config.lockEnabled) {
            return;
        }
        try {
            transaction(connection -> {
                acquire(connection);
                return null;
            });
        } catch (StorageException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new StorageException("获取 MariaDB 写锁失败: " + exception.getMessage(), exception);
        }
        heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MikuProtect-DbLock");
            thread.setDaemon(true);
            return thread;
        });
        long period = Math.max(5, config.lockTimeoutMs / 4000L);
        heartbeat.scheduleAtFixedRate(this::beat, period, period, TimeUnit.SECONDS);
        logger.info("MariaDB 写锁已获取: " + lockOwner);
    }

    private void acquire(Connection connection) throws SQLException {
        long now = System.currentTimeMillis();
        String owner = null;
        long last = 0L;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT owner, heartbeat FROM mp_lock WHERE id = 1")) {
            try (ResultSet result = select.executeQuery()) {
                if (result.next()) {
                    owner = result.getString(1);
                    last = result.getLong(2);
                }
            }
        }
        if (owner == null) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO mp_lock(id, owner, heartbeat) VALUES (1, ?, ?)")) {
                insert.setString(1, lockOwner);
                insert.setLong(2, now);
                insert.executeUpdate();
            }
            return;
        }
        if (lockOwner.equals(owner)) {
            touch(connection, now);
            return;
        }
        if (now - last <= config.lockTimeoutMs) {
            throw new SQLException("数据库正被其他服务端占用 (owner=" + owner + ")");
        }
        try (PreparedStatement takeover = connection.prepareStatement(
                "UPDATE mp_lock SET owner = ?, heartbeat = ? WHERE id = 1 AND owner = ? AND heartbeat = ?")) {
            takeover.setString(1, lockOwner);
            takeover.setLong(2, now);
            takeover.setString(3, owner);
            takeover.setLong(4, last);
            if (takeover.executeUpdate() == 0) {
                throw new SQLException("数据库写锁抢占失败");
            }
        }
    }

    private void beat() {
        try {
            transaction(connection -> {
                touch(connection, System.currentTimeMillis());
                return null;
            });
        } catch (Exception exception) {
            logger.log(Level.WARNING, "MariaDB 写锁心跳失败", exception);
        }
    }

    private void touch(Connection connection, long now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE mp_lock SET heartbeat = ? WHERE id = 1 AND owner = ?")) {
            update.setLong(1, now);
            update.setString(2, lockOwner);
            if (update.executeUpdate() == 0) {
                throw new SQLException("MariaDB 写锁已丢失");
            }
        }
    }

    private void release() {
        try {
            transaction(connection -> {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM mp_lock WHERE id = 1 AND owner = ?")) {
                    delete.setString(1, lockOwner);
                    delete.executeUpdate();
                }
                return null;
            });
        } catch (Exception exception) {
            logger.log(Level.FINE, "MariaDB 写锁释放失败", exception);
        }
    }

    @Override
    protected String dialect() {
        return Schema.MARIADB;
    }

    @Override
    protected String metaUpsertSql() {
        return "INSERT INTO mp_meta(k, v) VALUES (?, ?) ON DUPLICATE KEY UPDATE v = VALUES(v)";
    }

    @Override
    protected Channel openWriteChannel() throws SQLException {
        HikariDataSource source = dataSource;
        if (source == null) {
            throw new SQLException("MariaDB 连接池尚未初始化");
        }
        Connection connection = source.getConnection();
        return Channel.of(connection, () -> closeQuietly(connection));
    }

    @Override
    protected Channel openReadChannel() throws SQLException {
        HikariDataSource source = dataSource;
        if (source == null) {
            throw new SQLException("MariaDB 连接池尚未初始化");
        }
        Connection connection = source.getConnection();
        return Channel.of(connection, () -> closeQuietly(connection));
    }

    @Override
    protected void shutdown() {
        ScheduledExecutorService beat = heartbeat;
        heartbeat = null;
        if (beat != null) {
            beat.shutdownNow();
        }
        if (dataSource != null && config.lockEnabled) {
            release();
        }
        HikariDataSource source = dataSource;
        dataSource = null;
        if (source != null) {
            source.close();
        }
    }
}
