package vn.disciples.minions.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
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
 *
 * 0.13.1 (sửa lỗi mất dữ liệu):
 * - Chỉ đặt dirty=false SAU KHI tuần tự hóa thành công. Trước đây một ItemStack không serialize được
 *   làm task autosave ném lỗi (Bukkit cancel task) + dirty đã false => không bao giờ ghi file nữa.
 * - Một tool hỏng không còn làm mất cả record Đệ Tử: chỉ bỏ field tool, giữ nguyên Đệ Tử.
 * - Cờ loaded: nếu minions.yml không đọc được thì KHÔNG ghi đè và KHÔNG cho phép xoá item Đệ Tử.
 */
public final class MinionRepository {
    private static final int MAX_REVOKED = 20000;

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, MinionData> minions = new LinkedHashMap<UUID, MinionData>();
    /** ID đã xoá: item mang ID này sẽ bị thu hồi. Giới hạn kích thước để không phình vô hạn. */
    private final Set<UUID> revoked = new LinkedHashSet<UUID>();

    private volatile boolean dirty;
    /** true khi đã đọc xong minions.yml (hoặc file chưa tồn tại). false = dữ liệu chưa chắc chắn. */
    private volatile boolean loaded;
    private boolean warnedNotLoaded;
    private long sequence;            // chỉ đụng trên main thread
    private final Object writeLock = new Object();
    private long lastWrittenSequence; // chỉ đụng trong writeLock

    public MinionRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "minions.yml");
    }

    /** Dữ liệu đã đọc thành công chưa. Nếu false, tuyệt đối không được xoá item hay ghi đè file. */
    public boolean isLoaded() { return loaded; }

    public void load() {
        loaded = false;
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        minions.clear();
        revoked.clear();

        boolean existedWithContent = file.isFile() && file.length() > 0L;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("minions");
        boolean looksValid = root != null || yaml.contains("revoked");
        if (existedWithContent && !looksValid) {
            // File có nội dung nhưng không đọc ra cấu trúc mong đợi: nhiều khả năng YAML hỏng.
            // Giữ nguyên file, không cho autosave ghi đè, không cho RevokedItemTask xoá item.
            plugin.getLogger().severe("minions.yml có nội dung nhưng không đọc được cấu trúc 'minions:' - "
                    + "đã TẠM DỪNG ghi file để tránh mất dữ liệu. Hãy kiểm tra/sao lưu file rồi /detu reload.");
            return;
        }

        if (root != null) {
            for (String key : root.getKeys(false)) {
                try {
                    MinionData d = readMinion(yaml, key);
                    if (d != null) minions.put(d.getId(), d);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Bỏ qua dữ liệu Đệ Tử lỗi: " + key + " - " + ex.getMessage());
                }
            }
        }
        for (String id : yaml.getStringList("revoked")) {
            try { revoked.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) {}
        }
        trimRevoked();
        dirty = false;
        loaded = true;
    }

    /**
     * Đọc 1 Đệ Tử. Tool lỗi chỉ làm mất tool, KHÔNG làm mất cả Đệ Tử (trước đây catch ở ngoài bỏ cả record
     * => lần save sau ghi file thiếu Đệ Tử đó, coi như xoá vĩnh viễn).
     */
    private MinionData readMinion(YamlConfiguration yaml, String key) {
        UUID id = UUID.fromString(key);
        String p = "minions." + key + ".";
        UUID owner = UUID.fromString(yaml.getString(p + "owner"));
        MinionType type = parseEnum(MinionType.class, yaml.getString(p + "type"), MinionType.FARM);
        MinionData d = new MinionData(id, owner, type);
        d.setOwnerName(yaml.getString(p + "owner-name"));
        d.setState(parseEnum(MinionState.class, yaml.getString(p + "state"), MinionState.ITEM));
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
        d.setOutputMode(parseEnum(OutputMode.class, yaml.getString(p + "output-mode"), hasChest ? OutputMode.CHEST : OutputMode.NONE));
        String tool = yaml.getString(p + "tool");
        if (tool != null) {
            try {
                d.setTool(ItemCodec.decode(tool));
            } catch (Throwable ex) {
                plugin.getLogger().warning("Không đọc được công cụ của Đệ Tử " + id
                        + " - giữ nguyên Đệ Tử và bỏ công cụ: " + ex.getMessage());
            }
        }
        return d;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback) {
        if (raw == null) return fallback;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
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
        if (!loaded) {
            if (!warnedNotLoaded) {
                warnedNotLoaded = true;
                plugin.getLogger().warning("Bỏ qua lưu minions.yml vì dữ liệu chưa đọc thành công (xem log lúc bật plugin).");
            }
            return;
        }
        // Tuần tự hóa TRƯỚC khi hạ cờ dirty: nếu lỗi thì dirty vẫn true và autosave sẽ thử lại.
        final String data;
        try {
            data = serialize();
        } catch (Throwable ex) {
            plugin.getLogger().severe("Không thể tuần tự hóa dữ liệu Đệ Tử, sẽ thử lại: " + ex);
            return;
        }
        final long seq = ++sequence;
        dirty = false;
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
        if (!loaded) {
            plugin.getLogger().severe("Không ghi minions.yml khi tắt server vì dữ liệu chưa đọc thành công.");
            return;
        }
        try {
            String data = serialize();
            long seq = ++sequence;
            dirty = false;
            writeFile(data, seq);
        } catch (Throwable ex) {
            dirty = true;
            plugin.getLogger().severe("Không thể lưu minions.yml khi tắt server: " + ex);
        }
    }

    private String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (MinionData d : minions.values()) {
            String p = "minions." + d.getId() + ".";
            try {
                yaml.set(p + "owner", d.getOwner().toString());
                if (d.getOwnerName() != null) yaml.set(p + "owner-name", d.getOwnerName());
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
            } catch (Throwable ex) {
                plugin.getLogger().warning("Bỏ qua field lỗi của Đệ Tử " + d.getId() + ": " + ex.getMessage());
            }
            // Tool tách riêng: một ItemStack không serialize được chỉ mất tool, không mất Đệ Tử.
            try {
                ItemStack tool = d.getTool();
                if (tool != null) yaml.set(p + "tool", ItemCodec.encode(tool));
            } catch (Throwable ex) {
                plugin.getLogger().warning("Không lưu được công cụ của Đệ Tử " + d.getId()
                        + " (Đệ Tử vẫn được giữ): " + ex.getMessage());
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

    private void trimRevoked() {
        if (revoked.size() <= MAX_REVOKED) return;
        Iterator<UUID> it = revoked.iterator();
        while (revoked.size() > MAX_REVOKED && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    public Collection<MinionData> all() { return Collections.unmodifiableCollection(minions.values()); }
    public MinionData get(UUID id) { return minions.get(id); }
    public void put(MinionData data) { minions.put(data.getId(), data); flush(); }
    public void delete(UUID id) { minions.remove(id); revoked.add(id); trimRevoked(); flush(); }
    public boolean isRevoked(UUID id) { return revoked.contains(id); }
    public void revoke(UUID id) { revoked.add(id); trimRevoked(); flush(); }
    public List<MinionData> byOwner(UUID owner) {
        List<MinionData> out = new ArrayList<MinionData>();
        for (MinionData d : minions.values()) if (d.getOwner().equals(owner)) out.add(d);
        return out;
    }
}
