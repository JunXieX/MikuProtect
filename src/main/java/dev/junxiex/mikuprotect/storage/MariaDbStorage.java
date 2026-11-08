package dev.junxiex.mikuprotect.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.junxiex.mikuprotect.config.MikuConfig;
import java.sql.Connection;
import java.sql.SQLException;
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

    private volatile HikariDataSource dataSource;

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
        HikariDataSource source = dataSource;
        dataSource = null;
        if (source != null) {
            source.close();
        }
    }
}