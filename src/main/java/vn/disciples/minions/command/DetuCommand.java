package vn.disciples.minions.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import vn.disciples.minions.DiscipleMinionsPlugin;
import vn.disciples.minions.model.MinionData;
import vn.disciples.minions.model.MinionType;
import vn.disciples.minions.util.ItemUtil;
import vn.disciples.minions.util.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Mọi lệnh đều dùng được từ console. Lệnh mở GUI (menu, admin) cần chỉ định người chơi khi chạy từ console:
 *   /detu menu <player>      /detu admin <player>
 */
public final class DetuCommand implements CommandExecutor, TabCompleter {
    private final DiscipleMinionsPlugin plugin;
    public DetuCommand(DiscipleMinionsPlugin plugin) { this.plugin = plugin; }

    private void msg(CommandSender sender, String text) {
        sender.sendMessage(plugin.getMinionService().prefix() + Text.color(text));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase();

        if (sub.equals("menu") || sub.equals("gui")) {
            Player target = resolveGuiTarget(sender, args, "menu");
            if (target == null) return true;
            if (target != sender && !sender.hasPermission("detu.admin")) { noPermission(sender); return true; }
            plugin.getGui().openPlayer(target, 0);
            if (target != sender) msg(sender, "&aĐã mở danh sách Đệ Tử cho &f" + target.getName() + "&a.");
            return true;
        }
        if (sub.equals("admin")) {
            if (!sender.hasPermission("detu.admin")) { noPermission(sender); return true; }
            Player target = resolveGuiTarget(sender, args, "admin");
            if (target == null) return true;
            if (!target.hasPermission("detu.admin")) {
                msg(sender, "&c" + target.getName() + " không có quyền detu.admin nên không thể dùng bảng quản trị.");
                return true;
            }
            plugin.getGui().openAdmin(target, 0);
            if (target != sender) msg(sender, "&aĐã mở bảng quản trị cho &f" + target.getName() + "&a.");
            return true;
        }
        if (sub.equals("reload")) {
            if (!sender.hasPermission("detu.admin")) { noPermission(sender); return true; }
            plugin.reloadEverything();
            msg(sender, "&aĐã tải lại config.yml, gui.yml và các hook.");
            return true;
        }
        if (sub.equals("givecard")) {
            if (!sender.hasPermission("detu.give")) { noPermission(sender); return true; }
            if (args.length < 2) {
                msg(sender, "&eDùng: &f/detu givecard <player> [số lượng]");
                return true;
            }
            Player target = plugin.getBedrock().findPlayer(args[1]);
            if (target == null) {
                msg(sender, "&cNgười chơi không online.");
                return true;
            }
            int amount = 1;
            if (args.length >= 3) {
                try { amount = Math.max(1, Math.min(2304, Integer.parseInt(args[2]))); } catch (Exception ignored) {}
            }
            int left = amount;
            while (left > 0) {
                int stack = Math.min(64, left);
                ItemUtil.giveOrDrop(target, plugin.getUpgradeCardService().create(stack));
                left -= stack;
            }
            msg(sender, "&aĐã đưa &f" + amount + " &aThẻ Nâng Cấp cho &f" + target.getName() + "&a.");
            msg(target, "&aBạn nhận được &f" + amount + " &aThẻ Nâng Cấp Đệ Tử.");
            return true;
        }
        if (sub.equals("give")) {
            if (!sender.hasPermission("detu.give")) { noPermission(sender); return true; }
            if (args.length < 3) {
                msg(sender, "&eDùng: &f/detu give <player> <farm|miner> [số lượng]");
                return true;
            }
            Player target = plugin.getBedrock().findPlayer(args[1]);
            if (target == null) {
                msg(sender, "&cNgười chơi không online.");
                return true;
            }
            MinionType type;
            try { type = MinionType.valueOf(args[2].toUpperCase()); }
            catch (Exception ex) {
                msg(sender, "&cLoại hợp lệ: &ffarm &choặc &fminer");
                return true;
            }
            int amount = 1;
            if (args.length >= 4) {
                try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[3]))); } catch (Exception ignored) {}
            }
            int allowed = amount;
            if (!target.hasPermission("detu.limit.bypass")) {
                int remaining = plugin.getMinionService().remainingOwnedSlots(target.getUniqueId());
                if (remaining <= 0) {
                    int max = plugin.getMinionService().maxOwnedPerPlayer();
                    msg(sender, "&c" + target.getName() + " đã đạt giới hạn &f" + max + " &cĐệ Tử.");
                    msg(target, "&cBạn đã đạt giới hạn &f" + max + " &cĐệ Tử có thể sở hữu.");
                    return true;
                }
                allowed = Math.min(amount, remaining);
            }
            for (int i = 0; i < allowed; i++) {
                MinionData d = plugin.getMinionService().create(target.getUniqueId(), type);
                ItemStack item = plugin.getItemService().create(d);
                ItemUtil.giveOrDrop(target, item);
            }
            msg(sender, "&aĐã tạo &f" + allowed + " &aĐệ Tử cho &f" + target.getName() + "&a.");
            if (allowed < amount) {
                msg(sender, "&eChỉ tạo được &f" + allowed + "&e/&f" + amount + " &evì giới hạn sở hữu của người chơi.");
            }
            return true;
        }

        sendHelp(sender);
        return true;
    }

    /** Người chơi mở GUI: tham số thứ 2 nếu có, nếu không thì chính người gõ lệnh (console bắt buộc phải chỉ định). */
    private Player resolveGuiTarget(CommandSender sender, String[] args, String sub) {
        if (args.length >= 2) {
            Player target = plugin.getBedrock().findPlayer(args[1]);
            if (target == null) msg(sender, "&cNgười chơi không online.");
            return target;
        }
        if (sender instanceof Player) return (Player) sender;
        msg(sender, "&eTừ console hãy dùng: &f/detu " + sub + " <player>");
        return null;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Text.color("&8&m------------------------------------------"));
        sender.sendMessage(Text.color("&6&l        ✦ HỆ THỐNG ĐỆ TỬ ✦"));
        sender.sendMessage(Text.color("&7Quản lý, nâng cấp bằng thẻ và chuyển quyền sở hữu Đệ Tử."));
        sender.sendMessage("");
        sender.sendMessage(Text.color("&e/detu menu [player] &8- &fMở danh sách Đệ Tử (console: bắt buộc chỉ định player)"));
        if (sender.hasPermission("detu.admin")) {
            sender.sendMessage(Text.color("&c/detu admin [player] &8- &fMở bảng quản trị toàn server"));
        }
        if (sender.hasPermission("detu.give")) {
            sender.sendMessage(Text.color("&c/detu give <player> <farm|miner> [số lượng] &8- &fTạo Đệ Tử"));
            sender.sendMessage(Text.color("&c/detu givecard <player> [số lượng] &8- &fTạo Thẻ Nâng Cấp"));
        }
        if (sender.hasPermission("detu.admin")) {
            sender.sendMessage(Text.color("&c/detu reload &8- &fTải lại cấu hình"));
        }
        sender.sendMessage(Text.color("&8&m------------------------------------------"));
    }

    private void noPermission(CommandSender sender) {
        sender.sendMessage(plugin.getMinionService().prefix() + Text.color(plugin.getConfig().getString("messages.no-permission", "&cBạn không có quyền sử dụng lệnh này.")));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<String>(Arrays.asList("help", "menu"));
            if (sender.hasPermission("detu.admin")) values.addAll(Arrays.asList("admin", "reload"));
            if (sender.hasPermission("detu.give")) values.addAll(Arrays.asList("give", "givecard"));
            return filter(values, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("givecard")
                || args[0].equalsIgnoreCase("menu") || args[0].equalsIgnoreCase("admin"))) {
            List<String> names = new ArrayList<String>();
            for (Player online : Bukkit.getOnlinePlayers()) names.add(online.getName());
            return filter(names, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return filter(Arrays.asList("farm", "miner"), args[2]);
        return Collections.emptyList();
    }

    private List<String> filter(List<String> values, String prefix) {
        List<String> out = new ArrayList<String>();
        String p = prefix.toLowerCase();
        for (String value : values) if (value.toLowerCase().startsWith(p)) out.add(value);
        return out;
    }
}
