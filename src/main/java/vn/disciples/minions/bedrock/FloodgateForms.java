package vn.disciples.minions.bedrock;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import vn.disciples.minions.DiscipleMinionsPlugin;
import vn.disciples.minions.gui.GuiConfig;
import vn.disciples.minions.gui.GuiManager;
import vn.disciples.minions.model.MinionData;
import vn.disciples.minions.model.MinionState;
import vn.disciples.minions.model.MinionType;
import vn.disciples.minions.util.Text;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private volatile boolean imagesSupported = true;

    public FloodgateForms(DiscipleMinionsPlugin plugin) { this.plugin = plugin; }

    // ======================================================
    // TRY* – gọi từ GuiManager, trả về true nếu gửi form
    // ======================================================

    // ------------------------------------------------------------------
    // Nút lấy từ gui.yml (buttons.<id>.bedrock.*): tên, mô tả, ảnh
    // ------------------------------------------------------------------

    private FormButton btn(String id, Map<String, String> ph) { return plugin.getGuiConfig().bedrock(id, ph); }

    private FormButton btn(String id) { return btn(id, null); }

    private String t(String key) { return plugin.getGuiConfig().text(key); }

    private String typeLabel(MinionData d) { return t(d.getType() == MinionType.FARM ? "type-farm" : "type-miner"); }

    private String stateLabel(MinionData d) { return t(d.getState() == MinionState.PLACED ? "state-placed" : "state-item"); }

    private String placeLabel(MinionData d) {
        if (d.getState() != MinionState.PLACED) return t("place-item");
        return GuiConfig.apply(t("place-location"), GuiConfig.ph("x", String.valueOf(d.getBlockX()),
                "y", String.valueOf(d.getBlockY()), "z", String.valueOf(d.getBlockZ()), "world", String.valueOf(d.getWorldName())));
    }

    private Map<String, String> minionPh(MinionData d) {
        String owner = d.displayOwnerName();
        return GuiConfig.ph("state", stateLabel(d), "type", typeLabel(d), "level", String.valueOf(d.getLevel()),
                "place", placeLabel(d), "owner", owner);
    }

    private static Map<String, String> materialPh(Material m, GuiManager gui) {
        return GuiConfig.ph("item", gui.pretty(m.name()), "material", m.name(), "material_lower", m.name().toLowerCase());
    }

    public boolean tryPlayerList(Player p) {
        if (!isBedrock(p)) return false;
        List<MinionData> list = plugin.getRepository().byOwner(p.getUniqueId());
        if (list.isEmpty()) {
            msg(p, "&eChưa có Đệ Tử nào."); return true;
        }
        List<FormButton> buttons = new ArrayList<FormButton>();
        final List<UUID> ids = new ArrayList<UUID>();
        for (MinionData d : list) {
            buttons.add(btn(d.getType() == MinionType.FARM ? "minion.farm" : "minion.miner", minionPh(d)));
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

    // ------------------------------------------------------------------
    // QUẢN LÝ ĐỆ TỬ (chủ nhân + admin)
    // ------------------------------------------------------------------

    private static final int A_LINK = 0, A_ES = 1, A_TOOL = 2, A_UPGRADE = 3, A_TRADE = 4, A_RECLAIM = 5, A_LIST = 6,
            A_LEVEL_UP = 7, A_LEVEL_DOWN = 8, A_DELETE = 9, A_BACK_ADMIN = 10;

    private static void addButton(List<FormButton> buttons, List<Integer> actions, FormButton button, int action) {
        buttons.add(button);
        actions.add(action);
    }

    /** Form quản lý Đệ Tử: bản chủ nhân (admin=false) hoặc bản admin (admin=true). */
    public boolean tryManage(Player p, MinionData d, boolean admin) {
        if (!isBedrock(p)) return false;
        if (admin && !p.hasPermission("detu.admin")) return false;
        final boolean placed = d.getState() == MinionState.PLACED;
        final int max = plugin.getConfig().getInt("levels.max", 3);
        final List<FormButton> buttons = new ArrayList<FormButton>();
        final List<Integer> actions = new ArrayList<Integer>();
        addButton(buttons, actions, btn("manage.link-chest"), A_LINK);
        addButton(buttons, actions, btn("manage.extra-storage"), A_ES);
        // Kho Dụng Cụ: mở GUI rương giống PC (đặt/lấy công cụ bằng ô slot).
        addButton(buttons, actions, btn("manage.tool"), A_TOOL);
        if (admin) {
            addButton(buttons, actions, btn("manage.level-up"), A_LEVEL_UP);
            addButton(buttons, actions, btn("manage.level-down"), A_LEVEL_DOWN);
            addButton(buttons, actions, btn("manage.reclaim-admin"), A_RECLAIM);
            addButton(buttons, actions, btn("manage.delete"), A_DELETE);
            addButton(buttons, actions, btn("manage.back-admin"), A_BACK_ADMIN);
        } else {
            addButton(buttons, actions, btn("manage.upgrade"), A_UPGRADE);
            addButton(buttons, actions, btn("manage.trade"), A_TRADE);
            if (placed) addButton(buttons, actions, btn("manage.reclaim"), A_RECLAIM);
            addButton(buttons, actions, btn("manage.back"), A_LIST);
        }

        int area = plugin.getMinionService().radius(d) * 2 + 1;
        String content = (admin ? "[ADMIN] " : "") + typeLabel(d) + " - Cấp " + d.getLevel() + "/" + max
                + "\nPhạm vi: " + area + "x" + area
                + "\nTốc độ: " + plugin.getMinionService().speedDisplay(d)
                + "\nĐầu ra: " + Text.color(plugin.getGui().outputName(d.getOutputMode()))
                + "\nCông cụ: " + (d.getTool() == null ? Text.color(t("no-tool")) : plugin.getGui().itemName(d.getTool()))
                + "\nTrạng thái: " + (placed ? t("state-placed") + " " + placeLabel(d) : t("place-item"));
        final UUID mid = d.getId();
        final boolean adminMode = admin;
        return sendSimpleForm(p, typeLabel(d) + " Lv" + d.getLevel(), content, buttons,
                new Callback<Integer>() {
                    @Override public void run(Player pl, Integer clicked) {
                        MinionData cur = plugin.getRepository().get(mid);
                        if (cur == null) return;
                        boolean isOwner = cur.getOwner().equals(pl.getUniqueId());
                        if (adminMode ? !pl.hasPermission("detu.admin") : !isOwner) return;
                        if (clicked < 0 || clicked >= actions.size()) return;
                        int maxLevel = plugin.getConfig().getInt("levels.max", 3);
                        switch (actions.get(clicked)) {
                            case A_LINK: plugin.getActions().startLinkChest(pl, cur); break;
                            case A_ES: plugin.getActions().toggleExtraStorage(pl, cur, adminMode); break;
                            case A_TOOL: plugin.getGui().openTool(pl, cur, adminMode); break;
                            case A_UPGRADE:
                                plugin.getActions().upgradeFromInventory(pl, cur);
                                plugin.getGui().openManage(pl, cur, false);
                                break;
                            case A_TRADE: plugin.getGui().openTradeInput(pl, cur); break;
                            case A_RECLAIM:
                                if (isOwner) plugin.getActions().reclaim(pl, cur);
                                else msg(pl, "&cAdmin chỉ có thể thu hồi trực tiếp Đệ Tử của chính mình.");
                                break;
                            case A_LIST: plugin.getGui().openPlayer(pl, 0); break;
                            case A_LEVEL_UP:
                                if (cur.getLevel() >= maxLevel) msg(pl, "&eĐệ Tử đã đạt cấp tối đa.");
                                else {
                                    cur.setLevel(cur.getLevel() + 1);
                                    plugin.getRepository().save();
                                    plugin.getMinionService().refreshVisual(cur);
                                    msg(pl, "&aAdmin đã tăng Đệ Tử lên cấp &f" + cur.getLevel() + "&a, không tốn thẻ.");
                                }
                                plugin.getGui().openManage(pl, cur, true);
                                break;
                            case A_LEVEL_DOWN:
                                if (cur.getLevel() > 1) {
                                    cur.setLevel(cur.getLevel() - 1);
                                    plugin.getRepository().save();
                                    plugin.getMinionService().refreshVisual(cur);
                                    msg(pl, "&eAdmin đã hạ Đệ Tử xuống cấp &f" + cur.getLevel() + "&e.");
                                }
                                plugin.getGui().openManage(pl, cur, true);
                                break;
                            case A_DELETE: confirmDelete(pl, cur); break;
                            case A_BACK_ADMIN: plugin.getGui().openAdmin(pl, 0); break;
                            default: break;
                        }
                    }
                });
    }

    /** Xác nhận trước khi xóa vĩnh viễn Đệ Tử (admin). */
    private void confirmDelete(Player p, MinionData d) {
        final UUID mid = d.getId();
        List<FormButton> buttons = new ArrayList<FormButton>();
        buttons.add(btn("confirm.delete"));
        buttons.add(btn("confirm.cancel"));
        boolean sent = sendSimpleForm(p, "Xóa Đệ Tử?", "Xóa " + typeLabel(d) + " Lv" + d.getLevel()
                + " (ID " + mid.toString().substring(0, 8) + ").\nKhông thể hoàn tác!", buttons,
                new Callback<Integer>() {
                    @Override public void run(Player pl, Integer clicked) {
                        if (!pl.hasPermission("detu.admin")) return;
                        MinionData cur = plugin.getRepository().get(mid);
                        if (cur == null) return;
                        if (clicked == 0) {
                            plugin.getGui().clearTrade(mid);
                            plugin.getMinionService().delete(cur);
                            msg(pl, "&cĐã xóa vĩnh viễn Đệ Tử ID &f" + mid);
                            plugin.getGui().openAdmin(pl, 0);
                        } else {
                            plugin.getGui().openManage(pl, cur, true);
                        }
                    }
                });
        if (!sent) {
            // Form không gửi được: không xóa, báo để dùng GUI rương.
            msg(p, "&cKhông mở được form xác nhận, hãy thử lại.");
        }
    }

    // ------------------------------------------------------------------
    // ADMIN: danh sách Đệ Tử toàn server + danh sách cho phép
    // ------------------------------------------------------------------

    private interface Act { void run(Player pl); }

    public boolean tryAdminList(Player p, int page) {
        if (!isBedrock(p) || !p.hasPermission("detu.admin")) return false;
        final int perPage = 20;
        final List<MinionData> all = new ArrayList<MinionData>(plugin.getRepository().all());
        final int pages = Math.max(1, (int) Math.ceil(all.size() / (double) perPage));
        final int cur = Math.max(0, Math.min(page, pages - 1));

        final List<FormButton> buttons = new ArrayList<FormButton>();
        final List<Act> acts = new ArrayList<Act>();
        buttons.add(btn("admin.allowed-miner", GuiConfig.ph("count", String.valueOf(plugin.getAllowedItemManager().materials(MinionType.MINER).size()))));
        acts.add(new Act() { @Override public void run(Player pl) { plugin.getGui().openAllowedItems(pl, MinionType.MINER); } });
        buttons.add(btn("admin.allowed-farm", GuiConfig.ph("count", String.valueOf(plugin.getAllowedItemManager().materials(MinionType.FARM).size()))));
        acts.add(new Act() { @Override public void run(Player pl) { plugin.getGui().openAllowedItems(pl, MinionType.FARM); } });

        for (int i = cur * perPage; i < Math.min(all.size(), (cur + 1) * perPage); i++) {
            MinionData d = all.get(i);
            final UUID mid = d.getId();
            buttons.add(btn(d.getType() == MinionType.FARM ? "minion.admin-farm" : "minion.admin-miner", minionPh(d)));
            acts.add(new Act() {
                @Override public void run(Player pl) {
                    MinionData target = plugin.getRepository().get(mid);
                    if (target != null) plugin.getGui().openManage(pl, target, true);
                }
            });
        }
        if (cur > 0) {
            buttons.add(btn("nav.prev"));
            acts.add(new Act() { @Override public void run(Player pl) { plugin.getGui().openAdmin(pl, cur - 1); } });
        }
        if (cur < pages - 1) {
            buttons.add(btn("nav.next"));
            acts.add(new Act() { @Override public void run(Player pl) { plugin.getGui().openAdmin(pl, cur + 1); } });
        }
        return sendSimpleForm(p, "Quản Trị Đệ Tử [" + (cur + 1) + "/" + pages + "]",
                "Tổng Đệ Tử: " + all.size() + "\nChọn Đệ Tử để quản lý:", buttons,
                new Callback<Integer>() {
                    @Override public void run(Player pl, Integer clicked) {
                        if (!pl.hasPermission("detu.admin")) return;
                        if (clicked >= 0 && clicked < acts.size()) acts.get(clicked).run(pl);
                    }
                });
    }

    /** Danh sách vật phẩm cho phép (Khoáng Sản / Nông Sản): bấm để xóa, hoặc thêm từ danh sách gợi ý. */
    public boolean tryAllowedItems(Player p, final MinionType type) {
        if (!isBedrock(p) || !p.hasPermission("detu.admin")) return false;
        final List<Material> allowed = plugin.getAllowedItemManager().materials(type);
        List<FormButton> buttons = new ArrayList<FormButton>();
        buttons.add(btn("allowed.add"));
        for (Material m : allowed) buttons.add(btn("allowed.item", materialPh(m, plugin.getGui())));
        buttons.add(btn("allowed.back"));
        String name = t(type == MinionType.MINER ? "type-miner" : "type-farm");
        return sendSimpleForm(p, name + " Cho Phép", "Đang cho phép " + allowed.size() + " loại. Bấm một mục để xóa.", buttons,
                new Callback<Integer>() {
                    @Override public void run(Player pl, Integer clicked) {
                        if (!pl.hasPermission("detu.admin")) return;
                        if (clicked == 0) { tryAddAllowed(pl, type); return; }
                        if (clicked == allowed.size() + 1) { plugin.getGui().openAdmin(pl, 0); return; }
                        int idx = clicked - 1;
                        if (idx >= 0 && idx < allowed.size()) {
                            Material m = allowed.get(idx);
                            if (plugin.getAllowedItemManager().remove(type, m)) {
                                msg(pl, "&cĐã xóa &f" + m.name() + " &ckhỏi danh sách cho phép.");
                            }
                        }
                        plugin.getGui().openAllowedItems(pl, type);
                    }
                });
    }

    private void tryAddAllowed(Player p, final MinionType type) {
        final List<Material> candidates = plugin.getAllowedItemManager().candidates(type);
        if (candidates.isEmpty()) {
            msg(p, "&eKhông còn vật phẩm nào để thêm.");
            plugin.getGui().openAllowedItems(p, type);
            return;
        }
        List<FormButton> buttons = new ArrayList<FormButton>();
        for (Material m : candidates) buttons.add(btn("allowed.candidate", materialPh(m, plugin.getGui())));
        buttons.add(btn("allowed.back"));
        boolean sent = sendSimpleForm(p, "Thêm Vật Phẩm", "Chọn vật phẩm để cho phép:", buttons,
                new Callback<Integer>() {
                    @Override public void run(Player pl, Integer clicked) {
                        if (!pl.hasPermission("detu.admin")) return;
                        if (clicked >= 0 && clicked < candidates.size()) {
                            Material m = candidates.get(clicked);
                            if (plugin.getAllowedItemManager().add(type, m)) {
                                msg(pl, "&aĐã thêm &f" + m.name() + " &avào danh sách cho phép.");
                            }
                        }
                        plugin.getGui().openAllowedItems(pl, type);
                    }
                });
        if (!sent) plugin.getGui().openAllowedItems(p, type);
    }

    public boolean tryTradeInput(Player p, MinionData d) {
        if (!isBedrock(p)) return false;
        if (plugin.getGui().isTradePending(d.getId())) {
            msg(p, "&cĐệ Tử này đang có yêu cầu trade chưa hoàn tất."); return true;
        }
        final UUID mid = d.getId();
        final List<String> onlineNames = new ArrayList<String>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(p)) onlineNames.add(online.getName());
        }
        if (onlineNames.isEmpty()) {
            msg(p, "&cKhông có người chơi nào khác online."); return true;
        }
        List<FormButton> buttons = new ArrayList<FormButton>();
        for (String name : onlineNames) buttons.add(btn("trade.player", GuiConfig.ph("player", name)));
        return sendSimpleForm(p, "Chọn Người Nhận", "Giao Đệ Tử cho:", buttons,
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
                + "Công cụ: " + (d.getTool() == null ? Text.color(t("no-tool"))
                        : plugin.getGui().itemName(d.getTool()));
        List<FormButton> btns = new ArrayList<FormButton>();
        btns.add(btn("trade.accept", GuiConfig.ph("sender", sender.getName())));
        btns.add(btn("trade.reject", GuiConfig.ph("sender", sender.getName())));
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

    private boolean sendSimpleForm(Player p, String title, String body, List<FormButton> buttons, Callback<Integer> cb) {
        return sendSimpleForm(p, title, body, buttons, cb, null);
    }

    /**
     * Xây SimpleForm (Cumulus 1.1+) bằng reflection rồi gửi qua FloodgateApi.sendForm(UUID, Form).
     * Lỗi bất kỳ => tắt form vĩnh viễn (log 1 lần) và trả về false để dùng GUI rương/chat.
     */
    private boolean sendSimpleForm(final Player p, String title, String body, List<FormButton> buttons,
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
            for (FormButton b : buttons) addFormButton(builder, cl, b);

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

    /** Thêm nút; có ảnh thì dùng button(text, FormImage.Type, url|path), lỗi ảnh => tự về nút chỉ có chữ. */
    private void addFormButton(Object builder, ClassLoader cl, FormButton b) throws Exception {
        if (b.hasImage() && imagesSupported) {
            try {
                Object type = imageType(cl, b.imageType);
                if (type != null) {
                    callMethod(builder, "button", b.text, type, b.imageValue);
                    return;
                }
                plugin.getLogger().warning("gui.yml: image.type không hợp lệ '" + b.imageType + "' (chỉ có URL hoặc PATH).");
            } catch (Throwable ex) {
                imagesSupported = false;
                plugin.getLogger().warning("Floodgate/Cumulus phiên bản này không hỗ trợ ảnh trên nút ("
                        + ex.getClass().getSimpleName() + "), các nút chỉ hiển thị chữ.");
            }
        }
        callMethod(builder, "button", b.text);
    }

    private static Object imageType(ClassLoader cl, String name) throws Exception {
        Class<?> cls = Class.forName("org.geysermc.cumulus.util.FormImage$Type", true, cl);
        for (Object constant : cls.getEnumConstants()) {
            if (((Enum<?>) constant).name().equalsIgnoreCase(name)) return constant;
        }
        return null;
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

    private void msg(Player p, String text) {
        p.sendMessage(plugin.getMinionService().prefix() + Text.color(text));
    }

    @FunctionalInterface
    interface Callback<T> { void run(Player player, T value); }
}
