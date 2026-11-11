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
import vn.disciples.minions.util.Compat;
import vn.disciples.minions.util.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** GUI Đệ Tử 0.7: nút chức năng liền nhau, nâng cấp bằng thẻ, settings Admin riêng. */
public final class GuiManager {
    private final DiscipleMinionsPlugin plugin;
    private final NamespacedKey guiIdKey;
    private final Map<UUID, UUID> linkingChest = new HashMap<UUID, UUID>();
    /** Đệ Tử -> người đang mở Kho Công Cụ (chặn 2 người cùng mở => nhân đôi công cụ). */
    private final Map<UUID, UUID> toolViewers = new HashMap<UUID, UUID>();
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
            int skip = (h instanceof ToolHolder || h instanceof UpgradeHolder) ? 13 : -1;
            plugin.getBedrock().cleanInventory(inv, skip);
        }
        viewer.openInventory(inv);
    }

    // ======================================================================
    // Nút cấu hình qua gui.yml
    // ======================================================================

    private static Map<String, String> ph(String... kv) { return GuiConfig.ph(kv); }

    private String t(String key) { return plugin.getGuiConfig().text(key); }

    private String t(String key, Map<String, String> ph) { return plugin.getGuiConfig().text(key, ph); }

    /**
     * Tạo nút Java từ gui.yml (buttons.&lt;id&gt;.java.*). autoMaterial dùng khi material = AUTO.
     * ph: biến thay vào tên/lore, ví dụ {level}.
     */
    public ItemStack button(String id, Material autoMaterial, Map<String, String> ph) {
        GuiConfig g = plugin.getGuiConfig();
        List<String> raw = g.javaLore(id);
        String[] lore = new String[raw.size()];
        for (int i = 0; i < lore.length; i++) lore[i] = GuiConfig.apply(raw.get(i), ph);
        ItemStack icon = item(g.material(id, autoMaterial), GuiConfig.apply(g.javaName(id), ph), lore);
        int cmd = g.customModelData(id);
        boolean glow = g.glow(id);
        if (cmd > 0 || glow) {
            ItemMeta meta = icon.getItemMeta();
            if (cmd > 0) meta.setCustomModelData(cmd);
            if (glow) Compat.glow(meta);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    public ItemStack button(String id, Material autoMaterial) { return button(id, autoMaterial, null); }

    public void openPlayer(Player player, int page) {
        if (plugin.getForms().tryPlayerList(player)) return; // Bedrock + Floodgate: dùng form
        List<MinionData> list = plugin.getRepository().byOwner(player.getUniqueId());
        int pages = Math.max(1, (int) Math.ceil(list.size() / 45.0));
        page = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = Bukkit.createInventory(new PlayerListHolder(page), 54,
                title(player, "&0Đệ Tử • Cá Nhân &8[&f" + (page + 1) + "&8/&f" + pages + "&8]"));
        int start = page * 45;
        for (int i = start; i < Math.min(start + 45, list.size()); i++) inv.setItem(i - start, minionIcon(list.get(i)));
        if (list.isEmpty()) inv.setItem(22, button("player.empty", Material.BARRIER));
        fillBottomBar(inv);
        inv.setItem(48, button("nav.prev", Material.ARROW));
        int max = plugin.getMinionService().maxOwnedPerPlayer();
        int placed = countPlaced(list);
        inv.setItem(49, button("player.summary", Material.NETHER_STAR, ph(
                "owned", String.valueOf(list.size()),
                "max", max < 0 ? "&a∞" : "&f" + max,
                "placed", String.valueOf(placed),
                "item_count", String.valueOf(list.size() - placed))));
        inv.setItem(50, button("nav.next", Material.ARROW));
        show(player, inv);
    }

    public void openAdmin(Player player, int page) {
        if (plugin.getForms().tryAdminList(player, page)) return; // Bedrock + Floodgate: dùng form
        List<MinionData> list = new ArrayList<MinionData>(plugin.getRepository().all());
        int pages = Math.max(1, (int) Math.ceil(list.size() / 45.0));
        page = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = Bukkit.createInventory(new AdminListHolder(page), 54,
                title(player, "&0Đệ Tử • Quản Trị &8[&f" + (page + 1) + "&8/&f" + pages + "&8]"));
        int start = page * 45;
        for (int i = start; i < Math.min(start + 45, list.size()); i++) inv.setItem(i - start, adminMinionIcon(list.get(i)));
        if (list.isEmpty()) inv.setItem(22, button("admin.empty", Material.BARRIER));
        fillBottomBar(inv);
        inv.setItem(45, button("admin.allowed-miner", Material.DIAMOND_ORE,
                ph("count", String.valueOf(plugin.getAllowedItemManager().materials(MinionType.MINER).size()))));
        inv.setItem(46, button("admin.allowed-farm", Material.WHEAT,
                ph("count", String.valueOf(plugin.getAllowedItemManager().materials(MinionType.FARM).size()))));
        inv.setItem(48, button("nav.prev", Material.ARROW));
        inv.setItem(49, button("admin.summary", Material.COMPASS, ph("total", String.valueOf(list.size()))));
        inv.setItem(50, button("nav.next", Material.ARROW));
        show(player, inv);
    }

    public void openManage(Player player, MinionData d, boolean admin) {
        if (plugin.getForms().tryManage(player, d, admin)) return; // Bedrock + Floodgate: dùng form (cả bản admin)
        Inventory inv = Bukkit.createInventory(new ManageHolder(d.getId(), admin), 45,
                title(player, admin ? "&0Đệ Tử • Settings Admin" : "&0Đệ Tử • Settings"));
        decorate(inv);
        inv.setItem(4, admin ? adminMinionIcon(d) : minionIcon(d));

        int slot = 10;
        inv.setItem(slot++, button("manage.link-chest", Material.CHEST, ph("output", outputName(d.getOutputMode()))));

        StorageItemStatus es = plugin.getMinionService().getStorageBridge().checkStorage(d.getOwner());
        boolean enabled = d.getOutputMode() == OutputMode.EXTRA_STORAGE;
        inv.setItem(slot++, button("manage.extra-storage", Material.ENDER_CHEST, ph(
                "state", t(enabled ? "state-on" : "state-off"),
                "storage", storageStatus(es),
                "action", t(enabled ? "action-disable" : "action-enable"))));

        ItemStack tool = d.getTool();
        inv.setItem(slot++, button("manage.tool", tool == null ? Material.IRON_PICKAXE : safeIcon(tool.getType()),
                ph("tool", tool == null ? t("no-tool") : "&f" + itemName(tool))));

        int max = plugin.getConfig().getInt("levels.max", 3);
        boolean atMax = d.getLevel() >= max;
        int area = plugin.getMinionService().radius(d) * 2 + 1;
        int nextArea = plugin.getConfig().getInt("levels.radius." + Math.min(max, d.getLevel() + 1), plugin.getMinionService().radius(d)) * 2 + 1;
        String nextSpeed = speedForLevel(Math.min(max, d.getLevel() + 1));
        String arrow = t("next-arrow");
        Map<String, String> levelPh = ph(
                "level", String.valueOf(d.getLevel()),
                "level_next", atMax ? "" : arrow + (d.getLevel() + 1),
                "max", String.valueOf(max),
                "area", area + "x" + area,
                "area_next", atMax ? "" : arrow + nextArea + "x" + nextArea,
                "speed", plugin.getMinionService().speedDisplay(d),
                "speed_next", atMax ? "" : arrow + nextSpeed,
                "action", atMax ? t("max-reached") : t(admin ? "action-level-up" : "action-upgrade"));
        if (admin) {
            inv.setItem(slot++, button("manage.level-up", Material.EXPERIENCE_BOTTLE, levelPh));
            inv.setItem(slot++, button("manage.level-down", Material.REDSTONE));
            inv.setItem(slot++, button("manage.reclaim-admin", Material.NETHER_STAR));
            inv.setItem(slot++, button("manage.delete", Material.REDSTONE_BLOCK));
            inv.setItem(slot++, button("manage.back-admin", Material.ARROW));
        } else {
            inv.setItem(slot++, button("manage.upgrade", Material.PAPER, levelPh));
            inv.setItem(slot++, button("manage.trade", Material.NAME_TAG));
            inv.setItem(slot++, button("manage.reclaim", Material.NETHER_STAR, ph(
                    "action", t(d.getState() == MinionState.PLACED ? "action-reclaim" : "reclaim-unavailable"))));
            inv.setItem(slot++, button("manage.back", Material.ARROW));
        }
        show(player, inv);
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
        inv.setItem(12, button("tool.info", Material.BOOK));
        inv.setItem(13, d.getTool() == null ? null : d.getTool().clone());
        inv.setItem(14, button("tool.back", Material.ARROW));
        show(player, inv);
    }

    public void openUpgrade(Player player, MinionData d) {
        int max = plugin.getConfig().getInt("levels.max", 3);
        boolean atMax = d.getLevel() >= max;
        Inventory inv = Bukkit.createInventory(new UpgradeHolder(d.getId()), 27, title(player, "&0Đệ Tử • Nâng Cấp"));
        decorate(inv);
        int required = requiredCards(d.getLevel() + 1);
        inv.setItem(11, button("upgrade.info", Material.BOOK, ph(
                "level", String.valueOf(d.getLevel()),
                "level_status", atMax ? t("level-max-tag") : t("next-arrow") + (d.getLevel() + 1),
                "requirement", atMax ? "" : t("upgrade-requirement", ph("count", String.valueOf(required))))));
        inv.setItem(12, button("upgrade.slot", Material.HOPPER));
        // Slot 13 là slot thật để kéo/thả thẻ.
        inv.setItem(13, null);
        inv.setItem(14, button("upgrade.confirm", Material.LIME_DYE,
                ph("action", t(atMax ? "upgrade-max-reached" : "action-upgrade"))));
        inv.setItem(15, button("upgrade.back", Material.ARROW));
        show(player, inv);
    }

    public void openAllowedItems(Player admin, MinionType type) {
        if (plugin.getForms().tryAllowedItems(admin, type)) return; // Bedrock + Floodgate: dùng form
        Inventory inv = Bukkit.createInventory(new AllowedItemsHolder(type), 54,
                title(admin, type == MinionType.MINER ? "&0Đệ Tử • Khoáng Sản Cho Phép" : "&0Đệ Tử • Nông Sản Cho Phép"));
        int slot = 0;
        for (Material material : plugin.getAllowedItemManager().materials(type)) {
            if (slot >= 45) break;
            inv.setItem(slot++, button("allowed.item", displayMaterial(type, material),
                    ph("item", pretty(material.name()), "material", material.name())));
        }
        fillBottomBar(inv);
        inv.setItem(45, button("allowed.add", Material.BOOK));
        inv.setItem(49, button("allowed.back", Material.ARROW));
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
            base.setItem(0, button("trade.input", Material.PAPER));
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
        inv.setItem(11, button("trade.reject", Material.RED_WOOL));
        inv.setItem(13, tradeIcon(d));
        inv.setItem(15, button("trade.accept", Material.LIME_WOOL, ph("sender", sender.getName())));
        show(target, inv);
    }

    /** Icon Đệ Tử trong màn xác nhận: thêm công cụ để người nhận biết mình nhận gì. */
    private ItemStack tradeIcon(MinionData d) {
        ItemStack icon = minionIcon(d, ""); // bỏ dòng "Click để quản lý" vì không áp dụng ở màn này
        ItemMeta meta = icon.getItemMeta();
        List<String> lore = meta.getLore() == null ? new ArrayList<String>() : new ArrayList<String>(meta.getLore());
        while (!lore.isEmpty() && ChatColor.stripColor(lore.get(lore.size() - 1)).trim().isEmpty()) lore.remove(lore.size() - 1);
        ItemStack tool = d.getTool();
        lore.add(Text.color(t("trade-tool-line", ph("tool", tool == null ? t("no-tool") : "&f" + itemName(tool)))));
        meta.setLore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    public boolean isTradePlaceholder(String typed) {
        if (typed == null) return false;
        String configured = ChatColor.stripColor(Text.color(plugin.getGuiConfig().javaName("trade.input")));
        return typed.equalsIgnoreCase(TRADE_PLACEHOLDER) || typed.equalsIgnoreCase(configured);
    }

    public ItemStack minionIcon(MinionData d) {
        return minionIcon(d, t("minion-footer"));
    }

    private ItemStack minionIcon(MinionData d, String footer) {
        Material mat = d.getState() == MinionState.PLACED ? Material.ARMOR_STAND : Material.NETHER_STAR;
        String owner = d.displayOwnerName();
        int area = plugin.getMinionService().radius(d) * 2 + 1;
        ItemStack icon = button(d.getType() == MinionType.FARM ? "minion.farm" : "minion.miner", mat, ph(
                "level", String.valueOf(d.getLevel()),
                "area", area + "x" + area,
                "speed", plugin.getMinionService().speedDisplay(d),
                "output", outputName(d.getOutputMode()),
                "owner", owner,
                "footer", footer));
        ItemMeta meta = icon.getItemMeta();
        meta.getPersistentDataContainer().set(guiIdKey, PersistentDataType.STRING, d.getId().toString());
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack adminMinionIcon(MinionData d) {
        ItemStack icon = minionIcon(d);
        ItemMeta meta = icon.getItemMeta();
        List<String> lore = meta.getLore() == null ? new ArrayList<String>() : new ArrayList<String>(meta.getLore());
        lore.add(Text.color(t("admin-line")));
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
                || h instanceof ToolHolder || h instanceof UpgradeHolder || h instanceof AllowedItemsHolder
                || h instanceof TradeInputHolder || h instanceof TradeConfirmHolder;
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
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        Compat.addFlags(m, Compat.hideAdditionalTooltip());
        i.setItemMeta(m);
        return i;
    }

    private Material safeIcon(Material material) {
        if (material == null || material == Material.AIR || !material.isItem()) return Material.PAPER;
        return material;
    }

    private void decorate(Inventory inv) {
        ItemStack pane = button("filler", Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private void fillBottomBar(Inventory inv) {
        ItemStack pane = button("filler", Material.BLACK_STAINED_GLASS_PANE);
        for (int i = inv.getSize() - 9; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private int countPlaced(List<MinionData> list) {
        int count = 0;
        for (MinionData d : list) if (d.getState() == MinionState.PLACED) count++;
        return count;
    }

    public String outputName(OutputMode mode) {
        if (mode == OutputMode.CHEST) return t("output-chest");
        if (mode == OutputMode.EXTRA_STORAGE) return t("output-extra-storage");
        return t("output-none");
    }

    private String storageStatus(StorageItemStatus status) {
        if (status == StorageItemStatus.ALLOWED) return t("storage-allowed");
        if (status == StorageItemStatus.FULL) return t("storage-full");
        if (status == StorageItemStatus.STORAGE_DISABLED) return t("storage-disabled");
        if (status == StorageItemStatus.USER_UNAVAILABLE) return t("storage-user-unavailable");
        if (status == StorageItemStatus.API_UNAVAILABLE) return t("storage-api-unavailable");
        return t("storage-unknown");
    }

    public String itemName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) return ChatColor.stripColor(item.getItemMeta().getDisplayName());
        return pretty(item.getType().name());
    }

    public String pretty(String raw) {
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

    /**
     * Dọn toàn bộ trạng thái tạm của người chơi khi họ thoát.
     * Trước đây chỉ xoá prompt trade: nếu thoát giữa lúc "chọn rương", lần sau right-click block bất kỳ
     * sẽ liên kết nhầm rương cho Đệ Tử.
     */
    public void clearPlayerState(UUID player) {
        linkingChest.remove(player);
        tradePrompts.remove(player);
        Iterator<Map.Entry<UUID, UUID>> it = toolViewers.entrySet().iterator();
        while (it.hasNext()) {
            if (player.equals(it.next().getValue())) it.remove();
        }
    }

    public static final class PlayerListHolder implements InventoryHolder { public final int page; public PlayerListHolder(int p){page=p;} public Inventory getInventory(){return null;} }
    public static final class AdminListHolder implements InventoryHolder { public final int page; public AdminListHolder(int p){page=p;} public Inventory getInventory(){return null;} }
    public static final class ManageHolder implements InventoryHolder { public final UUID id; public final boolean admin; public ManageHolder(UUID id, boolean a){this.id=id;admin=a;} public Inventory getInventory(){return null;} }
    public static final class ToolHolder implements InventoryHolder { public final UUID id; public final boolean admin; public ToolHolder(UUID id, boolean a){this.id=id;admin=a;} public Inventory getInventory(){return null;} }
    public static final class UpgradeHolder implements InventoryHolder { public final UUID id; public UpgradeHolder(UUID id){this.id=id;} public Inventory getInventory(){return null;} }
    public static final class AllowedItemsHolder implements InventoryHolder { public final MinionType type; public AllowedItemsHolder(MinionType type){this.type=type;} public Inventory getInventory(){return null;} }
    public static final class TradeInputHolder implements InventoryHolder { public final UUID id; public TradeInputHolder(UUID id){this.id=id;} public Inventory getInventory(){return null;} }
    public static final class TradeConfirmHolder implements InventoryHolder { public final UUID id; public final UUID sender; public TradeConfirmHolder(UUID id, UUID s){this.id=id;sender=s;} public Inventory getInventory(){return null;} }
}
