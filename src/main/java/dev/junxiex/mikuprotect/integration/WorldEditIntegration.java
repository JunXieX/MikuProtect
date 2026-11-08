package dev.junxiex.mikuprotect.integration;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import dev.junxiex.mikuprotect.MikuProtectPlugin;
import dev.junxiex.mikuprotect.model.ActionType;
import dev.junxiex.mikuprotect.model.ActivityRecord;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Optional WorldEdit/FAWE bridge. Wraps the edit session extent and records each block change
 * through the same queue as normal events.
 *
 * <p>WorldEdit may execute on its own thread. Only block coordinates and block-state strings are
 * captured there; the queue and storage are thread-safe.</p>
 */
public final class WorldEditIntegration {

    private final MikuProtectPlugin plugin;

    public WorldEditIntegration(MikuProtectPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        WorldEdit.getInstance().getEventBus().register(this);
    }

    public void unregister() {
        try {
            WorldEdit.getInstance().getEventBus().unregister(this);
        } catch (RuntimeException ignored) {
            // WorldEdit may already be shutting down
        }
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (event.getStage() != EditSession.Stage.BEFORE_CHANGE || event.getWorld() == null) {
            return;
        }
        Extent original = event.getExtent();
        if (original == null) {
            return;
        }
        String actor = event.getActor() == null || event.getActor().getName() == null
                ? "WorldEdit" : event.getActor().getName();
        event.setExtent(new RecordingExtent(plugin, event.getWorld(), original, actor,
                plugin.config().worldEditMaxBlocks));
    }

    private static final class RecordingExtent extends AbstractDelegateExtent {

        private final MikuProtectPlugin plugin;
        private final com.sk89q.worldedit.world.World world;
        private final String actor;
        private final int maxBlocks;
        private final AtomicInteger recorded = new AtomicInteger();
        private final AtomicBoolean warned = new AtomicBoolean();

        private RecordingExtent(MikuProtectPlugin plugin, com.sk89q.worldedit.world.World world, Extent extent,
                String actor, int maxBlocks) {
            super(extent);
            this.plugin = plugin;
            this.world = world;
            this.actor = actor;
            this.maxBlocks = maxBlocks;
        }

        /**
         * FAWE adds bulk methods to Extent; WorldEdit 7.3 does not. Keeping the exact descriptors
         * here means FAWE dispatches its //set and //replace fast paths through this recorder
         * instead of bypassing setBlock. The methods are inert on plain WorldEdit.
         */
        public <B extends BlockStateHolder<B>> int setBlocks(Region region, B block)
                throws com.sk89q.worldedit.MaxChangedBlocksException {
            int affected = 0;
            for (BlockVector3 position : region) {
                if (bulkSet(position, block)) {
                    affected++;
                }
            }
            return affected;
        }

        public int setBlocks(Region region, Pattern pattern)
                throws com.sk89q.worldedit.MaxChangedBlocksException {
            int affected = 0;
            for (BlockVector3 position : region) {
                if (bulkSet(position, pattern.applyBlock(position))) {
                    affected++;
                }
            }
            return affected;
        }

        public int setBlocks(java.util.Set<BlockVector3> positions, Pattern pattern) {
            int affected = 0;
            for (BlockVector3 position : positions) {
                try {
                    if (setBlock(position, pattern.applyBlock(position))) {
                        affected++;
                    }
                } catch (com.sk89q.worldedit.WorldEditException ignored) {
                    // keep the remaining bulk positions going
                }
            }
            return affected;
        }

        public <B extends BlockStateHolder<B>> int replaceBlocks(Region region,
                java.util.Set<BaseBlock> from, B to) throws com.sk89q.worldedit.MaxChangedBlocksException {
            int affected = 0;
            for (BlockVector3 position : region) {
                BaseBlock current = getFullBlock(position);
                if (from == null || from.contains(current)) {
                    if (bulkSet(position, to)) {
                        affected++;
                    }
                }
            }
            return affected;
        }

        public int replaceBlocks(Region region, java.util.Set<BaseBlock> from, Pattern pattern)
                throws com.sk89q.worldedit.MaxChangedBlocksException {
            int affected = 0;
            for (BlockVector3 position : region) {
                BaseBlock current = getFullBlock(position);
                if (from == null || from.contains(current)) {
                    if (bulkSet(position, pattern.applyBlock(position))) {
                        affected++;
                    }
                }
            }
            return affected;
        }

        public int replaceBlocks(Region region, Mask mask, Pattern pattern)
                throws com.sk89q.worldedit.MaxChangedBlocksException {
            int affected = 0;
            for (BlockVector3 position : region) {
                if (mask == null || mask.test(position)) {
                    if (bulkSet(position, pattern.applyBlock(position))) {
                        affected++;
                    }
                }
            }
            return affected;
        }

        private <B extends BlockStateHolder<B>> boolean bulkSet(BlockVector3 position, B block)
                throws com.sk89q.worldedit.MaxChangedBlocksException {
            try {
                return setBlock(position, block);
            } catch (com.sk89q.worldedit.MaxChangedBlocksException exception) {
                throw exception;
            } catch (com.sk89q.worldedit.WorldEditException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 position, T block)
                throws com.sk89q.worldedit.WorldEditException {
            BlockState before = getExtent().getBlock(position);
            boolean changed = super.setBlock(position, block);
            if (!changed) {
                return false;
            }
            try {
                if (maxBlocks <= 0 || recorded.incrementAndGet() <= maxBlocks) {
                    record(position, before, block.toImmutableState());
                } else if (warned.compareAndSet(false, true)) {
                    plugin.getLogger().warning("WorldEdit edit exceeded recording.worldedit-max-blocks="
                            + maxBlocks + "; further blocks in this session are not logged");
                }
            } catch (RuntimeException ignored) {
                // never let a recording failure abort the WorldEdit operation
            }
            return true;
        }

        private void record(BlockVector3 position, BlockState before, BlockState after) {
            World bukkitWorld = BukkitAdapter.adapt(world);
            if (bukkitWorld == null) {
                return;
            }
            Location location = BukkitAdapter.adapt(bukkitWorld, position);
            String beforeData = before == null ? "minecraft:air" : before.getAsString();
            String afterData = after == null ? "minecraft:air" : after.getAsString();
            String beforeType = before == null ? "minecraft:air" : before.getBlockType().id();
            String afterType = after == null ? "minecraft:air" : after.getBlockType().id();
            ActionType action;
            if ("minecraft:air".equals(beforeType) && !"minecraft:air".equals(afterType)) {
                action = ActionType.BLOCK_PLACE;
            } else if (!"minecraft:air".equals(beforeType) && "minecraft:air".equals(afterType)) {
                action = ActionType.BLOCK_BREAK;
            } else {
                action = ActionType.BLOCK_ENTITY_CHANGE;
            }
            String material = "minecraft:air".equals(afterType) ? beforeType : afterType;
            plugin.recording().record(ActivityRecord.block(location, action, actor, null,
                    material, afterData, beforeData));
        }
    }
}
