package vn.disciples.minions.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class MinionData {
    private final UUID id;
    private UUID owner;
    /** Tên chủ nhân được lưu sẵn: tránh gọi Bukkit.getOfflinePlayer() (có thể block main thread) mỗi lần vẽ hologram/GUI. */
    private String ownerName;
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
    /** Đổi chủ và ghi luôn tên hiển thị (dùng khi trade). */
    public void setOwner(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName;
    }
    /** Tên chủ nhân đã lưu; có thể null với dữ liệu cũ chưa có owner-name. */
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    /** Tên để hiển thị: ưu tiên tên đã lưu, nếu chưa có thì 8 ký tự đầu của UUID. */
    public String displayOwnerName() {
        if (ownerName != null && !ownerName.isEmpty()) return ownerName;
        return owner == null ? "?" : owner.toString().substring(0, 8);
    }
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
    /** Xoá vị trí (dùng khi đặt thất bại, tránh trạng thái PLACED ma). */
    public void clearLocation() {
        this.world = null;
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.yaw = 0F;
    }
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
