package vn.disciples.minions.service;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import vn.disciples.minions.DiscipleMinionsPlugin;
import vn.disciples.minions.model.MinionData;
import vn.disciples.minions.model.MinionState;
import vn.disciples.minions.model.MinionType;
import vn.disciples.minions.util.Text;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Cơ chế làm việc khi chủ nhân OFFLINE, cần Thẻ Offline còn năng lượng.
 *
 * - Chủ nhân online: thẻ đã gắn tự nạp năng lượng (mặc định 1 phút online = 1 phút năng lượng), tối đa dung lượng của bậc thẻ.
 * - Chủ nhân offline: Đệ Tử làm việc và tiêu năng lượng (mặc định 1 phút = 1 phút). Hết năng lượng thì tạm dừng.
 * - Trong lúc làm việc offline, chunk của Đệ Tử được giữ load bằng plugin chunk ticket (nếu bật keep-chunk-loaded).
 */
public final class OfflineService {
    private static final class TicketRef {
        final String world;
        final int cx;
        final int cz;
        TicketRef(String world, int cx, int cz) { this.world = world; this.cx = cx; this.cz = cz; }
    }

    private final DiscipleMinionsPlugin plugin;
    private boolean enabled = true;
    private boolean allowFarm = true;
    private boolean allowMiner = true;
    private boolean keepChunkLoaded = true;
    private boolean verifiedProtection = true;
    private double chargeRatio = 1D;
    private double consumeRatio = 1D;
    private long verifyTtlMs = 72L * 3600000L;
    private long lastTick;
    private final Map<String, TicketRef> tickets = new HashMap<String, TicketRef>();

    public OfflineService(DiscipleMinionsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("offline.enabled", true);
        allowFarm = plugin.getConfig().getBoolean("offline.allow-farm", true);
        allowMiner = plugin.getConfig().getBoolean("offline.allow-miner", true);
        keepChunkLoaded = plugin.getConfig().getBoolean("offline.keep-chunk-loaded", true);
        verifiedProtection = !"NONE".equalsIgnoreCase(plugin.getConfig().getString("offline.protection", "VERIFIED"));
        chargeRatio = Math.max(0.01D, plugin.getConfig().getDouble("offline.charge-ratio", 1D));
        consumeRatio = Math.max(0.01D, plugin.getConfig().getDouble("offline.consume-ratio", 1D));
        verifyTtlMs = Math.max(1L, plugin.getConfig().getLong("offline.verify-ttl-hours", 72L)) * 3600000L;
    }

    public boolean isEnabled() { return enabled; }
    public boolean verifiedProtection() { return verifiedProtection; }
    public long verifyTtlMs() { return verifyTtlMs; }

    public boolean typeAllowed(MinionData d) {
        return d.getType() == MinionType.FARM ? allowFarm : allowMiner;
    }

    /** Đệ Tử có đủ điều kiện làm việc khi chủ nhân offline không (có thẻ, còn năng lượng, loại được phép). */
    public boolean canWorkOffline(MinionData d) {
        return enabled && typeAllowed(d) && d.hasOfflineCard() && d.getOfflineEnergyMs() > 0L;
    }

    /** true nếu Đệ Tử phải tạm dừng: chủ nhân offline mà không có năng lượng từ Thẻ Offline. */
    public boolean isPaused(MinionData d) {
        if (Bukkit.getPlayer(d.getOwner()) != null) return false;
        return !canWorkOffline(d);
    }

    /** Chạy mỗi giây: nạp khi chủ nhân online, tiêu hao khi offline, quản lý chunk ticket. */
    public void energyTick() {
        long now = System.currentTimeMillis();
        long dt = lastTick == 0L ? 1000L : Math.min(Math.max(now - lastTick, 0L), 5000L);
        lastTick = now;
        if (dt <= 0L) return;

        boolean dirty = false;
        Map<String, TicketRef> desired = new HashMap<String, TicketRef>();
        for (MinionData d : plugin.getRepository().all()) {
            if (!d.hasOfflineCard() || d.getState() != MinionState.PLACED || !d.hasLocationData()) continue;
            // Đang mở GUI thẻ: đóng băng năng lượng để không lệch với bản hiển thị.
            if (plugin.getGui() != null && plugin.getGui().isOfflineCardOpen(d.getId())) continue;

            Player owner = Bukkit.getPlayer(d.getOwner());
            if (owner != null) {
                long cap = d.getOfflineCapacityMs();
                if (d.getOfflineEnergyMs() < cap) {
                    d.setOfflineEnergyMs(Math.min(cap, d.getOfflineEnergyMs() + Math.round(dt * chargeRatio)));
                    dirty = true;
                }
                continue;
            }

            // Chủ nhân offline
            if (!enabled || !typeAllowed(d) || d.getOfflineEnergyMs() <= 0L) continue;
            World world = Bukkit.getWorld(d.getWorldName());
            if (world == null) continue;
            if (keepChunkLoaded) {
                String key = world.getName() + ";" + d.getChunkX() + ";" + d.getChunkZ();
                desired.put(key, new TicketRef(world.getName(), d.getChunkX(), d.getChunkZ()));
            }
            if (!world.isChunkLoaded(d.getChunkX(), d.getChunkZ())) continue; // chunk chưa load xong: chưa tiêu năng lượng
            long left = d.getOfflineEnergyMs() - Math.round(dt * consumeRatio);
            d.setOfflineEnergyMs(Math.max(0L, left));
            if (left <= 0L) d.setDepletedNotice(true);
            dirty = true;
        }
        if (dirty) plugin.getRepository().save();
        syncTickets(desired);
    }

    private void syncTickets(Map<String, TicketRef> desired) {
        for (Iterator<Map.Entry<String, TicketRef>> it = tickets.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, TicketRef> e = it.next();
            if (desired.containsKey(e.getKey())) continue;
            World w = Bukkit.getWorld(e.getValue().world);
            if (w != null) w.removePluginChunkTicket(e.getValue().cx, e.getValue().cz, plugin);
            it.remove();
        }
        for (Map.Entry<String, TicketRef> e : desired.entrySet()) {
            if (tickets.containsKey(e.getKey())) continue;
            World w = Bukkit.getWorld(e.getValue().world);
            if (w == null) continue;
            w.addPluginChunkTicket(e.getValue().cx, e.getValue().cz, plugin);
            tickets.put(e.getKey(), e.getValue());
        }
    }

    public void releaseAllTickets() {
        for (TicketRef ref : tickets.values()) {
            World w = Bukkit.getWorld(ref.world);
            if (w != null) w.removePluginChunkTicket(ref.cx, ref.cz, plugin);
        }
        tickets.clear();
    }

    /** Khi chủ nhân vào game: báo các Đệ Tử đã hết năng lượng trong lúc offline. */
    public void onJoin(Player p) {
        int depleted = 0;
        for (MinionData d : plugin.getRepository().byOwner(p.getUniqueId())) {
            if (d.isDepletedNotice()) {
                depleted++;
                d.setDepletedNotice(false);
            }
        }
        if (depleted > 0) {
            p.sendMessage(plugin.getMinionService().prefix() + Text.color("&e" + depleted
                    + " Đệ Tử đã hết năng lượng Thẻ Offline khi bạn vắng mặt. Thẻ sẽ tự nạp lại khi bạn online."));
        }
    }
}
