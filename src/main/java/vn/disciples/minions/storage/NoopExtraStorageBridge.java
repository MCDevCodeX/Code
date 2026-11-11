package vn.disciples.minions.storage;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

public final class NoopExtraStorageBridge implements StorageBridge {
    @Override public boolean isAvailable() { return false; }
    @Override public Collection<ItemStack> deposit(UUID owner, Collection<ItemStack> items) {
        return new ArrayList<ItemStack>(items);
    }
    @Override public StorageItemStatus checkStorage(UUID owner) { return StorageItemStatus.API_UNAVAILABLE; }
    @Override public StorageItemStatus checkItem(UUID owner, ItemStack item) { return StorageItemStatus.API_UNAVAILABLE; }
    @Override public String getName() { return "ExtraStorage"; }
}
