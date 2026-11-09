package vn.disciples.minions.service;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import vn.disciples.minions.DiscipleMinionsPlugin;
import vn.disciples.minions.gui.GuiManager;
import vn.disciples.minions.model.MinionData;
import vn.disciples.minions.model.MinionState;
import vn.disciples.minions.util.ItemUtil;
import vn.disciples.minions.util.Text;

import java.util.UUID;

/**
 * Logic trade dùng chung cho mọi giao diện: Anvil, chat, GUI rương và Floodgate form (Bedrock).
 */
public final class TradeService {
    private final DiscipleMinionsPlugin plugin;

    public TradeService(DiscipleMinionsPlugin plugin) { this.plugin = plugin; }

    private void msg(Player p, String text) {
        p.sendMessage(plugin.getMinionService().prefix() + Text.color(text));
    }

    /** Gửi yêu cầu trade. Trả về true nếu thành công. closeAnvil=true khi gọi từ GUI Anvil. */
    public boolean submit(final Player p, MinionData d, String rawName, boolean closeAnvil) {
        String targetName = rawName == null ? "" : rawName.trim();
        if (targetName.isEmpty() || targetName.equalsIgnoreCase(GuiManager.TRADE_PLACEHOLDER)
                || targetName.equalsIgnoreCase("Nhập tên người chơi") || targetName.equalsIgnoreCase("Paper")) {
            msg(p, "&cHãy nhập tên người chơi hợp lệ.");
            return false;
        }
        if (!d.getOwner().equals(p.getUniqueId())) return false;
        if (plugin.getGui().isTradePending(d.getId())) {
            msg(p, "&cĐệ Tử này đang có yêu cầu trade chưa hoàn tất.");
            return false;
        }
        final Player target = plugin.getBedrock().findPlayer(targetName);
        if (target == null || target.equals(p)) {
            msg(p, "&cNgười chơi không online, không tồn tại hoặc là chính bạn.");
            return false;
        }
        if (!target.hasPermission("detu.limit.bypass") && !plugin.getMinionService().canOwnMore(target.getUniqueId())) {
            msg(p, "&c" + target.getName() + " đã đạt giới hạn Đệ Tử.");
            return false;
        }
        if (d.getState() == MinionState.ITEM && !hasMinionItem(p, d.getId())) {
            msg(p, "&cĐệ Tử đang ở dạng vật phẩm nhưng không có trong túi đồ của bạn. Không thể trade để tránh nhân bản item.");
            return false;
        }

        final UUID minionId = d.getId();
        plugin.getGui().beginTrade(d, p, target); // khóa Đệ Tử ngay lập tức
        msg(p, "&eĐã gửi yêu cầu nhận Đệ Tử cho &f" + target.getName() + "&e.");

        Runnable open = new Runnable() {
            @Override public void run() {
                MinionData cur = plugin.getRepository().get(minionId);
                if (cur != null && plugin.getGui().isTradePending(minionId) && target.isOnline() && p.isOnline()) {
                    plugin.getGui().openTradeConfirm(target, p, cur);
                } else {
                    plugin.getGui().clearTrade(minionId);
                }
            }
        };
        if (closeAnvil) {
            p.closeInventory();
            plugin.getServer().getScheduler().runTask(plugin, open);
        } else {
            open.run();
        }
        return true;
    }

    /** Người nhận bấm "Xác nhận". */
    public void accept(Player p, UUID minionId, UUID senderId) {
        GuiManager.PendingTrade trade = plugin.getGui().getPendingTrade(minionId);
        Player sender = plugin.getServer().getPlayer(senderId);

        if (trade == null || !trade.target.equals(p.getUniqueId()) || !trade.sender.equals(senderId)) {
            p.closeInventory();
            msg(p, "&cYêu cầu trade không còn hiệu lực.");
            return;
        }
        MinionData d = plugin.getRepository().get(minionId);
        if (d == null || sender == null || !d.getOwner().equals(sender.getUniqueId())) {
            plugin.getGui().clearTrade(minionId);
            p.closeInventory();
            msg(p, "&cTrade thất bại: người gửi đã offline hoặc Đệ Tử không còn hợp lệ.");
            if (sender != null) msg(sender, "&cTrade đã hủy vì Đệ Tử không còn hợp lệ.");
            return;
        }
        if (!p.hasPermission("detu.limit.bypass") && !plugin.getMinionService().canOwnMore(p.getUniqueId())) {
            plugin.getGui().clearTrade(minionId);
            p.closeInventory();
            msg(p, "&cBạn đã đạt giới hạn Đệ Tử.");
            msg(sender, "&c" + p.getName() + " đã đạt giới hạn Đệ Tử, trade bị hủy.");
            return;
        }
        if (d.getState() == MinionState.ITEM && !takeMinionItem(sender, d.getId())) {
            plugin.getGui().clearTrade(minionId);
            p.closeInventory();
            msg(p, "&cTrade thất bại: item Đệ Tử không còn trong túi người gửi.");
            msg(sender, "&cTrade đã hủy vì item Đệ Tử không còn trong túi của bạn.");
            return;
        }

        plugin.getGui().clearTrade(minionId); // xóa trước để sự kiện đóng GUI không báo "đã hủy"
        d.setOwner(p.getUniqueId());
        d.clearOutputLinks();
        d.clearVerified(); // quyền của chủ nhân mới khác chủ nhân cũ
        if (d.getState() == MinionState.ITEM) ItemUtil.giveOrDrop(p, plugin.getItemService().create(d));
        plugin.getRepository().flush();
        plugin.getMinionService().refreshVisual(d); // đổi tên chủ nhân trên hologram tại chỗ
        p.closeInventory();
        msg(p, "&aBạn đã nhận Đệ Tử.");
        msg(sender, "&aĐã chuyển Đệ Tử cho &f" + p.getName() + "&a.");
    }

    /** Người nhận bấm "Từ chối". */
    public void decline(Player p, UUID minionId, UUID senderId) {
        GuiManager.PendingTrade trade = plugin.getGui().getPendingTrade(minionId);
        if (trade != null && trade.target.equals(p.getUniqueId())) plugin.getGui().clearTrade(minionId);
        p.closeInventory();
        Player sender = plugin.getServer().getPlayer(senderId);
        if (sender != null) msg(sender, "&c" + p.getName() + " đã từ chối giao dịch.");
    }

    /** Người nhận đóng GUI/form mà không chọn gì => hủy yêu cầu và báo người gửi. */
    public void targetClosed(Player p, UUID minionId) {
        GuiManager.PendingTrade t = plugin.getGui().getPendingTrade(minionId);
        if (t == null || !t.target.equals(p.getUniqueId())) return;
        plugin.getGui().clearTrade(minionId);
        Player sender = plugin.getServer().getPlayer(t.sender);
        if (sender != null) msg(sender, "&e" + p.getName() + " đã đóng giao dịch, trade bị hủy.");
    }

    public boolean hasMinionItem(Player player, UUID id) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (id.equals(plugin.getItemService().getId(item))) return true;
        }
        return false;
    }

    public boolean takeMinionItem(Player player, UUID id) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!id.equals(plugin.getItemService().getId(item))) continue;
            if (item.getAmount() <= 1) player.getInventory().setItem(slot, null);
            else {
                ItemStack left = item.clone();
                left.setAmount(item.getAmount() - 1);
                player.getInventory().setItem(slot, left);
            }
            return true;
        }
        return false;
    }
}
