package vn.disciples.minions.util;

import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Lớp tương thích API giữa các phiên bản Paper/Spigot.
 *
 * Vì sao cần: một số hằng số enum đã bị ĐỔI TÊN trong 1.20.5/1.20.6
 * (HIDE_POTION_EFFECTS -> HIDE_ADDITIONAL_TOOLTIP, DURABILITY -> UNBREAKING).
 * Tham chiếu tĩnh tới hằng số cũ vẫn biên dịch được với paper-api 1.20.4 nhưng sẽ ném
 * NoSuchFieldError khi chạy trên server 1.20.6+. Tra cứu động ở đây chạy đúng trên cả hai.
 */
public final class Compat {
    private Compat() {}

    private static ItemFlag tooltipFlag;
    private static boolean tooltipResolved;

    private static Enchantment unbreaking;
    private static boolean unbreakingResolved;

    private static Method glintOverride;
    private static boolean glintResolved;

    /** ItemFlag ẩn dòng mô tả hiệu ứng: HIDE_ADDITIONAL_TOOLTIP (1.20.5+) hoặc HIDE_POTION_EFFECTS (<= 1.20.4). */
    public static ItemFlag hideAdditionalTooltip() {
        if (!tooltipResolved) {
            tooltipResolved = true;
            tooltipFlag = flag("HIDE_ADDITIONAL_TOOLTIP");
            if (tooltipFlag == null) tooltipFlag = flag("HIDE_POTION_EFFECTS");
        }
        return tooltipFlag;
    }

    private static ItemFlag flag(String name) {
        try {
            return ItemFlag.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }

    /** Thêm item flag, bỏ qua flag null (server không có hằng số tương ứng). */
    public static void addFlags(ItemMeta meta, ItemFlag... flags) {
        if (meta == null || flags == null) return;
        for (ItemFlag flag : flags) {
            if (flag == null) continue;
            try {
                meta.addItemFlags(flag);
            } catch (Throwable ignored) {
                // phiên bản server không hỗ trợ flag này: bỏ qua, không làm hỏng item
            }
        }
    }

    /**
     * Enchantment "unbreaking": key là minecraft:unbreaking ở mọi phiên bản,
     * chỉ tên hằng số Java đổi (DURABILITY -> UNBREAKING).
     */
    public static Enchantment unbreaking() {
        if (!unbreakingResolved) {
            unbreakingResolved = true;
            try {
                Method getByKey = Enchantment.class.getMethod("getByKey", NamespacedKey.class);
                unbreaking = (Enchantment) getByKey.invoke(null, NamespacedKey.minecraft("unbreaking"));
            } catch (Throwable ignored) {
                unbreaking = null;
            }
            if (unbreaking == null) {
                try {
                    Field f = Enchantment.class.getField("UNBREAKING");
                    unbreaking = (Enchantment) f.get(null);
                } catch (Throwable ignored) {
                    unbreaking = null;
                }
            }
            if (unbreaking == null) {
                try {
                    Field f = Enchantment.class.getField("DURABILITY");
                    unbreaking = (Enchantment) f.get(null);
                } catch (Throwable ignored) {
                    unbreaking = null;
                }
            }
        }
        return unbreaking;
    }

    /** Làm item phát sáng: glint override (1.20.5+) hoặc enchant ẩn (mọi phiên bản). */
    public static void glow(ItemMeta meta) {
        if (meta == null) return;
        if (!glintResolved) {
            glintResolved = true;
            try {
                glintOverride = ItemMeta.class.getMethod("setEnchantmentGlintOverride", Boolean.class);
            } catch (Throwable ignored) {
                glintOverride = null;
            }
        }
        if (glintOverride != null) {
            try {
                glintOverride.invoke(meta, Boolean.TRUE);
                return;
            } catch (Throwable ignored) {
                // rơi xuống cách enchant ẩn bên dưới
            }
        }
        Enchantment ench = unbreaking();
        if (ench != null) {
            try {
                meta.addEnchant(ench, 1, true);
            } catch (Throwable ignored) {
                // không thể glow: không phải lỗi nghiêm trọng
            }
        }
    }
}
