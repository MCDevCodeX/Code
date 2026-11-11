package vn.disciples.minions.service;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import vn.disciples.minions.DiscipleMinionsPlugin;
import vn.disciples.minions.model.MinionData;
import vn.disciples.minions.model.MinionState;
import vn.disciples.minions.model.OutputMode;
import vn.disciples.minions.storage.StorageItemStatus;
import vn.disciples.minions.util.ItemUtil;
import vn.disciples.minions.util.Text;

/**
 * Các hành động quản lý Đệ Tử dùng chung cho GUI rương (Java) và Floodgate form (Bedrock).
 * Gồm cả cách nâng cấp / trang bị công cụ KHÔNG cần kéo thả item vào ô slot (tiện cho Bedrock).
 */
public final class MinionActions {
    private final DiscipleMinionsPlugin plugin;

    public MinionActions(DiscipleMinionsPlugin plugin) { this.plugin = plugin; }

    private void msg(Player p, String text) {
        p.sendMessage(plugin.getMinionService().prefix() + Text.color(text));
    }

    public void startLinkChest(Player p, MinionData d) {
        plugin.getGui().setLinkingChest(p, d.getId());
        p.closeInventory();
        msg(p, "&eClick phải vào rương/kho muốn liên kết.");
    }

    public void toggleExtraStorage(Player p, MinionData d, boolean admin) {
        if (d.getOutputMode() == OutputMode.EXTRA_STORAGE) {
            d.setOutputMode(OutputMode.NONE);
            plugin.getRepository().save();
            msg(p, "&cĐã tắt ExtraStorage cho Đệ Tử này.");
            plugin.getGui().openManage(p, d, admin);
            return;
        }
        StorageItemStatus status = plugin.getMinionService().getStorageBridge().checkStorage(d.getOwner());
        if (status == StorageItemStatus.API_UNAVAILABLE || status == StorageItemStatus.ERROR) {
            msg(p, "&cExtraStorage chưa sẵn sàng hoặc API không tương thích.");
            return;
        }
        if (status == StorageItemStatus.USER_UNAVAILABLE) {
            msg(p, "&cChưa tải được dữ liệu ExtraStorage của chủ nhân.");
            return;
        }
        if (status == StorageItemStatus.STORAGE_DISABLED) {
            msg(p, "&cKho ExtraStorage của chủ nhân đang tắt.");
            return;
        }
        d.setOutputMode(OutputMode.EXTRA_STORAGE);
        plugin.getRepository().save();
        msg(p, "&aĐã bật ExtraStorage cho Đệ Tử này.");
        plugin.getGui().openManage(p, d, admin);
    }

    public void reclaim(Player p, MinionData d) {
        if (!d.getOwner().equals(p.getUniqueId())) {
            msg(p, "&cChỉ chủ nhân mới có thể thu hồi Đệ Tử.");
            return;
        }
        if (plugin.getGui().isTradePending(d.getId())) {
            msg(p, "&cĐệ Tử đang chờ giao dịch, không thể thu hồi.");
            return;
        }
        if (d.getState() == MinionState.PLACED) {
            plugin.getMinionService().reclaim(p, d);
            msg(p, "&aĐã thu hồi Đệ Tử về túi đồ.");
        }
        p.closeInventory();
    }

    /** Nâng cấp bằng cách trừ thẻ trực tiếp từ túi đồ (không cần mở GUI nâng cấp). */
    public boolean upgradeFromInventory(Player p, MinionData d) {
        if (plugin.getGui().isTradePending(d.getId())) {
            msg(p, "&cĐệ Tử đang chờ giao dịch, không thể nâng cấp.");
            return false;
        }
        int max = plugin.getConfig().getInt("levels.max", 3);
        if (d.getLevel() >= max) {
            msg(p, "&eĐệ Tử đã đạt cấp tối đa.");
            return false;
        }
        int required = plugin.getGui().requiredCards(d.getLevel() + 1);
        ItemStack[] contents = p.getInventory().getStorageContents();
        int have = 0;
        for (ItemStack it : contents) {
            if (plugin.getUpgradeCardService().isPristineCard(it)) have += it.getAmount();
        }
        if (have < required) {
            msg(p, "&cCần &f" + required + " &cThẻ Nâng Cấp trong túi đồ (bạn có &f" + have + "&c).");
            return false;
        }
        int remaining = required;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack it = contents[i];
            if (!plugin.getUpgradeCardService().isPristineCard(it)) continue;
            int take = Math.min(remaining, it.getAmount());
            if (take >= it.getAmount()) contents[i] = null;
            else {
                ItemStack copy = it.clone();
                copy.setAmount(it.getAmount() - take);
                contents[i] = copy;
            }
            remaining -= take;
        }
        p.getInventory().setStorageContents(contents);
        d.setLevel(d.getLevel() + 1);
        plugin.getRepository().flush(); // quan trọng: thẻ đã bị tiêu
        plugin.getMinionService().refreshVisual(d);
        msg(p, "&aNâng cấp thành công! &7Đệ Tử hiện cấp &f" + d.getLevel() + "&7.");
        return true;
    }
}
