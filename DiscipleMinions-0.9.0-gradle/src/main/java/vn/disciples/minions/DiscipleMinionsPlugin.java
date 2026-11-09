package vn.disciples.minions;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import vn.disciples.minions.bedrock.BedrockSupport;
import vn.disciples.minions.bedrock.FloodgateForms;
import vn.disciples.minions.service.MinionActions;
import vn.disciples.minions.service.OfflineCardService;
import vn.disciples.minions.service.OfflineService;
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
    private BedrockSupport bedrock;
    private FloodgateForms forms;
    private TradeService trade;
    private MinionActions actions;
    private OfflineCardService offlineCards;
    private OfflineService offline;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        repository = new MinionRepository(this);
        repository.load();
        itemService = new MinionItemService(this);
        upgradeCardService = new UpgradeCardService(this);
        offlineCards = new OfflineCardService(this);
        offline = new OfflineService(this);
        allowedItemManager = new AllowedItemManager(this);
        allowedItemManager.load();
        StorageBridge bridge = new ExtraStorageReflectionBridge(this);
        minionService = new MinionService(this, repository, itemService, bridge);
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

        long period = Math.max(1L, getConfig().getLong("minion.tick-period", 1L));
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override public void run() { minionService.tick(); }
        }, period, period);

        long autosave = Math.max(20L, getConfig().getLong("minion.autosave-period", 200L));
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override public void run() { repository.flushAsyncIfDirty(); }
        }, autosave, autosave);

        // Mỗi giây: nạp năng lượng Thẻ Offline khi chủ nhân online, tiêu hao khi offline.
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override public void run() { offline.energyTick(); }
        }, 40L, 20L);

        getServer().getScheduler().runTaskTimer(this, new RevokedItemTask(this), 40L, 40L);
        getLogger().info("DiscipleMinions enabled. ExtraStorage=" + bridge.isAvailable());
    }

    @Override
    public void onDisable() {
        if (offline != null) offline.releaseAllTickets();
        if (repository != null) repository.saveNow();
    }

    public void reloadEverything() {
        reloadConfig();
        if (minionService != null) minionService.reloadHooks();
        if (allowedItemManager != null) allowedItemManager.load();
        if (bedrock != null) bedrock.reload();
        if (offlineCards != null) offlineCards.reload();
        if (offline != null) offline.reload();
    }

    public MinionRepository getRepository() { return repository; }
    public MinionItemService getItemService() { return itemService; }
    public MinionService getMinionService() { return minionService; }
    public GuiManager getGui() { return gui; }
    public AllowedItemManager getAllowedItemManager() { return allowedItemManager; }
    public UpgradeCardService getUpgradeCardService() { return upgradeCardService; }
    public BedrockSupport getBedrock() { return bedrock; }
    public FloodgateForms getForms() { return forms; }
    public TradeService getTrade() { return trade; }
    public MinionActions getActions() { return actions; }
    public OfflineCardService getOfflineCards() { return offlineCards; }
    public OfflineService getOffline() { return offline; }
}
