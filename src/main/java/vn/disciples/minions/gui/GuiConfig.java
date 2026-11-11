package vn.disciples.minions.gui;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import vn.disciples.minions.DiscipleMinionsPlugin;
import vn.disciples.minions.bedrock.FormButton;
import vn.disciples.minions.util.Text;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Đọc gui.yml: tên, lore, material, ảnh của từng nút cho CẢ GUI Java (rương) và form Bedrock.
 *
 * File gui.yml trong thư mục plugin được ghép với bản mặc định nằm trong jar: khóa nào thiếu sẽ tự lấy giá trị mặc định,
 * nên chỉ cần ghi những gì muốn đổi.
 */
public final class GuiConfig {
    private final DiscipleMinionsPlugin plugin;
    private YamlConfiguration cfg = new YamlConfiguration();

    public GuiConfig(DiscipleMinionsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "gui.yml");
        if (!file.exists()) plugin.saveResource("gui.yml", false);
        YamlConfiguration user = YamlConfiguration.loadConfiguration(file);
        InputStream in = plugin.getResource("gui.yml");
        if (in != null) {
            user.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
        cfg = user;
    }

    private static String path(String id, String leaf) { return "buttons." + id + "." + leaf; }

    // ------------------------------------------------------------------ Java

    /** Material của nút; "AUTO" (hoặc sai tên) = dùng material do code chọn (auto), mặc định PAPER. */
    public Material material(String id, Material auto) {
        String raw = cfg.getString(path(id, "java.material"), "AUTO");
        if (raw != null && !raw.equalsIgnoreCase("AUTO")) {
            Material m = Material.matchMaterial(raw.trim());
            if (m != null && m.isItem()) return m;
            plugin.getLogger().warning("gui.yml: material không hợp lệ '" + raw + "' ở nút " + id);
        }
        return auto == null ? Material.PAPER : auto;
    }

    public String javaName(String id) { return cfg.getString(path(id, "java.name"), id); }

    public List<String> javaLore(String id) {
        List<String> lore = cfg.getStringList(path(id, "java.lore"));
        return lore == null ? new ArrayList<String>() : lore;
    }

    public int customModelData(String id) { return cfg.getInt(path(id, "java.custom-model-data"), 0); }

    public boolean glow(String id) { return cfg.getBoolean(path(id, "java.glow"), false); }

    // ------------------------------------------------------------------ Bedrock

    /** Nút form Bedrock: "tên" + xuống dòng + "mô tả" (nếu có), kèm ảnh nếu cấu hình. */
    public FormButton bedrock(String id, Map<String, String> ph) {
        String name = cfg.getString(path(id, "bedrock.name"), id);
        String desc = cfg.getString(path(id, "bedrock.description"), "");
        String text = apply(name, ph);
        if (desc != null && !desc.isEmpty()) text += "\n" + apply(desc, ph);
        text = plugin.getBedrock().clean(Text.color(text));
        String type = cfg.getString(path(id, "bedrock.image.type"), "URL");
        String value = apply(cfg.getString(path(id, "bedrock.image.value"), ""), ph);
        return new FormButton(text, type == null ? "" : type.trim().toUpperCase(), value == null ? "" : value.trim());
    }

    // ------------------------------------------------------------------ Chữ dùng chung

    public String text(String key) { return cfg.getString("texts." + key, key); }

    public String text(String key, Map<String, String> ph) { return apply(text(key), ph); }

    public static String apply(String s, Map<String, String> ph) {
        if (s == null || ph == null || ph.isEmpty()) return s;
        for (Map.Entry<String, String> e : ph.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return s;
    }

    /** Tiện ích: ph("a", "1", "b", "2"). */
    public static Map<String, String> ph(String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
