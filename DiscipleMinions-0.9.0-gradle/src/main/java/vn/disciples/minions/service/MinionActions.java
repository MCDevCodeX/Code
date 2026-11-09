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

    /** Lấy công cụ đang cầm ở tay chính gắn vào Đệ Tử; công cụ cũ (nếu có) trả lại túi đồ. */
    public boolean equipHeldTool(Player p, MinionData d) {
        if (plugin.getGui().isTradePending(d.getId())) {
            msg(p, "&cĐệ Tử đang chờ giao dịch, không thể chỉnh công cụ.");
            return false;
        }
        if (plugin.getGui().isToolOpen(d.getId())) {
            msg(p, "&cĐang có người khác chỉnh công cụ của Đệ Tử này.");
            return false;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            msg(p, "&cHãy cầm công cụ ở tay chính trước.");
            return false;
        }
        if (plugin.getItemService().getId(hand) != null || plugin.getUpgradeCardService().isCard(hand)
                || plugin.getOfflineCards().isCard(hand)) {
            msg(p, "&cKhông thể gắn vật phẩm Đệ Tử hoặc Thẻ làm công cụ.");
            return false;
        }
        ItemStack one = hand.clone();
        one.setAmount(1);
        ItemStack old = d.getTool();
        if (hand.getAmount() > 1) {
            ItemStack rest = hand.clone();
            rest.setAmount(hand.getAmount() - 1);
            p.getInventory().setItemInMainHand(rest);
        } else {
            p.getInventory().setItemInMainHand(null);
        }
        d.setTool(one);
        if (old != null) ItemUtil.giveOrDrop(p, old);
        plugin.getRepository().flush();
        plugin.getMinionService().refreshVisual(d);
        msg(p, "&aĐã trang bị công cụ cho Đệ Tử.");
        return true;
    }

    // ------------------------------------------------------------------
    // THẺ OFFLINE
    // ------------------------------------------------------------------

    private boolean offlineGuard(Player p, MinionData d) {
        if (!plugin.getOffline().isEnabled()) {
            msg(p, "&cTính năng Thẻ Offline đang tắt.");
            return false;
        }
        if (plugin.getGui().isTradePending(d.getId())) {
            msg(p, "&cĐệ Tử đang chờ giao dịch, không thể chỉnh thẻ.");
            return false;
        }
        if (plugin.getGui().isOfflineCardOpen(d.getId())) {
            msg(p, "&cĐang có người khác chỉnh thẻ của Đệ Tử này.");
            return false;
        }
        return true;
    }

    /** Gắn thẻ vào Đệ Tử: năng lượng lấy từ thẻ, dung lượng theo bậc của thẻ. */
    private void installOfflineCard(MinionData d, ItemStack card) {
        long cap = plugin.getOfflineCards().capacityOf(card);
        d.setOfflineCard(card);
        d.setOfflineCapacityMs(cap);
        d.setOfflineEnergyMs(Math.min(plugin.getOfflineCards().energyOf(card), cap));
        d.setDepletedNotice(false);
    }

    /** Gắn Thẻ Offline đang cầm ở tay chính; thẻ cũ (nếu có) trả lại kèm năng lượng hiện tại. */
    public boolean equipHeldOfflineCard(Player p, MinionData d) {
        if (!offlineGuard(p, d)) return false;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!plugin.getOfflineCards().isCard(hand)) {
            msg(p, "&cHãy cầm Thẻ Offline Đệ Tử ở tay chính trước.");
            return false;
        }
        ItemStack one = hand.clone();
        one.setAmount(1);
        if (hand.getAmount() > 1) {
            ItemStack rest = hand.clone();
            rest.setAmount(hand.getAmount() - 1);
            p.getInventory().setItemInMainHand(rest);
        } else {
            p.getInventory().setItemInMainHand(null);
        }
        ItemStack old = d.hasOfflineCard()
                ? plugin.getOfflineCards().withEnergy(d.getOfflineCard(), d.getOfflineEnergyMs()) : null;
        installOfflineCard(d, one);
        if (old != null) ItemUtil.giveOrDrop(p, old);
        plugin.getRepository().flush(); // thẻ đã rời túi đồ: lưu ngay
        msg(p, "&aĐã gắn Thẻ Offline cho Đệ Tử.");
        return true;
    }

    public boolean unequipOfflineCard(Player p, MinionData d) {
        if (!offlineGuard(p, d)) return false;
        if (!d.hasOfflineCard()) {
            msg(p, "&eĐệ Tử chưa gắn Thẻ Offline.");
            return false;
        }
        ItemStack out = plugin.getOfflineCards().withEnergy(d.getOfflineCard(), d.getOfflineEnergyMs());
        d.clearOfflineCard();
        ItemUtil.giveOrDrop(p, out);
        plugin.getRepository().flush();
        msg(p, "&aĐã gỡ Thẻ Offline về túi đồ.");
        return true;
    }

    /**
     * Gọi khi người chơi đóng GUI Thẻ Offline; inSlot là thứ họ để lại ở ô thẻ (null = ô trống).
     * Cùng một thẻ (cùng ID) thì giữ nguyên năng lượng hiện có của Đệ Tử.
     */
    public void applyOfflineCardSlot(Player p, MinionData d, ItemStack inSlot) {
        OfflineCardService cards = plugin.getOfflineCards();
        if (inSlot != null && !cards.isCard(inSlot)) {
            ItemUtil.giveOrDrop(p, inSlot);
            inSlot = null;
        }
        if (inSlot == null) {
            if (d.hasOfflineCard()) {
                d.clearOfflineCard();
                plugin.getRepository().flush();
                msg(p, "&aĐã gỡ Thẻ Offline khỏi Đệ Tử.");
            }
            return;
        }
        if (inSlot.getAmount() > 1) {
            ItemStack extra = inSlot.clone();
            extra.setAmount(inSlot.getAmount() - 1);
            ItemUtil.giveOrDrop(p, extra);
            inSlot = inSlot.clone();
            inSlot.setAmount(1);
        }
        if (cards.sameCard(inSlot, d.getOfflineCard())) return; // không đổi
        installOfflineCard(d, inSlot);
        plugin.getRepository().flush();
        msg(p, "&aĐã gắn Thẻ Offline (" + Text.color(cards.tierName(cards.tierOf(inSlot))) + "&a) cho Đệ Tử.");
    }

    public boolean unequipTool(Player p, MinionData d) {
        if (plugin.getGui().isTradePending(d.getId())) {
            msg(p, "&cĐệ Tử đang chờ giao dịch, không thể chỉnh công cụ.");
            return false;
        }
        if (plugin.getGui().isToolOpen(d.getId())) {
            msg(p, "&cĐang có người khác chỉnh công cụ của Đệ Tử này.");
            return false;
        }
        ItemStack old = d.getTool();
        if (old == null) {
            msg(p, "&eĐệ Tử chưa có công cụ.");
            return false;
        }
        d.setTool(null);
        ItemUtil.giveOrDrop(p, old);
        plugin.getRepository().flush();
        plugin.getMinionService().refreshVisual(d);
        msg(p, "&aĐã gỡ công cụ về túi đồ.");
        return true;
    }
}
