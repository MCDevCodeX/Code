package vn.disciples.minions.task;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import vn.disciples.minions.DiscipleMinionsPlugin;
import vn.disciples.minions.data.MinionRepository;

import java.util.UUID;

/**
 * Thu hồi item Đệ Tử đã bị xoá.
 *
 * LƯU Ý AN TOÀN: chỉ chạy khi repository đã đọc minions.yml thành công. Trước đây nếu file lỗi/thiếu,
 * mọi item Đệ Tử trên server bị coi là "không tồn tại" và bị xoá sạch trong vài giây.
 */
public final class RevokedItemTask implements Runnable {
    private final DiscipleMinionsPlugin plugin;
    public RevokedItemTask(DiscipleMinionsPlugin plugin) { this.plugin = plugin; }

    @Override
    public void run() {
        MinionRepository repo = plugin.getRepository();
        if (repo == null || !repo.isLoaded()) return; // dữ liệu chưa chắc chắn: không xoá gì
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack[] contents = p.getInventory().getContents();
            boolean changed = false;
            for (int i = 0; i < contents.length; i++) {
                UUID id = plugin.getItemService().getId(contents[i]);
                if (id == null) continue;
                if (repo.isRevoked(id) || repo.get(id) == null) {
                    p.getInventory().setItem(i, null);
                    changed = true;
                }
            }
            if (changed) p.updateInventory();
        }
    }
}
