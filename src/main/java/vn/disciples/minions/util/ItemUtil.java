package vn.disciples.minions.util;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public final class ItemUtil {
    private ItemUtil() {}

    /** Đưa item vào túi đồ, phần dư thả xuống đất tại chỗ người chơi. */
    public static void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(item);
        for (ItemStack rest : left.values()) player.getWorld().dropItemNaturally(player.getLocation(), rest);
    }
}
