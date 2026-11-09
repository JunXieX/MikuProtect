package dev.junxiex.mikuprotect.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.DyeColor;
import org.bukkit.Server;
import org.bukkit.block.Banner;
import org.bukkit.block.Beacon;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Campfire;
import org.bukkit.block.CommandBlock;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Furnace;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Lectern;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.Structure;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.inventory.ItemStack;

/**
 * Serializes the tile-entity part of a block state (container contents, sign text, spawner
 * settings, ...) into a compact binary payload. Block data alone only covers block-state
 * properties; redstone machines also depend on what is inside hoppers, droppers and dispensers.
 *
 * <p>The format is self-describing by tile type and versioned by {@link #FORMAT_VERSION}. It is
 * written in a forward-compatible way: unknown trailing fields are ignored, missing trailing
 * fields keep their defaults.</p>
 */
public final class TileEntityCodec {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static final int FORMAT_VERSION = 1;

    private static final int TYPE_CONTAINER = 1;
    private static final int TYPE_SIGN = 2;
    private static final int TYPE_SPAWNER = 3;
    private static final int TYPE_BANNER = 4;
    private static final int TYPE_SKULL = 5;
    private static final int TYPE_JUKEBOX = 6;
    private static final int TYPE_LECTERN = 7;
    private static final int TYPE_BEACON = 8;
    private static final int TYPE_COMMAND = 9;
    private static final int TYPE_STRUCTURE = 10;
    private static final int TYPE_CAMPFIRE = 11;

    private static final int MAX_SLOTS = 4096;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;

    private TileEntityCodec() {
    }

    public static byte[] encode(BlockState state) {
        if (state == null) {
            return null;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeByte(FORMAT_VERSION);
                if (state instanceof Container container) {
                    out.writeByte(TYPE_CONTAINER);
                    writeInventory(out, container.getSnapshotInventory().getContents());
                    if (state instanceof Furnace furnace) {
                        out.writeShort(furnace.getBurnTime());
                        out.writeShort(furnace.getCookTime());
                        out.writeInt(furnace.getCookTimeTotal());
                    }
                    if (state instanceof BrewingStand brewing) {
                        out.writeInt(brewing.getBrewingTime());
                        out.writeInt(brewing.getFuelLevel());
                    }
                } else if (state instanceof Sign sign) {
                    out.writeByte(TYPE_SIGN);
                    writeSignSide(out, sign.getSide(Side.FRONT));
                    writeSignSide(out, sign.getSide(Side.BACK));
                    out.writeBoolean(sign.isWaxed());
                } else if (state instanceof Lectern lectern) {
                    out.writeByte(TYPE_LECTERN);
                    writeInventory(out, lectern.getSnapshotInventory().getContents());
                    out.writeInt(lectern.getPage());
                } else if (state instanceof CreatureSpawner spawner) {
                    out.writeByte(TYPE_SPAWNER);
                    out.writeUTF(spawner.getSpawnedType() == null ? "" : spawner.getSpawnedType().getKey().toString());
                    out.writeInt(spawner.getDelay());
                    out.writeInt(spawner.getMinSpawnDelay());
                    out.writeInt(spawner.getMaxSpawnDelay());
                    out.writeInt(spawner.getSpawnCount());
                    out.writeInt(spawner.getMaxNearbyEntities());
                    out.writeInt(spawner.getRequiredPlayerRange());
                    out.writeInt(spawner.getSpawnRange());
                } else if (state instanceof Banner banner) {
                    out.writeByte(TYPE_BANNER);
                    out.writeUTF(banner.getBaseColor().name());
                    List<Pattern> patterns = banner.getPatterns();
                    out.writeInt(patterns.size());
                    for (Pattern pattern : patterns) {
                        out.writeUTF(pattern.getColor().name());
                        org.bukkit.NamespacedKey patternKey =
                                org.bukkit.Registry.BANNER_PATTERN.getKey(pattern.getPattern());
                        out.writeUTF(patternKey == null ? "" : patternKey.toString());
                    }
                } else if (state instanceof Skull skull) {
                    out.writeByte(TYPE_SKULL);
                    ResolvableProfile profile = skull.getProfile();
                    out.writeUTF(profile == null || profile.uuid() == null ? "" : profile.uuid().toString());
                    out.writeUTF(skull.getRotation() == null ? "" : skull.getRotation().name());
                } else if (state instanceof Jukebox jukebox) {
                    out.writeByte(TYPE_JUKEBOX);
                    writeBytes(out, ItemCodec.encode(jukebox.getRecord()));
                } else if (state instanceof Beacon beacon) {
                    out.writeByte(TYPE_BEACON);
                    out.writeInt(beacon.getTier());
                    out.writeUTF(beacon.getPrimaryEffect() == null ? ""
                            : beacon.getPrimaryEffect().getType().getKey().toString());
                    out.writeUTF(beacon.getSecondaryEffect() == null ? ""
                            : beacon.getSecondaryEffect().getType().getKey().toString());
                } else if (state instanceof CommandBlock commandBlock) {
                    out.writeByte(TYPE_COMMAND);
                    writeString(out, commandBlock.getCommand());
                    Component name = commandBlock.name();
                    writeString(out, name == null ? "" : LEGACY.serialize(name));
                } else if (state instanceof Structure structure) {
                    out.writeByte(TYPE_STRUCTURE);
                    writeString(out, structure.getStructureName());
                    writeString(out, structure.getAuthor());
                    writeString(out, structure.getUsageMode().name());
                    out.writeInt(structure.getRelativePosition().getBlockX());
                    out.writeInt(structure.getRelativePosition().getBlockY());
                    out.writeInt(structure.getRelativePosition().getBlockZ());
                    out.writeInt(structure.getStructureSize().getBlockX());
                    out.writeInt(structure.getStructureSize().getBlockY());
                    out.writeInt(structure.getStructureSize().getBlockZ());
                    out.writeFloat(structure.getIntegrity());
                    out.writeLong(structure.getSeed());
                    writeString(out, structure.getMetadata());
                } else if (state instanceof Campfire campfire) {
                    out.writeByte(TYPE_CAMPFIRE);
                    out.writeInt(campfire.getSize());
                    for (int index = 0; index < campfire.getSize(); index++) {
                        writeBytes(out, ItemCodec.encode(campfire.getItem(index)));
                        out.writeInt(campfire.getCookTime(index));
                        out.writeInt(campfire.getCookTimeTotal(index));
                    }
                } else {
                    return null;
                }
                return bytes.toByteArray();
            }
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    /**
     * Applies a payload captured by {@link #encode(BlockState)} to a live state. The caller must
     * already have set the block data; this only fills the tile-entity part and calls
     * {@code update(true, false)}.
     */
    public static boolean apply(BlockState state, byte[] payload, Server server) {
        if (state == null || payload == null || payload.length == 0) {
            return false;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int version = in.readUnsignedByte();
            if (version > FORMAT_VERSION) {
                return false;
            }
            int type = in.readUnsignedByte();
            switch (type) {
                case TYPE_CONTAINER -> {
                    if (state instanceof Container container) {
                        ItemStack[] contents = readInventory(in);
                        // A double chest's live inventory can be larger than a single-block
                        // snapshot. Copy slot-by-slot so a shorter payload never clears the tail;
                        // a too-long payload cannot be applied faithfully.
                        ItemStack[] current = container.getInventory().getContents();
                        if (contents.length > current.length) {
                            return false;
                        }
                        System.arraycopy(contents, 0, current, 0, contents.length);
                        container.getInventory().setContents(current);
                        if (state instanceof Furnace furnace) {
                            furnace.setBurnTime(readShortSafely(in, furnace.getBurnTime()));
                            furnace.setCookTime(readShortSafely(in, furnace.getCookTime()));
                            furnace.setCookTimeTotal(readIntSafely(in, furnace.getCookTimeTotal()));
                        }
                        if (state instanceof BrewingStand brewing) {
                            brewing.setBrewingTime(readIntSafely(in, brewing.getBrewingTime()));
                            brewing.setFuelLevel(readIntSafely(in, brewing.getFuelLevel()));
                        }
                    }
                }
                case TYPE_SIGN -> {
                    if (state instanceof Sign sign) {
                        readSignSide(in, sign.getSide(Side.FRONT));
                        readSignSide(in, sign.getSide(Side.BACK));
                        sign.setWaxed(readBooleanSafely(in, sign.isWaxed()));
                    }
                }
                case TYPE_LECTERN -> {
                    if (state instanceof Lectern lectern) {
                        ItemStack[] contents = readInventory(in);
                        ItemStack[] current = lectern.getInventory().getContents();
                        if (contents.length > current.length) {
                            return false;
                        }
                        System.arraycopy(contents, 0, current, 0, contents.length);
                        lectern.getInventory().setContents(current);
                        lectern.setPage(readIntSafely(in, lectern.getPage()));
                    }
                }
                case TYPE_SPAWNER -> {
                    if (state instanceof CreatureSpawner spawner) {
                        String entity = readStringSafely(in, "");
                        if (!entity.isEmpty()) {
                            org.bukkit.entity.EntityType entityType =
                                    org.bukkit.entity.EntityType.fromName(entity.replace("minecraft:", ""));
                            if (entityType != null) {
                                spawner.setSpawnedType(entityType);
                            }
                        }
                        spawner.setDelay(readIntSafely(in, spawner.getDelay()));
                        spawner.setMinSpawnDelay(readIntSafely(in, spawner.getMinSpawnDelay()));
                        spawner.setMaxSpawnDelay(readIntSafely(in, spawner.getMaxSpawnDelay()));
                        spawner.setSpawnCount(readIntSafely(in, spawner.getSpawnCount()));
                        spawner.setMaxNearbyEntities(readIntSafely(in, spawner.getMaxNearbyEntities()));
                        spawner.setRequiredPlayerRange(readIntSafely(in, spawner.getRequiredPlayerRange()));
                        spawner.setSpawnRange(readIntSafely(in, spawner.getSpawnRange()));
                    }
                }
                case TYPE_BANNER -> {
                    if (state instanceof Banner banner) {
                        String baseColor = readStringSafely(in, banner.getBaseColor().name());
                        try {
                            banner.setBaseColor(DyeColor.valueOf(baseColor));
                        } catch (IllegalArgumentException ignored) {
                            // The value was already consumed above; never consume a second field.
                        }
                        int count = Math.min(1024, Math.max(0, readIntSafely(in, 0)));
                        List<Pattern> patterns = new ArrayList<>(count);
                        for (int index = 0; index < count; index++) {
                            DyeColor color;
                            try {
                                color = DyeColor.valueOf(readStringSafely(in, DyeColor.WHITE.name()));
                            } catch (IllegalArgumentException exception) {
                                color = DyeColor.WHITE;
                            }
                            String patternKey = readStringSafely(in, "");
                            PatternType patternType = org.bukkit.Registry.BANNER_PATTERN.get(
                                    org.bukkit.NamespacedKey.fromString(patternKey));
                            if (patternType != null) {
                                patterns.add(new Pattern(color, patternType));
                            }
                        }
                        banner.setPatterns(patterns);
                    }
                }
                case TYPE_SKULL -> {
                    if (state instanceof Skull skull) {
                        String uuid = readStringSafely(in, "");
                        if (!uuid.isEmpty()) {
                            skull.setProfile(ResolvableProfile.resolvableProfile(
                                    server.createProfile(java.util.UUID.fromString(uuid))));
                        }
                        String rotation = readStringSafely(in, "");
                        if (!rotation.isEmpty()) {
                            try {
                                skull.setRotation(BlockFace.valueOf(rotation));
                            } catch (IllegalArgumentException ignored) {
                                // keep current rotation
                            }
                        }
                    }
                }
                case TYPE_JUKEBOX -> {
                    if (state instanceof Jukebox jukebox) {
                        ItemStack record = ItemCodec.decode(readBytes(in));
                        if (record != null) {
                            jukebox.setRecord(record);
                        }
                    }
                }
                case TYPE_BEACON -> {
                    if (state instanceof Beacon beacon) {
                        readIntSafely(in, beacon.getTier());
                        String primary = readStringSafely(in, "");
                        String secondary = readStringSafely(in, "");
                        org.bukkit.potion.PotionEffectType primaryType = effect(primary);
                        if (primaryType != null) {
                            beacon.setPrimaryEffect(primaryType);
                        }
                        org.bukkit.potion.PotionEffectType secondaryType = effect(secondary);
                        if (secondaryType != null) {
                            beacon.setSecondaryEffect(secondaryType);
                        }
                    }
                }
                case TYPE_COMMAND -> {
                    if (state instanceof CommandBlock commandBlock) {
                        commandBlock.setCommand(readStringSafely(in, commandBlock.getCommand()));
                        commandBlock.name(LEGACY.deserialize(readStringSafely(in, "")));
                    }
                }
                case TYPE_STRUCTURE -> {
                    if (state instanceof Structure structure) {
                        structure.setStructureName(readStringSafely(in, structure.getStructureName()));
                        structure.setAuthor(readStringSafely(in, structure.getAuthor()));
                        String usageMode = readStringSafely(in, "");
                        if (!usageMode.isEmpty()) {
                            try {
                                structure.setUsageMode(org.bukkit.block.structure.UsageMode.valueOf(usageMode));
                            } catch (IllegalArgumentException ignored) {
                                // keep current mode
                            }
                        }
                        structure.setRelativePosition(new org.bukkit.util.BlockVector(
                                readIntSafely(in, 0), readIntSafely(in, 0), readIntSafely(in, 0)));
                        structure.setStructureSize(new org.bukkit.util.BlockVector(
                                readIntSafely(in, 0), readIntSafely(in, 0), readIntSafely(in, 0)));
                        structure.setIntegrity(readFloatSafely(in, structure.getIntegrity()));
                        structure.setSeed(readLongSafely(in, structure.getSeed()));
                        structure.setMetadata(readStringSafely(in, structure.getMetadata()));
                    }
                }
                case TYPE_CAMPFIRE -> {
                    if (state instanceof Campfire campfire) {
                        int size = Math.min(16, Math.max(0, readIntSafely(in, campfire.getSize())));
                        for (int index = 0; index < size; index++) {
                            ItemStack item = ItemCodec.decode(readBytes(in));
                            int cook = readIntSafely(in, campfire.getCookTime(index));
                            int total = readIntSafely(in, campfire.getCookTimeTotal(index));
                            campfire.setItem(index, item);
                            campfire.setCookTime(index, cook);
                            campfire.setCookTimeTotal(index, total);
                        }
                    }
                }
                default -> {
                    return false;
                }
            }
            state.update(true, false);
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static org.bukkit.potion.PotionEffectType effect(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        org.bukkit.NamespacedKey namespaced = org.bukkit.NamespacedKey.fromString(key);
        return namespaced == null ? null : org.bukkit.Registry.EFFECT.get(namespaced);
    }

    private static void writeInventory(DataOutputStream out, ItemStack[] contents) throws IOException {
        int size = Math.min(contents.length, MAX_SLOTS);
        out.writeInt(size);
        for (int index = 0; index < size; index++) {
            writeBytes(out, ItemCodec.encode(contents[index]));
        }
    }

    private static ItemStack[] readInventory(DataInputStream in) throws IOException {
        int size = Math.min(MAX_SLOTS, Math.max(0, in.readInt()));
        ItemStack[] contents = new ItemStack[size];
        for (int index = 0; index < size; index++) {
            contents[index] = ItemCodec.decode(readBytes(in));
        }
        return contents;
    }

    private static void writeSignSide(DataOutputStream out, SignSide side) throws IOException {
        if (side == null) {
            out.writeUTF("");
            out.writeUTF("");
            out.writeUTF("");
            out.writeUTF("");
            out.writeUTF("");
        } else {
            List<Component> lines = side.lines();
            for (int index = 0; index < 5; index++) {
                Component line = index < lines.size() ? lines.get(index) : null;
                out.writeUTF(line == null ? "" : LEGACY.serialize(line));
            }
        }
        out.writeUTF(side == null || side.getColor() == null ? "" : side.getColor().name());
        out.writeBoolean(side != null && side.isGlowingText());
    }

    private static void readSignSide(DataInputStream in, SignSide side) throws IOException {
        for (int index = 0; index < 5; index++) {
            String line = readStringSafely(in, "");
            if (side != null && index < 4) {
                side.line(index, LEGACY.deserialize(line));
            }
        }
        String color = readStringSafely(in, "");
        if (side != null && !color.isEmpty()) {
            try {
                side.setColor(DyeColor.valueOf(color));
            } catch (IllegalArgumentException ignored) {
                // keep current color
            }
        }
        boolean glowing = readBooleanSafely(in, side != null && side.isGlowingText());
        if (side != null) {
            side.setGlowingText(glowing);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        writeBytes(out, value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readStringSafely(DataInputStream in, String fallback) {
        try {
            byte[] bytes = readBytes(in);
            return bytes == null ? fallback : new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return fallback;
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        if (bytes == null) {
            out.writeInt(-1);
            return;
        }
        int length = Math.min(bytes.length, MAX_PAYLOAD);
        out.writeInt(length);
        out.write(bytes, 0, length);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        if (length > MAX_PAYLOAD) {
            throw new IOException("tile payload too large: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static int readIntSafely(DataInputStream in, int fallback) {
        try {
            return in.readInt();
        } catch (IOException exception) {
            return fallback;
        }
    }

    private static short readShortSafely(DataInputStream in, short fallback) {
        try {
            return in.readShort();
        } catch (IOException exception) {
            return fallback;
        }
    }

    private static float readFloatSafely(DataInputStream in, float fallback) {
        try {
            return in.readFloat();
        } catch (IOException exception) {
            return fallback;
        }
    }

    private static long readLongSafely(DataInputStream in, long fallback) {
        try {
            return in.readLong();
        } catch (IOException exception) {
            return fallback;
        }
    }

    private static boolean readBooleanSafely(DataInputStream in, boolean fallback) {
        try {
            return in.readBoolean();
        } catch (IOException exception) {
            return fallback;
        }
    }
}
