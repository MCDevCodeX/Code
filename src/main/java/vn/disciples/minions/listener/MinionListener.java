package vn.disciples.minions.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import vn.disciples.minions.DiscipleMinionsPlugin;
import vn.disciples.minions.gui.GuiManager;
import vn.disciples.minions.model.MinionData;
import vn.disciples.minions.model.MinionState;
import vn.disciples.minions.model.MinionType;
import vn.disciples.minions.util.Text;

import java.util.Map;
import java.util.UUID;

public final class MinionListener implements Listener {
    private final DiscipleMinionsPlugin plugin;

    public MinionListener(DiscipleMinionsPlugin plugin) { this.plugin = plugin; }

    private void msg(Player p, String text) {
        p.sendMessage(plugin.getMinionService().prefix() + Text.color(text));
    }

    // ======================================================================
    // ĐẶT ĐỆ TỬ / LIÊN KẾT RƯƠNG
    // ======================================================================

    /**
     * ignoreCancelled = true: nếu plugin bảo vệ (claim, spawn...) đã chặn thao tác thì không đặt Đệ Tử
     * và không liên kết rương trong vùng người chơi không có quyền.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        EquipmentSlot hand = e.getHand();
        if (hand == null) return;

        if (hand == EquipmentSlot.HAND) {
            UUID linking = plugin.getGui().consumeLinkingChest(p);
            if (linking != null) {
                e.setCancelled(true);
                MinionData d = plugin.getRepository().get(linking);
                if (d == null || (!d.getOwner().equals(p.getUniqueId()) && !p.hasPermission("detu.admin"))) return;
                if (!(e.getClickedBlock().getState() instanceof Container)) {
                    msg(p, "&cBlock này không phải rương/kho hợp lệ.");
                    return;
                }
                d.linkChest(e.getClickedBlock().getLocation());
                plugin.getRepository().save();
                msg(p, "&aĐã liên kết rương/kho thành công.");
                plugin.getGui().openManage(p, d, p.hasPermission("detu.admin") && !d.getOwner().equals(p.getUniqueId()));
                return;
            }
        }

        ItemStack held = e.getItem();
        UUID id = plugin.getItemService().getId(held);
        if (id == null) return;
        e.setCancelled(true);
        MinionData d = plugin.getRepository().get(id);
        if (d == null || plugin.getRepository().isRevoked(id)) {
            msg(p, "&cID Đệ Tử này không còn hợp lệ.");
            removeOneFromHand(p, hand, id);
            return;
        }
        if (!d.getOwner().equals(p.getUniqueId())) {
            msg(p, "&cĐệ Tử này không thuộc về bạn.");
            return;
        }
        if (d.getState() == MinionState.PLACED) {
            msg(p, "&cĐệ Tử này đang được đặt ở nơi khác.");
            return;
        }
        if (plugin.getGui().isTradePending(d.getId())) {
            msg(p, "&cĐệ Tử này đang chờ giao dịch, chưa thể đặt.");
            return;
        }
        if (plugin.getMinionService().place(p, d, e.getClickedBlock().getRelative(e.getBlockFace()).getLocation())) {
            removeOneFromHand(p, hand, id);
            msg(p, "&aĐã đặt Đệ Tử. &8ID: &f" + d.getId());
        }
    }

    /** Trừ 1 item ở ĐÚNG tay đã dùng (bản cũ luôn xóa tay chính => mất item + nhân đôi khi dùng tay phụ). */
    private void removeOneFromHand(Player p, EquipmentSlot slot, UUID expectedId) {
        PlayerInventory inv = p.getInventory();
        boolean off = slot == EquipmentSlot.OFF_HAND;
        ItemStack cur = off ? inv.getItemInOffHand() : inv.getItemInMainHand();
        if (cur == null || cur.getType() == Material.AIR) return;
        if (!expectedId.equals(plugin.getItemService().getId(cur))) return;
        ItemStack next = null;
        if (cur.getAmount() > 1) {
            next = cur.clone();
            next.setAmount(cur.getAmount() - 1);
        }
        if (off) inv.setItemInOffHand(next);
        else inv.setItemInMainHand(next);
    }

    @EventHandler
    public void onDiscipleClick(PlayerInteractAtEntityEvent e) {
        MinionData d = plugin.getMinionService().fromEntity(e.getRightClicked());
        if (d == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return; // sự kiện tay phụ: chỉ chặn, không mở GUI lần 2
        Player p = e.getPlayer();
        boolean admin = p.hasPermission("detu.admin");
        if (!d.getOwner().equals(p.getUniqueId()) && !admin) {
            msg(p, "&cĐệ Tử này không thuộc về bạn.");
            return;
        }
        plugin.getGui().openManage(p, d, admin && !d.getOwner().equals(p.getUniqueId()));
    }

    @EventHandler
    public void onManipulate(PlayerArmorStandManipulateEvent e) {
        if (plugin.getMinionService().isMinionEntity(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (plugin.getMinionService().isMinionEntity(e.getEntity())) e.setCancelled(true);
    }

    // ======================================================================
    // CHUNK / WORLD: visual không được lưu vào chunk nên phải tạo lại khi chunk load
    // ======================================================================

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) { plugin.getMinionService().onChunkLoad(e.getChunk()); }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) { plugin.getMinionService().onChunkUnload(e.getChunk()); }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) { plugin.getMinionService().onWorldLoad(e.getWorld()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.getGui().clearTradePrompt(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.getOffline().onJoin(e.getPlayer());
    }

    /** Thẻ Nâng Cấp hoặc Thẻ Offline: không được đổi tên/đưa vào bàn chế tạo, không đặt vào ô công cụ. */
    private boolean isProtectedCard(ItemStack item) {
        return plugin.getUpgradeCardService().isCard(item) || plugin.getOfflineCards().isCard(item);
    }

    // ======================================================================
    // BẢO VỆ THẺ NÂNG CẤP KHỎI ANVIL / CRAFTING...
    // ======================================================================

    private boolean isCardGuardedType(InventoryType type) {
        return type == InventoryType.ANVIL || type == InventoryType.GRINDSTONE || type == InventoryType.SMITHING
                || type == InventoryType.WORKBENCH || type == InventoryType.CRAFTING || type == InventoryType.ENCHANTING;
    }

    /** Item sẽ được đặt vào ô đang click (con trỏ, phím số hoặc phím đổi tay phụ). */
    private ItemStack incomingItem(InventoryClickEvent e, Player p) {
        ClickType c = e.getClick();
        if (c == ClickType.NUMBER_KEY) {
            int b = e.getHotbarButton();
            return b >= 0 ? p.getInventory().getItem(b) : null;
        }
        if ("SWAP_OFFHAND".equals(c.name())) return p.getInventory().getItemInOffHand();
        return e.getCursor();
    }

    /**
     * Chỉ chặn khi Thẻ Nâng Cấp sắp được đưa VÀO ô chế tạo/anvil/... Bản cũ chặn mọi click liên quan thẻ
     * trong cả túi đồ của người chơi (inventory thường có type CRAFTING) nên không thể di chuyển thẻ.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onProtectedCardEdit(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (GuiManager.isGuiHolder(top.getHolder())) return; // GUI của plugin tự xử lý
        InventoryType type = top.getType();
        if (!isCardGuardedType(type) || !(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        int raw = e.getRawSlot();
        boolean cardIntoTop = false;
        if (raw >= 0 && raw < top.getSize()) {
            cardIntoTop = isProtectedCard(incomingItem(e, p));
        } else if (raw >= top.getSize() && e.isShiftClick() && type != InventoryType.CRAFTING) {
            // Shift-click trong túi đồ có thể đẩy thẻ vào ô chế tạo/anvil (riêng túi cá nhân thì không).
            cardIntoTop = isProtectedCard(e.getCurrentItem());
        }
        if (cardIntoTop) {
            e.setCancelled(true);
            msg(p, "&cThẻ Nâng Cấp Đệ Tử không thể đổi tên hoặc chỉnh sửa.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onProtectedCardDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (GuiManager.isGuiHolder(top.getHolder())) return;
        if (!isCardGuardedType(top.getType()) || !isProtectedCard(e.getOldCursor())) return;
        for (int raw : e.getRawSlots()) {
            if (raw < top.getSize()) {
                e.setCancelled(true);
                if (e.getWhoClicked() instanceof Player) msg((Player) e.getWhoClicked(), "&cThẻ Nâng Cấp Đệ Tử không thể đổi tên hoặc chỉnh sửa.");
                return;
            }
        }
    }

    // ======================================================================
    // GUI CỦA PLUGIN
    // ======================================================================

    @EventHandler
    public void onGui(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        Inventory top = e.getView().getTopInventory();
        InventoryHolder h = top.getHolder();
        if (h == null || !GuiManager.isGuiHolder(h)) return;

        if (h instanceof GuiManager.ToolHolder) {
            handleToolClick(e, p, top, (GuiManager.ToolHolder) h);
            return;
        }
        if (h instanceof GuiManager.UpgradeHolder) {
            handleUpgradeClick(e, p, top, (GuiManager.UpgradeHolder) h);
            return;
        }
        if (h instanceof GuiManager.OfflineCardHolder) {
            handleOfflineCardClick(e, p, top, (GuiManager.OfflineCardHolder) h);
            return;
        }

        // Mọi GUI còn lại chỉ để bấm nút: chặn mọi thao tác di chuyển item.
        e.setCancelled(true);

        if (h instanceof GuiManager.AllowedItemsHolder) {
            if (!p.hasPermission("detu.admin")) return;
            GuiManager.AllowedItemsHolder ah = (GuiManager.AllowedItemsHolder) h;
            if (e.getRawSlot() == 49) { plugin.getGui().openAdmin(p, 0); return; }

            if (e.getRawSlot() >= 0 && e.getRawSlot() < 45) {
                ItemStack current = e.getCurrentItem();
                Material mapped = plugin.getAllowedItemManager().mapInventoryItem(ah.type, current == null ? null : current.getType());
                if (mapped != null && plugin.getAllowedItemManager().remove(ah.type, mapped)) {
                    msg(p, "&cĐã xóa &f" + mapped.name() + " &ckhỏi danh sách cho phép.");
                    plugin.getGui().openAllowedItems(p, ah.type);
                }
                return;
            }

            if (e.getRawSlot() >= top.getSize()) {
                ItemStack current = e.getCurrentItem();
                Material mapped = plugin.getAllowedItemManager().mapInventoryItem(ah.type, current == null ? null : current.getType());
                if (mapped == null) {
                    msg(p, ah.type == MinionType.FARM
                            ? "&cVật phẩm này không phải nông sản có thể thu hoạch."
                            : "&cVật phẩm này không phải khoáng sản/ore hợp lệ.");
                    return;
                }
                boolean wasPresent = plugin.getAllowedItemManager().contains(ah.type, mapped);
                plugin.getAllowedItemManager().toggleFromItem(ah.type, current);
                msg(p, wasPresent
                        ? "&cĐã xóa &f" + mapped.name() + " &ckhỏi danh sách cho phép."
                        : "&aĐã thêm &f" + mapped.name() + " &avào danh sách cho phép.");
                plugin.getGui().openAllowedItems(p, ah.type);
            }
            return;
        }

        if (h instanceof GuiManager.PlayerListHolder) {
            GuiManager.PlayerListHolder ph = (GuiManager.PlayerListHolder) h;
            if (e.getRawSlot() == 48) { plugin.getGui().openPlayer(p, ph.page - 1); return; }
            if (e.getRawSlot() == 50) { plugin.getGui().openPlayer(p, ph.page + 1); return; }
            UUID id = plugin.getGui().readId(e.getCurrentItem());
            MinionData d = id == null ? null : plugin.getRepository().get(id);
            if (d != null && d.getOwner().equals(p.getUniqueId())) plugin.getGui().openManage(p, d, false);
            return;
        }

        if (h instanceof GuiManager.AdminListHolder) {
            if (!p.hasPermission("detu.admin")) return;
            GuiManager.AdminListHolder ah = (GuiManager.AdminListHolder) h;
            if (e.getRawSlot() == 45) { plugin.getGui().openAllowedItems(p, MinionType.MINER); return; }
            if (e.getRawSlot() == 46) { plugin.getGui().openAllowedItems(p, MinionType.FARM); return; }
            if (e.getRawSlot() == 48) { plugin.getGui().openAdmin(p, ah.page - 1); return; }
            if (e.getRawSlot() == 50) { plugin.getGui().openAdmin(p, ah.page + 1); return; }
            UUID id = plugin.getGui().readId(e.getCurrentItem());
            MinionData d = id == null ? null : plugin.getRepository().get(id);
            if (d != null) plugin.getGui().openManage(p, d, true);
            return;
        }

        if (h instanceof GuiManager.ManageHolder) {
            handleManageClick(e, p, (GuiManager.ManageHolder) h);
            return;
        }

        if (h instanceof GuiManager.TradeInputHolder) {
            GuiManager.TradeInputHolder th = (GuiManager.TradeInputHolder) h;
            MinionData d = plugin.getRepository().get(th.id);
            if (d == null || !d.getOwner().equals(p.getUniqueId())) { p.closeInventory(); return; }
            if (e.getRawSlot() != 2) return;
            String targetName = top instanceof AnvilInventory ? ((AnvilInventory) top).getRenameText() : null;
            if (targetName == null || targetName.trim().isEmpty()) {
                msg(p, "&cHãy nhập tên người chơi hợp lệ.");
                return;
            }
            submitTrade(p, d, targetName, true);
            return;
        }

        if (h instanceof GuiManager.TradeConfirmHolder) {
            handleTradeConfirmClick(e, p, (GuiManager.TradeConfirmHolder) h);
        }
    }

    // ---------------------------------------------------------------- Kho công cụ

    private void handleToolClick(InventoryClickEvent e, Player p, Inventory top, GuiManager.ToolHolder th) {
        int raw = e.getRawSlot();
        if (raw < 0) return; // click ra ngoài cửa sổ (thả item)
        if (raw >= top.getSize()) {
            // Túi đồ người chơi: chặn shift-click và gom item để không đẩy nhầm vào GUI.
            if (e.isShiftClick() || e.getClick() == ClickType.DOUBLE_CLICK) e.setCancelled(true);
            return;
        }
        if (raw != 13) {
            e.setCancelled(true);
            if (raw == 14) {
                MinionData d = plugin.getRepository().get(th.id);
                if (d != null) plugin.getGui().openManage(p, d, th.admin);
            }
            return;
        }
        if (e.isShiftClick()) { e.setCancelled(true); return; }
        ItemStack incoming = incomingItem(e, p);
        if (incoming != null && incoming.getType() != Material.AIR && isForbiddenTool(incoming)) {
            e.setCancelled(true);
            msg(p, "&cKhông thể đặt vật phẩm Đệ Tử hoặc Thẻ Nâng Cấp vào ô công cụ.");
        }
    }

    // ---------------------------------------------------------------- Thẻ Offline

    private void handleOfflineCardClick(InventoryClickEvent e, Player p, Inventory top, GuiManager.OfflineCardHolder oh) {
        int raw = e.getRawSlot();
        if (raw < 0) return; // click ra ngoài cửa sổ
        if (raw >= top.getSize()) {
            if (e.isShiftClick() || e.getClick() == ClickType.DOUBLE_CLICK) e.setCancelled(true);
            return;
        }
        if (raw != 13) {
            e.setCancelled(true);
            if (raw == 14) {
                MinionData d = plugin.getRepository().get(oh.id);
                if (d != null) plugin.getGui().openManage(p, d, oh.admin);
            }
            return;
        }
        if (e.isShiftClick()) { e.setCancelled(true); return; }
        ItemStack incoming = incomingItem(e, p);
        if (incoming != null && incoming.getType() != Material.AIR && !plugin.getOfflineCards().isCard(incoming)) {
            e.setCancelled(true);
            msg(p, "&cÔ này chỉ nhận Thẻ Offline Đệ Tử.");
        }
    }

    private boolean isForbiddenTool(ItemStack item) {
        return plugin.getItemService().getId(item) != null || isProtectedCard(item);
    }

    // ---------------------------------------------------------------- Nâng cấp

    private void handleUpgradeClick(InventoryClickEvent e, Player p, Inventory top, GuiManager.UpgradeHolder uh) {
        MinionData d = plugin.getRepository().get(uh.id);
        if (d == null || !d.getOwner().equals(p.getUniqueId())) { e.setCancelled(true); p.closeInventory(); return; }

        int raw = e.getRawSlot();
        if (raw < 0) return; // click ra ngoài cửa sổ

        if (raw == 13) {
            // Ô thẻ thật. Chỉ nhận Thẻ Nâng Cấp nguyên bản (kể cả khi đặt bằng phím số / phím đổi tay phụ).
            ItemStack incoming = incomingItem(e, p);
            if (incoming != null && incoming.getType() != Material.AIR && !plugin.getUpgradeCardService().isPristineCard(incoming)) {
                e.setCancelled(true);
                msg(p, "&cÔ này chỉ nhận Thẻ Nâng Cấp Đệ Tử hợp lệ.");
            }
            return;
        }

        if (raw >= top.getSize()) {
            // Túi đồ người chơi: cho phép nhặt/thả thẻ bình thường (bản cũ chặn cả click thường nên chỉ shift-click mới dùng được).
            if (e.getClick() == ClickType.DOUBLE_CLICK) { e.setCancelled(true); return; }
            if (!e.isShiftClick()) return;
            e.setCancelled(true);
            ItemStack clicked = e.getCurrentItem();
            if (!plugin.getUpgradeCardService().isPristineCard(clicked)) return;
            ItemStack current = top.getItem(13);
            if (current != null && current.getType() != Material.AIR) {
                msg(p, "&eÔ thẻ đang có vật phẩm. Hãy lấy ra trước.");
                return;
            }
            top.setItem(13, clicked.clone());
            e.setCurrentItem(null);
            return;
        }

        e.setCancelled(true);
        if (raw == 14) {
            if (plugin.getGui().isTradePending(d.getId())) {
                msg(p, "&cĐệ Tử đang chờ giao dịch, không thể nâng cấp.");
                return;
            }
            int max = plugin.getConfig().getInt("levels.max", 3);
            if (d.getLevel() >= max) {
                msg(p, "&eĐệ Tử đã đạt cấp tối đa.");
                return;
            }
            ItemStack cards = top.getItem(13);
            int required = plugin.getGui().requiredCards(d.getLevel() + 1);
            if (!plugin.getUpgradeCardService().isPristineCard(cards) || cards.getAmount() < required) {
                msg(p, "&cBạn cần đặt đủ &f" + required + " &cThẻ Nâng Cấp hợp lệ vào ô nâng cấp.");
                return;
            }
            if (cards.getAmount() == required) top.setItem(13, null);
            else { cards.setAmount(cards.getAmount() - required); top.setItem(13, cards); }
            d.setLevel(d.getLevel() + 1);
            plugin.getRepository().flush(); // quan trọng: thẻ đã bị tiêu
            plugin.getMinionService().refreshVisual(d);
            msg(p, "&aNâng cấp thành công! &7Đệ Tử hiện cấp &f" + d.getLevel() + "&7.");
            plugin.getGui().openUpgrade(p, d);
        } else if (raw == 15) {
            plugin.getGui().openManage(p, d, false);
        }
    }

    // ---------------------------------------------------------------- Settings

    private void handleManageClick(InventoryClickEvent e, Player p, GuiManager.ManageHolder mh) {
        MinionData d = plugin.getRepository().get(mh.id);
        if (d == null) { p.closeInventory(); return; }
        if (!d.getOwner().equals(p.getUniqueId()) && !p.hasPermission("detu.admin")) return;

        int slot = e.getRawSlot();
        if (slot == 22) { plugin.getGui().openOfflineCard(p, d, mh.admin); return; }
        if (slot == 10) { plugin.getActions().startLinkChest(p, d); return; }
        if (slot == 11) { plugin.getActions().toggleExtraStorage(p, d, mh.admin); return; }
        if (slot == 12) { plugin.getGui().openTool(p, d, mh.admin); return; }
        if (slot == 13) {
            int max = plugin.getConfig().getInt("levels.max", 3);
            if (d.getLevel() >= max) {
                msg(p, "&eĐệ Tử đã đạt cấp tối đa.");
            } else if (mh.admin && p.hasPermission("detu.admin")) {
                d.setLevel(d.getLevel() + 1);
                plugin.getRepository().save();
                plugin.getMinionService().refreshVisual(d);
                msg(p, "&aAdmin đã tăng Đệ Tử lên cấp &f" + d.getLevel() + "&a, không tốn thẻ.");
                plugin.getGui().openManage(p, d, true);
            } else if (plugin.getGui().isTradePending(d.getId())) {
                msg(p, "&cĐệ Tử đang chờ giao dịch, không thể nâng cấp.");
            } else {
                plugin.getGui().openUpgrade(p, d);
            }
            return;
        }

        if (mh.admin) {
            if (slot == 14 && p.hasPermission("detu.admin")) {
                if (d.getLevel() > 1) {
                    d.setLevel(d.getLevel() - 1);
                    plugin.getRepository().save();
                    plugin.getMinionService().refreshVisual(d);
                    msg(p, "&eAdmin đã hạ Đệ Tử xuống cấp &f" + d.getLevel() + "&e.");
                }
                plugin.getGui().openManage(p, d, true);
                return;
            }
            if (slot == 15) {
                if (d.getOwner().equals(p.getUniqueId())) reclaim(p, d);
                else msg(p, "&cAdmin chỉ có thể thu hồi trực tiếp Đệ Tử của chính mình.");
                return;
            }
            if (slot == 16 && p.hasPermission("detu.admin")) {
                plugin.getGui().clearTrade(d.getId());
                plugin.getMinionService().delete(d);
                p.closeInventory();
                msg(p, "&cĐã xóa vĩnh viễn Đệ Tử ID &f" + d.getId());
                return;
            }
            if (slot == 17) { plugin.getGui().openAdmin(p, 0); return; }
        } else {
            if (slot == 14) { plugin.getGui().openTradeInput(p, d); return; }
            if (slot == 15) { reclaim(p, d); return; }
            if (slot == 16) { plugin.getGui().openPlayer(p, 0); return; }
        }
    }

    private void reclaim(Player p, MinionData d) { plugin.getActions().reclaim(p, d); }

    // ---------------------------------------------------------------- Trade

    /**
     * Dùng chung cho Anvil và chat. Trả về true nếu đã gửi yêu cầu thành công.
     */
    private boolean submitTrade(final Player p, MinionData d, String rawName, boolean closeAnvil) {
        return plugin.getTrade().submit(p, d, rawName, closeAnvil);
    }

    private void handleTradeConfirmClick(InventoryClickEvent e, Player p, GuiManager.TradeConfirmHolder th) {
        int slot = e.getRawSlot();
        if (slot != 15 && slot != 11) return;
        if (slot == 11) {
            plugin.getTrade().decline(p, th.id, th.sender); return;
        }

        plugin.getTrade().accept(p, th.id, th.sender);
    }

    // ======================================================================
    // CHAT (nhập tên người nhận khi Anvil không dùng được)
    // ======================================================================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        final Player p = e.getPlayer();
        // Lấy prompt NGUYÊN TỬ ngay tại đây: tin nhắn thứ 2 gửi liền sau sẽ không bị nuốt/xử lý trùng.
        final UUID minionId = plugin.getGui().takeTradePrompt(p.getUniqueId());
        if (minionId == null) return;
        e.setCancelled(true);
        final String text = e.getMessage().trim();
        plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() { handleTradePrompt(p, minionId, text); }
        });
    }

    private void handleTradePrompt(Player p, UUID minionId, String text) {
        String lower = text.toLowerCase();
        if (lower.equals("huy") || lower.equals("hủy") || lower.equals("cancel")) {
            msg(p, "&eĐã hủy trade.");
            return;
        }
        MinionData d = plugin.getRepository().get(minionId);
        if (d == null || !d.getOwner().equals(p.getUniqueId())) {
            msg(p, "&cĐệ Tử không còn hợp lệ.");
            return;
        }
        if (!submitTrade(p, d, text, false)) {
            msg(p, "&7Gõ lại tên người chơi, hoặc gõ &fhuy &7để hủy.");
            plugin.getGui().startTradePrompt(p, d, false);
        }
    }

    // ======================================================================
    // DRAG
    // ======================================================================

    /** Bản cũ chỉ chặn drag ở GUI nâng cấp nên có thể kéo item vào các GUI khác và bị mất. */
    @EventHandler
    public void onGuiDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder h = top.getHolder();
        if (h == null || !GuiManager.isGuiHolder(h)) return;
        int topSize = top.getSize();
        boolean touchesTop = false;
        boolean onlySlot13 = true;
        for (int raw : e.getRawSlots()) {
            if (raw < topSize) {
                touchesTop = true;
                if (raw != 13) onlySlot13 = false;
            }
        }
        if (!touchesTop) return;

        Player p = e.getWhoClicked() instanceof Player ? (Player) e.getWhoClicked() : null;
        if (h instanceof GuiManager.UpgradeHolder) {
            if (!onlySlot13 || !plugin.getUpgradeCardService().isPristineCard(e.getOldCursor())) {
                e.setCancelled(true);
                if (p != null) msg(p, "&cChỉ có thể kéo Thẻ Nâng Cấp hợp lệ vào ô này.");
            }
        } else if (h instanceof GuiManager.OfflineCardHolder) {
            if (!onlySlot13 || !plugin.getOfflineCards().isCard(e.getOldCursor())) e.setCancelled(true);
        } else if (h instanceof GuiManager.ToolHolder) {
            ItemStack cursor = e.getOldCursor();
            if (!onlySlot13 || (cursor != null && isForbiddenTool(cursor))) e.setCancelled(true);
        } else {
            e.setCancelled(true);
        }
    }

    // ======================================================================
    // ĐÓNG GUI
    // ======================================================================

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        InventoryHolder h = inv.getHolder();
        Player player = e.getPlayer() instanceof Player ? (Player) e.getPlayer() : null;

        if (h instanceof GuiManager.ToolHolder) {
            GuiManager.ToolHolder th = (GuiManager.ToolHolder) h;
            plugin.getGui().releaseTool(th.id, e.getPlayer().getUniqueId());
            ItemStack tool = inv.getItem(13);
            inv.setItem(13, null);
            if (tool != null && tool.getType() == Material.AIR) tool = null;

            MinionData d = plugin.getRepository().get(th.id);
            if (d == null) { // Đệ Tử đã bị xóa khi GUI đang mở: trả công cụ lại cho người chơi
                if (tool != null && player != null) giveOrDrop(player, tool);
                return;
            }
            boolean allowed = player != null
                    && (d.getOwner().equals(player.getUniqueId()) || player.hasPermission("detu.admin"));
            if (!allowed) { // không còn quyền (ví dụ đã chuyển chủ): không ghi đè, chỉ trả item lạ
                if (tool != null && player != null && !tool.isSimilar(d.getTool())) giveOrDrop(player, tool);
                return;
            }
            if (tool != null && isForbiddenTool(tool)) {
                if (player != null) giveOrDrop(player, tool);
                tool = null;
            }
            if (tool != null && tool.getAmount() > 1) {
                ItemStack extra = tool.clone();
                extra.setAmount(tool.getAmount() - 1);
                tool = tool.clone();
                tool.setAmount(1);
                if (player != null) giveOrDrop(player, extra);
            }
            d.setTool(tool);
            plugin.getRepository().save();
            plugin.getMinionService().refreshVisual(d);
            return;
        }
        if (h instanceof GuiManager.OfflineCardHolder) {
            GuiManager.OfflineCardHolder oh = (GuiManager.OfflineCardHolder) h;
            plugin.getGui().releaseOfflineCard(oh.id, e.getPlayer().getUniqueId());
            ItemStack inSlot = inv.getItem(13);
            inv.setItem(13, null);
            if (inSlot != null && inSlot.getType() == Material.AIR) inSlot = null;

            MinionData d = plugin.getRepository().get(oh.id);
            if (d == null) { // Đệ Tử đã bị xóa khi GUI đang mở: trả thẻ lại cho người chơi
                if (inSlot != null && player != null) giveOrDrop(player, inSlot);
                return;
            }
            boolean allowed = player != null
                    && (d.getOwner().equals(player.getUniqueId()) || player.hasPermission("detu.admin"));
            if (!allowed) { // không còn quyền (ví dụ đã chuyển chủ): không ghi đè, chỉ trả thẻ lạ
                if (inSlot != null && player != null && !plugin.getOfflineCards().sameCard(inSlot, d.getOfflineCard())) {
                    giveOrDrop(player, inSlot);
                }
                return;
            }
            plugin.getActions().applyOfflineCardSlot(player, d, inSlot);
            return;
        }
        if (h instanceof GuiManager.UpgradeHolder) {
            ItemStack cards = inv.getItem(13);
            if (cards != null && cards.getType() != Material.AIR && player != null) {
                inv.setItem(13, null);
                giveOrDrop(player, cards);
            }
            return;
        }
        if (h instanceof GuiManager.TradeInputHolder) {
            // Item trong Anvil chỉ để trang trí: xóa để server không trả lại cho người chơi (tạo item vô hạn).
            inv.clear();
            return;
        }
        if (h instanceof GuiManager.TradeConfirmHolder) {
            GuiManager.TradeConfirmHolder th = (GuiManager.TradeConfirmHolder) h;
            GuiManager.PendingTrade t = plugin.getGui().getPendingTrade(th.id);
            if (t != null && player != null && t.target.equals(player.getUniqueId())) {
                plugin.getGui().clearTrade(th.id);
                Player sender = plugin.getServer().getPlayer(t.sender);
                if (sender != null) msg(sender, "&e" + player.getName() + " đã đóng giao dịch, trade bị hủy.");
            }
        }
    }

    // ======================================================================
    // TIỆN ÍCH
    // ======================================================================

    private boolean hasMinionItem(Player player, UUID id) {
        return plugin.getTrade().hasMinionItem(player, id);
    }
    private boolean takeMinionItem(Player player, UUID id) {
        return plugin.getTrade().takeMinionItem(player, id);
    }

    private void giveOrDrop(Player player, ItemStack item) {
        vn.disciples.minions.util.ItemUtil.giveOrDrop(player, item);
    }
}
