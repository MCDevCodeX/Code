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
 */
public final class ProtectionService {
    public enum Result { ALLOW, ALLOW_NO_DROPS, DENY }

    private static final int MAX_CACHE_PER_MINION = 512;
    private static final long NOTIFY_COOLDOWN_MS = 60000L;

    private final DiscipleMinionsPlugin plugin;
    private boolean enabled = true;
    private long deniedCacheMs = 30000L;
    private boolean notifyOwner = true;
    private boolean respectNoDrops = true;

    private final Map<UUID, Map<Long, Long>> denied = new HashMap<UUID, Map<Long, Long>>();
    private final Map<UUID, Long> notifiedAt = new HashMap<UUID, Long>();

    public ProtectionService(DiscipleMinionsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("protection.enabled", true);
        deniedCacheMs = Math.max(1L, plugin.getConfig().getLong("protection.denied-cache-seconds", 30L)) * 1000L;
        notifyOwner = plugin.getConfig().getBoolean("protection.notify-owner", true);
        respectNoDrops = plugin.getConfig().getBoolean("protection.respect-no-drops", true);
        denied.clear();
    }

    public void forget(UUID minionId) {
        denied.remove(minionId);
        notifiedAt.remove(minionId);
    }

    public Result check(MinionData d, Block block, long now) {
        if (!enabled) return Result.ALLOW;
        long key = pack(block.getX(), block.getY(), block.getZ());
        Map<Long, Long> cache = denied.get(d.getId());
        if (cache != null) {
            Long until = cache.get(key);
            if (until != null) {
                if (now < until) return Result.DENY;
                cache.remove(key);
            }
        }
        Player owner = Bukkit.getPlayer(d.getOwner());
        if (owner == null) {
            // Chủ nhân offline (làm việc nhờ Thẻ Offline): không có Player để gọi event.
            // VERIFIED: chỉ được phá các vị trí plugin bảo vệ ĐÃ cho phép lúc chủ nhân online (trong verify-ttl-hours).
            if (!plugin.getOffline().verifiedProtection()) return Result.ALLOW;
            return d.isVerified(key, now, plugin.getOffline().verifyTtlMs()) ? Result.ALLOW : Result.DENY;
        }
        BlockBreakEvent event = new BlockBreakEvent(block, owner);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            d.unverify(key);
            remember(d, key, now);
            notifyDenied(owner, d, block, now);
            return Result.DENY;
        }
        if (d.markVerified(key, now)) plugin.getRepository().save();
        // Plugin auto-pickup/custom drop có thể tắt drop để tự xử lý: tôn trọng để không nhân đôi item.
        return respectNoDrops && !event.isDropItems() ? Result.ALLOW_NO_DROPS : Result.ALLOW;
    }

    private void remember(MinionData d, long key, long now) {
        Map<Long, Long> cache = denied.get(d.getId());
        if (cache == null) {
            cache = new HashMap<Long, Long>();
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

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) (y & 0xFFF);
    }
}
