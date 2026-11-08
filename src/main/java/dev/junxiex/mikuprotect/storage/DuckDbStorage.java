package dev.junxiex.mikuprotect.storage;

import dev.junxiex.mikuprotect.config.MikuConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 默认后端：嵌入式 DuckDB。
 *
 * <p>一个写连接加一个读连接。写事务由写锁串行化，查询由读锁保护，两把锁互不嵌套：
 * 嵌入式引擎自身对写事务持排他锁，读会在写提交后继续，因此这里不会再叠加跨锁等待。</p>
 *
 * <p>数据库是单个文件，主键由应用侧分配，多服共库不受支持。</p>
 */
public final class DuckDbStorage extends JdbcStorage {

    private static final String DRIVER = "org.duckdb.DuckDBDriver";
    private static final String DEFAULT_FILE = "data/mikuprotect.duckdb";
    private static final String DEFAULT_MEMORY_LIMIT = "512MB";
    private static final String DEFAULT_MAX_TEMP_SIZE = "10GB";

    /** PRAGMA 的值不能走占位符，只接受「数字 + 容量单位」这一种形状。 */
    private static final Pattern SIZE = Pattern.compile("[0-9]{1,9}(\\.[0-9]{1,3})?(B|KB|MB|GB|TB|PB|KIB|MIB|GIB|TIB)");

    private final Path file;
    private final String memoryLimit;
    private final String maxTempSize;
    private final int threads;
    private final ReentrantLock writeLock = new ReentrantLock(true);
    private final ReentrantLock readLock = new ReentrantLock(true);

    private Connection writeConnection;
    private Connection readConnection;

    /**
     * @param config     配置快照
     * @param dataFolder 插件数据目录，相对路径按它解析；null 时按服务端工作目录解析
     * @param logger     日志
     */
    public DuckDbStorage(MikuConfig config, Path dataFolder, Logger logger) {
        super(StorageType.DUCKDB, config, logger);
        this.file = resolveFile(config.duckdbFile, dataFolder);
        this.memoryLimit = safeSize(config.duckdbMemoryLimit, DEFAULT_MEMORY_LIMIT);
        this.maxTempSize = safeSize(config.duckdbMaxTempSize, DEFAULT_MAX_TEMP_SIZE);
        this.threads = Math.max(1, config.duckdbThreads);
    }

    /**
     * @param config 配置快照
     * @param logger 日志
     */
    public DuckDbStorage(MikuConfig config, Logger logger) {
        this(config, null, logger);
    }

    @Override
    protected void loadDriver() throws ClassNotFoundException {
        Class.forName(DRIVER, true, getClass().getClassLoader());
    }

    @Override
    protected void connect() throws SQLException {
        Path parent = file.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException failure) {
                throw new SQLException("无法创建数据目录: " + parent, failure);
            }
        }
        String url = "jdbc:duckdb:" + file.toString().replace('\\', '/');
        Connection writer = DriverManager.getConnection(url);
        Connection reader = null;
        try {
            reader = DriverManager.getConnection(url);
            configure(writer);
            configure(reader);
        } catch (SQLException failure) {
            closeQuietly(reader);
            closeQuietly(writer);
            throw failure;
        }
        this.writeConnection = writer;
        this.readConnection = reader;
    }

    @Override
    protected String dialect() {
        return Schema.DUCKDB;
    }

    @Override
    protected String metaUpsertSql() {
        return "INSERT INTO mp_meta(k, v) VALUES (?, ?) ON CONFLICT (k) DO UPDATE SET v = excluded.v";
    }

    @Override
    protected Channel openWriteChannel() throws SQLException {
        Connection connection = writeConnection;
        if (connection == null) {
            throw new SQLException("DuckDB 写连接尚未建立");
        }
        writeLock.lock();
        return Channel.of(connection, writeLock::unlock);
    }

    @Override
    protected Channel openReadChannel() throws SQLException {
        Connection connection = readConnection;
        if (connection == null) {
            throw new SQLException("DuckDB 读连接尚未建立");
        }
        readLock.lock();
        return Channel.of(connection, readLock::unlock);
    }

    @Override
    protected void shutdown() {
        boolean write = false;
        boolean read = false;
        try {
            write = writeLock.tryLock(2, TimeUnit.SECONDS);
            read = readLock.tryLock(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        try {
            closeQuietly(readConnection);
            closeQuietly(writeConnection);
            readConnection = null;
            writeConnection = null;
        } finally {
            if (read) {
                readLock.unlock();
            }
            if (write) {
                writeLock.unlock();
            }
        }
    }

    /** 文件路径按需建目录，最终统一成绝对路径交给驱动。 */
    private static Path resolveFile(String configured, Path dataFolder) {
        String value = configured == null || configured.isBlank() ? DEFAULT_FILE : configured.trim();
        Path path = Path.of(value);
        if (!path.isAbsolute() && dataFolder != null) {
            path = dataFolder.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    /** 容量参数只接受数字加单位，非法值回落到默认值，避免拼出坏掉的 PRAGMA。 */
    private static String safeSize(String configured, String fallback) {
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        String value = configured.trim().toUpperCase(Locale.ROOT);
        return SIZE.matcher(value).matches() ? value : fallback;
    }

    private void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA memory_limit='" + memoryLimit + "'");
            statement.execute("PRAGMA threads=" + threads);
            statement.execute("PRAGMA max_temp_directory_size='" + maxTempSize + "'");
        }
    }
}
