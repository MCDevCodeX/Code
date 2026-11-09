package vn.disciples.minions.service;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import vn.disciples.minions.model.MinionData;
import vn.disciples.minions.model.MinionType;
import vn.disciples.minions.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MinionItemService {
    private final NamespacedKey idKey;
    private final NamespacedKey typeKey;

    public MinionItemService(JavaPlugin plugin) {
        idKey = new NamespacedKey(plugin, "minion_id");
        typeKey = new NamespacedKey(plugin, "minion_type");
    }

    public ItemStack create(MinionData d) {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Text.color(d.getType() == MinionType.FARM ? "&a&l✦ ĐỆ TỬ NÔNG SẢN ✦" : "&b&l✦ ĐỆ TỬ KHOÁNG SẢN ✦"));
        List<String> lore = new ArrayList<String>();
        lore.add(Text.color("&8• &7Cấp độ  &f" + d.getLevel()));
        lore.add(Text.color("&8• &7Loại     &f" + d.getType().displayName()));
        lore.add("");
        lore.add(Text.color("&e➜ Click phải để đặt"));
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(idKey, PersistentDataType.STRING, d.getId().toString());
        pdc.set(typeKey, PersistentDataType.STRING, d.getType().name());
        item.setItemMeta(meta);
        return item;
    }

    public UUID getId(ItemStack item) {
        if (item == null || item.getType() != Material.NETHER_STAR || !item.hasItemMeta()) return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException ex) { return null; }
    }

    public boolean isMinion(ItemStack item) { return getId(item) != null; }
}
