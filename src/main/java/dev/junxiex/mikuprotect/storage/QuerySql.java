package dev.junxiex.mikuprotect.storage;

import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.LookupQuery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 把 {@link LookupQuery} 翻译成 WHERE / ORDER BY / 分页片段，并附带参数列表。
 *
 * <p>外部值（名字、时间、搜索词、id 列表）全部转成占位符参数；世界/玩家/材质名先经
 * {@link IdRegistry} 解析成 id，所以条件只引用 mp_activity 自身的列，计数查询无需 join 维度表。
 * 唯一按字面量拼接的是 LIMIT/OFFSET：两者都是内部算出的整数（不来自命令文本），
 * 而两种引擎对参数化 LIMIT 的支持并不一致，写整数在两边都成立。</p>
 */
public final class QuerySql {

    /** LIKE 的转义符，避开两种方言对反斜杠转义的不同处理。 */
    private static final char LIKE_ESCAPE = '!';

    /** 调用方给的页大小非法时的兜底值。 */
    private static final int DEFAULT_PAGE_LIMIT = 1000;

    private final String whereClause;
    private final String orderClause;
    private final String pageClause;
    private final List<Object> params;

    private QuerySql(String whereClause, String orderClause, String pageClause, List<Object> params) {
        this.whereClause = whereClause;
        this.orderClause = orderClause;
        this.pageClause = pageClause;
        this.params = params;
    }

    /**
     * 构造分页查询片段。
     *
     * @param filter   查询条件，null 视作无条件
     * @param registry 名字到 id 的解析来源
     * @param maxLimit 单页上限，来自配置
     * @return 可直接拼在 SELECT 之后的片段
     */
    public static QuerySql forLookup(LookupQuery filter, IdRegistry registry, int maxLimit) {
        int cap = maxLimit > 0 ? maxLimit : DEFAULT_PAGE_LIMIT;
        int limit = Math.max(1, Math.min(filter == null ? cap : filter.limit, cap));
        return build(filter, registry, limit);
    }

    /**
     * 构造大批量查询片段：行数直接取调用方给的 cap，不再受 query.limit 约束（回滚等场景使用）。
     *
     * @param filter   查询条件，null 视作无条件
     * @param registry 名字到 id 的解析来源
     * @param cap      本次最多返回的行数，由调用方负责上限校验
     * @return 可直接拼在 SELECT 之后的片段
     */
    public static QuerySql forScan(LookupQuery filter, IdRegistry registry, int cap) {
        return build(filter, registry, Math.max(1, cap));
    }

    private static QuerySql build(LookupQuery filter, IdRegistry registry, int limit) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(filter, registry, params);
        long offset = (long) (Math.max(1, filter == null ? 1 : filter.page) - 1) * limit;
        boolean ascending = filter != null && filter.ascending;
        String order = ascending
                ? " ORDER BY a.time ASC, a.id ASC"
                : " ORDER BY a.time DESC, a.id DESC";
        String page = " LIMIT " + limit + " OFFSET " + offset;
        return new QuerySql(where, order, page, List.copyOf(params));
    }

    /**
     * 构造计数片段，不带排序与分页。
     *
     * @param filter   查询条件，null 视作无条件
     * @param registry 名字到 id 的解析来源
     * @return 只含 WHERE 的片段
     */
    public static QuerySql forCount(LookupQuery filter, IdRegistry registry) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(filter, registry, params);
        return new QuerySql(where, "", "", List.copyOf(params));
    }

    /** 已含 " WHERE " 前缀的片段，无条件时为空串。 */
    public String whereClause() {
        return whereClause;
    }

    /** 已含 " ORDER BY " 前缀的片段，计数查询为空串。 */
    public String orderClause() {
        return orderClause;
    }

    /** 已含 " LIMIT " 前缀的片段，计数查询为空串。 */
    public String pageClause() {
        return pageClause;
    }

    /** 条件参数，顺序与占位符一致。 */
    public List<Object> params() {
        return params;
    }

    private static String buildWhere(LookupQuery filter, IdRegistry registry, List<Object> params) {
        if (filter == null) {
            return "";
        }
        List<String> conditions = new ArrayList<>(10);
        nameCondition(conditions, params, "a.world_id", registry.worlds(), filter.worldName, false);
        nameCondition(conditions, params, "a.user_id", registry.users(), filter.userName, true);
        nameCondition(conditions, params, "a.material_id", registry.materials(), filter.material, false);
        actionCondition(conditions, params, filter.actions, false);
        actionCondition(conditions, params, filter.excludedActions, true);

        if (filter.entityType != null && !filter.entityType.isBlank()) {
            conditions.add("a.entity_type = ?");
            params.add(filter.entityType);
        }
        if (filter.since > 0L) {
            conditions.add("a.time >= ?");
            params.add(filter.since);
        }
        if (filter.before > 0L) {
            conditions.add("a.time < ?");
            params.add(filter.before);
        }
        if (filter.hasRadius()) {
            radiusConditions(conditions, params, filter, registry);
        }
        if (filter.search != null && !filter.search.isBlank()) {
            searchConditions(conditions, params, filter.search);
        }
        idConditions(conditions, params, filter.ids);

        if (conditions.isEmpty()) {
            return "";
        }
        return " WHERE " + String.join(" AND ", conditions);
    }

    /** 名字类条件：缓存里没有该名字说明库里也不可能有，直接让条件恒假。 */
    private static void nameCondition(List<String> conditions, List<Object> params, String column,
            IdRegistry.NameTable table, String name, boolean ignoreCase) {
        if (name == null || name.isBlank()) {
            return;
        }
        Long id = ignoreCase ? table.idIgnoreCase(name) : table.id(name);
        if (id == null) {
            conditions.add("1 = 0");
            return;
        }
        conditions.add(column + " = ?");
        params.add(id);
    }

    private static void actionCondition(List<String> conditions, List<Object> params, Collection<ActionType> actions,
            boolean excluded) {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        List<Object> ids = new ArrayList<>(actions.size());
        for (ActionType action : actions) {
            if (action != null) {
                ids.add(action.id());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        conditions.add("a.action " + (excluded ? "NOT IN " : "IN ") + placeholders(ids.size()));
        params.addAll(ids);
    }

    /** 圆柱形范围：x/z 先走索引区间，再用平方和判圆；给了中心 y 时再限高。 */
    private static void radiusConditions(List<String> conditions, List<Object> params, LookupQuery filter,
            IdRegistry registry) {
        Integer centerX = filter.centerX;
        Integer centerZ = filter.centerZ;
        if (centerX == null || centerZ == null) {
            return;
        }
        if (filter.centerWorld != null && !filter.centerWorld.isBlank()) {
            Long worldId = registry.worlds().id(filter.centerWorld);
            if (worldId == null) {
                conditions.add("1 = 0");
                return;
            }
            conditions.add("a.world_id = ?");
            params.add(worldId);
        }
        int radius = Math.max(0, filter.radius);
        conditions.add("a.x BETWEEN ? AND ?");
        params.add(centerX - radius);
        params.add(centerX + radius);
        conditions.add("a.z BETWEEN ? AND ?");
        params.add(centerZ - radius);
        params.add(centerZ + radius);
        Integer centerY = filter.centerY;
        if (centerY != null) {
            conditions.add("a.y BETWEEN ? AND ?");
            params.add(centerY - radius);
            params.add(centerY + radius);
        }
        if (radius > 0) {
            conditions.add("((a.x - ?) * (a.x - ?) + (a.z - ?) * (a.z - ?)) <= ?");
            params.add(centerX);
            params.add(centerX);
            params.add(centerZ);
            params.add(centerZ);
            params.add((long) radius * radius);
        }
    }

    private static void searchConditions(List<String> conditions, List<Object> params, String search) {
        String pattern = "%" + escapeLike(search.trim()) + "%";
        conditions.add("(" + likeCondition("a.message")
                + " OR " + likeCondition("a.target_user")
                + " OR " + likeCondition("a.block_data") + ")");
        params.add(pattern);
        params.add(pattern);
        params.add(pattern);
    }

    private static String likeCondition(String column) {
        return column + " LIKE ? ESCAPE '" + LIKE_ESCAPE + "'";
    }

    private static void idConditions(List<String> conditions, List<Object> params, List<Long> ids) {
        if (ids == null) {
            return;
        }
        if (ids.isEmpty()) {
            conditions.add("1 = 0");
            return;
        }
        conditions.add("a.id IN " + placeholders(ids.size()));
        params.addAll(ids);
    }

    private static String escapeLike(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == LIKE_ESCAPE || c == '%' || c == '_') {
                builder.append(LIKE_ESCAPE);
            }
            builder.append(c);
        }
        return builder.toString();
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
}
