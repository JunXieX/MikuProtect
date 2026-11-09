package dev.junxiex.mikuprotect.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 维度表的 name↔id 双缓存与主键分配器。
 *
 * <p>主键由应用侧分配：启动时用 SELECT COALESCE(MAX(id), 0) 把分配水位推到库中最大值之上，
 * 运行期新名字由 {@link NameTable#assign(String)} 分配 id 并标记为待落库，写入成功后由
 * {@link NameTable#markPersisted(Collection)} 清标记。活动与容器只有 id 分配器，没有名字缓存。</p>
 */
public final class IdRegistry {

    private final NameTable worlds = new NameTable();
    private final NameTable users = new NameTable();
    private final NameTable materials = new NameTable();
    private final AtomicLong activities = new AtomicLong();
    private final AtomicLong containers = new AtomicLong();

    public NameTable worlds() {
        return worlds;
    }

    public NameTable users() {
        return users;
    }

    public NameTable materials() {
        return materials;
    }

    /** 分配下一个活动 id。 */
    public long nextActivityId() {
        return activities.incrementAndGet();
    }

    /** 分配下一个容器行 id。 */
    public long nextContainerId() {
        return containers.incrementAndGet();
    }

    /** 用库中已有的最大活动 id 抬高分配水位。 */
    public void seedActivities(long maxId) {
        activities.accumulateAndGet(maxId, Math::max);
    }

    /** 用库中已有的最大容器 id 抬高分配水位。 */
    public void seedContainers(long maxId) {
        containers.accumulateAndGet(maxId, Math::max);
    }

    /**
     * 单张维度表的 name↔id 双缓存。读多写少，因此用并发映射；分配动作本身是原子的。
     */
    public static final class NameTable {

        private final Map<String, Long> nameToId = new ConcurrentHashMap<>();
        private final Map<String, Long> lowerToId = new ConcurrentHashMap<>();
        private final Set<String> pending = ConcurrentHashMap.newKeySet();
        private final AtomicLong sequence = new AtomicLong();

        /** 查名字对应的 id，未见过返回 null。 */
        public Long id(String name) {
            return name == null ? null : nameToId.get(name);
        }

        /** Case-insensitive lookup, used for player names. */
        public Long idIgnoreCase(String name) {
            return name == null ? null : lowerToId.get(name.toLowerCase(java.util.Locale.ROOT));
        }

        /** 取已有 id，没有则分配一个新的并标记为待落库。 */
        public long assign(String name) {
            if (name == null) {
                throw new IllegalArgumentException("维度名字不能为空");
            }
            Long cached = nameToId.get(name);
            if (cached != null) {
                return cached;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            Long existing = lowerToId.get(lower);
            if (existing != null) {
                // Player names are case-insensitive; a case change must not create a second UUID row.
                nameToId.putIfAbsent(name, existing);
                return existing;
            }
            long id = nameToId.computeIfAbsent(name, key -> {
                long assigned = sequence.incrementAndGet();
                pending.add(key);
                return assigned;
            });
            lowerToId.putIfAbsent(lower, id);
            return id;
        }

        /** 启动时装载库中已有的行，并把分配水位推到该 id 之上。 */
        public void load(String name, long id) {
            if (name == null) {
                return;
            }
            nameToId.put(name, id);
            lowerToId.putIfAbsent(name.toLowerCase(java.util.Locale.ROOT), id);
            sequence.accumulateAndGet(id, Math::max);
        }

        /** 待落库的名字快照，顺序固定便于测试与排查。 */
        public List<String> pendingNames() {
            List<String> names = new ArrayList<>(pending);
            Collections.sort(names);
            return names;
        }

        /** 确认这些名字已经落库，移除待落库标记。 */
        public void markPersisted(Collection<String> names) {
            if (names == null || names.isEmpty()) {
                return;
            }
            pending.removeAll(names);
        }

        /** 已缓存的名字数量。 */
        public int size() {
            return nameToId.size();
        }
    }
}
