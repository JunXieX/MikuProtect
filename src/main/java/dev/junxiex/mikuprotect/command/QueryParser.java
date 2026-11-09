package dev.junxiex.mikuprotect.command;

import dev.junxiex.mikuprotect.config.MikuConfig;
import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.LookupQuery;
import dev.junxiex.mikuprotect.util.TimeUtil;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.Material;

/**
 * 把 "/mp" 子命令后面的参数串解析为 {@link LookupQuery}。
 *
 * <p>支持的形式：
 * <pre>
 * u:Steve a:block-break,block-place b:stone r:10 t:1h p:2 #global w:world msg:abc
 * a!:block-break  排除动作
 * </pre>
 * 解析失败时给出中文提示，半径超过配置上限会被截断并记录一条警告。
 */
public final class QueryParser {

    /** 解析结果，error 为 null 表示成功。 */
    public record Result(LookupQuery query, List<String> warnings, String error, int filters) {

        public Result {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean ok() {
            return error == null;
        }
    }

    private final MikuConfig config;

    public QueryParser(MikuConfig config) {
        this.config = config;
    }

    /**
     * @param raw          子命令后的原始参数串，可为 null
     * @param center       玩家当前位置，控制台为 null；半径查询以它为圆心
     * @param locationMode true 表示 near 一类必须带位置的查询，未写半径时使用默认半径
     */
    public Result parse(String raw, Location center, boolean locationMode) {
        List<String> warnings = new ArrayList<>();
        LookupQuery query = LookupQuery.all();
        long now = System.currentTimeMillis();
        query.since(now - Math.max(0L, config.defaultSince));
        query.page(1);
        query.limit(config.pageSize);

        int filters = 0;
        boolean sinceSpecified = false;
        Integer radius = null;

        for (String token : tokenize(raw)) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.charAt(0) == '#') {
                String world = token.substring(1).trim();
                if (world.isEmpty()) {
                    return fail("世界名不可为空，例如 #world_nether", warnings, filters);
                }
                // #global is the documented "all worlds" token; a real world named global is
                // still addressable with w:global.
                if (!world.equalsIgnoreCase("global")) {
                    query.world(world);
                    filters++;
                }
                continue;
            }
            int colon = token.indexOf(':');
            if (colon <= 0) {
                return fail("未识别的参数: " + token + "，请使用 key:value 形式（/mp help 查看语法）", warnings, filters);
            }
            String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = token.substring(colon + 1).trim();
            if (value.isEmpty()) {
                return fail("参数 " + key + ": 缺少值", warnings, filters);
            }
            switch (key) {
                case "u", "user", "player", "name" -> {
                    query.user(value);
                    filters++;
                }
                case "a", "action", "actions", "act" -> {
                    String error = collectActions(value, query, false);
                    if (error != null) {
                        return fail(error, warnings, filters);
                    }
                    filters++;
                }
                case "a!", "!a", "action!", "exclude" -> {
                    String error = collectActions(value, query, true);
                    if (error != null) {
                        return fail(error, warnings, filters);
                    }
                    filters++;
                }
                case "b", "block", "material", "mat" -> {
                    Material material = matchMaterial(value);
                    if (material == null) {
                        return fail("未知材质: " + value, warnings, filters);
                    }
                    query.material(material.getKey().toString());
                    filters++;
                }
                case "e", "entity", "mob" -> {
                    org.bukkit.entity.EntityType type = matchEntity(value);
                    if (type == null) {
                        return fail("未知实体类型: " + value, warnings, filters);
                    }
                    query.entityType(type.getKey().toString());
                    filters++;
                }
                case "r", "radius", "rad" -> {
                    int parsed;
                    try {
                        parsed = Integer.parseInt(value);
                    } catch (NumberFormatException exception) {
                        return fail("半径必须是整数: " + value, warnings, filters);
                    }
                    if (parsed < 0) {
                        return fail("半径必须大于或等于 0: " + value, warnings, filters);
                    }
                    if (parsed > config.maxRadius) {
                        warnings.add("半径 " + parsed + " 超过上限，已截断为 " + config.maxRadius);
                        parsed = config.maxRadius;
                    }
                    radius = parsed;
                    filters++;
                }
                case "t", "time", "since", "dur" -> {
                    long duration;
                    try {
                        duration = TimeUtil.parseDuration(value);
                    } catch (RuntimeException exception) {
                        return fail("时间格式无效: " + value + "（示例 30m、12h、7d）", warnings, filters);
                    }
                    if (duration < 0L) {
                        return fail("时间必须为正数: " + value, warnings, filters);
                    }
                    query.since(now - duration);
                    sinceSpecified = true;
                    filters++;
                }
                case "before", "tb", "older" -> {
                    long duration;
                    try {
                        duration = TimeUtil.parseDuration(value);
                    } catch (RuntimeException exception) {
                        return fail("时间格式无效: " + value + "（示例 30m、12h、7d）", warnings, filters);
                    }
                    if (duration < 0L) {
                        return fail("时间必须为正数: " + value, warnings, filters);
                    }
                    query.before(now - duration);
                    if (!sinceSpecified) {
                        query.since(0L);
                    }
                    filters++;
                }
                case "p", "page" -> {
                    int page;
                    try {
                        page = Integer.parseInt(value);
                    } catch (NumberFormatException exception) {
                        return fail("页码必须是正整数: " + value, warnings, filters);
                    }
                    if (page < 1) {
                        return fail("页码必须是正整数: " + value, warnings, filters);
                    }
                    query.page(page);
                }
                case "l", "limit", "size" -> {
                    int limit;
                    try {
                        limit = Integer.parseInt(value);
                    } catch (NumberFormatException exception) {
                        return fail("每页数量必须是正整数: " + value, warnings, filters);
                    }
                    if (limit < 1) {
                        return fail("每页数量必须是正整数: " + value, warnings, filters);
                    }
                    if (limit > config.maxPageSize) {
                        warnings.add("每页数量 " + limit + " 超过上限，已截断为 " + config.maxPageSize);
                        limit = config.maxPageSize;
                    }
                    query.limit(limit);
                }
                case "w", "world" -> {
                    query.world(value);
                    filters++;
                }
                case "msg", "message", "chat", "search", "s" -> {
                    query.search(value);
                    filters++;
                }
                case "sort", "order" -> {
                    String mode = value.toLowerCase(Locale.ROOT);
                    switch (mode) {
                        case "asc", "ascending", "oldest" -> query.ascending(true);
                        case "desc", "descending", "newest" -> query.ascending(false);
                        default -> {
                            return fail("排序只能为 asc 或 desc: " + value, warnings, filters);
                        }
                    }
                }
                default -> {
                    return fail("未知参数: " + key + "（/mp help 查看可用参数）", warnings, filters);
                }
            }
        }

        if (locationMode) {
            if (center == null || center.getWorld() == null) {
                return fail("该命令需要玩家位置，控制台不可使用。", warnings, filters);
            }
            int effective = radius == null ? config.defaultRadius : radius;
            if (effective > config.maxRadius) {
                warnings.add("半径 " + effective + " 超过上限，已截断为 " + config.maxRadius);
                effective = config.maxRadius;
            }
            query.center(center.getWorld().getName(), center.getBlockX(), center.getBlockY(), center.getBlockZ());
            query.radius(effective);
        } else if (radius != null) {
            if (center != null && center.getWorld() != null) {
                query.center(center.getWorld().getName(), center.getBlockX(), center.getBlockY(), center.getBlockZ());
                query.radius(radius);
            } else {
                return fail("半径查询需要玩家位置，控制台请改用 u:/w:/t: 等条件。", warnings, filters);
            }
        }
        return new Result(query, warnings, null, filters);
    }

    private static Result fail(String message, List<String> warnings, int filters) {
        return new Result(null, warnings, message, filters);
    }

    private static Material matchMaterial(String value) {
        Material material = Material.matchMaterial(value);
        if (material == null) {
            int colon = value.indexOf(':');
            if (colon >= 0 && colon + 1 < value.length()) {
                material = Material.matchMaterial(value.substring(colon + 1));
            }
        }
        return material;
    }

    private static org.bukkit.entity.EntityType matchEntity(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        int colon = normalized.indexOf(':');
        if (colon >= 0 && colon + 1 < normalized.length()) {
            normalized = normalized.substring(colon + 1);
        }
        return org.bukkit.entity.EntityType.fromName(normalized);
    }

    private static String collectActions(String value, LookupQuery query, boolean forceExclude) {
        for (String rawPart : value.split(",")) {
            String part = rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }
            boolean exclude = forceExclude;
            while (!part.isEmpty() && (part.charAt(0) == '!' || part.charAt(0) == '-')) {
                exclude = true;
                part = part.substring(1).trim();
            }
            if (part.isEmpty()) {
                continue;
            }
            EnumSet<ActionType> resolved = resolveActions(part.toLowerCase(Locale.ROOT));
            if (resolved == null) {
                return "未知动作: " + part
                        + "（示例 block-break、block-place，或类别 block/container/item/entity/player/message/sign/world）";
            }
            if (exclude) {
                query.excludeActions(resolved);
            } else {
                query.actions(resolved);
            }
        }
        return null;
    }

    private static EnumSet<ActionType> resolveActions(String key) {
        if ("session".equals(key)) {
            return EnumSet.of(ActionType.PLAYER_JOIN, ActionType.PLAYER_QUIT);
        }
        ActionType single = ActionType.byKey(key);
        if (single != null) {
            return EnumSet.of(single);
        }
        ActionType alias = ActionType.categoryOf(key);
        if (alias != null) {
            return EnumSet.of(alias);
        }
        ActionType.Category category = categoryOfName(key);
        if (category == null) {
            return null;
        }
        EnumSet<ActionType> resolved = EnumSet.noneOf(ActionType.class);
        for (ActionType type : ActionType.values()) {
            if (type.category() == category) {
                resolved.add(type);
            }
        }
        return resolved;
    }

    private static ActionType.Category categoryOfName(String key) {
        return switch (key) {
            case "block", "blocks" -> ActionType.Category.BLOCK;
            case "container", "containers" -> ActionType.Category.CONTAINER;
            case "item", "items" -> ActionType.Category.ITEM;
            case "entity", "entities", "mob", "mobs" -> ActionType.Category.ENTITY;
            case "player", "players" -> ActionType.Category.PLAYER;
            case "message", "messages" -> ActionType.Category.MESSAGE;
            case "sign", "signs" -> ActionType.Category.SIGN;
            case "world", "worlds" -> ActionType.Category.WORLD;
            default -> null;
        };
    }

    /**
     * 按空白切分，支持用单/双引号包裹含空格的参数值，例如 {@code msg:"hello world"}。
     */
    static List<String> tokenize(String raw) {
        List<String> tokens = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (quote != 0) {
                if (character == quote) {
                    quote = 0;
                } else {
                    current.append(character);
                }
            } else if (character == '"' || character == '\'') {
                quote = character;
            } else if (Character.isWhitespace(character)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
