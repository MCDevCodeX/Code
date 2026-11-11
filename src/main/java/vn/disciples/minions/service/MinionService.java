package vn.disciples.minions.service;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;
import vn.disciples.minions.DiscipleMinionsPlugin;
import vn.disciples.minions.data.MinionRepository;
import vn.disciples.minions.model.MinionData;
import vn.disciples.minions.model.MinionState;
import vn.disciples.minions.model.MinionType;
import vn.disciples.minions.model.OutputMode;
import vn.disciples.minions.storage.StorageBridge;
import vn.disciples.minions.storage.StorageItemStatus;
import vn.disciples.minions.util.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MinionService {
    private static final String TAG = "disciple_minion";
    private static final int CHUNKS_PER_TICK = 16;
    private static final int MAX_STORAGE_NOTICES = 512;

    private final JavaPlugin plugin;
    private final DiscipleMinionsPlugin main;
    private final MinionRepository repository;
    private final MinionItemService items;
    private final StorageBridge storageBridge;
    private final ToolEngine toolEngine;

    /** Armor stand chính -> Đệ Tử. */
    private final Map<UUID, UUID> entityToMinion = new HashMap<UUID, UUID>();
    /** Đệ Tử -> armor stand chính. */
    private final Map<UUID, UUID> mainStand = new HashMap<UUID, UUID>();
    private final Map<UUID, List<UUID>> holograms = new HashMap<UUID, List<UUID>>();
    private final Set<UUID> hologramEntities = new HashSet<UUID>();
    private final Map<String, Long> storageNoticeAt = new HashMap<String, Long>();

    /** Chunk vừa load, xử lý dần trong tick() (khôi phục visual + dọn visual cũ). */
    private final ArrayDeque<Chunk> pendingChunks = new ArrayDeque<Chunk>();
    /** Chống đưa trùng 1 chunk vào queue khi load/unload liên tục. */
    private final Set<String> queuedChunks = new HashSet<String>();
    /** Index "world:chunkX:chunkZ" -> các Đệ Tử đang đặt trong chunk (trước đây quét toàn bộ mỗi chunk). */
    private final Map<String, Set<UUID>> chunkIndex = new HashMap<String, Set<UUID>>();
    private boolean chunkIndexDirty = true;
    private int legacyPurgeRemaining = 0;
    private final List<MinionData> tickBuffer = new ArrayList<MinionData>();

    // ---- cache cấu hình (đọc 1 lần, tránh tra config mỗi tick) ----
    private int[] radiusByLevel = new int[] {0, 0, 1, 2};
    private long[] minerCooldownByLevel = new long[] {0L, 500L, 333L, 250L};
    private long[] farmCycleByLevel = new long[] {0L, 8000L, 5333L, 4000L};
    private int farmStages = 1;
    private int yOffset = -1;
    private int verticalScan = 1;
    private boolean replant = true;
    private boolean accelerate = true;
    private boolean dropIfFull = true;
    private boolean onlyLoadedChunks = true;
    private boolean purgeLegacy = true;
    private boolean physicsOnBreak = false;
    private int maxLegacyPurgeChunks = 512;
    private double hologramHeight = 2.35D;
    private boolean durabilityEnabled = true;
    private boolean durabilityFarm = false;
    private boolean requireTool = true;
    private long idleScanMillis = 200L;
    private long budgetNanos = 3000000L;

    private final ProtectionService protection;
    private final Map<String, World> worldCache = new HashMap<String, World>();
    private int cursor;
    private boolean warnedLegacySpeedKeys;

    public MinionService(JavaPlugin plugin, MinionRepository repository, MinionItemService items, StorageBridge storageBridge) {
        this.plugin = plugin;
        this.main = (DiscipleMinionsPlugin) plugin;
        this.repository = repository;
        this.items = items;
        this.storageBridge = storageBridge;
        this.toolEngine = new ToolEngine(plugin);
        this.protection = new ProtectionService(main);
        reloadCache();
    }

    public void reloadHooks() {
        toolEngine.reloadHooks();
        reloadCache();
    }

    private void reloadCache() {
        FileConfiguration c = plugin.getConfig();
        int cfgMax = Math.max(1, c.getInt("levels.max", 3));
        int size = Math.max(cfgMax, 3) + 1; // chỉ số 0 không dùng
        int[] radius = new int[size];
        long[] minerCd = new long[size];
        long[] farmCycle = new long[size];
        // TỐC ĐỘ: levels.speed-multiplier là NGUỒN DUY NHẤT. Giá trị hiển thị trong GUI cũng chính là tốc độ thật.
        double minerBase = Math.max(0.05D, c.getDouble("miner.base-cooldown-seconds", 0.5D));
        double farmBase = Math.max(0.25D, c.getDouble("farm.base-growth-interval-seconds", 8D));
        for (int lv = 1; lv < size; lv++) {
            double mult = Math.max(0.1D, c.getDouble("levels.speed-multiplier." + lv, 1D));
            radius[lv] = c.getInt("levels.radius." + lv, Math.max(0, lv - 1));
            minerCd[lv] = Math.max(50L, Math.round(minerBase * 1000D / mult));
            farmCycle[lv] = Math.max(250L, Math.round(farmBase * 1000D / mult));
        }
        radiusByLevel = radius;
        minerCooldownByLevel = minerCd;
        farmCycleByLevel = farmCycle;
        farmStages = Math.max(1, c.getInt("farm.growth-stages", 1));
        yOffset = c.getInt("miner.y-offset", -1);
        requireTool = c.getBoolean("miner.require-tool", true);
        verticalScan = Math.max(0, c.getInt("farm.vertical-scan", 1));
        replant = c.getBoolean("farm.replant", true);
        accelerate = c.getBoolean("farm.accelerate-growth", true);
        dropIfFull = c.getBoolean("output.drop-if-full", true);
        onlyLoadedChunks = c.getBoolean("minion.only-work-in-loaded-chunks", true);
        purgeLegacy = c.getBoolean("minion.purge-legacy-visuals", true);
        maxLegacyPurgeChunks = Math.max(0, c.getInt("minion.max-legacy-purge-chunks", 512));
        legacyPurgeRemaining = maxLegacyPurgeChunks;
        physicsOnBreak = c.getBoolean("miner.physics-on-break", false);
        hologramHeight = c.getDouble("minion.hologram-height", 2.35D);
        durabilityEnabled = c.getBoolean("durability.enabled", true);
        durabilityFarm = c.getBoolean("durability.farm", false);
        idleScanMillis = Math.max(1L, c.getLong("minion.idle-scan-ticks", 4L)) * 50L;
        budgetNanos = (long) (Math.max(0.2D, c.getDouble("minion.tick-budget-ms", 3.0D)) * 1000000D);
        protection.reload();

        if (!warnedLegacySpeedKeys && (c.isConfigurationSection("levels.miner-cooldown-seconds")
                || c.isConfigurationSection("farm.growth-interval-seconds") || c.isConfigurationSection("farm.growth-stages"))) {
            warnedLegacySpeedKeys = true;
            plugin.getLogger().warning("config.yml còn khóa tốc độ cũ (levels.miner-cooldown-seconds, farm.growth-interval-seconds, "
                    + "farm.growth-stages theo cấp) - chúng KHÔNG còn được dùng. Tốc độ nay chỉ lấy từ levels.speed-multiplier, "
                    + "miner.base-cooldown-seconds và farm.base-growth-interval-seconds.");
        }
    }

    private int levelIndex(int level, int length) {
        return Math.min(Math.max(level, 1), length - 1);
    }

    public MinionData create(UUID owner, MinionType type) {
        MinionData d = new MinionData(UUID.randomUUID(), owner, type);
        Player online = Bukkit.getPlayer(owner);
        if (online != null) d.setOwnerName(online.getName());
        repository.put(d);
        return d;
    }

    /** Tổng số Đệ Tử người chơi đang sở hữu, gồm cả đang đặt và đang ở dạng vật phẩm. */
    public int ownedCount(UUID owner) {
        return repository.byOwner(owner).size();
    }

    /** -1 hoặc 0 = không giới hạn. */
    public int maxOwnedPerPlayer() {
        int configured = plugin.getConfig().getInt("limits.max-owned-per-player",
                plugin.getConfig().getInt("minion.max-per-player", 20));
        return configured <= 0 ? -1 : configured;
    }

    public boolean canOwnMore(UUID owner) {
        int max = maxOwnedPerPlayer();
        return max < 0 || ownedCount(owner) < max;
    }

    public int remainingOwnedSlots(UUID owner) {
        int max = maxOwnedPerPlayer();
        if (max < 0) return Integer.MAX_VALUE;
        return Math.max(0, max - ownedCount(owner));
    }

    public boolean place(Player player, MinionData d, Location location) {
        if (!d.getOwner().equals(player.getUniqueId()) || d.getState() == MinionState.PLACED) return false;
        Location at = location.clone().add(0.5, 0, 0.5);
        d.setOwnerName(player.getName());
        d.setLocation(at);
        d.setState(MinionState.PLACED);
        d.setNextRunAt(System.currentTimeMillis());
        try {
            spawnVisual(d);
        } catch (Throwable ex) {
            // Không để trạng thái "đã đặt" khi visual lỗi: nếu không, item không bị trừ nhưng Đệ Tử vẫn PLACED
            // => người chơi giữ item và Đệ Tử cùng lúc (item ma).
            plugin.getLogger().warning("Không tạo được hình ảnh Đệ Tử " + d.getId() + ", hoàn tác đặt: " + ex);
            d.setState(MinionState.ITEM);
            d.clearLocation();
            repository.save();
            invalidateChunkIndex();
            return false;
        }
        invalidateChunkIndex();
        repository.flush(); // quan trọng: item đã bị lấy khỏi tay người chơi
        return true;
    }

    // ======================================================================
    // VISUAL (armor stand + hologram)
    // Visual KHÔNG được lưu vào chunk (setPersistent(false)); chúng được tạo lại khi chunk load.
    // Nhờ vậy không còn visual "ma" nhân đôi sau mỗi lần restart.
    // ======================================================================

    public void spawnAll() {
        cleanupOwnedVisuals();
        invalidateChunkIndex();
        legacyPurgeRemaining = maxLegacyPurgeChunks;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) queueChunk(chunk);
        }
    }

    /** Dọn visual cũ (từ phiên bản trước đã được lưu vào chunk) trong các chunk đang load. */
    private void cleanupOwnedVisuals() {
        for (World world : Bukkit.getWorlds()) {
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                if (stand.getScoreboardTags().contains(TAG)) stand.remove();
            }
        }
        entityToMinion.clear();
        mainStand.clear();
        holograms.clear();
        hologramEntities.clear();
    }

    public void onChunkLoad(Chunk chunk) {
        // Chỉ xử lý chunk có Đệ Tử (tra index O(1)) hoặc còn hạn mức dọn visual cũ.
        if (hasPlacedInChunk(chunk) || (purgeLegacy && legacyPurgeRemaining > 0)) queueChunk(chunk);
    }

    public void onChunkUnload(Chunk chunk) {
        for (UUID id : chunkIds(chunk)) {
            forgetVisual(id); // entity không persistent nên tự biến mất cùng chunk
        }
    }

    public void onWorldLoad(World world) {
        for (Chunk chunk : world.getLoadedChunks()) queueChunk(chunk);
    }

    private void queueChunk(Chunk chunk) {
        String key = chunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        if (queuedChunks.add(key)) pendingChunks.add(chunk);
    }

    private boolean hasPlacedInChunk(Chunk chunk) {
        return !chunkIds(chunk).isEmpty();
    }

    // ------------------------------------------------------------------
    // INDEX THEO CHUNK
    // Trước đây mỗi lần chunk load/unload lại quét toàn bộ repository => O(Đệ Tử x chunk),
    // tụt TPS khi người chơi bay/teleport. Index được dựng lại khi có thay đổi vị trí/trạng thái.
    // ------------------------------------------------------------------

    /** Gọi sau khi đặt/thu hồi/xoá Đệ Tử hoặc load lại dữ liệu. */
    public void invalidateChunkIndex() { chunkIndexDirty = true; }

    private static String chunkKey(String world, int cx, int cz) {
        return world + ':' + cx + ':' + cz;
    }

    private void rebuildChunkIndex() {
        chunkIndex.clear();
        for (MinionData d : repository.all()) {
            if (d.getState() != MinionState.PLACED || !d.hasLocationData()) continue;
            String key = chunkKey(d.getWorldName(), d.getChunkX(), d.getChunkZ());
            Set<UUID> ids = chunkIndex.get(key);
            if (ids == null) {
                ids = new HashSet<UUID>();
                chunkIndex.put(key, ids);
            }
            ids.add(d.getId());
        }
        chunkIndexDirty = false;
    }

    private Set<UUID> chunkIds(Chunk chunk) {
        if (chunkIndexDirty) rebuildChunkIndex();
        Set<UUID> ids = chunkIndex.get(chunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()));
        return ids == null ? Collections.<UUID>emptySet() : ids;
    }

    private void processPendingChunks(long startNanos) {
        int budget = CHUNKS_PER_TICK;
        long chunkBudget = Math.max(500000L, budgetNanos / 2L); // tối đa một nửa ngân sách tick
        Chunk chunk;
        while (budget-- > 0 && (chunk = pendingChunks.poll()) != null) {
            queuedChunks.remove(chunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()));
            restoreChunk(chunk);
            if (System.nanoTime() - startNanos > chunkBudget) break;
        }
    }

    private void restoreChunk(Chunk chunk) {
        if (!chunk.isLoaded()) return;
        if (purgeLegacy && legacyPurgeRemaining > 0) {
            legacyPurgeRemaining--;
            for (Entity e : chunk.getEntities()) {
                if (!(e instanceof ArmorStand) || !e.getScoreboardTags().contains(TAG)) continue;
                UUID id = e.getUniqueId();
                if (entityToMinion.containsKey(id) || hologramEntities.contains(id)) continue;
                e.remove(); // visual cũ không còn được theo dõi
            }
        }
        for (UUID id : new ArrayList<UUID>(chunkIds(chunk))) {
            MinionData d = repository.get(id);
            if (d == null || d.getState() != MinionState.PLACED || !d.hasLocationData()) continue;
            if (d.getChunkX() != chunk.getX() || d.getChunkZ() != chunk.getZ()) continue;
            if (!hasVisual(d)) spawnVisual(d);
        }
    }

    private boolean hasVisual(MinionData d) {
        UUID standId = mainStand.get(d.getId());
        if (standId == null) return false;
        Entity e = Bukkit.getEntity(standId);
        return e != null && e.isValid();
    }

    public void spawnVisual(MinionData d) {
        Location loc = d.getLocation();
        if (loc == null || loc.getWorld() == null) return;
        removeVisual(d.getId());
        // Chunk chưa load: visual sẽ được tạo khi ChunkLoadEvent (tránh ép server load chunk).
        if (!loc.getWorld().isChunkLoaded(d.getChunkX(), d.getChunkZ())) return;
        ArmorStand stand = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.addScoreboardTag(TAG);
        stand.addScoreboardTag(TAG + "_" + d.getId());
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setCustomNameVisible(false);
        stand.setGravity(false);
        stand.setSmall(true);
        stand.setArms(true);
        stand.setBasePlate(false);
        stand.setVisible(true);
        stand.setRemoveWhenFarAway(false);
        stand.setPersistent(false);
        equipDiscipleSkin(stand, d);
        entityToMinion.put(stand.getUniqueId(), d.getId());
        mainStand.put(d.getId(), stand.getUniqueId());
        spawnHologram(d);
    }

    private void equipDiscipleSkin(ArmorStand stand, MinionData d) {
        EntityEquipment eq = stand.getEquipment();
        if (eq == null) return;
        Color color = d.getType() == MinionType.MINER ? Color.fromRGB(20, 20, 20) : Color.fromRGB(255, 215, 0);
        eq.setHelmet(coloredLeather(Material.LEATHER_HELMET, color));
        eq.setChestplate(coloredLeather(Material.LEATHER_CHESTPLATE, color));
        eq.setLeggings(coloredLeather(Material.LEATHER_LEGGINGS, color));
        eq.setBoots(coloredLeather(Material.LEATHER_BOOTS, color));
        ItemStack tool = d.getTool();
        eq.setItemInMainHand(tool == null ? new ItemStack(Material.AIR) : tool.clone());
        try {
            eq.setHelmetDropChance(0F);
            eq.setChestplateDropChance(0F);
            eq.setLeggingsDropChance(0F);
            eq.setBootsDropChance(0F);
            eq.setItemInMainHandDropChance(0F);
        } catch (Throwable ignored) {}
    }

    private ItemStack coloredLeather(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        if (item.getItemMeta() instanceof LeatherArmorMeta) {
            LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
            meta.setColor(color);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String[] hologramLines(MinionData d) {
        String owner = d.displayOwnerName();
        return new String[] {
                "&6&l✦ ĐỆ TỬ ✦",
                "&7Chủ nhân: &f" + owner,
                "&7Nghề nghiệp: &b" + d.getType().displayName(),
                "&7Cấp Đệ Tử: &e" + d.getLevel() + " &8• &7Tốc độ: &a" + speedDisplay(d)
        };
    }

    private void spawnHologram(MinionData d) {
        Location loc = d.getLocation();
        if (loc == null) return;
        Location base = loc.clone().add(0, hologramHeight, 0);
        String[] lines = hologramLines(d);
        List<UUID> ids = new ArrayList<UUID>();
        for (int i = 0; i < lines.length; i++) {
            Location l = base.clone().add(0, (lines.length - i - 1) * 0.25, 0);
            ArmorStand stand = (ArmorStand) l.getWorld().spawnEntity(l, EntityType.ARMOR_STAND);
            stand.addScoreboardTag(TAG);
            stand.setVisible(false);
            stand.setGravity(false);
            stand.setMarker(true);
            stand.setSmall(true);
            stand.setCustomName(Text.color(lines[i]));
            stand.setCustomNameVisible(true);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setPersistent(false);
            ids.add(stand.getUniqueId());
            hologramEntities.add(stand.getUniqueId());
        }
        holograms.put(d.getId(), ids);
    }

    /** Đổi chữ hologram tại chỗ, không hủy/tạo lại entity (hết nhấp nháy). */
    private void updateHologram(MinionData d) {
        List<UUID> ids = holograms.get(d.getId());
        String[] lines = hologramLines(d);
        if (ids == null || ids.size() != lines.length) {
            removeHolograms(d.getId());
            spawnHologram(d);
            return;
        }
        for (int i = 0; i < lines.length; i++) {
            Entity e = Bukkit.getEntity(ids.get(i));
            if (e == null || !e.isValid()) {
                removeHolograms(d.getId());
                spawnHologram(d);
                return;
            }
            e.setCustomName(Text.color(lines[i]));
        }
    }

    /**
     * Cập nhật visual tại chỗ (đổi công cụ, cấp, chủ nhân). Chỉ tạo lại khi entity không còn.
     */
    public void refreshVisual(MinionData d) {
        if (d.getState() != MinionState.PLACED) return;
        UUID standId = mainStand.get(d.getId());
        Entity e = standId == null ? null : Bukkit.getEntity(standId);
        if (!(e instanceof ArmorStand) || !e.isValid()) {
            spawnVisual(d);
            return;
        }
        equipDiscipleSkin((ArmorStand) e, d);
        updateHologram(d);
    }

    public void removeVisual(UUID minionId) {
        UUID standId = mainStand.remove(minionId);
        if (standId != null) {
            entityToMinion.remove(standId);
            Entity e = Bukkit.getEntity(standId);
            if (e != null) e.remove();
        }
        removeHolograms(minionId);
    }

    private void removeHolograms(UUID minionId) {
        List<UUID> ids = holograms.remove(minionId);
        if (ids == null) return;
        for (UUID id : ids) {
            hologramEntities.remove(id);
            Entity e = Bukkit.getEntity(id); // tra cứu trực tiếp theo UUID, không quét toàn bộ entity
            if (e != null) e.remove();
        }
    }

    /** Quên visual (entity đã/đang biến mất cùng chunk) mà không đụng tới entity. */
    private void forgetVisual(UUID minionId) {
        UUID standId = mainStand.remove(minionId);
        if (standId != null) entityToMinion.remove(standId);
        List<UUID> ids = holograms.remove(minionId);
        if (ids != null) hologramEntities.removeAll(ids);
    }

    public MinionData fromEntity(Entity entity) {
        UUID id = entityToMinion.get(entity.getUniqueId());
        return id == null ? null : repository.get(id);
    }

    public boolean isMinionEntity(Entity entity) {
        return entity != null && entity.getScoreboardTags().contains(TAG);
    }

    public void reclaim(Player player, MinionData d) {
        removeVisual(d.getId());
        d.setState(MinionState.ITEM);
        invalidateChunkIndex();
        repository.flush();
        ItemStack item = items.create(d);
        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack left : overflow.values()) player.getWorld().dropItemNaturally(player.getLocation(), left);
    }

    public void delete(MinionData d) {
        protection.forget(d.getId());
        removeVisual(d.getId());
        invalidateChunkIndex();
        repository.delete(d.getId());
    }

    // ======================================================================
    // TICK
    // ======================================================================

    /**
     * Gọi mỗi {@code minion.tick-period} tick. Để chống lag khi có nhiều Đệ Tử:
     *  - mỗi tick chỉ dành tối đa {@code minion.tick-budget-ms} cho Đệ Tử, phần còn lại xử lý ở tick sau (xoay vòng);
     *  - Miner chưa thấy ore chỉ quét lại sau {@code minion.idle-scan-ticks} tick (không quét mỗi tick);
     *  - Đệ Tử chỉ hoạt động khi chunk của nó đang được load.
     */
    public void tick() {
        long start = System.nanoTime();
        processPendingChunks(start);
        tickBuffer.clear();
        tickBuffer.addAll(repository.all());
        int total = tickBuffer.size();
        if (total == 0) return;

        long now = System.currentTimeMillis();
        worldCache.clear();
        if (cursor >= total) cursor = 0;
        int done = 0;
        while (done < total) {
            if ((done & 3) == 3 && System.nanoTime() - start > budgetNanos) break; // hết ngân sách tick này
            MinionData d = tickBuffer.get((cursor + done) % total);
            done++;
            runMinion(d, now);
        }
        cursor = (cursor + done) % total;
        tickBuffer.clear();
        worldCache.clear();
    }

    private void runMinion(MinionData d, long now) {
        if (d.getState() != MinionState.PLACED || !d.hasLocationData() || now < d.getNextRunAt()) return;
        String worldName = d.getWorldName();
        if (!worldCache.containsKey(worldName)) worldCache.put(worldName, Bukkit.getWorld(worldName));
        World world = worldCache.get(worldName);
        if (world == null) return; // world chưa load: giữ nguyên dữ liệu, chờ world load
        if (onlyLoadedChunks && !world.isChunkLoaded(d.getChunkX(), d.getChunkZ())) return;
        // Đang mở GUI công cụ: tạm dừng để không ghi đè công cụ cũ khi người chơi đóng GUI.
        if (main.getGui() != null && main.getGui().isToolOpen(d.getId())) return;
        try {
            if (d.getType() == MinionType.MINER) {
                boolean mined = mine(d, world, now);
                d.setNextRunAt(now + (mined ? minerCooldownMillis(d) : idleScanMillis));
            } else {
                farm(d, world, now);
                d.setNextRunAt(now + farmCycleMillis(d));
            }
        } catch (Throwable ex) {
            plugin.getLogger().warning("Đệ Tử " + d.getId() + " gặp lỗi khi hoạt động: " + ex.getMessage());
            d.setNextRunAt(now + 1000L);
        }
    }

    private long minerCooldownMillis(MinionData d) {
        return minerCooldownByLevel[levelIndex(d.getLevel(), minerCooldownByLevel.length)];
    }

    private long farmCycleMillis(MinionData d) {
        return farmCycleByLevel[levelIndex(d.getLevel(), farmCycleByLevel.length)];
    }

    /** Tốc độ hiển thị. Đây cũng chính là hệ số tốc độ THẬT (cooldown = giá trị gốc / hệ số này). */
    public String speedDisplay(MinionData d) {
        double value = plugin.getConfig().getDouble("levels.speed-multiplier." + d.getLevel(), 1D);
        return String.format(java.util.Locale.US, "%.2fx", value);
    }

    public int radius(MinionData d) {
        return radiusByLevel[levelIndex(d.getLevel(), radiusByLevel.length)];
    }

    /** Công cụ hết độ bền: gỡ khỏi Đệ Tử, báo chủ nhân, phát âm thanh, cập nhật visual. */
    private void onToolBroke(MinionData d) {
        d.setTool(null);
        repository.save();
        refreshVisual(d);
        Location loc = d.getLocation();
        if (loc != null && loc.getWorld() != null) loc.getWorld().playSound(loc, Sound.ENTITY_ITEM_BREAK, 1F, 1F);
        Player owner = Bukkit.getPlayer(d.getOwner());
        if (owner != null) {
            owner.sendMessage(prefix() + Text.color("&cCông cụ của Đệ Tử đã bị hỏng! Hãy trang bị công cụ mới."));
        }
    }

    /** Mine ONLY configured ore names. No surrounding structure block can be broken. */
    private boolean mine(MinionData d, World world, long now) {
        if (requireTool && !d.hasTool()) return false;       // không có công cụ => không đào miễn phí
        if (protection.isPaused(d)) return false;            // chủ nhân offline, không kiểm tra quyền được
        Set<String> ores = main.getAllowedItemManager().names(MinionType.MINER);
        if (ores.isEmpty()) return false;
        int r = radius(d);
        int baseX = d.getBlockX();
        int baseY = d.getBlockY() + yOffset;
        int baseZ = d.getBlockZ();

        List<ItemStack> drops = null;
        ItemStack tool = null;
        boolean toolLoaded = false; // chỉ clone tool khi thực sự đào được (tránh clone mỗi lần quét)
        boolean toolBroke = false;
        boolean minedAny = false;
        int lastCx = Integer.MIN_VALUE;
        int lastCz = Integer.MIN_VALUE;
        boolean lastLoaded = true;

        scan:
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = baseX + dx;
                int z = baseZ + dz;
                if (onlyLoadedChunks) {
                    int cx = x >> 4;
                    int cz = z >> 4;
                    if (cx != lastCx || cz != lastCz) {
                        lastCx = cx;
                        lastCz = cz;
                        lastLoaded = world.isChunkLoaded(cx, cz);
                    }
                    if (!lastLoaded) continue;
                }
                Block block = world.getBlockAt(x, baseY, z);
                if (!ores.contains(block.getType().name())) continue;

                ProtectionService.Result guard = protection.check(d, block, now);
                if (guard == ProtectionService.Result.DENY) continue; // WorldGuard/claim chặn

                if (!toolLoaded) {
                    tool = d.getTool();
                    toolLoaded = true;
                    drops = new ArrayList<ItemStack>();
                }
                ToolEngine.MineResult result = toolEngine.mine(block, tool);
                if (guard != ProtectionService.Result.ALLOW_NO_DROPS) drops.addAll(result.getDrops());
                tool = result.getToolRaw();
                block.setType(Material.AIR, physicsOnBreak);
                minedAny = true;

                if (durabilityEnabled && tool != null && toolEngine.damage(tool, 1)) {
                    toolBroke = true;
                    break scan; // công cụ đã hỏng: dừng đào
                }
            }
        }
        if (toolBroke) onToolBroke(d);
        else if (toolLoaded && tool != null) {
            d.setTool(tool);
            repository.save(); // độ bền / tiến độ cúp đã đổi: phải đánh dấu để autosave ghi lại
        }
        if (minedAny) output(d, drops);
        return minedAny;
    }

    /**
     * Existing crops are the template. Player plants once; the disciple accelerates growth,
     * harvests only fully grown crops (nếu được bảo vệ cho phép), then resets age to 0 without consuming any seed.
     */
    private boolean farm(MinionData d, World world, long now) {
        Set<String> allowed = main.getAllowedItemManager().names(MinionType.FARM);
        if (allowed.isEmpty()) return false;
        int r = radius(d);
        boolean canHarvest = !protection.isPaused(d);
        int baseX = d.getBlockX();
        int baseY = d.getBlockY();
        int baseZ = d.getBlockZ();
        List<ItemStack> drops = new ArrayList<ItemStack>();
        ItemStack tool = null;
        boolean toolLoaded = false;
        boolean toolBroke = false;
        boolean changed = false;
        int lastCx = Integer.MIN_VALUE;
        int lastCz = Integer.MIN_VALUE;
        boolean lastLoaded = true;

        scan:
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = baseX + dx;
                int z = baseZ + dz;
                if (onlyLoadedChunks) {
                    int cx = x >> 4;
                    int cz = z >> 4;
                    if (cx != lastCx || cz != lastCz) {
                        lastCx = cx;
                        lastCz = cz;
                        lastLoaded = world.isChunkLoaded(cx, cz);
                    }
                    if (!lastLoaded) continue;
                }
                for (int dy = -verticalScan; dy <= verticalScan; dy++) {
                    Block block = world.getBlockAt(x, baseY + dy, z);
                    if (!allowed.contains(block.getType().name())) continue;
                    BlockData data = block.getBlockData();
                    if (!(data instanceof Ageable)) continue;
                    Ageable age = (Ageable) data;
                    if (age.getAge() >= age.getMaximumAge()) {
                        if (!canHarvest) continue;
                        ProtectionService.Result guard = protection.check(d, block, now);
                        if (guard == ProtectionService.Result.DENY) continue; // không thu hoạch trong vùng được bảo vệ
                        if (!toolLoaded) { tool = d.getTool(); toolLoaded = true; }
                        if (guard != ProtectionService.Result.ALLOW_NO_DROPS) {
                            Collection<ItemStack> got = tool == null ? block.getDrops() : block.getDrops(tool);
                            for (ItemStack item : got) if (item != null && item.getType() != Material.AIR) drops.add(item.clone());
                        }
                        if (replant) {
                            age.setAge(0);
                            block.setBlockData(age, false); // không cần physics cho cây: giảm lag đáng kể
                        } else block.setType(Material.AIR, true);
                        changed = true;
                        if (durabilityFarm && tool != null && toolEngine.damage(tool, 1)) {
                            toolBroke = true;
                            break scan;
                        }
                    } else if (accelerate) {
                        // Tôn trọng vùng bảo vệ: không thúc lớn cây trong claim người khác.
                        // Dùng check không bắn event để tránh plugin auto-sell/auto-pickup xử lý nhầm.
                        ProtectionService.Result guard = protection.check(d, block, now, false);
                        if (guard == ProtectionService.Result.DENY) continue;
                        age.setAge(Math.min(age.getMaximumAge(), age.getAge() + farmStages));
                        block.setBlockData(age, false);
                        changed = true;
                    }
                }
            }
        }
        if (toolBroke) onToolBroke(d);
        else if (toolLoaded && tool != null && durabilityFarm) {
            d.setTool(tool);
            repository.save();
        }
        if (!drops.isEmpty()) output(d, drops);
        return changed;
    }

    /** Gộp các stack giống nhau để giảm số lần gọi addItem / ExtraStorage. */
    private List<ItemStack> merge(Collection<ItemStack> drops) {
        List<ItemStack> out = new ArrayList<ItemStack>();
        for (ItemStack item : drops) {
            if (item == null || item.getAmount() <= 0 || item.getType() == Material.AIR) continue;
            ItemStack rest = item.clone();
            for (ItemStack existing : out) {
                if (rest.getAmount() <= 0) break;
                if (existing.getAmount() < existing.getMaxStackSize() && existing.isSimilar(rest)) {
                    int move = Math.min(existing.getMaxStackSize() - existing.getAmount(), rest.getAmount());
                    existing.setAmount(existing.getAmount() + move);
                    rest.setAmount(rest.getAmount() - move);
                }
            }
            if (rest.getAmount() > 0) out.add(rest);
        }
        return out;
    }

    private void output(MinionData d, Collection<ItemStack> drops) {
        if (drops == null || drops.isEmpty()) return;
        Collection<ItemStack> remaining = merge(drops);
        if (remaining.isEmpty()) return;

        try {
            if (d.getOutputMode() == OutputMode.CHEST && d.getChestLocation() != null) {
                Location chestLoc = d.getChestLocation();
                // Không gọi getBlock() khi chunk rương chưa load: sẽ ép server load chunk đồng bộ (lag).
                boolean chestLoaded = chestLoc.getWorld() != null
                        && chestLoc.getWorld().isChunkLoaded(chestLoc.getBlockX() >> 4, chestLoc.getBlockZ() >> 4);
                Block block = chestLoaded ? chestLoc.getBlock() : null;
                if (block != null && block.getState() instanceof Container) {
                    Inventory inv = ((Container) block.getState()).getInventory();
                    // Hàng đợi dùng chung tham chiếu với "remaining": item đã cất được gỡ khỏi hàng đợi ngay,
                    // nên nếu lỗi giữa chừng cũng không thể vừa nằm trong rương vừa rơi ra đất.
                    List<ItemStack> queue = new ArrayList<ItemStack>(remaining);
                    remaining = queue;
                    while (!queue.isEmpty()) {
                        ItemStack item = queue.remove(0);
                        try {
                            queue.addAll(inv.addItem(item).values());
                        } catch (Throwable ex) {
                            plugin.getLogger().warning("Lỗi khi cất item vào rương của Đệ Tử " + d.getId() + ": " + ex.getMessage());
                            queue.add(item);
                            break;
                        }
                    }
                }
            } else if (d.getOutputMode() == OutputMode.EXTRA_STORAGE) {
                if (!storageBridge.isAvailable()) {
                    notifyExtraStorage(d, null, StorageItemStatus.API_UNAVAILABLE);
                } else {
                    List<ItemStack> allowed = new ArrayList<ItemStack>();
                    List<ItemStack> rejected = new ArrayList<ItemStack>();
                    for (ItemStack item : remaining) {
                        StorageItemStatus status = storageBridge.checkItem(d.getOwner(), item);
                        if (status == StorageItemStatus.ALLOWED) {
                            allowed.add(item);
                        } else {
                            rejected.add(item);
                            notifyExtraStorage(d, item, status);
                        }
                    }
                    List<ItemStack> leftovers = new ArrayList<ItemStack>(rejected);
                    if (!allowed.isEmpty()) leftovers.addAll(storageBridge.deposit(d.getOwner(), allowed));
                    remaining = leftovers;
                }
            }
        } catch (Throwable ex) {
            // Không để mất drop vì lỗi kho/rương: phần còn lại sẽ rơi ra đất bên dưới.
            plugin.getLogger().warning("Lỗi khi xuất item của Đệ Tử " + d.getId() + ": " + ex);
        }

        if (!remaining.isEmpty() && dropIfFull) {
            Location base = d.getLocation();
            if (base == null || base.getWorld() == null) return;
            Location loc = base.add(0, 1, 0);
            for (ItemStack item : remaining) {
                try {
                    loc.getWorld().dropItemNaturally(loc, item);
                } catch (Throwable ex) {
                    plugin.getLogger().warning("Không thể thả item của Đệ Tử " + d.getId() + ": " + ex.getMessage());
                }
            }
        }
    }


    private void notifyExtraStorage(MinionData d, ItemStack item, StorageItemStatus status) {
        if (!plugin.getConfig().getBoolean("extrastorage.notifications.enabled", true)) return;
        Player owner = Bukkit.getPlayer(d.getOwner());
        if (owner == null || !owner.isOnline()) return;

        String itemKey = item == null ? "none" : item.getType().name();
        String key = d.getOwner() + ":" + d.getId() + ":" + status.name() + ":" + itemKey;
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0L, plugin.getConfig().getLong("extrastorage.notifications.cooldown-seconds", 8L) * 1000L);
        Long last = storageNoticeAt.get(key);
        if (last != null && now - last < cooldown) return;
        if (storageNoticeAt.size() >= MAX_STORAGE_NOTICES) {
            Iterator<Map.Entry<String, Long>> it = storageNoticeAt.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() >= cooldown) it.remove();
            }
            if (storageNoticeAt.size() >= MAX_STORAGE_NOTICES) storageNoticeAt.clear();
        }
        storageNoticeAt.put(key, now);

        String amountAndName = item == null ? "" : "&f" + item.getAmount() + "x &e" + friendlyItemName(item);
        String reason;
        switch (status) {
            case NOT_IN_ALLOWED_LIST:
                reason = "&cchưa có trong danh sách vật phẩm được phép của ExtraStorage";
                break;
            case STORAGE_DISABLED:
                reason = "&cExtraStorage của bạn đang tắt";
                break;
            case FULL:
                reason = "&ckho ExtraStorage đã đầy";
                break;
            case USER_UNAVAILABLE:
                reason = "&ckhông tải được dữ liệu kho của chủ nhân";
                break;
            case API_UNAVAILABLE:
                reason = "&cExtraStorage chưa sẵn sàng hoặc API không tương thích";
                break;
            default:
                reason = "&ccó lỗi khi kiểm tra ExtraStorage";
                break;
        }

        String id = d.getId().toString();
        String shortId = id.length() > 8 ? id.substring(0, 8) : id;
        owner.sendMessage(prefix() + Text.color("&6Đệ Tử &f#" + shortId + " &7không thể cất " + amountAndName + "&7: " + reason + "&7."));
        if (status == StorageItemStatus.NOT_IN_ALLOWED_LIST) {
            owner.sendMessage(prefix() + Text.color("&8↳ &7Hãy thêm/bật vật phẩm này trong danh sách ExtraStorage rồi Đệ Tử sẽ tự cất ở lần tiếp theo."));
        }
    }

    private String friendlyItemName(ItemStack item) {
        if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return ChatColor.stripColor(item.getItemMeta().getDisplayName());
        }
        if (item == null) return "Vật phẩm";
        String[] parts = item.getType().name().toLowerCase().split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    public String prefix() { return Text.color(plugin.getConfig().getString("messages.prefix", "&8[&6Đệ Tử&8] &r")); }
    public StorageBridge getStorageBridge() { return storageBridge; }
}
