package vn.disciples.minions.storage;

import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.UUID;

public interface StorageBridge {
    boolean isAvailable();
    Collection<ItemStack> deposit(UUID owner, Collection<ItemStack> items);
    StorageItemStatus checkStorage(UUID owner);
    StorageItemStatus checkItem(UUID owner, ItemStack item);
    String getName();
}
