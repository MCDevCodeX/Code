package vn.disciples.minions.service;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import vn.disciples.minions.util.Compat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Executes mining with the exact ItemStack stored by the minion.
 *
 * Design goals:
 *  - Never rebuild a custom pickaxe from Material/name/lore.
 *  - Preserve the exact ItemStack, PDC/NBT, enchants, model data and plugin metadata.
 *  - Hook LevelingPickaxe without a hard compile-time dependency.
 *  - Fall back safely to Bukkit drops for vanilla/MMOItems/custom items.
 */
public final class ToolEngine {
    private final JavaPlugin plugin;
    private LevelingPickaxeHook levelingPickaxe;

    public ToolEngine(JavaPlugin plugin) {
        this.plugin = plugin;
        reloadHooks();
    }

    public void reloadHooks() {
        Plugin lvp = Bukkit.getPluginManager().getPlugin("LevelingPickaxe");
        if (lvp != null && lvp.isEnabled()) {
            try {
                levelingPickaxe = new LevelingPickaxeHook(lvp);
                plugin.getLogger().info("Tool hook: LevelingPickaxe enabled.");
            } catch (Throwable ex) {
                levelingPickaxe = null;
                plugin.getLogger().warning("Could not hook LevelingPickaxe: " + ex.getMessage());
            }
        } else {
            levelingPickaxe = null;
        }

        Plugin mmo = Bukkit.getPluginManager().getPlugin("MMOItems");
        if (mmo != null && mmo.isEnabled()) {
            plugin.getLogger().info("Tool hook: MMOItems detected. Exact ItemStack is preserved; Bukkit drop/enchant semantics are used.");
        }
    }

    /** Result contains drops and the potentially-mutated tool which MUST be saved back. */
    public MineResult mine(Block block, ItemStack originalTool) {
        ItemStack tool = originalTool == null ? null : originalTool.clone();
        Collection<ItemStack> raw = tool == null ? block.getDrops() : block.getDrops(tool);
        List<ItemStack> drops = new ArrayList<ItemStack>();
        for (ItemStack item : raw) if (item != null && item.getType() != Material.AIR) drops.add(item.clone());

        if (tool != null && levelingPickaxe != null) {
            try {
                levelingPickaxe.onMined(tool, block.getType());
            } catch (Throwable ex) {
                plugin.getLogger().warning("LevelingPickaxe hook error: " + ex.getMessage());
            }
        }
        return new MineResult(drops, tool);
    }

    /**
     * Trừ độ bền công cụ giống vanilla (có tính Unbreaking). Sửa trực tiếp ItemStack truyền vào.
     *
     * @return true nếu công cụ đã HỎNG (hết độ bền) và cần được gỡ bỏ.
     *         Vật phẩm không có độ bền, Unbreakable hoặc không phải Damageable thì không bao giờ hỏng.
     */
    public boolean damage(ItemStack tool, int amount) {
        if (tool == null || amount <= 0) return false;
        int max = tool.getType().getMaxDurability();
        if (max <= 0) return false;
        ItemMeta meta = tool.getItemMeta();
        if (!(meta instanceof Damageable) || meta.isUnbreakable()) return false;

        Enchantment unbreakingEnchant = Compat.unbreaking();
        int unbreaking = unbreakingEnchant == null ? 0 : tool.getEnchantmentLevel(unbreakingEnchant);
        int applied = 0;
        for (int i = 0; i < amount; i++) {
            if (unbreaking <= 0 || ThreadLocalRandom.current().nextInt(unbreaking + 1) == 0) applied++;
        }
        if (applied == 0) return false;

        Damageable damageable = (Damageable) meta;
        int next = damageable.getDamage() + applied;
        if (next >= max) return true;
        damageable.setDamage(next);
        tool.setItemMeta(meta);
        return false;
    }

    public static final class MineResult {
        private final List<ItemStack> drops;
        private final ItemStack tool;
        public MineResult(List<ItemStack> drops, ItemStack tool) {
            this.drops = drops;
            this.tool = tool;
        }
        public List<ItemStack> getDrops() { return drops; }
        public ItemStack getTool() { return tool == null ? null : tool.clone(); }
        /** Dùng nội bộ trong vòng lặp đào: không clone, tránh 2 lần clone mỗi block. */
        public ItemStack getToolRaw() { return tool; }
    }

    private static final class LevelingPickaxeHook {
        private final Object manager;
        private final Method get;
        private final Method addMiningProgress;
        private final Method refresh;

        private LevelingPickaxeHook(Plugin lvp) throws Exception {
            Method getManager = lvp.getClass().getMethod("getPickaxeManager");
            manager = getManager.invoke(lvp);
            Class<?> managerClass = manager.getClass();
            get = managerClass.getMethod("get", ItemStack.class);

            Class<?> dataClass = Class.forName("vn.levelingpickaxe.manager.PickaxeData", true, lvp.getClass().getClassLoader());
            addMiningProgress = managerClass.getMethod("addMiningProgress", ItemStack.class, dataClass, Material.class);
            refresh = managerClass.getMethod("refresh", ItemStack.class);
        }

        private void onMined(ItemStack tool, Material blockType) throws Exception {
            Object data = get.invoke(manager, tool);
            if (data == null) return;
            addMiningProgress.invoke(manager, tool, data, blockType);
            // refresh writes current level/enchant/lore back into THIS same ItemStack.
            refresh.invoke(manager, tool);
        }
    }
}
