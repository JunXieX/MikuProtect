package dev.junxiex.mikuprotect.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Every recordable action. The numeric id is persisted, so ids must never be reused.
 */
public enum ActionType {

    BLOCK_BREAK(1, Category.BLOCK, true, "block-break"),
    BLOCK_PLACE(2, Category.BLOCK, true, "block-place"),
    BLOCK_BURN(3, Category.BLOCK, true, "block-burn"),
    BLOCK_FADE(4, Category.BLOCK, true, "block-fade"),
    BLOCK_FORM(5, Category.BLOCK, true, "block-form"),
    BLOCK_SPREAD(6, Category.BLOCK, true, "block-spread"),
    BLOCK_IGNITE(7, Category.BLOCK, true, "block-ignite"),
    BLOCK_PISTON_EXTEND(8, Category.BLOCK, true, "block-piston-extend"),
    BLOCK_PISTON_RETRACT(9, Category.BLOCK, true, "block-piston-retract"),
    BLOCK_BUCKET_EMPTY(10, Category.BLOCK, true, "block-bucket-empty"),
    BLOCK_BUCKET_FILL(11, Category.BLOCK, true, "block-bucket-fill"),
    BLOCK_GROWTH(12, Category.BLOCK, true, "block-growth"),
    BLOCK_DECAY(13, Category.BLOCK, true, "block-decay"),
    BLOCK_FERTILIZE(14, Category.BLOCK, true, "block-fertilize"),
    BLOCK_LIQUID_FLOW(15, Category.BLOCK, true, "block-liquid-flow"),
    BLOCK_EXPLOSION(16, Category.BLOCK, true, "block-explosion"),
    BLOCK_ENTITY_CHANGE(17, Category.BLOCK, true, "block-entity-change"),
    CONTAINER_ADD(18, Category.CONTAINER, true, "container-add"),
    CONTAINER_REMOVE(19, Category.CONTAINER, true, "container-remove"),
    CONTAINER_OPEN(20, Category.CONTAINER, false, "container-open"),
    ITEM_DROP(21, Category.ITEM, false, "item-drop"),
    ITEM_PICKUP(22, Category.ITEM, false, "item-pickup"),
    ITEM_CRAFT(23, Category.ITEM, false, "item-craft"),
    ITEM_BREAK(24, Category.ITEM, false, "item-break"),
    ITEM_CONSUME(25, Category.ITEM, false, "item-consume"),
    ENTITY_SPAWN(26, Category.ENTITY, true, "entity-spawn"),
    ENTITY_KILL(27, Category.ENTITY, true, "entity-kill"),
    ENTITY_EXPLODE(28, Category.ENTITY, false, "entity-explode"),
    ENTITY_INTERACT(29, Category.ENTITY, false, "entity-interact"),
    ENTITY_TRANSFORM(30, Category.ENTITY, false, "entity-transform"),
    ENTITY_PLACE(31, Category.ENTITY, true, "entity-place"),
    ENTITY_SHEAR(32, Category.ENTITY, false, "entity-shear"),
    ENTITY_DYE(33, Category.ENTITY, false, "entity-dye"),
    ENTITY_LEASH(34, Category.ENTITY, false, "entity-leash"),
    PLAYER_JOIN(35, Category.PLAYER, false, "player-join"),
    PLAYER_QUIT(36, Category.PLAYER, false, "player-quit"),
    PLAYER_CHAT(37, Category.MESSAGE, false, "player-chat"),
    PLAYER_COMMAND(38, Category.MESSAGE, false, "player-command"),
    PLAYER_DEATH(39, Category.PLAYER, false, "player-death"),
    PLAYER_KILL(40, Category.PLAYER, false, "player-kill"),
    PLAYER_INTERACT(41, Category.PLAYER, false, "player-interact"),
    SIGN_CHANGE(42, Category.SIGN, true, "sign-change"),
    WORLD_PORTAL(43, Category.WORLD, true, "world-portal"),
    WORLD_STRUCTURE(44, Category.WORLD, true, "world-structure"),
    BLOCK_DISPENSE(45, Category.CONTAINER, false, "block-dispense"),
    PLAYER_NAME_CHANGE(46, Category.PLAYER, false, "player-name-change");

    public enum Category {
        BLOCK, CONTAINER, ITEM, ENTITY, PLAYER, MESSAGE, SIGN, WORLD
    }

    private static final Map<String, ActionType> BY_KEY = new HashMap<>();
    private static final ActionType[] BY_ID = new ActionType[64];

    static {
        for (ActionType type : values()) {
            BY_KEY.put(type.key, type);
            BY_ID[type.id] = type;
        }
    }

    private final int id;
    private final Category category;
    private final boolean reversible;
    private final String key;

    ActionType(int id, Category category, boolean reversible, String key) {
        this.id = id;
        this.category = category;
        this.reversible = reversible;
        this.key = key;
    }

    public int id() {
        return id;
    }

    public Category category() {
        return category;
    }

    public boolean reversible() {
        return reversible;
    }

    public String key() {
        return key;
    }

    public static ActionType byId(int id) {
        return id > 0 && id < BY_ID.length ? BY_ID[id] : null;
    }

    public static ActionType byKey(String key) {
        if (key == null) {
            return null;
        }
        return BY_KEY.get(key.toLowerCase(java.util.Locale.ROOT));
    }

    public static ActionType categoryOf(String key) {
        if (key == null) {
            return null;
        }
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        if ("chat".equals(normalized)) {
            return PLAYER_CHAT;
        }
        if ("command".equals(normalized)) {
            return PLAYER_COMMAND;
        }
        if ("session".equals(normalized)) {
            return PLAYER_JOIN;
        }
        return byKey(normalized);
    }
}
