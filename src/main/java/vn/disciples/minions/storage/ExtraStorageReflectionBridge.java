package vn.disciples.minions.storage;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter runtime cho HSGamer ExtraStorage.
 *
 * Điểm quan trọng:
 * - ExtraStorage trả getFreeSpace() = -1 khi kho KHÔNG GIỚI HẠN.
 *   Bản cũ coi -1 là đầy kho nên không thể gửi item. Bản này xử lý đúng.
 * - canStore() chỉ true khi item tồn tại trong danh sách của kho và đang được bật filter.
 * - API được gọi qua interface Method để ổn định hơn khi class triển khai thay đổi.
 */
public final class ExtraStorageReflectionBridge implements StorageBridge {
    private final JavaPlugin plugin;
    private boolean available;
    private Object api;

    private Method apiGetUser;
    private Method userGetStorage;
    private Method userSave;
    private Method storageGetStatus;
    private Method storageGetItem;
    private Method storageCanStore;
    private Method storageAdd;
    private Method storageGetFreeSpace;
    private Method itemIsFiltered;

    public ExtraStorageReflectionBridge(JavaPlugin plugin) {
        this.plugin = plugin;
        hook();
    }

    private synchronized void hook() {
        Plugin extra = Bukkit.getPluginManager().getPlugin("ExtraStorage");
        if (extra == null || !extra.isEnabled()) {
            available = false;
            return;
        }
        try {
            ClassLoader loader = extra.getClass().getClassLoader();
            Class<?> apiClass = Class.forName("me.hsgamer.extrastorage.api.StorageAPI", true, loader);
            Class<?> userClass = Class.forName("me.hsgamer.extrastorage.api.user.User", true, loader);
            Class<?> storageClass = Class.forName("me.hsgamer.extrastorage.api.storage.Storage", true, loader);
            Class<?> itemClass = Class.forName("me.hsgamer.extrastorage.api.item.Item", true, loader);

            api = apiClass.getMethod("getInstance").invoke(null);
            apiGetUser = apiClass.getMethod("getUser", UUID.class);
            userGetStorage = userClass.getMethod("getStorage");
            userSave = userClass.getMethod("save");
            storageGetStatus = storageClass.getMethod("getStatus");
            storageGetItem = storageClass.getMethod("getItem", Object.class);
            storageCanStore = storageClass.getMethod("canStore", Object.class);
            storageAdd = storageClass.getMethod("add", Object.class, long.class);
            storageGetFreeSpace = storageClass.getMethod("getFreeSpace");
            itemIsFiltered = itemClass.getMethod("isFiltered");

            available = api != null;
            if (available) plugin.getLogger().info("Da ket noi ExtraStorage API thanh cong.");
        } catch (Throwable ex) {
            available = false;
            plugin.getLogger().warning("Khong the ket noi ExtraStorage API: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        Plugin extra = Bukkit.getPluginManager().getPlugin("ExtraStorage");
        if (extra == null || !extra.isEnabled()) {
            available = false;
            return false;
        }
        if (!available || api == null) hook();
        return available;
    }

    @Override
    public StorageItemStatus checkStorage(UUID owner) {
        if (!isAvailable()) return StorageItemStatus.API_UNAVAILABLE;
        try {
            Object user = apiGetUser.invoke(api, owner);
            if (user == null) return StorageItemStatus.USER_UNAVAILABLE;
            Object storage = userGetStorage.invoke(user);
            if (storage == null) return StorageItemStatus.USER_UNAVAILABLE;
            if (!Boolean.TRUE.equals(storageGetStatus.invoke(storage))) return StorageItemStatus.STORAGE_DISABLED;
            long free = ((Number) storageGetFreeSpace.invoke(storage)).longValue();
            return free == 0L ? StorageItemStatus.FULL : StorageItemStatus.ALLOWED;
        } catch (Throwable ex) {
            plugin.getLogger().warning("ExtraStorage check storage loi: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return StorageItemStatus.ERROR;
        }
    }

    @Override
    public StorageItemStatus checkItem(UUID owner, ItemStack item) {
        if (item == null || item.getAmount() <= 0) return StorageItemStatus.ERROR;
        StorageItemStatus storageStatus = checkStorage(owner);
        if (storageStatus != StorageItemStatus.ALLOWED && storageStatus != StorageItemStatus.FULL) return storageStatus;
        if (storageStatus == StorageItemStatus.FULL) return StorageItemStatus.FULL;
        try {
            Object user = apiGetUser.invoke(api, owner);
            if (user == null) return StorageItemStatus.USER_UNAVAILABLE;
            Object storage = userGetStorage.invoke(user);
            if (storage == null) return StorageItemStatus.USER_UNAVAILABLE;

            Object optionalObj = storageGetItem.invoke(storage, item);
            if (!(optionalObj instanceof Optional)) return StorageItemStatus.ERROR;
            Optional<?> optional = (Optional<?>) optionalObj;
            if (!optional.isPresent()) return StorageItemStatus.NOT_IN_ALLOWED_LIST;

            Object storedItem = optional.get();
            boolean filtered = Boolean.TRUE.equals(itemIsFiltered.invoke(storedItem));
            if (!filtered) return StorageItemStatus.NOT_IN_ALLOWED_LIST;

            boolean canStore = Boolean.TRUE.equals(storageCanStore.invoke(storage, item));
            if (!canStore) return StorageItemStatus.NOT_IN_ALLOWED_LIST;

            long free = ((Number) storageGetFreeSpace.invoke(storage)).longValue();
            // -1 = unlimited theo ExtraStorage API.
            if (free == 0L) return StorageItemStatus.FULL;
            return StorageItemStatus.ALLOWED;
        } catch (Throwable ex) {
            plugin.getLogger().warning("ExtraStorage check item loi: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return StorageItemStatus.ERROR;
        }
    }

    @Override
    public Collection<ItemStack> deposit(UUID owner, Collection<ItemStack> items) {
        List<ItemStack> leftovers = new ArrayList<ItemStack>();
        if (items == null || items.isEmpty()) return leftovers;
        if (!isAvailable()) {
            cloneAll(items, leftovers);
            return leftovers;
        }

        // "remaining" = những item CHƯA được cất. Khi lỗi giữa chừng chỉ trả lại phần còn trong đây,
        // không trả lại cả lô (trước đây trả lại tất cả => item vừa vào kho vừa rơi ra đất = nhân bản).
        List<ItemStack> remaining = new ArrayList<ItemStack>();
        cloneAll(items, remaining);

        try {
            Object user = apiGetUser.invoke(api, owner);
            if (user == null) {
                leftovers.addAll(remaining);
                return leftovers;
            }
            Object storage = userGetStorage.invoke(user);
            if (storage == null || !Boolean.TRUE.equals(storageGetStatus.invoke(storage))) {
                leftovers.addAll(remaining);
                return leftovers;
            }

            while (!remaining.isEmpty()) {
                ItemStack item = remaining.get(0);
                if (item == null || item.getAmount() <= 0) {
                    remaining.remove(0);
                    continue;
                }
                if (checkItem(owner, item) != StorageItemStatus.ALLOWED) {
                    leftovers.add(item);
                    remaining.remove(0);
                    continue;
                }

                long free = ((Number) storageGetFreeSpace.invoke(storage)).longValue();
                // ExtraStorage: -1 nghĩa là vô hạn, không phải đầy.
                int storeAmount;
                if (free < 0L) {
                    storeAmount = item.getAmount();
                } else if (free == 0L) {
                    leftovers.add(item);
                    remaining.remove(0);
                    continue;
                } else {
                    storeAmount = (int) Math.min((long) item.getAmount(), free);
                }

                if (storeAmount > 0) storageAdd.invoke(storage, item, (long) storeAmount);
                if (storeAmount < item.getAmount()) {
                    ItemStack rest = item.clone();
                    rest.setAmount(item.getAmount() - storeAmount);
                    leftovers.add(rest);
                }
                remaining.remove(0); // item này đã xử lý xong (cất hoặc đã ghi nhận phần dư)
            }
            try { userSave.invoke(user); } catch (Throwable ignored) {}
        } catch (Throwable ex) {
            plugin.getLogger().warning("ExtraStorage deposit loi: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            // Chỉ trả lại phần chưa kịp cất.
            leftovers.addAll(remaining);
        }
        return leftovers;
    }

    private void cloneAll(Collection<ItemStack> source, Collection<ItemStack> target) {
        for (ItemStack item : source) if (item != null && item.getAmount() > 0) target.add(item.clone());
    }

    @Override
    public String getName() { return "ExtraStorage"; }
}
