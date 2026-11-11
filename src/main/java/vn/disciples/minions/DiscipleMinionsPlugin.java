package vn.disciples.minions;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import vn.disciples.minions.bedrock.BedrockSupport;
import vn.disciples.minions.gui.GuiConfig;
import vn.disciples.minions.bedrock.FloodgateForms;
import vn.disciples.minions.service.MinionActions;
import vn.disciples.minions.service.TradeService;
import vn.disciples.minions.command.DetuCommand;
import vn.disciples.minions.data.MinionRepository;
import vn.disciples.minions.gui.GuiManager;
import vn.disciples.minions.listener.MinionListener;
import vn.disciples.minions.service.MinionItemService;
import vn.disciples.minions.service.MinionService;
import vn.disciples.minions.service.AllowedItemManager;
import vn.disciples.minions.service.UpgradeCardService;
import vn.disciples.minions.storage.ExtraStorageReflectionBridge;
import vn.disciples.minions.storage.StorageBridge;
import vn.disciples.minions.task.RevokedItemTask;

public final class DiscipleMinionsPlugin extends JavaPlugin {
    private MinionRepository repository;
    private MinionItemService itemService;
    private MinionService minionService;
    private GuiManager gui;
    private AllowedItemManager allowedItemManager;
    private UpgradeCardService upgradeCardService;
    private GuiConfig guiConfig;
    private BedrockSupport bedrock;
    private FloodgateForms forms;
    private TradeService trade;
    private MinionActions actions;
    private BukkitTask tickTask;
    private BukkitTask autosaveTask;
    private BukkitTask revokedTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        repository = new MinionRepository(this);
        repository.load();
        itemService = new MinionItemService(this);
        upgradeCardService = new UpgradeCardService(this);
        allowedItemManager = new AllowedItemManager(this);
        allowedItemManager.load();
        StorageBridge bridge = new ExtraStorageReflectionBridge(this);
        minionService = new MinionService(this, repository, itemService, bridge);
        guiConfig = new GuiConfig(this);
        gui = new GuiManager(this);
        bedrock = new BedrockSupport(this);
        forms = new FloodgateForms(this);
        trade = new TradeService(this);
        actions = new MinionActions(this);

        getServer().getPluginManager().registerEvents(new MinionListener(this), this);
        PluginCommand detu = getCommand("detu");
        if (detu != null) {
            DetuCommand executor = new DetuCommand(this);
            detu.setExecutor(executor);
            detu.setTabCompleter(executor);
        }

        getServer().getScheduler().runTaskLater(this, new Runnable() {
            @Override public void run() { minionService.spawnAll(); }
        }, 1L);

        startTasks();
        getLogger().info("DiscipleMinions enabled. ExtraStorage=" + bridge.isAvailable());
    }

    /**
     * Khởi động lại các task lặp. Gọi cả khi /detu reload để tick-period / autosave-period mới có hiệu lực
     * (trước đây đổi trong config nhưng task cũ vẫn chạy với chu kỳ cũ).
     */
    private void startTasks() {
        stopTasks();
        long period = Math.max(1L, getConfig().getLong("minion.tick-period", 1L));
        tickTask = getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override public void run() { minionService.tick(); }
        }, period, period);

        long autosave = Math.max(20L, getConfig().getLong("minion.autosave-period", 200L));
        autosaveTask = getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override public void run() { repository.flushAsyncIfDirty(); }
        }, autosave, autosave);

        long revokedPeriod = Math.max(20L, getConfig().getLong("minion.revoked-check-period", 40L));
        revokedTask = getServer().getScheduler().runTaskTimer(this, new RevokedItemTask(this), revokedPeriod, revokedPeriod);
    }

    private void stopTasks() {
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
        if (autosaveTask != null) { autosaveTask.cancel(); autosaveTask = null; }
        if (revokedTask != null) { revokedTask.cancel(); revokedTask = null; }
    }

    @Override
    public void onDisable() {
        stopTasks();
        getServer().getScheduler().cancelTasks(this); // hủy mọi task lặp còn lại (kiểm tra thẻ...)
        if (allowedItemManager != null) allowedItemManager.saveNow();
        if (repository != null) repository.saveNow();
    }

    public void reloadEverything() {
        reloadConfig();
        if (minionService != null) minionService.reloadHooks();
        if (allowedItemManager != null) allowedItemManager.load();
        if (bedrock != null) bedrock.reload();
        if (guiConfig != null) guiConfig.reload();
        startTasks();
    }

    public MinionRepository getRepository() { return repository; }
    public MinionItemService getItemService() { return itemService; }
    public MinionService getMinionService() { return minionService; }
    public GuiManager getGui() { return gui; }
    public AllowedItemManager getAllowedItemManager() { return allowedItemManager; }
    public UpgradeCardService getUpgradeCardService() { return upgradeCardService; }
    public GuiConfig getGuiConfig() { return guiConfig; }
    public BedrockSupport getBedrock() { return bedrock; }
    public FloodgateForms getForms() { return forms; }
    public TradeService getTrade() { return trade; }
    public MinionActions getActions() { return actions; }
}
