package vn.disciples.minions.bedrock;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import vn.disciples.minions.DiscipleMinionsPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hỗ trợ người chơi Bedrock (PE) vào qua Geyser. KHÔNG phụ thuộc Floodgate/Geyser ở compile-time.
 *
 * - Nhận diện: UUID kiểu Floodgate (most-significant-bits = 0) hoặc tên bắt đầu bằng prefix của Floodgate (mặc định ".").
 * - Tìm người chơi theo tên có/không có prefix (người chơi Bedrock gõ "Steve" vẫn tìm ra ".Steve").
 * - Thay các ký tự đặc biệt trong tên/lore GUI cho người chơi Bedrock (danh sách chỉnh được trong config).
 */
public final class BedrockSupport {
    private final DiscipleMinionsPlugin plugin;
    private boolean enabled = true;
    private String prefix = ".";
    private boolean cleanSymbols = true;
    private final Map<String, String> replacements = new LinkedHashMap<String, String>();

    public BedrockSupport(DiscipleMinionsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("bedrock.enabled", true);
        String p = plugin.getConfig().getString("bedrock.name-prefix", ".");
        prefix = p == null ? "" : p;
        cleanSymbols = plugin.getConfig().getBoolean("bedrock.clean-symbols", true);
        replacements.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("bedrock.symbol-replacements");
        if (section != null) {
            for (String key : section.getKeys(false)) replacements.put(key, section.getString(key, ""));
        } else {
            replacements.put("✦", "*");
            replacements.put("➜", ">");
            replacements.put("‹", "<");
            replacements.put("›", ">");
            replacements.put("•", "-");
            replacements.put("⚙", "");
            replacements.put("✔", "+");
            replacements.put("∞", "inf");
        }
    }

    public boolean isBedrock(Player p) {
        if (!enabled || p == null) return false;
        UUID id = p.getUniqueId();
        if (id.getMostSignificantBits() == 0L) return true; // UUID do Floodgate tạo
        return !prefix.isEmpty() && p.getName().startsWith(prefix);
    }

    /** Tìm người chơi online theo tên, bỏ qua prefix Bedrock và đổi khoảng trắng thành "_". */
    public Player findPlayer(String typed) {
        if (typed == null) return null;
        String name = typed.trim();
        if (name.isEmpty()) return null;
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;
        String wanted = stripPrefix(name);
        String wantedUnderscore = wanted.replace(' ', '_');
        for (Player online : Bukkit.getOnlinePlayers()) {
            String n = stripPrefix(online.getName());
            if (n.equalsIgnoreCase(wanted) || n.equalsIgnoreCase(wantedUnderscore)) return online;
        }
        return null;
    }

    private String stripPrefix(String name) {
        return !prefix.isEmpty() && name.startsWith(prefix) ? name.substring(prefix.length()) : name;
    }

    /** Thay các ký tự có thể không hiển thị trên Bedrock. */
    public String clean(String s) {
        if (!cleanSymbols || s == null) return s;
        for (Map.Entry<String, String> e : replacements.entrySet()) s = s.replace(e.getKey(), e.getValue());
        return s;
    }

    /** Làm sạch tên/lore của mọi item trong inventory, trừ ô skipSlot (chứa item thật của người chơi). */
    public void cleanInventory(Inventory inv, int skipSlot) {
        if (!cleanSymbols) return;
        for (int i = 0; i < inv.getSize(); i++) {
            if (i == skipSlot) continue;
            ItemStack item = inv.getItem(i);
            if (item == null || !item.hasItemMeta()) continue;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) continue;
            boolean changed = false;
            if (meta.hasDisplayName()) {
                String cleaned = clean(meta.getDisplayName());
                if (!cleaned.equals(meta.getDisplayName())) { meta.setDisplayName(cleaned); changed = true; }
            }
            if (meta.hasLore()) {
                List<String> lore = new ArrayList<String>();
                for (String line : meta.getLore()) {
                    String cleaned = clean(line);
                    if (!cleaned.equals(line)) changed = true;
                    lore.add(cleaned);
                }
                if (changed) meta.setLore(lore);
            }
            if (changed) {
                item.setItemMeta(meta);
                inv.setItem(i, item);
            }
        }
    }
}
