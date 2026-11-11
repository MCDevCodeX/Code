package vn.disciples.minions.service;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import vn.disciples.minions.util.Text;

import java.util.ArrayList;
import java.util.List;

/** Thẻ nâng cấp có PDC riêng, không gắn owner nên có thể giao dịch cho người khác. */
public final class UpgradeCardService {
    private final NamespacedKey key;
    private final NamespacedKey versionKey;

    public UpgradeCardService(JavaPlugin plugin) {
        this.key = new NamespacedKey(plugin, "upgrade_card");
        this.versionKey = new NamespacedKey(plugin, "upgrade_card_version");
    }

    public ItemStack create(int amount) {
        ItemStack item = new ItemStack(Material.PAPER, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Text.color("&6&l✦ THẺ NÂNG CẤP ĐỆ TỬ ✦"));
        List<String> lore = new ArrayList<String>();
        lore.add(Text.color("&7Nâng cấp Đệ Tử lên cấp tiếp theo."));
        lore.add("");
        lore.add(Text.color("&8• &bTăng phạm vi"));
        lore.add(Text.color("&8• &aTăng tốc độ"));
        lore.add("");
        lore.add(Text.color("&8Có thể giao dịch."));
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(versionKey, PersistentDataType.INTEGER, 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isCard(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        Integer version = item.getItemMeta().getPersistentDataContainer().get(versionKey, PersistentDataType.INTEGER);
        return marker != null && marker == (byte) 1 && version != null && version == 1;
    }

    /** Card đã bị plugin khác sửa tên/lore sẽ không còn được coi là hợp lệ để nâng cấp. */
    public boolean isPristineCard(ItemStack item) {
        if (!isCard(item)) return false;
        ItemStack canonical = create(1);
        ItemMeta a = item.getItemMeta();
        ItemMeta b = canonical.getItemMeta();
        if (a == null || b == null) return false;
        // Thẻ bị bỏ tên (anvil) => getDisplayName() null, không được NPE ở đây.
        String nameA = a.hasDisplayName() ? a.getDisplayName() : null;
        String nameB = b.hasDisplayName() ? b.getDisplayName() : null;
        if (nameA == null ? nameB != null : !nameA.equals(nameB)) return false;
        List<String> loreA = a.getLore();
        List<String> loreB = b.getLore();
        if (loreA == null || loreB == null) return loreA == null && loreB == null;
        return loreA.equals(loreB);
    }
}
