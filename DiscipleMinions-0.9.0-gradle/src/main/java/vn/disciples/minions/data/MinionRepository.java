package vn.disciples.minions.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import vn.disciples.minions.model.*;
import vn.disciples.minions.util.ItemCodec;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Lưu trữ Đệ Tử.
 *
 * 0.7.2:
 * - save() chỉ đánh dấu "dirty", việc ghi file được gom lại (autosave) thay vì ghi đồng bộ mỗi lần.
 * - flush() tuần tự hóa trên main thread rồi ghi file ở thread phụ (dùng cho thao tác quan trọng).
 * - saveNow() ghi đồng bộ (dùng khi tắt server).
 * - Vị trí được lưu dạng thô, không phụ thuộc world đã load hay chưa => không mất dữ liệu nữa.
 */
public final class MinionRepository {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, MinionData> minions = new LinkedHashMap<UUID, MinionData>();
    private final Set<UUID> revoked = new HashSet<UUID>();

    private volatile boolean dirty;
    private long sequence;            // chỉ đụng trên main thread
    private final Object writeLock = new Object();
    private long lastWrittenSequence; // chỉ đụng trong writeLock

    public MinionRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "minions.yml");
    }

    public void load() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        minions.clear();
        revoked.clear();

        ConfigurationSection root = yaml.getConfigurationSection("minions");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    String p = "minions." + key + ".";
                    UUID owner = UUID.fromString(yaml.getString(p + "owner"));
                    MinionType type = MinionType.valueOf(yaml.getString(p + "type", "FARM"));
                    MinionData d = new MinionData(id, owner, type);
                    d.setState(MinionState.valueOf(yaml.getString(p + "state", "ITEM")));
                    d.setLevel(yaml.getInt(p + "level", 1));
                    d.setNextRunAt(yaml.getLong(p + "next-run-at", 0L));
                    if (yaml.contains(p + "world")) {
                        d.setRawLocation(yaml.getString(p + "world"),
                                yaml.getDouble(p + "x"), yaml.getDouble(p + "y"), yaml.getDouble(p + "z"),
                                (float) yaml.getDouble(p + "yaw"));
                    }
                    boolean hasChest = yaml.contains(p + "chest.world");
                    if (hasChest) {
                        d.setRawChest(yaml.getString(p + "chest.world"),
                                yaml.getInt(p + "chest.x"), yaml.getInt(p + "chest.y"), yaml.getInt(p + "chest.z"));
                    }
                    d.setOutputMode(OutputMode.valueOf(yaml.getString(p + "output-mode", hasChest ? "CHEST" : "NONE")));
                    String tool = yaml.getString(p + "tool");
                    if (tool != null) d.setTool(ItemCodec.decode(tool));
                    String offlineCard = yaml.getString(p + "offline.card");
                    if (offlineCard != null) {
                        d.setOfflineCard(ItemCodec.decode(offlineCard));
                        d.setOfflineCapacityMs(yaml.getLong(p + "offline.capacity-ms", 0L));
                        d.setOfflineEnergyMs(yaml.getLong(p + "offline.energy-ms", 0L));
                    }
                    for (String entry : yaml.getStringList(p + "verified")) {
                        String[] parts = entry.split(":");
                        if (parts.length != 2) continue;
                        try { d.putVerifiedRaw(Long.parseLong(parts[0]), Long.parseLong(parts[1])); }
                        catch (NumberFormatException ignored) {}
                    }
                    minions.put(id, d);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Bỏ qua dữ liệu Đệ Tử lỗi: " + key + " - " + ex.getMessage());
                }
            }
        }
        for (String id : yaml.getStringList("revoked")) {
            try { revoked.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) {}
        }
        dirty = false;
    }

    /** Đánh dấu cần lưu. Autosave sẽ ghi file sau tối đa vài giây. */
    public void save() { dirty = true; }

    /** Lưu ngay (ghi file ở thread phụ). Gọi trên main thread. */
    public void flush() {
        dirty = true;
        flushAsyncIfDirty();
    }

    /** Dùng cho autosave: chỉ ghi khi có thay đổi. */
    public void flushAsyncIfDirty() {
        if (!dirty) return;
        dirty = false;
        final long seq = ++sequence;
        final String data = serialize();
        if (!plugin.isEnabled()) {
            writeFile(data, seq);
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override public void run() { writeFile(data, seq); }
        });
    }

    /** Ghi đồng bộ, dùng khi onDisable. */
    public void saveNow() {
        dirty = false;
        long seq = ++sequence;
        writeFile(serialize(), seq);
    }

    private String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (MinionData d : minions.values()) {
            String p = "minions." + d.getId() + ".";
            yaml.set(p + "owner", d.getOwner().toString());
            yaml.set(p + "type", d.getType().name());
            yaml.set(p + "state", d.getState().name());
            yaml.set(p + "level", d.getLevel());
            yaml.set(p + "speed-level", d.getLevel()); // legacy compatibility
            yaml.set(p + "output-mode", d.getOutputMode().name());
            yaml.set(p + "next-run-at", d.getNextRunAt());
            if (d.hasLocationData()) {
                yaml.set(p + "world", d.getWorldName());
                yaml.set(p + "x", d.getX());
                yaml.set(p + "y", d.getY());
                yaml.set(p + "z", d.getZ());
                yaml.set(p + "yaw", d.getYaw());
            }
            if (d.hasChestData()) {
                yaml.set(p + "chest.world", d.getChestWorld());
                yaml.set(p + "chest.x", d.getChestX());
                yaml.set(p + "chest.y", d.getChestY());
                yaml.set(p + "chest.z", d.getChestZ());
            }
            org.bukkit.inventory.ItemStack tool = d.getTool();
            if (tool != null) yaml.set(p + "tool", ItemCodec.encode(tool));
            if (d.hasOfflineCard()) {
                yaml.set(p + "offline.card", ItemCodec.encode(d.getOfflineCard()));
                yaml.set(p + "offline.capacity-ms", d.getOfflineCapacityMs());
                yaml.set(p + "offline.energy-ms", d.getOfflineEnergyMs());
            }
            if (!d.getVerified().isEmpty()) {
                List<String> verifiedList = new ArrayList<String>();
                for (Map.Entry<Long, Long> e : d.getVerified().entrySet()) verifiedList.add(e.getKey() + ":" + e.getValue());
                yaml.set(p + "verified", verifiedList);
            }
        }
        List<String> ids = new ArrayList<String>();
        for (UUID id : revoked) ids.add(id.toString());
        yaml.set("revoked", ids);
        return yaml.saveToString();
    }

    private void writeFile(String data, long seq) {
        synchronized (writeLock) {
            if (seq < lastWrittenSequence) return; // đã có bản mới hơn được ghi
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try {
                Files.write(tmp.toPath(), data.getBytes(StandardCharsets.UTF_8));
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                lastWrittenSequence = seq;
            } catch (IOException e) {
                dirty = true; // thử lại ở lần autosave sau
                plugin.getLogger().severe("Không thể lưu minions.yml: " + e.getMessage());
            }
        }
    }

    public Collection<MinionData> all() { return Collections.unmodifiableCollection(minions.values()); }
    public MinionData get(UUID id) { return minions.get(id); }
    public void put(MinionData data) { minions.put(data.getId(), data); flush(); }
    public void delete(UUID id) { minions.remove(id); revoked.add(id); flush(); }
    public boolean isRevoked(UUID id) { return revoked.contains(id); }
    public void revoke(UUID id) { revoked.add(id); flush(); }
    public List<MinionData> byOwner(UUID owner) {
        List<MinionData> out = new ArrayList<MinionData>();
        for (MinionData d : minions.values()) if (d.getOwner().equals(owner)) out.add(d);
        return out;
    }
}
