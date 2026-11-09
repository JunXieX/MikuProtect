package dev.junxiex.mikuprotect.model;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A portable query description. Storage implementations translate it to SQL.
 */
public final class LookupQuery {

    /** A larger page number only creates a deeper OFFSET scan, not more useful results. */
    public static final int MAX_PAGE = 100_000;

    public String worldName;
    public String userName;
    public Set<ActionType> actions = new LinkedHashSet<>();
    public Set<ActionType> excludedActions = new LinkedHashSet<>();
    public String material;
    public String entityType;
    public long since;
    public long before;
    public int radius = -1;
    public String centerWorld;
    public Integer centerX;
    public Integer centerY;
    public Integer centerZ;
    public int page = 1;
    public int limit = 10;
    public boolean ascending;
    public String search;
    public List<Long> ids;

    public static LookupQuery all() {
        return new LookupQuery();
    }

    /** Snapshot for asynchronous callers; the rollback engine rewrites page/limit. */
    public LookupQuery copy() {
        LookupQuery copy = new LookupQuery();
        copy.worldName = worldName;
        copy.userName = userName;
        copy.actions = new LinkedHashSet<>(actions);
        copy.excludedActions = new LinkedHashSet<>(excludedActions);
        copy.material = material;
        copy.entityType = entityType;
        copy.since = since;
        copy.before = before;
        copy.radius = radius;
        copy.centerWorld = centerWorld;
        copy.centerX = centerX;
        copy.centerY = centerY;
        copy.centerZ = centerZ;
        copy.page = page;
        copy.limit = limit;
        copy.ascending = ascending;
        copy.search = search;
        copy.ids = ids == null ? null : List.copyOf(ids);
        return copy;
    }

    public LookupQuery world(String value) {
        this.worldName = value;
        return this;
    }

    public LookupQuery user(String value) {
        this.userName = value;
        return this;
    }

    public LookupQuery actions(Collection<ActionType> value) {
        if (value != null) {
            this.actions.addAll(value);
        }
        return this;
    }

    public LookupQuery excludeActions(Collection<ActionType> value) {
        if (value != null) {
            this.excludedActions.addAll(value);
        }
        return this;
    }

    public LookupQuery material(String value) {
        this.material = value;
        return this;
    }

    public LookupQuery entityType(String value) {
        this.entityType = value;
        return this;
    }

    public LookupQuery since(long epochMillis) {
        this.since = epochMillis;
        return this;
    }

    public LookupQuery before(long epochMillis) {
        this.before = epochMillis;
        return this;
    }

    public LookupQuery radius(int value) {
        this.radius = value;
        return this;
    }

    public LookupQuery center(String world, int x, int y, int z) {
        this.centerWorld = world;
        this.centerX = x;
        this.centerY = y;
        this.centerZ = z;
        return this;
    }

    public LookupQuery page(int value) {
        this.page = Math.min(MAX_PAGE, Math.max(1, value));
        return this;
    }

    public LookupQuery limit(int value) {
        this.limit = value;
        return this;
    }

    public LookupQuery ascending(boolean value) {
        this.ascending = value;
        return this;
    }

    public LookupQuery search(String value) {
        this.search = value;
        return this;
    }

    public LookupQuery ids(List<Long> value) {
        this.ids = value;
        return this;
    }

    public boolean hasRadius() {
        return radius >= 0 && centerX != null && centerZ != null;
    }
}
