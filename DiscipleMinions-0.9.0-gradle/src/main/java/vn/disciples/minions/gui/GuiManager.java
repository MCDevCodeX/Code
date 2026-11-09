package vn.disciples.minions.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import vn.disciples.minions.DiscipleMinionsPlugin;
import vn.disciples.minions.model.MinionData;
import vn.disciples.minions.model.MinionState;
import vn.disciples.minions.model.MinionType;
import vn.disciples.minions.model.OutputMode;
import vn.disciples.minions.storage.StorageItemStatus;
import vn.disciples.minions.service.OfflineCardService;
import vn.disciples.minions.util.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** GUI Đệ Tử 0.7: nút chức năng liền nhau, nâng cấp bằng thẻ, settings Admin riêng. */
public final class GuiManager {
    private final DiscipleMinionsPlugin plugin;
    private final NamespacedKey guiIdKey;
    private final Map<UUID, UUID> linkingChest = new HashMap<UUID, UUID>();
    /** Đệ Tử -> người đang mở Kho Công Cụ (chặn 2 người cùng mở => nhân đôi công cụ). */
    private final Map<UUID, UUID> toolViewers = new HashMap<UUID, UUID>();
    /** Đệ Tử -> người đang mở ô Thẻ Offline (chặn 2 người cùng mở => nhân đôi thẻ/năng lượng). */
    private final Map<UUID, UUID> offlineViewers = new HashMap<UUID, UUID>();
    /** Đệ Tử -> yêu cầu trade đang chờ xác nhận. */
    private final Map<UUID, PendingTrade> pendingTrades = new HashMap<UUID, PendingTrade>();
    /** Người chơi -> Đệ Tử đang chờ nhập tên người nhận qua chat (đọc từ thread chat nên dùng ConcurrentHashMap). */
    private final Map<UUID, UUID> tradePrompts = new ConcurrentHashMap<UUID, UUID>();
    public static final String TRADE_PLACEHOLDER = "✦ NHẬP TÊN NGƯỜI NHẬN";

    public GuiManager(DiscipleMinionsPlugin plugin) {
        this.plugin = plugin;
        this.guiIdKey = new NamespacedKey(plugin, "gui_disciple_id");
    }

    /** Tiêu đề inventory: người chơi Bedrock được thay các ký tự đặc biệt có thể không hiển thị được. */
    private String title(Player viewer, String raw) {
        return Text.color(plugin.getBedrock().isBedrock(viewer) ? plugin.getBedrock().clean(raw) : raw);
    }

    /** Mở inventory; với người chơi Bedrock làm sạch tên/lore item trước. */
    private void show(Player viewer, Inventory inv) {
        if (plugin.getBedrock().isBedrock(viewer)) {
            // Ô 13 của Kho Công Cụ/Nâng Cấp chứa item THẬT của người chơi: tuyệt đối không sửa tên/lore.
            InventoryHolder h = inv.getHolder();
            int skip = (h instanceof ToolHolder || h instanceof UpgradeHolder || h instanceof OfflineCardHolder) ? 13 : -1;
            plugin.getBedrock().cleanInventory(inv, skip);
        }
        viewer.openInventory(inv);
    }

    public void openPlayer(Player player, int page) {
        if (plugin.getForms().tryPlayerList(player)) return; // Bedrock + Floodgate: dùng form
        List<MinionData> list = plugin.getRepository().byOwner(player.getUniqueId());
        int pages = Math.max(1, (int) Math.ceil(list.size() / 45.0));
        page = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = Bukkit.createInventory(new PlayerListHolder(page), 54,
                title(player, "&0Đệ Tử • Cá Nhân &8[&f" + (page + 1) + "&8/&f" + pages + "&8]"));
        int start = page * 45;
        for (int i = start; i < Math.min(start + 45, list.size()); i++) inv.setItem(i - start, minionIcon(list.get(i)));
        if (list.isEmpty()) inv.setItem(22, item(Material.BARRIER, "&c✦ &fCHƯA CÓ ĐỆ TỬ", "", "&7Chưa có Đệ Tử để quản lý."));
        fillBottomBar(inv);
        inv.setItem(48, item(Material.ARROW, "&e&l‹ TRANG TRƯỚC"));
        int max = plugin.getMinionService().maxOwnedPerPlayer();
        inv.setItem(49, item(Material.NETHER_STAR, "&6✦ &fTỔNG QUAN",
                "&8• &7Sở hữu    &f" + list.size() + (max < 0 ? "&8/&a∞" : "&8/&f" + max),
                "&8• &7Đang đặt  &a" + countPlaced(list),
                "&8• &7Dạng item &e" + (list.size() - countPlaced(list))));
        inv.setItem(50, item(Material.ARROW, "&e&lTRANG SAU ›"));
        show(player, inv);
    }

    public void openAdmin(Player player, int page) {
        List<MinionData> list = new ArrayList<MinionData>(plugin.getRepository().all());
        int pages = Math.max(1, (int) Math.ceil(list.size() / 45.0));
        page = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = Bukkit.createInventory(new AdminListHolder(page), 54,
                title(player, "&0Đệ Tử • Quản Trị &8[&f" + (page + 1) + "&8/&f" + pages + "&8]"));
        int start = page * 45;
        for (int i = start; i < Math.min(start + 45, list.size()); i++) inv.setItem(i - start, adminMinionIcon(list.get(i)));
        if (list.isEmpty()) inv.setItem(22, item(Material.BARRIER, "&c✦ &fKHÔNG CÓ DỮ LIỆU", "", "&7Chưa có Đệ Tử trên server."));
        fillBottomBar(inv);
        inv.setItem(45, item(Material.DIAMOND_ORE, "&b✦ &fKHOÁNG SẢN CHO PHÉP",
                "", "&8• &7Đang cho phép &f" + plugin.getAllowedItemManager().materials(MinionType.MINER).size() + " loại", "", "&e➜ Click để chỉnh sửa"));
        inv.setItem(46, item(Material.WHEAT, "&a✦ &fNÔNG SẢN CHO PHÉP",
                "", "&8• &7Đang cho phép &f" + plugin.getAllowedItemManager().materials(MinionType.FARM).size() + " loại", "", "&e➜ Click để chỉnh sửa"));
        inv.setItem(48, item(Material.ARROW, "&e&l‹ TRANG TRƯỚC"));
        inv.setItem(49, item(Material.COMPASS, "&c✦ &fBẢNG QUẢN TRỊ", "", "&8• &7Tổng Đệ Tử &f" + list.size(), "", "&e➜ Click Đệ Tử để quản lý"));
        inv.setItem(50, item(Material.ARROW, "&e&lTRANG SAU ›"));
        show(player, inv);
    }

    public void openManage(Player player, MinionData d, boolean admin) {
        if (!admin && plugin.getForms().tryManage(player, d)) return; // Bedrock + Floodgate: dùng form
        Inventory inv = Bukkit.createInventory(new ManageHolder(d.getId(), admin), 45,
                title(player, admin ? "&0Đệ Tử • Settings Admin" : "&0Đệ Tử • Settings"));
        decorate(inv);
        inv.setItem(4, admin ? adminMinionIcon(d) : minionIcon(d));

        int slot = 10;
        inv.setItem(slot++, item(Material.CHEST, "&6✦ &fLIÊN KẾT RƯƠNG",
                "", "&8• &7Đầu ra &f" + outputName(d.getOutputMode()),
                "", "&e➜ Click để liên kết"));

        StorageItemStatus es = plugin.getMinionService().getStorageBridge().checkStorage(d.getOwner());
        boolean enabled = d.getOutputMode() == OutputMode.EXTRA_STORAGE;
        inv.setItem(slot++, item(Material.ENDER_CHEST, "&d✦ &fEXTRASTORAGE",
                "", "&8• &7Trạng thái " + (enabled ? "&aBật" : "&cTắt"),
                "&8• &7Kho       " + storageStatus(es), "",
                enabled ? "&e➜ Click để tắt" : "&e➜ Click để bật"));

        ItemStack tool = d.getTool();
        inv.setItem(slot++, item(tool == null ? Material.IRON_PICKAXE : safeIcon(tool.getType()), "&b✦ &fKHO DỤNG CỤ",
                "", "&8• &7Công cụ " + (tool == null ? "&8Chưa trang bị" : "&f" + itemName(tool)),
                "", "&e➜ Click để mở"));

        int max = plugin.getConfig().getInt("levels.max", 3);
        int area = plugin.getMinionService().radius(d) * 2 + 1;
        int nextArea = plugin.getConfig().getInt("levels.radius." + Math.min(max, d.getLevel() + 1), plugin.getMinionService().radius(d)) * 2 + 1;
        String nextSpeed = speedForLevel(Math.min(max, d.getLevel() + 1));
        if (admin) {
            inv.setItem(slot++, item(Material.EXPERIENCE_BOTTLE, "&a✦ &fTĂNG CẤP ADMIN",
                    "", "&8• &7Cấp      &f" + d.getLevel() + "&8/&f" + max,
                    "&8• &7Phạm vi  &b" + area + "x" + area + (d.getLevel() < max ? " &8→ &a" + nextArea + "x" + nextArea : ""),
                    "&8• &7Tốc độ   &a" + plugin.getMinionService().speedDisplay(d) + (d.getLevel() < max ? " &8→ &a" + nextSpeed : ""),
                    "", d.getLevel() >= max ? "&a✔ Đã tối đa" : "&e➜ Click để tăng cấp"));
            inv.setItem(slot++, item(Material.REDSTONE, "&e✦ &fHẠ CẤP ADMIN", "", "&8• &7Giảm 1 cấp", "", "&e➜ Click để hạ cấp"));
            inv.setItem(slot++, item(Material.NETHER_STAR, "&6✦ &fTHU HỒI", "", "&8• &7Đưa Đệ Tử về inventory", "", "&e➜ Click để thu hồi"));
            inv.setItem(slot++, item(Material.REDSTONE_BLOCK, "&4✦ &cXÓA VĨNH VIỄN", "", "&cKhông thể hoàn tác.", "", "&4➜ Click để xóa"));
            inv.setItem(slot++, item(Material.ARROW, "&c‹ &fQUAY LẠI"));
        } else {
            inv.setItem(slot++, item(Material.PAPER, "&6✦ &fNÂNG CẤP",
                    "", "&8• &7Cấp      &f" + d.getLevel() + (d.getLevel() < max ? " &8→ &a" + (d.getLevel() + 1) : ""),
                    "&8• &7Phạm vi  &b" + area + "x" + area + (d.getLevel() < max ? " &8→ &a" + nextArea + "x" + nextArea : ""),
                    "&8• &7Tốc độ   &a" + plugin.getMinionService().speedDisplay(d) + (d.getLevel() < max ? " &8→ &a" + nextSpeed : ""),
                    "", d.getLevel() >= max ? "&a✔ Đã tối đa" : "&e➜ Click để nâng cấp"));
            inv.setItem(slot++, item(Material.NAME_TAG, "&a✦ &fTRADE ĐỆ TỬ",
                    "", "&8• &7Chuyển quyền sở hữu", "", "&e➜ Click để tiếp tục"));
            inv.setItem(slot++, item(Material.NETHER_STAR, "&c✦ &fTHU HỒI",
                    "", "&8• &7Đưa Đệ Tử về inventory", "",
                    d.getState() == MinionState.PLACED ? "&c➜ Click để thu hồi" : "&8Đang ở dạng item"));
            inv.setItem(slot++, item(Material.ARROW, "&c‹ &fQUAY LẠI"));
        }
        inv.setItem(22, offlineCardIcon(d));
        show(player, inv);
    }

    private ItemStack offlineCardIcon(MinionData d) {
        if (!plugin.getOffline().isEnabled()) {
            return item(Material.BARRIER, "&c✦ &fTHẺ OFFLINE", "", "&8Tính năng đang tắt trên server.");
        }
        if (!d.hasOfflineCard()) {
            return item(Material.CLOCK, "&b✦ &fTHẺ OFFLINE",
                    "", "&8• &7Thẻ        &8Chưa gắn",
                    "&8• &7Hiện tại   &7Chỉ làm việc khi bạn online",
                    "", "&e➜ Click để gắn thẻ");
        }
        ItemStack card = d.getOfflineCard();
        int tier = plugin.getOfflineCards().tierOf(card);
        long cap = d.getOfflineCapacityMs();
        long energy = d.getOfflineEnergyMs();
        return item(Material.CLOCK, "&b✦ &fTHẺ OFFLINE",
                "", "&8• &7Bậc          " + plugin.getOfflineCards().tierName(tier),
                "&8• &7Năng lượng  &a" + OfflineCardService.formatDuration(energy) + " &8/ &f" + OfflineCardService.formatDuration(cap),
                "&8• &7Trạng thái   " + (energy <= 0 ? "&cHết năng lượng" : "&aSẵn sàng làm việc offline"),
                "", "&e➜ Click để mở");
    }

    public void openOfflineCard(Player player, MinionData d, boolean admin) {
        if (!plugin.getOffline().isEnabled()) {
            player.sendMessage(plugin.getMinionService().prefix() + Text.color("&cTính năng Thẻ Offline đang tắt."));
            return;
        }
        if (isTradePending(d.getId())) {
            player.sendMessage(plugin.getMinionService().prefix() + Text.color("&cĐệ Tử đang chờ giao dịch, không thể chỉnh thẻ."));
            return;
        }
        UUID viewer = offlineViewers.get(d.getId());
        if (viewer != null && !viewer.equals(player.getUniqueId()) && isOfflineCardOpen(d.getId())) {
            player.sendMessage(plugin.getMinionService().prefix() + Text.color("&cĐang có người khác chỉnh thẻ của Đệ Tử này."));
            return;
        }
        offlineViewers.put(d.getId(), player.getUniqueId());
        Inventory inv = Bukkit.createInventory(new OfflineCardHolder(d.getId(), admin), 27, title(player, "&0Đệ Tử • Thẻ Offline"));
        decorate(inv);
        inv.setItem(12, item(Material.BOOK, "&b✦ &fTHẺ OFFLINE", "", "&8• &7Đặt 1 Thẻ Offline vào ô bên cạnh",
                "&8• &7Chủ nhân online: thẻ tự nạp", "&8• &7Chủ nhân offline: Đệ Tử dùng năng lượng"));
        ItemStack card = d.getOfflineCard();
        inv.setItem(13, card == null ? null : plugin.getOfflineCards().withEnergy(card, d.getOfflineEnergyMs()));
        inv.setItem(14, item(Material.ARROW, "&c‹ &fQUAY LẠI"));
        show(player, inv);
    }

    /** Có ai đang mở GUI Thẻ Offline của Đệ Tử này không (tự xóa khóa cũ nếu người đó đã đóng GUI). */
    public boolean isOfflineCardOpen(UUID minionId) {
        UUID viewer = offlineViewers.get(minionId);
        if (viewer == null) return false;
        Player p = Bukkit.getPlayer(viewer);
        if (p != null) {
            InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder();
            if (h instanceof OfflineCardHolder && ((OfflineCardHolder) h).id.equals(minionId)) return true;
        }
        offlineViewers.remove(minionId);
        return false;
    }

    public void releaseOfflineCard(UUID minionId, UUID viewer) {
        if (viewer.equals(offlineViewers.get(minionId))) offlineViewers.remove(minionId);
    }

    public void openTool(Player player, MinionData d, boolean admin) {
        if (isTradePending(d.getId())) {
            player.sendMessage(plugin.getMinionService().prefix() + Text.color("&cĐệ Tử đang chờ giao dịch, không thể chỉnh công cụ."));
            return;
        }
        UUID viewer = toolViewers.get(d.getId());
        if (viewer != null && !viewer.equals(player.getUniqueId()) && isToolOpen(d.getId())) {
            player.sendMessage(plugin.getMinionService().prefix() + Text.color("&cĐang có người khác chỉnh công cụ của Đệ Tử này."));
            return;
        }
        toolViewers.put(d.getId(), player.getUniqueId());
        Inventory inv = Bukkit.createInventory(new ToolHolder(d.getId(), admin), 27, title(player, "&0Đệ Tử • Kho Công Cụ"));
        decorate(inv);
        inv.setItem(12, item(Material.BOOK, "&b✦ &fDỤNG CỤ", "", "&8• &7Đặt 1 item vào ô bên cạnh"));
        inv.setItem(13, d.getTool() == null ? null : d.getTool().clone());
        inv.setItem(14, item(Material.ARROW, "&c‹ &fQUAY LẠI"));
        show(player, inv);
    }

    public void openUpgrade(Player player, MinionData d) {
        int max = plugin.getConfig().getInt("levels.max", 3);
        Inventory inv = Bukkit.createInventory(new UpgradeHolder(d.getId()), 27, title(player, "&0Đệ Tử • Nâng Cấp"));
        decorate(inv);
        int required = requiredCards(d.getLevel() + 1);
        inv.setItem(11, item(Material.BOOK, "&6✦ &fNÂNG CẤP",
                "", "&8• &7Cấp &f" + d.getLevel() + (d.getLevel() >= max ? " &a(MAX)" : " &8→ &a" + (d.getLevel() + 1)),
                d.getLevel() >= max ? "" : "&8• &7Yêu cầu &6" + required + "x Thẻ Nâng Cấp"));
        inv.setItem(12, item(Material.HOPPER, "&6✦ &fÔ ĐẶT THẺ", "", "&e➜ Kéo thẻ vào ô bên cạnh"));
        // Slot 13 là slot thật để kéo/thả thẻ.
        inv.setItem(13, null);
        inv.setItem(14, item(Material.LIME_DYE, "&a✦ &fXÁC NHẬN",
                "", d.getLevel() >= max ? "&aĐã đạt cấp tối đa" : "&e➜ Click để nâng cấp"));
        inv.setItem(15, item(Material.ARROW, "&c‹ &fQUAY LẠI"));
        show(player, inv);
    }

    public void openAllowedItems(Player admin, MinionType type) {
        Inventory inv = Bukkit.createInventory(new AllowedItemsHolder(type), 54,
                title(admin, type == MinionType.MINER ? "&0Đệ Tử • Khoáng Sản Cho Phép" : "&0Đệ Tử • Nông Sản Cho Phép"));
        int slot = 0;
        for (Material material : plugin.getAllowedItemManager().materials(type)) {
            if (slot >= 45) break;
            Material icon = displayMaterial(type, material);
            inv.setItem(slot++, item(icon, "&a✦ &f" + pretty(material.name()),
                    "", "&8• &7Trạng thái &aĐược phép", "", "&c➜ Click để xóa"));
        }
        fillBottomBar(inv);
        inv.setItem(45, item(Material.BOOK, "&e✦ &fTHÊM VẬT PHẨM", "",
                "&e➜ Click item trong inventory để thêm"));
        inv.setItem(49, item(Material.ARROW, "&c‹ &fQUAY LẠI"));
        show(admin, inv);
    }

    public void openTradeInput(Player sender, MinionData d) {
        if (isTradePending(d.getId())) {
            sender.sendMessage(plugin.getMinionService().prefix() + Text.color("&cĐệ Tử này đang có yêu cầu trade chưa hoàn tất."));
            return;
        }
        if (plugin.getForms().tryTradeInput(sender, d)) return; // Bedrock + Floodgate: form chọn người chơi
        // Bukkit.createInventory(..., ANVIL) thường KHÔNG trả về AnvilInventory thật (không đọc được chữ đã nhập).
        // Nếu server có AnvilInventory hoạt động thì dùng Anvil, ngược lại chuyển sang nhập tên bằng chat.
        Inventory base = null;
        try {
            base = Bukkit.createInventory(new TradeInputHolder(d.getId()), InventoryType.ANVIL, title(sender, "&0Đệ Tử • Nhập Người Nhận"));
        } catch (Throwable ignored) {}
        // Bedrock không nhập chữ được vào Anvil giả => luôn dùng chat.
        if (base instanceof AnvilInventory && !plugin.getBedrock().isBedrock(sender)) {
            ((AnvilInventory) base).setRepairCost(0);
            base.setItem(0, item(Material.PAPER, "&e" + TRADE_PLACEHOLDER, "", "&e➜ Nhập tên rồi click Paper"));
            show(sender, base);
        } else {
            startTradePrompt(sender, d, true);
        }
    }

    public void startTradePrompt(final Player p, MinionData d, boolean announce) {
        final UUID pid = p.getUniqueId();
        final UUID mid = d.getId();
        tradePrompts.put(pid, mid);
        if (announce) {
            p.closeInventory();
            p.sendMessage(plugin.getMinionService().prefix() + Text.color("&eGõ &ftên người chơi nhận Đệ Tử &evào chat &7(gõ &fhuy &7để hủy, 30 giây)&e."));
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() {
                if (mid.equals(tradePrompts.get(pid)) && tradePrompts.remove(pid, mid)) {
                    Player pl = Bukkit.getPlayer(pid);
                    if (pl != null) pl.sendMessage(plugin.getMinionService().prefix() + Text.color("&eĐã hết thời gian nhập tên, trade bị hủy."));
                }
            }
        }, 600L);
    }

    public boolean hasTradePrompt(UUID player) { return tradePrompts.containsKey(player); }
    public UUID takeTradePrompt(UUID player) { return tradePrompts.remove(player); }
    public void clearTradePrompt(UUID player) { tradePrompts.remove(player); }

    public void openTradeConfirm(Player target, Player sender, MinionData d) {
        if (plugin.getForms().tryTradeConfirm(target, sender, d)) return; // Bedrock + Floodgate: dùng form
        Inventory inv = Bukkit.createInventory(new TradeConfirmHolder(d.getId(), sender.getUniqueId()), 27, title(target, "&0Trade Đệ Tử • Xác Nhận"));
        decorate(inv);
        inv.setItem(11, item(Material.RED_WOOL, "&c✦ &fTỪ CHỐI", "", "&c➜ Click để từ chối"));
        inv.setItem(13, tradeIcon(d));
        inv.setItem(15, item(Material.LIME_WOOL, "&a✦ &fXÁC NHẬN", "", "&8• &7Từ &f" + sender.getName(), "", "&a➜ Click để nhận"));
        show(target, inv);
    }

    /** Icon Đệ Tử trong màn xác nhận: thêm công cụ để người nhận biết mình nhận gì. */
    private ItemStack tradeIcon(MinionData d) {
        ItemStack icon = minionIcon(d);
        ItemMeta meta = icon.getItemMeta();
        List<String> lore = meta.getLore() == null ? new ArrayList<String>() : new ArrayList<String>(meta.getLore());
        // bỏ 2 dòng cuối ("" và "Click để quản lý") vì không áp dụng ở màn này
        if (lore.size() >= 2) { lore.remove(lore.size() - 1); lore.remove(lore.size() - 1); }
        ItemStack tool = d.getTool();
        lore.add(Text.color("&8• &7Công cụ  " + (tool == null ? "&8Chưa trang bị" : "&f" + itemName(tool))));
        meta.setLore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    // ------------------------------------------------------------------
    // Khóa Kho Công Cụ
    // ------------------------------------------------------------------

    /** Có ai đang mở Kho Công Cụ của Đệ Tử này không (tự xóa khóa cũ nếu người đó đã đóng GUI). */
    public boolean isToolOpen(UUID minionId) {
        UUID viewer = toolViewers.get(minionId);
        if (viewer == null) return false;
        Player p = Bukkit.getPlayer(viewer);
        if (p != null) {
            InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder();
            if (h instanceof ToolHolder && ((ToolHolder) h).id.equals(minionId)) return true;
        }
        toolViewers.remove(minionId);
        return false;
    }

    public void releaseTool(UUID minionId, UUID viewer) {
        if (viewer.equals(toolViewers.get(minionId))) toolViewers.remove(minionId);
    }

    // ------------------------------------------------------------------
    // Yêu cầu trade đang chờ
    // ------------------------------------------------------------------

    public static final class PendingTrade {
        public final UUID minionId;
        public final UUID sender;
        public final UUID target;
        public final long expiresAt;
        PendingTrade(UUID minionId, UUID sender, UUID target, long expiresAt) {
            this.minionId = minionId;
            this.sender = sender;
            this.target = target;
            this.expiresAt = expiresAt;
        }
    }

    public boolean isTradePending(UUID minionId) { return getPendingTrade(minionId) != null; }

    public PendingTrade getPendingTrade(UUID minionId) {
        PendingTrade t = pendingTrades.get(minionId);
        if (t == null) return null;
        if (System.currentTimeMillis() > t.expiresAt) {
            pendingTrades.remove(minionId);
            return null;
        }
        return t;
    }

    public void clearTrade(UUID minionId) { pendingTrades.remove(minionId); }

    /** Đăng ký yêu cầu trade. Trong lúc chờ: không thể trade tiếp, đổi công cụ, nâng cấp, thu hồi hay đặt Đệ Tử. */
    public PendingTrade beginTrade(MinionData d, Player sender, Player target) {
        long timeoutMs = Math.max(10L, plugin.getConfig().getLong("minion.trade-request-timeout-seconds", 60L)) * 1000L;
        final PendingTrade t = new PendingTrade(d.getId(), sender.getUniqueId(), target.getUniqueId(), System.currentTimeMillis() + timeoutMs);
        pendingTrades.put(d.getId(), t);
        plugin.getServer().getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() {
                if (pendingTrades.get(t.minionId) != t) return; // đã xong hoặc đã bị thay bằng yêu cầu khác
                pendingTrades.remove(t.minionId);
                Player tp = Bukkit.getPlayer(t.target);
                if (tp != null) {
                    InventoryHolder h = tp.getOpenInventory().getTopInventory().getHolder();
                    if (h instanceof TradeConfirmHolder && ((TradeConfirmHolder) h).id.equals(t.minionId)) tp.closeInventory();
                }
                Player sp = Bukkit.getPlayer(t.sender);
                if (sp != null) sp.sendMessage(plugin.getMinionService().prefix() + Text.color("&eYêu cầu trade đã hết hạn."));
            }
        }, Math.max(1L, timeoutMs / 50L));
        return t;
    }

    public static boolean isGuiHolder(InventoryHolder h) {
        return h instanceof PlayerListHolder || h instanceof AdminListHolder || h instanceof ManageHolder
                || h instanceof ToolHolder || h instanceof OfflineCardHolder || h instanceof UpgradeHolder || h instanceof AllowedItemsHolder
                || h instanceof TradeInputHolder || h instanceof TradeConfirmHolder;
    }

    public ItemStack minionIcon(MinionData d) {
        Material mat = d.getState() == MinionState.PLACED ? Material.ARMOR_STAND : Material.NETHER_STAR;
        String owner = Optional.ofNullable(Bukkit.getOfflinePlayer(d.getOwner()).getName()).orElse(d.getOwner().toString().substring(0, 8));
        int area = plugin.getMinionService().radius(d) * 2 + 1;
        ItemStack icon = item(mat,
                d.getType() == MinionType.FARM ? "&a✦ &fĐỆ TỬ NÔNG SẢN &a✦" : "&b✦ &fĐỆ TỬ KHOÁNG SẢN &b✦",
                "",
                "&8• &7Cấp độ   &f" + d.getLevel(),
                "&8• &7Phạm vi  &b" + area + "x" + area,
                "&8• &7Tốc độ   &a" + plugin.getMinionService().speedDisplay(d),
                "&8• &7Đầu ra   " + outputName(d.getOutputMode()),
                "",
                "&e➜ Click để quản lý");
        ItemMeta meta = icon.getItemMeta();
        meta.getPersistentDataContainer().set(guiIdKey, PersistentDataType.STRING, d.getId().toString());
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack adminMinionIcon(MinionData d) {
        ItemStack icon = minionIcon(d);
        ItemMeta meta = icon.getItemMeta();
        List<String> lore = meta.getLore() == null ? new ArrayList<String>() : new ArrayList<String>(meta.getLore());
        lore.add(Text.color("&c⚙ &7Quản trị"));
        meta.setLore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    public UUID readId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(guiIdKey, PersistentDataType.STRING);
        if (raw != null) try { return UUID.fromString(raw); } catch (IllegalArgumentException ignored) {}
        return null;
    }

    public int requiredCards(int targetLevel) {
        return Math.max(1, plugin.getConfig().getInt("upgrade-card.requirements." + targetLevel, 1));
    }

    public ItemStack item(Material material, String name, String... lore) {
        material = safeIcon(material);
        ItemStack i = new ItemStack(material);
        ItemMeta m = i.getItemMeta();
        m.setDisplayName(Text.color(name));
        List<String> l = new ArrayList<String>();
        for (String s : lore) l.add(Text.color(s));
        m.setLore(l);
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_POTION_EFFECTS);
        i.setItemMeta(m);
        return i;
    }

    private Material safeIcon(Material material) {
        if (material == null || material == Material.AIR || !material.isItem()) return Material.PAPER;
        return material;
    }

    private void decorate(Inventory inv) {
        ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private void fillBottomBar(Inventory inv) {
        ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = inv.getSize() - 9; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private int countPlaced(List<MinionData> list) {
        int count = 0;
        for (MinionData d : list) if (d.getState() == MinionState.PLACED) count++;
        return count;
    }

    public String outputName(OutputMode mode) {
        if (mode == OutputMode.CHEST) return "&6Rương/Kho";
        if (mode == OutputMode.EXTRA_STORAGE) return "&dExtraStorage";
        return "&7Thả ra ngoài";
    }

    private String storageStatus(StorageItemStatus status) {
        if (status == StorageItemStatus.ALLOWED) return "&aSẵn sàng";
        if (status == StorageItemStatus.FULL) return "&cKho đầy";
        if (status == StorageItemStatus.STORAGE_DISABLED) return "&cKho đang tắt";
        if (status == StorageItemStatus.USER_UNAVAILABLE) return "&eChưa tải dữ liệu";
        if (status == StorageItemStatus.API_UNAVAILABLE) return "&cAPI không khả dụng";
        return "&eKhông xác định";
    }

    public String itemName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) return ChatColor.stripColor(item.getItemMeta().getDisplayName());
        return pretty(item.getType().name());
    }

    private String pretty(String raw) {
        String[] parts = raw.toLowerCase().split("_");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return out.toString();
    }

    private String speedForLevel(int level) {
        double value = plugin.getConfig().getDouble("levels.speed-multiplier." + level, 1D);
        return String.format(java.util.Locale.US, "%.2fx", value);
    }

    private Material displayMaterial(MinionType type, Material block) {
        if (type == MinionType.MINER) return safeIcon(block);
        String n = block.name();
        try {
            if (n.equals("CARROTS")) return Material.valueOf("CARROT");
            if (n.equals("POTATOES")) return Material.valueOf("POTATO");
            if (n.equals("BEETROOTS")) return Material.valueOf("BEETROOT");
            if (n.equals("COCOA")) return Material.valueOf("COCOA_BEANS");
        } catch (IllegalArgumentException ignored) {}
        return safeIcon(block);
    }

    public void setLinkingChest(Player p, UUID id) { linkingChest.put(p.getUniqueId(), id); }
    public UUID consumeLinkingChest(Player p) { return linkingChest.remove(p.getUniqueId()); }

    public static final class PlayerListHolder implements InventoryHolder { public final int page; public PlayerListHolder(int p){page=p;} public Inventory getInventory(){return null;} }
    public static final class AdminListHolder implements InventoryHolder { public final int page; public AdminListHolder(int p){page=p;} public Inventory getInventory(){return null;} }
    public static final class ManageHolder implements InventoryHolder { public final UUID id; public final boolean admin; public ManageHolder(UUID id, boolean a){this.id=id;admin=a;} public Inventory getInventory(){return null;} }
    public static final class ToolHolder implements InventoryHolder { public final UUID id; public final boolean admin; public ToolHolder(UUID id, boolean a){this.id=id;admin=a;} public Inventory getInventory(){return null;} }
    public static final class OfflineCardHolder implements InventoryHolder { public final UUID id; public final boolean admin; public OfflineCardHolder(UUID id, boolean a){this.id=id;admin=a;} public Inventory getInventory(){return null;} }
    public static final class UpgradeHolder implements InventoryHolder { public final UUID id; public UpgradeHolder(UUID id){this.id=id;} public Inventory getInventory(){return null;} }
    public static final class AllowedItemsHolder implements InventoryHolder { public final MinionType type; public AllowedItemsHolder(MinionType type){this.type=type;} public Inventory getInventory(){return null;} }
    public static final class TradeInputHolder implements InventoryHolder { public final UUID id; public TradeInputHolder(UUID id){this.id=id;} public Inventory getInventory(){return null;} }
    public static final class TradeConfirmHolder implements InventoryHolder { public final UUID id; public final UUID sender; public TradeConfirmHolder(UUID id, UUID s){this.id=id;sender=s;} public Inventory getInventory(){return null;} }
}
