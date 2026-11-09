package vn.disciples.minions.task;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import vn.disciples.minions.DiscipleMinionsPlugin;

import java.util.UUID;

public final class RevokedItemTask implements Runnable {
    private final DiscipleMinionsPlugin plugin;
    public RevokedItemTask(DiscipleMinionsPlugin plugin) { this.plugin = plugin; }

    @Override
    public void run() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack[] contents = p.getInventory().getContents();
            boolean changed = false;
            for (int i = 0; i < contents.length; i++) {
                UUID id = plugin.getItemService().getId(contents[i]);
                if (id != null && (plugin.getRepository().isRevoked(id) || plugin.getRepository().get(id) == null)) {
                    p.getInventory().setItem(i, null);
                    changed = true;
                }
            }
            if (changed) p.updateInventory();
        }
    }
}
