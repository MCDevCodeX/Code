package vn.disciples.minions.bedrock;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import vn.disciples.minions.DiscipleMinionsPlugin;
import vn.disciples.minions.gui.GuiManager;
import vn.disciples.minions.model.MinionData;
import vn.disciples.minions.model.MinionState;
import vn.disciples.minions.model.MinionType;
import vn.disciples.minions.service.OfflineCardService;
import vn.disciples.minions.util.Text;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Gửi Floodgate form cho người chơi Bedrock qua reflection thuần – không cần Floodgate ở compile-time.
 *
 * API mục tiêu (org.geysermc.floodgate:api:2.x):
 *   FloodgateApi.getInstance().sendForm(UUID, Form)
 * Form được xây bằng các builder nội bộ cũng thông qua reflection.
 *
 * Nếu Floodgate không cài / API không tìm thấy, tryXxx() trả về false
 * và GuiManager tự dùng GUI rương hoặc chat prompt thay thế – không crash.
 */
public final class FloodgateForms {
    private final DiscipleMinionsPlugin plugin;
    private volatile Object api;      // FloodgateApi instance, null nếu Floodgate không có
    private volatile boolean checked; // đã kiểm tra rồi, không thử lại

    public FloodgateForms(DiscipleMinionsPlugin plugin) { this.plugin = plugin; }

    // ======================================================
    // TRY* – gọi từ GuiManager, trả về true nếu gửi form
    // ======================================================

    public boolean tryPlayerList(Player p) {
        if (!isBedrock(p)) return false;
        List<MinionData> list = plugin.getRepository().byOwner(p.getUniqueId());
        if (list.isEmpty()) {
            msg(p, "&eChưa có Đệ Tử nào."); return true;
        }
        List<String> buttons = new ArrayList<String>();
        final List<UUID> ids = new ArrayList<UUID>();
        for (MinionData d : list) {
            String state = d.getState() == MinionState.PLACED ? "▶" : "■";
            buttons.add(state + " " + typeLabel(d) + " Lv" + d.getLevel()
                    + "\n" + (d.getState() == MinionState.PLACED ? locStr(d) : "Trong túi đồ"));
            ids.add(d.getId());
        }
        return sendSimpleForm(p, "Đệ Tử Của Bạn", "Chọn Đệ Tử để quản lý:", buttons,
                new Callback<Integer>() {
                    @Override public void run(Player pl, Integer clicked) {
                        if (clicked < 0 || clicked >= ids.size()) return;
                        MinionData d = plugin.getRepository().get(ids.get(clicked));
                        if (d != null) plugin.getGui().openManage(pl, d, false);
                    }
                });
    }

    private static final int A_LINK = 0, A_ES = 1, A_EQUIP_TOOL = 2, A_UNEQUIP_TOOL = 3,
            A_EQUIP_CARD = 4, A_UNEQUIP_CARD = 5, A_UPGRADE = 6, A_TRADE = 7, A_RECLAIM = 8;

    private static void addButton(List<String> labels, List<Integer> actions, String label, int action) {
        labels.add(label);
        actions.add(action);
    }

    public boolean tryManage(Player p, MinionData d) {
        if (!isBedrock(p)) return false;
        boolean placed = d.getState() == MinionState.PLACED;
        final List<String> buttons = new ArrayList<String>();
        final List<Integer> actions = new ArrayList<Integer>();
        addButton(buttons, actions, "Liên Kết Rương\nGắn rương đầu ra", A_LINK);
        addButton(buttons, actions, "ExtraStorage\nBật/tắt kho tự động", A_ES);
        addButton(buttons, actions, "Trang Bị Công Cụ\nDùng vật phẩm cầm tay", A_EQUIP_TOOL);
        addButton(buttons, actions, "Gỡ Công Cụ\nTrả công cụ về túi đồ", A_UNEQUIP_TOOL);
        if (plugin.getOffline().isEnabled()) {
            addButton(buttons, actions, "Gắn Thẻ Offline\nDùng Thẻ cầm trên tay", A_EQUIP_CARD);
            addButton(buttons, actions, "Gỡ Thẻ Offline\nTrả thẻ kèm năng lượng", A_UNEQUIP_CARD);
        }
        addButton(buttons, actions, "Nâng Cấp\nDùng Thẻ trong túi đồ", A_UPGRADE);
        addButton(buttons, actions, "Giao Dịch\nChuyển Đệ Tử cho người khác", A_TRADE);
        if (placed) addButton(buttons, actions, "Thu Hồi\nLấy Đệ Tử về túi đồ", A_RECLAIM);

        String content = "Quản lý Đệ Tử:";
        if (plugin.getOffline().isEnabled()) {
            content += "\nThẻ Offline: " + (d.hasOfflineCard()
                    ? OfflineCardService.formatDuration(d.getOfflineEnergyMs()) + " / "
                    + OfflineCardService.formatDuration(d.getOfflineCapacityMs())
                    : "chưa gắn");
        }
        final UUID mid = d.getId();
        return sendSimpleForm(p, typeLabel(d) + " Lv" + d.getLevel(), content, buttons,
                new Callback<Integer>() {
                    @Override public void run(Player pl, Integer clicked) {
                        MinionData cur = plugin.getRepository().get(mid);
                        if (cur == null || !cur.getOwner().equals(pl.getUniqueId())) return;
                        if (clicked < 0 || clicked >= actions.size()) return;
                        switch (actions.get(clicked)) {
                            case A_LINK: plugin.getActions().startLinkChest(pl, cur); break;
                            case A_ES: plugin.getActions().toggleExtraStorage(pl, cur, false); break;
                            case A_EQUIP_TOOL: plugin.getActions().equipHeldTool(pl, cur); plugin.getGui().openManage(pl, cur, false); break;
                            case A_UNEQUIP_TOOL: plugin.getActions().unequipTool(pl, cur); plugin.getGui().openManage(pl, cur, false); break;
                            case A_EQUIP_CARD: plugin.getActions().equipHeldOfflineCard(pl, cur); plugin.getGui().openManage(pl, cur, false); break;
                            case A_UNEQUIP_CARD: plugin.getActions().unequipOfflineCard(pl, cur); plugin.getGui().openManage(pl, cur, false); break;
                            case A_UPGRADE: plugin.getActions().upgradeFromInventory(pl, cur); plugin.getGui().openManage(pl, cur, false); break;
                            // openTradeInput: thử form, nếu form lỗi thì tự chuyển sang nhập tên bằng chat
                            case A_TRADE: plugin.getGui().openTradeInput(pl, cur); break;
                            case A_RECLAIM: plugin.getActions().reclaim(pl, cur); break;
                            default: break;
                        }
                    }
                });
    }

    public boolean tryTradeInput(Player p, MinionData d) {
        if (!isBedrock(p)) return false;
        if (plugin.getGui().isTradePending(d.getId())) {
            msg(p, "&cĐệ Tử này đang có yêu cầu trade chưa hoàn tất."); return true;
        }
        final UUID mid = d.getId();
        List<String> onlineNames = new ArrayList<String>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(p)) onlineNames.add(online.getName());
        }
        if (onlineNames.isEmpty()) {
            msg(p, "&cKhông có người chơi nào khác online."); return true;
        }
        return sendSimpleForm(p, "Chọn Người Nhận", "Giao Đệ Tử cho:", onlineNames,
                new Callback<Integer>() {
                    @Override public void run(Player pl, Integer clicked) {
                        if (clicked < 0 || clicked >= onlineNames.size()) return;
                        MinionData cur = plugin.getRepository().get(mid);
                        if (cur == null || !cur.getOwner().equals(pl.getUniqueId())) return;
                        plugin.getTrade().submit(pl, cur, onlineNames.get(clicked), false);
                    }
                });
    }

    public boolean tryTradeConfirm(Player target, Player sender, MinionData d) {
        if (!isBedrock(target)) return false;
        final UUID mid = d.getId();
        final UUID senderId = sender.getUniqueId();
        String title = "Nhận Đệ Tử?";
        String body = "Từ: " + sender.getName() + "\n"
                + typeLabel(d) + " Lv" + d.getLevel() + "\n"
                + "Công cụ: " + (d.getTool() == null ? "Chưa trang bị"
                        : plugin.getGui().itemName(d.getTool()));
        List<String> btns = new ArrayList<String>();
        btns.add("✔ Xác Nhận");
        btns.add("✘ Từ Chối");
        return sendSimpleForm(target, title, body, btns,
                new Callback<Integer>() {
                    @Override public void run(Player pl, Integer clicked) {
                        if (clicked == 0) plugin.getTrade().accept(pl, mid, senderId);
                        else plugin.getTrade().decline(pl, mid, senderId);
                    }
                },
                new Runnable() { // onClose không bấm nút nào
                    @Override public void run() {
                        Player live = Bukkit.getPlayer(target.getUniqueId());
                        if (live != null) plugin.getTrade().targetClosed(live, mid);
                    }
                });
    }

    // ======================================================
    // REFLECTION
    // ======================================================

    private boolean isBedrock(Player p) {
        return plugin.getConfig().getBoolean("bedrock.enabled", true) && plugin.getBedrock().isBedrock(p);
    }

    private Object getApi() {
        if (checked) return api;
        checked = true;
        try {
            org.bukkit.plugin.Plugin fg = Bukkit.getPluginManager().getPlugin("floodgate");
            if (fg == null || !fg.isEnabled()) throw new ClassNotFoundException("floodgate plugin chưa cài/chưa bật");
            Class<?> cls = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, fg.getClass().getClassLoader());
            api = cls.getMethod("getInstance").invoke(null);
            plugin.getLogger().info("Floodgate API tìm thấy – form Bedrock đã bật.");
        } catch (Throwable ex) {
            plugin.getLogger().info("Floodgate không khả dụng – Bedrock dùng GUI rương/chat. (" + ex.getClass().getSimpleName() + ")");
        }
        return api;
    }

    private boolean sendSimpleForm(Player p, String title, String body, List<String> buttons, Callback<Integer> cb) {
        return sendSimpleForm(p, title, body, buttons, cb, null);
    }

    /**
     * Xây SimpleForm (Cumulus 1.1+) bằng reflection rồi gửi qua FloodgateApi.sendForm(UUID, Form).
     * Lỗi bất kỳ => tắt form vĩnh viễn (log 1 lần) và trả về false để dùng GUI rương/chat.
     */
    private boolean sendSimpleForm(final Player p, String title, String body, List<String> buttons,
                                   final Callback<Integer> onResponse, final Runnable onClose) {
        Object apiInst = getApi();
        if (apiInst == null) return false;
        try {
            // Đang mở GUI rương (ví dụ nút Quay lại): đóng trước, Bedrock không hiện form đè lên container.
            if (p.getOpenInventory() != null && p.getOpenInventory().getTopInventory().getHolder() != null
                    && GuiManager.isGuiHolder(p.getOpenInventory().getTopInventory().getHolder())) {
                p.closeInventory();
            }
            ClassLoader cl = apiInst.getClass().getClassLoader();
            Class<?> sfCls = Class.forName("org.geysermc.cumulus.form.SimpleForm", true, cl);
            Object builder = sfCls.getMethod("builder").invoke(null);
            callMethod(builder, "title", title);
            callMethod(builder, "content", body);
            for (String lbl : buttons) callMethod(builder, "button", lbl);

            Consumer<Object> valid = new Consumer<Object>() {
                @Override public void accept(Object resp) {
                    final int idx = intResult(resp, "clickedButtonId", "getClickedButtonId");
                    if (idx < 0) return;
                    runSync(new Runnable() {
                        @Override public void run() {
                            Player live = Bukkit.getPlayer(p.getUniqueId());
                            if (live != null && onResponse != null) onResponse.run(live, idx);
                        }
                    });
                }
            };
            callMethod(builder, "validResultHandler", valid);

            if (onClose != null) {
                Runnable closed = new Runnable() {
                    @Override public void run() {
                        runSync(new Runnable() { @Override public void run() { onClose.run(); } });
                    }
                };
                try {
                    callMethod(builder, "closedOrInvalidResultHandler", closed);
                } catch (NoSuchMethodException ex) {
                    try { callMethod(builder, "closedResultHandler", closed); }
                    catch (NoSuchMethodException ignored) {}
                }
            }

            Object form = callMethod(builder, "build");
            sendForm(apiInst, p.getUniqueId(), form);
            return true;
        } catch (Throwable ex) {
            disable(ex);
            return false;
        }
    }

    private void sendForm(Object apiInst, UUID uuid, Object form) throws Exception {
        for (Method m : apiInst.getClass().getMethods()) {
            Class<?>[] pt = m.getParameterTypes();
            if (m.getName().equals("sendForm") && pt.length == 2 && pt[0] == UUID.class && pt[1].isInstance(form)) {
                m.setAccessible(true);
                m.invoke(apiInst, uuid, form);
                return;
            }
        }
        throw new NoSuchMethodException("FloodgateApi#sendForm(UUID, Form)");
    }

    /** Tìm method public theo tên + số tham số + kiểu đối số (kể cả class impl không public). */
    private static Object callMethod(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
            Class<?>[] pt = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < pt.length; i++) {
                if (args[i] != null && !pt[i].isInstance(args[i])) { ok = false; break; }
            }
            if (!ok) continue;
            m.setAccessible(true);
            return m.invoke(target, args);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
    }

    private static int intResult(Object resp, String... methodNames) {
        for (String n : methodNames) {
            try {
                Object r = callMethod(resp, n);
                if (r instanceof Number) return ((Number) r).intValue();
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    private void disable(Throwable ex) {
        api = null;
        checked = true;
        plugin.getLogger().log(Level.WARNING, "Floodgate form không dùng được với phiên bản hiện tại ("
                + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                + "). Bedrock sẽ dùng GUI rương/chat thay thế.");
    }

    private void runSync(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else plugin.getServer().getScheduler().runTask(plugin, r);
    }

    private static String typeLabel(MinionData d) {
        return d.getType() == MinionType.FARM ? "Nông Sản" : "Khoáng Sản";
    }

    private static String locStr(MinionData d) {
        return "(" + d.getBlockX() + "," + d.getBlockY() + "," + d.getBlockZ() + ") " + d.getWorldName();
    }

    private void msg(Player p, String text) {
        p.sendMessage(plugin.getMinionService().prefix() + Text.color(text));
    }

    @FunctionalInterface
    interface Callback<T> { void run(Player player, T value); }
}
