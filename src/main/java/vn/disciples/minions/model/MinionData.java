package vn.disciples.minions.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class MinionData {
    private final UUID id;
    private UUID owner;
    private MinionType type;
    private MinionState state;
    private int level;
    private String world;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private OutputMode outputMode;
    private String chestWorld;
    private int chestX;
    private int chestY;
    private int chestZ;
    private ItemStack tool;
    private long nextRunAt;

    public MinionData(UUID id, UUID owner, MinionType type) {
        this.id = id;
        this.owner = owner;
        this.type = type;
        this.state = MinionState.ITEM;
        this.level = 1;
        this.outputMode = OutputMode.NONE;
    }

    public UUID getId() { return id; }
    public UUID getOwner() { return owner; }
    public void setOwner(UUID owner) { this.owner = owner; }
    public MinionType getType() { return type; }
    public void setType(MinionType type) { this.type = type; }
    public MinionState getState() { return state; }
    public void setState(MinionState state) { this.state = state; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = Math.max(1, level); }
    /** Tương thích dữ liệu cũ: tốc độ nay dùng chung cấp Đệ Tử. */
    public int getSpeedLevel() { return level; }
    public void setSpeedLevel(int speedLevel) { this.level = Math.max(this.level, speedLevel); }
    public OutputMode getOutputMode() { return outputMode; }
    public void setOutputMode(OutputMode outputMode) { this.outputMode = outputMode; }
    public boolean hasTool() { return tool != null; }

    // ---------------- Thẻ Offline ----------------
    private ItemStack offlineCard;
    private long offlineCapacityMs;
    private long offlineEnergyMs;
    private transient boolean depletedNotice;

    public boolean hasOfflineCard() { return offlineCard != null; }
    public ItemStack getOfflineCard() { return offlineCard == null ? null : offlineCard.clone(); }
    public void setOfflineCard(ItemStack card) { this.offlineCard = card == null ? null : card.clone(); }
    public long getOfflineCapacityMs() { return offlineCapacityMs; }
    public void setOfflineCapacityMs(long ms) { this.offlineCapacityMs = Math.max(0L, ms); }
    public long getOfflineEnergyMs() { return offlineEnergyMs; }
    public void setOfflineEnergyMs(long ms) { this.offlineEnergyMs = Math.max(0L, ms); }
    public boolean isDepletedNotice() { return depletedNotice; }
    public void setDepletedNotice(boolean v) { this.depletedNotice = v; }
    public void clearOfflineCard() {
        this.offlineCard = null;
        this.offlineCapacityMs = 0L;
        this.offlineEnergyMs = 0L;
        this.depletedNotice = false;
    }

    // ------- Vị trí đã được plugin bảo vệ duyệt khi chủ nhân ONLINE (dùng khi chủ nhân offline) -------
    private static final int MAX_VERIFIED = 256;
    private final java.util.LinkedHashMap<Long, Long> verified = new java.util.LinkedHashMap<Long, Long>();

    /** @return true nếu là vị trí mới hoặc đã cũ (cần lưu lại). */
    public boolean markVerified(long pos, long now) {
        Long prev = verified.get(pos);
        if (prev != null && now - prev < 3600000L) return false;
        verified.remove(pos);
        verified.put(pos, now);
        while (verified.size() > MAX_VERIFIED) {
            java.util.Iterator<Long> it = verified.keySet().iterator();
            it.next();
            it.remove();
        }
        return true;
    }
    public void unverify(long pos) { verified.remove(pos); }
    public boolean isVerified(long pos, long now, long ttlMs) {
        Long t = verified.get(pos);
        return t != null && now - t <= ttlMs;
    }
    public java.util.Map<Long, Long> getVerified() { return java.util.Collections.unmodifiableMap(verified); }
    public void putVerifiedRaw(long pos, long time) { verified.put(pos, time); }
    public void clearVerified() { verified.clear(); }
    public ItemStack getTool() { return tool == null ? null : tool.clone(); }
    public void setTool(ItemStack tool) { this.tool = tool == null ? null : tool.clone(); }
    public long getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(long nextRunAt) { this.nextRunAt = nextRunAt; }

    public void setLocation(Location location) {
        this.world = location.getWorld().getName();
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
        this.yaw = location.getYaw();
    }

    public Location getLocation() {
        World w = world == null ? null : Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(w, x, y, z, yaw, 0F);
    }

    /** Lưu vị trí thô, không cần world đã được load (tránh mất dữ liệu khi world load muộn). */
    public void setRawLocation(String world, double x, double y, double z, float yaw) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
    }

    public boolean hasLocationData() { return world != null; }
    public int getBlockX() { return (int) Math.floor(x); }
    public int getBlockY() { return (int) Math.floor(y); }
    public int getBlockZ() { return (int) Math.floor(z); }
    public int getChunkX() { return getBlockX() >> 4; }
    public int getChunkZ() { return getBlockZ() >> 4; }

    public String getWorldName() { return world; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public float getYaw() { return yaw; }

    public void linkChest(Location location) {
        this.chestWorld = location.getWorld().getName();
        this.chestX = location.getBlockX();
        this.chestY = location.getBlockY();
        this.chestZ = location.getBlockZ();
        this.outputMode = OutputMode.CHEST;
    }

    /** Lưu vị trí rương thô, không đổi OutputMode. */
    public void setRawChest(String world, int x, int y, int z) {
        this.chestWorld = world;
        this.chestX = x;
        this.chestY = y;
        this.chestZ = z;
    }

    public boolean hasChestData() { return chestWorld != null; }

    public Location getChestLocation() {
        if (chestWorld == null) return null;
        World w = Bukkit.getWorld(chestWorld);
        if (w == null) return null;
        return new Location(w, chestX, chestY, chestZ);
    }


    public void clearOutputLinks() {
        this.outputMode = OutputMode.NONE;
        this.chestWorld = null;
        this.chestX = 0;
        this.chestY = 0;
        this.chestZ = 0;
    }

    public String getChestWorld() { return chestWorld; }
    public int getChestX() { return chestX; }
    public int getChestY() { return chestY; }
    public int getChestZ() { return chestZ; }
}
