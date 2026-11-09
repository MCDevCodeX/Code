package vn.disciples.minions.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

public final class ItemCodec {
    private ItemCodec() {}

    public static String encode(ItemStack item) {
        if (item == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BukkitObjectOutputStream data = new BukkitObjectOutputStream(out);
            data.writeObject(item);
            data.close();
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Khong the serialize ItemStack", e);
        }
    }

    public static ItemStack decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            BukkitObjectInputStream data = new BukkitObjectInputStream(new ByteArrayInputStream(bytes));
            ItemStack item = (ItemStack) data.readObject();
            data.close();
            return item;
        } catch (Exception e) {
            throw new IllegalStateException("Khong the deserialize ItemStack", e);
        }
    }
}
