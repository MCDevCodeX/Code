package vn.disciples.minions.service;

import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import vn.disciples.minions.model.MinionType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Danh sách block Đệ Tử được phép xử lý, quản lý trực tiếp bằng GUI Admin. */
public final class AllowedItemManager {
    private final JavaPlugin plugin;
    private final File file;
    private final Set<String> miner = new LinkedHashSet<String>();
    private final Set<String> farm = new LinkedHashSet<String>();
    // View không đổi, tránh tạo wrapper mới mỗi lần gọi names() (hàm này được gọi mỗi tick).
    private final Set<String> minerView = Collections.unmodifiableSet(miner);
    private final Set<String> farmView = Collections.unmodifiableSet(farm);

    public AllowedItemManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "allowed-items.yml");
    }

    public void load() {
        miner.clear();
        farm.clear();
        if (!file.exists()) {
            for (String s : plugin.getConfig().getStringList("miner.ores")) miner.add(s.toUpperCase());
            for (String s : plugin.getConfig().getStringList("farm.crops")) farm.add(s.toUpperCase());
            save();
            reportUnknown();
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String s : y.getStringList("miner")) miner.add(s.toUpperCase());
        for (String s : y.getStringList("farm")) farm.add(s.toUpperCase());
        reportUnknown();
    }

    /**
     * Một số block chỉ có ở 1.17+ (COPPER_ORE, DEEPSLATE_*). Trên server cũ chúng vô hại nhưng gây khó hiểu,
     * nên ghi log một dòng. Tên vẫn được giữ lại trong file để dùng tiếp khi server nâng cấp.
     */
    private void reportUnknown() {
        List<String> unknown = new ArrayList<String>();
        for (String n : miner) if (!isKnown(n)) unknown.add(n);
        for (String n : farm) if (!isKnown(n)) unknown.add(n);
        if (!unknown.isEmpty()) {
            plugin.getLogger().info("Bỏ qua " + unknown.size() + " vật phẩm không tồn tại ở phiên bản server này: " + unknown);
        }
    }

    private boolean isKnown(String name) {
        try { Material.valueOf(name); return true; }
        catch (IllegalArgumentException ex) { return false; }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("miner", new ArrayList<String>(miner));
        y.set("farm", new ArrayList<String>(farm));
        try { y.save(file); }
        catch (IOException ex) { plugin.getLogger().severe("Không thể lưu allowed-items.yml: " + ex.getMessage()); }
    }

    public Set<String> names(MinionType type) {
        return type == MinionType.MINER ? minerView : farmView;
    }

    public boolean contains(MinionType type, Material material) {
        return material != null && names(type).contains(material.name());
    }

    public boolean toggleFromItem(MinionType type, ItemStack item) {
        Material mapped = mapInventoryItem(type, item == null ? null : item.getType());
        if (mapped == null) return false;
        Set<String> set = type == MinionType.MINER ? miner : farm;
        if (!set.remove(mapped.name())) set.add(mapped.name());
        save();
        return true;
    }

    public boolean remove(MinionType type, Material material) {
        if (material == null) return false;
        boolean changed = (type == MinionType.MINER ? miner : farm).remove(material.name());
        if (changed) save();
        return changed;
    }

    public Material mapInventoryItem(MinionType type, Material input) {
        if (input == null || input == Material.AIR) return null;
        if (type == MinionType.MINER) return isMinerMaterial(input) ? input : null;
        return mapCropMaterial(input);
    }

    public boolean isMinerMaterial(Material material) {
        String n = material.name();
        return n.endsWith("_ORE") || n.equals("ANCIENT_DEBRIS");
    }

    /** Map item người chơi cầm -> block crop thực tế. */
    public Material mapCropMaterial(Material input) {
        String n = input.name();
        if (n.equals("WHEAT") || n.equals("WHEAT_SEEDS")) return material("WHEAT");
        if (n.equals("CARROT") || n.equals("CARROTS")) return material("CARROTS");
        if (n.equals("POTATO") || n.equals("POTATOES")) return material("POTATOES");
        if (n.equals("BEETROOT") || n.equals("BEETROOT_SEEDS") || n.equals("BEETROOTS")) return material("BEETROOTS");
        if (n.equals("NETHER_WART")) return material("NETHER_WART");
        if (n.equals("COCOA") || n.equals("COCOA_BEANS")) return material("COCOA");

        try {
            if (input.isBlock() && input.createBlockData() instanceof Ageable) return input;
        } catch (Throwable ignored) {}
        return null;
    }

    private Material material(String name) {
        try { return Material.valueOf(name); }
        catch (IllegalArgumentException ex) { return null; }
    }

    public List<Material> materials(MinionType type) {
        List<Material> out = new ArrayList<Material>();
        for (String name : names(type)) {
            try { out.add(Material.valueOf(name)); } catch (IllegalArgumentException ignored) {}
        }
        return out;
    }
}
