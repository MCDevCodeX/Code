package vn.disciples.minions.service;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import vn.disciples.minions.DiscipleMinionsPlugin;
import vn.disciples.minions.model.MinionData;
import vn.disciples.minions.util.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Kiểm tra quyền phá block của Đệ Tử bằng cách gọi BlockBreakEvent NHƯ THỂ CHỦ NHÂN phá block.
 * WorldGuard, GriefPrevention, Lands, Towny... đều lắng nghe sự kiện này nên sẽ chặn được.
 *
 * - Chủ nhân offline: không có Player để kiểm tra => theo cấu hình (mặc định DENY: Đệ Tử tạm dừng).
 * - Block bị từ chối được nhớ một lúc (denied-cache-seconds) để không gọi lại event mỗi lần quét
 *   (tránh lag và tránh plugin bảo vệ gửi tin nhắn liên tục).
 *
 * LƯU Ý về nhân bản item: event được bắn trên block THẬT nhưng block không hề bị phá bởi event.
 * Một số plugin auto-pickup/auto-sell nghe BlockBreakEvent sẽ tự cộng item cho chủ, sau đó Đệ Tử
 * cộng thêm drop của block => nhân đôi. Cách xử lý:
 *   - protection.respect-no-drops: true (mặc định) => plugin nào gọi setDropItems(false) sẽ được tôn trọng.
 *   - protection.break-event: false => không bắn event (dùng cho server có auto-pickup/auto-sell);
 *     khi đó chỉ còn kiểm tra chủ nhân offline + cache, KHÔNG còn kiểm tra vùng bảo vệ.
 */
public final class ProtectionService {
    public enum Result { ALLOW, ALLOW_NO_DROPS, DENY }

    private static final int MAX_CACHE_PER_MINION = 512;
    private static final long NOTIFY_COOLDOWN_MS = 60000L;

    private final DiscipleMinionsPlugin plugin;
    private boolean enabled = true;
    private boolean denyWhenOwnerOffline = true;
    private long deniedCacheMs = 30000L;
    private boolean notifyOwner = true;
    private boolean respectNoDrops = true;
    private boolean breakEvent = true;

    /** key = world:x:y:z (trước đây thiếu world nên block ở world khác bị chặn nhầm). */
    private final Map<UUID, Map<String, Long>> denied = new HashMap<UUID, Map<String, Long>>();
    private final Map<UUID, Long> notifiedAt = new HashMap<UUID, Long>();

    public ProtectionService(DiscipleMinionsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("protection.enabled", true);
        denyWhenOwnerOffline = !"ALLOW".equalsIgnoreCase(plugin.getConfig().getString("protection.offline-owner", "DENY"));
        deniedCacheMs = Math.max(1L, plugin.getConfig().getLong("protection.denied-cache-seconds", 30L)) * 1000L;
        notifyOwner = plugin.getConfig().getBoolean("protection.notify-owner", true);
        respectNoDrops = plugin.getConfig().getBoolean("protection.respect-no-drops", true);
        breakEvent = plugin.getConfig().getBoolean("protection.break-event", true);
        denied.clear();
    }

    public void forget(UUID minionId) {
        denied.remove(minionId);
        notifiedAt.remove(minionId);
    }

    /** true nếu Đệ Tử không thể kiểm tra quyền (chủ nhân offline và cấu hình DENY). */
    public boolean isPaused(MinionData d) {
        return enabled && denyWhenOwnerOffline && Bukkit.getPlayer(d.getOwner()) == null;
    }

    /** Kiểm tra đầy đủ (có bắn BlockBreakEvent nếu cấu hình cho phép). */
    public Result check(MinionData d, Block block, long now) {
        return check(d, block, now, true);
    }

    /**
     * @param fireEvent false = chỉ kiểm tra cache + chủ nhân offline, không bắn event.
     *                  Dùng cho thao tác "thúc lớn cây" để không kích hoạt auto-sell/auto-pickup.
     */
    public Result check(MinionData d, Block block, long now, boolean fireEvent) {
        if (!enabled) return Result.ALLOW;
        String key = key(block);
        Map<String, Long> cache = denied.get(d.getId());
        if (cache != null) {
            Long until = cache.get(key);
            if (until != null) {
                if (now < until) return Result.DENY;
                cache.remove(key);
            }
        }
        Player owner = Bukkit.getPlayer(d.getOwner());
        if (owner == null) {
            if (denyWhenOwnerOffline) {
                remember(d, key, now);
                return Result.DENY;
            }
            return Result.ALLOW;
        }
        if (!fireEvent || !breakEvent) return Result.ALLOW;

        BlockBreakEvent event = new BlockBreakEvent(block, owner);
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (Throwable ex) {
            // Một listener của plugin khác ném lỗi không được phép làm hỏng vòng lặp của Đệ Tử.
            plugin.getLogger().warning("Lỗi khi gọi BlockBreakEvent cho Đệ Tử " + d.getId() + ": " + ex);
            return Result.ALLOW;
        }
        if (event.isCancelled()) {
            remember(d, key, now);
            notifyDenied(owner, d, block, now);
            return Result.DENY;
        }
        // Plugin auto-pickup/custom drop có thể tắt drop để tự xử lý: tôn trọng để không nhân đôi item.
        return respectNoDrops && !event.isDropItems() ? Result.ALLOW_NO_DROPS : Result.ALLOW;
    }

    private void remember(MinionData d, String key, long now) {
        Map<String, Long> cache = denied.get(d.getId());
        if (cache == null) {
            cache = new HashMap<String, Long>();
            denied.put(d.getId(), cache);
        }
        if (cache.size() >= MAX_CACHE_PER_MINION) cache.clear();
        cache.put(key, now + deniedCacheMs);
    }

    private void notifyDenied(Player owner, MinionData d, Block block, long now) {
        if (!notifyOwner) return;
        Long last = notifiedAt.get(d.getId());
        if (last != null && now - last < NOTIFY_COOLDOWN_MS) return;
        notifiedAt.put(d.getId(), now);
        owner.sendMessage(plugin.getMinionService().prefix() + Text.color("&cĐệ Tử không thể phá khối tại &f"
                + block.getX() + ", " + block.getY() + ", " + block.getZ() + " &cvì khu vực được bảo vệ."));
    }

    private static String key(Block block) {
        String world;
        try {
            world = block.getWorld().getName();
        } catch (Throwable ex) {
            world = "?";
        }
        return world + ':' + block.getX() + ':' + block.getY() + ':' + block.getZ();
    }
}
