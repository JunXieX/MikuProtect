package dev.junxiex.mikuprotect.storage;

import java.util.List;

/**
 * 两套方言的表结构与索引定义。
 *
 * <p>所有语句都是常量，只在初始化时执行一次。MariaDB 不支持 CREATE INDEX IF NOT EXISTS，
 * 其索引内联在 CREATE TABLE 中；DuckDB 的索引用独立的幂等语句建立。</p>
 */
public final class Schema {

    /** DuckDB 方言标识。 */
    public static final String DUCKDB = "duckdb";
    /** MariaDB 方言标识。 */
    public static final String MARIADB = "mariadb";

    /** 当前 schema 版本，结构变更时递增。 */
    public static final int VERSION = 1;

    /** mp_meta 中记录 schema 版本的键。 */
    public static final String VERSION_KEY = "schema_version";

    private static final List<String> DUCKDB_TABLES = List.of(
            """
            CREATE TABLE IF NOT EXISTS mp_world (
                id BIGINT PRIMARY KEY,
                name VARCHAR(64) NOT NULL,
                uuid VARCHAR(36)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS mp_user (
                id BIGINT PRIMARY KEY,
                name VARCHAR(64) NOT NULL,
                uuid VARCHAR(36),
                first_seen BIGINT,
                last_seen BIGINT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS mp_material (
                id BIGINT PRIMARY KEY,
                name VARCHAR(96) NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS mp_activity (
                id BIGINT PRIMARY KEY,
                time BIGINT NOT NULL,
                world_id BIGINT NOT NULL,
                x INTEGER,
                y INTEGER,
                z INTEGER,
                action SMALLINT NOT NULL,
                user_id BIGINT,
                material_id BIGINT,
                block_data TEXT,
                old_block_data TEXT,
                item_data BLOB,
                amount INTEGER,
                entity_type VARCHAR(64),
                entity_uuid VARCHAR(36),
                target_user VARCHAR(64),
                message TEXT,
                old_message TEXT,
                meta BLOB,
                old_meta BLOB,
                rolled_back TINYINT DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS mp_container (
                id BIGINT PRIMARY KEY,
                activity_id BIGINT NOT NULL,
                slot INTEGER NOT NULL,
                before_data BLOB,
                after_data BLOB,
                before_amount INTEGER,
                after_amount INTEGER
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS mp_meta (
                k VARCHAR(32) PRIMARY KEY,
                v VARCHAR(64)
            )
            """);

    private static final List<String> DUCKDB_INDEXES = List.of(
            "CREATE INDEX IF NOT EXISTS idx_mp_activity_pos ON mp_activity(world_id, x, z, time)",
            "CREATE INDEX IF NOT EXISTS idx_mp_activity_user ON mp_activity(user_id, time)",
            "CREATE INDEX IF NOT EXISTS idx_mp_activity_time ON mp_activity(time)",
            "CREATE INDEX IF NOT EXISTS idx_mp_activity_action ON mp_activity(action, time)",
            "CREATE INDEX IF NOT EXISTS idx_mp_activity_entity ON mp_activity(entity_uuid)",
            "CREATE INDEX IF NOT EXISTS idx_mp_container_activity ON mp_container(activity_id)");

    private static final List<String> MARIADB_TABLES = List.of(
            """
            CREATE TABLE IF NOT EXISTS mp_world (
                id BIGINT NOT NULL,
                name VARCHAR(64) NOT NULL,
                uuid VARCHAR(36),
                PRIMARY KEY (id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS mp_user (
                id BIGINT NOT NULL,
                name VARCHAR(64) NOT NULL,
                uuid VARCHAR(36),
                first_seen BIGINT,
                last_seen BIGINT,
                PRIMARY KEY (id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS mp_material (
                id BIGINT NOT NULL,
                name VARCHAR(96) NOT NULL,
                PRIMARY KEY (id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS mp_activity (
                id BIGINT NOT NULL,
                time BIGINT NOT NULL,
                world_id BIGINT NOT NULL,
                x INT,
                y INT,
                z INT,
                action SMALLINT NOT NULL,
                user_id BIGINT,
                material_id BIGINT,
                block_data TEXT,
                old_block_data TEXT,
                item_data LONGBLOB,
                amount INT,
                entity_type VARCHAR(64),
                entity_uuid VARCHAR(36),
                target_user VARCHAR(64),
                message TEXT,
                old_message TEXT,
                meta LONGBLOB,
                old_meta LONGBLOB,
                rolled_back TINYINT DEFAULT 0,
                PRIMARY KEY (id),
                KEY idx_mp_activity_pos (world_id, x, z, time),
                KEY idx_mp_activity_user (user_id, time),
                KEY idx_mp_activity_time (time),
                KEY idx_mp_activity_action (action, time),
                KEY idx_mp_activity_entity (entity_uuid)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS mp_container (
                id BIGINT NOT NULL,
                activity_id BIGINT NOT NULL,
                slot INT NOT NULL,
                before_data LONGBLOB,
                after_data LONGBLOB,
                before_amount INT,
                after_amount INT,
                PRIMARY KEY (id),
                KEY idx_mp_container_activity (activity_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS mp_meta (
                k VARCHAR(32) NOT NULL,
                v VARCHAR(64),
                PRIMARY KEY (k)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    /** MariaDB 的索引已写进建表语句，没有独立的索引语句。 */
    private static final List<String> MARIADB_INDEXES = List.of();

    private Schema() {
    }

    /**
     * 建表语句。
     *
     * @param dialect {@link #DUCKDB} 或 {@link #MARIADB}
     * @return 按依赖顺序排列的常量语句
     */
    public static List<String> tables(String dialect) {
        return MARIADB.equals(dialect) ? MARIADB_TABLES : DUCKDB_TABLES;
    }

    /**
     * 索引语句，可重复执行。
     *
     * @param dialect {@link #DUCKDB} 或 {@link #MARIADB}
     * @return DuckDB 的幂等索引语句；MariaDB 返回空列表
     */
    public static List<String> indexes(String dialect) {
        return MARIADB.equals(dialect) ? MARIADB_INDEXES : DUCKDB_INDEXES;
    }
}
