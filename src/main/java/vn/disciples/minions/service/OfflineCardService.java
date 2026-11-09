package vn.disciples.minions.service;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import vn.disciples.minions.DiscipleMinionsPlugin;
import vn.disciples.minions.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Thẻ Offline: cho phép Đệ Tử làm việc khi chủ nhân offline.
 * Mỗi thẻ có BẬC (dung lượng năng lượng cấu hình trong offline.card.tiers) và năng lượng hiện tại.
 * Thẻ tự mô tả (bậc, dung lượng, năng lượng nằm trong PDC) nên đổi config sau này không làm hỏng thẻ cũ.
 */
public final class OfflineCardService {
    private static final class Tier {
        final String name;
        final long capacityMs;
        Tier(String name, long capacityMs) { this.name = name; this.capacityMs = capacityMs; }
    }

    private final DiscipleMinionsPlugin plugin;
    private final NamespacedKey keyCard;
    private final NamespacedKey keyTier;
    private final NamespacedKey keyCapacity;
    private final NamespacedKey keyEnergy;
    private final NamespacedKey keyId;
    private final Map<Integer, Tier> tiers = new TreeMap<Integer, Tier>();
    private Material material = Material.CLOCK;
    private long initialEnergyMs = 0L;
    private double chargeRatio = 1D;

    public OfflineCardService(DiscipleMinionsPlugin plugin) {
        this.plugin = plugin;
        this.keyCard = new NamespacedKey(plugin, "offline_card");
        this.keyTier = new NamespacedKey(plugin, "offline_card_tier");
        this.keyCapacity = new NamespacedKey(plugin, "offline_card_capacity_ms");
        this.keyEnergy = new NamespacedKey(plugin, "offline_card_energy_ms");
        this.keyId = new NamespacedKey(plugin, "offline_card_id");
        reload();
    }

    public void reload() {
        tiers.clear();
        Material m = Material.matchMaterial(plugin.getConfig().getString("offline.card.material", "CLOCK"));
        material = m == null ? Material.CLOCK : m;
        initialEnergyMs = Math.max(0L, plugin.getConfig().getLong("offline.card.initial-energy-minutes", 0L)) * 60000L;
        chargeRatio = Math.max(0.01D, plugin.getConfig().getDouble("offline.charge-ratio", 1D));
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("offline.card.tiers");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    int tier = Integer.parseInt(key);
                    long minutes = Math.max(1L, section.getLong(key + ".capacity-minutes", 60L));
                    tiers.put(tier, new Tier(section.getString(key + ".name", "&fBậc " + tier), minutes * 60000L));
                } catch (NumberFormatException ignored) {}
            }
        }
        if (tiers.isEmpty()) {
            tiers.put(1, new Tier("&aBậc 1", 60L * 60000L));
            tiers.put(2, new Tier("&bBậc 2", 180L * 60000L));
            tiers.put(3, new Tier("&6Bậc 3", 480L * 60000L));
        }
    }

    public boolean validTier(int tier) { return tiers.containsKey(tier); }

    public int maxTier() {
        int max = 1;
        for (int t : tiers.keySet()) max = Math.max(max, t);
        return max;
    }

    public List<String> tierNumbers() {
        List<String> out = new ArrayList<String>();
        for (int t : tiers.keySet()) out.add(String.valueOf(t));
        return out;
    }

    public long defaultCapacityMs(int tier) {
        Tier t = tiers.get(tier);
        return t == null ? 0L : t.capacityMs;
    }

    public long initialEnergyMs() { return initialEnergyMs; }

    /** Tạo thẻ mới (mỗi thẻ có ID riêng nên không bao giờ xếp chồng). Trả về null nếu bậc không tồn tại. */
    public ItemStack create(int tier, long energyMs) {
        Tier t = tiers.get(tier);
        if (t == null) return null;
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyCard, PersistentDataType.BYTE, (byte) 1);
        pdc.set(keyTier, PersistentDataType.INTEGER, tier);
        pdc.set(keyCapacity, PersistentDataType.LONG, t.capacityMs);
        pdc.set(keyEnergy, PersistentDataType.LONG, clamp(energyMs, t.capacityMs));
        pdc.set(keyId, PersistentDataType.STRING, UUID.randomUUID().toString());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return refresh(item);
    }

    public boolean isCard(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(keyCard, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    public int tierOf(ItemStack item) {
        if (!isCard(item)) return 0;
        Integer v = item.getItemMeta().getPersistentDataContainer().get(keyTier, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    public long capacityOf(ItemStack item) {
        if (!isCard(item)) return 0L;
        Long v = item.getItemMeta().getPersistentDataContainer().get(keyCapacity, PersistentDataType.LONG);
        return v == null ? 0L : v;
    }

    public long energyOf(ItemStack item) {
        if (!isCard(item)) return 0L;
        Long v = item.getItemMeta().getPersistentDataContainer().get(keyEnergy, PersistentDataType.LONG);
        return v == null ? 0L : clamp(v, capacityOf(item));
    }

    public String idOf(ItemStack item) {
        if (!isCard(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
    }

    public boolean sameCard(ItemStack a, ItemStack b) {
        String x = idOf(a);
        return x != null && x.equals(idOf(b));
    }

    public String tierName(int tier) {
        Tier t = tiers.get(tier);
        return t == null ? "&fBậc " + tier : t.name;
    }

    /** Bản sao của thẻ với năng lượng mới (PDC + lore được cập nhật). */
    public ItemStack withEnergy(ItemStack card, long energyMs) {
        if (!isCard(card)) return card;
        ItemStack copy = card.clone();
        ItemMeta meta = copy.getItemMeta();
        meta.getPersistentDataContainer().set(keyEnergy, PersistentDataType.LONG, clamp(energyMs, capacityOf(card)));
        copy.setItemMeta(meta);
        return refresh(copy);
    }

    private ItemStack refresh(ItemStack item) {
        int tier = tierOf(item);
        long cap = capacityOf(item);
        long energy = energyOf(item);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Text.color("&b&l✦ THẺ OFFLINE ĐỆ TỬ ✦"));
        List<String> lore = new ArrayList<String>();
        lore.add(Text.color("&7Cho phép Đệ Tử làm việc khi chủ nhân &coffline&7."));
        lore.add("");
        lore.add(Text.color("&8• &7Bậc       " + tierName(tier)));
        lore.add(Text.color("&8• &7Dung lượng &f" + formatDuration(cap)));
        lore.add(Text.color("&8• &7Năng lượng &a" + formatDuration(energy) + " &8/ &f" + formatDuration(cap)));
        lore.add(Text.color("&8• " + bar(energy, cap)));
        lore.add("");
        String ratio = chargeRatio == 1D ? "1 phút" : String.format(java.util.Locale.US, "%.2f phút", chargeRatio);
        lore.add(Text.color("&7Chủ nhân online: &f1 phút &8= &f" + ratio + " &7năng lượng"));
        lore.add(Text.color("&7Thẻ tự nạp khi đã gắn vào Đệ Tử."));
        lore.add("");
        lore.add(Text.color("&8Có thể giao dịch."));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String bar(long energy, long cap) {
        int total = 10;
        int filled = cap <= 0 ? 0 : (int) Math.round(total * (double) energy / (double) cap);
        filled = Math.max(0, Math.min(total, filled));
        StringBuilder sb = new StringBuilder("&a");
        for (int i = 0; i < filled; i++) sb.append('|');
        sb.append("&8");
        for (int i = filled; i < total; i++) sb.append('|');
        return sb.toString();
    }

    private static long clamp(long v, long cap) {
        return Math.max(0L, Math.min(v, cap));
    }

    /** 90 phút -> "1 giờ 30 phút"; 75 giây -> "1 phút 15 giây". */
    public static String formatDuration(long ms) {
        long totalSeconds = Math.max(0L, ms / 1000L);
        long h = totalSeconds / 3600L;
        long m = (totalSeconds % 3600L) / 60L;
        long s = totalSeconds % 60L;
        if (h > 0) return h + " giờ" + (m > 0 ? " " + m + " phút" : "");
        if (m > 0) return m + " phút" + (s > 0 && m < 10 ? " " + s + " giây" : "");
        return s + " giây";
    }
}
