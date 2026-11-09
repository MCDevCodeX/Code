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
import vn.disciples.minions.util.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public final class DetuCommand implements CommandExecutor, TabCompleter {
    private final DiscipleMinionsPlugin plugin;
    public DetuCommand(DiscipleMinionsPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(Text.color("&cLệnh này cần được sử dụng trong game."));
            return true;
        }
        Player p = (Player) sender;

        if (args[0].equalsIgnoreCase("menu") || args[0].equalsIgnoreCase("gui")) {
            plugin.getGui().openPlayer(p, 0);
            return true;
        }
        if (args[0].equalsIgnoreCase("admin")) {
            if (!p.hasPermission("detu.admin")) { noPermission(p); return true; }
            plugin.getGui().openAdmin(p, 0);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!p.hasPermission("detu.admin")) { noPermission(p); return true; }
            plugin.reloadEverything();
            p.sendMessage(plugin.getMinionService().prefix() + Text.color("&aĐã tải lại config, tiền tệ và các hook."));
            return true;
        }
        if (args[0].equalsIgnoreCase("givecard")) {
            if (!p.hasPermission("detu.give")) { noPermission(p); return true; }
            if (args.length < 2) {
                p.sendMessage(plugin.getMinionService().prefix() + Text.color("&eDùng: &f/detu givecard <player> [số lượng]"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                p.sendMessage(plugin.getMinionService().prefix() + Text.color("&cNgười chơi không online."));
                return true;
            }
            int amount = 1;
            if (args.length >= 3) {
                try { amount = Math.max(1, Math.min(2304, Integer.parseInt(args[2]))); } catch (Exception ignored) {}
            }
            int left = amount;
            while (left > 0) {
                int stack = Math.min(64, left);
                ItemStack card = plugin.getUpgradeCardService().create(stack);
                HashMap<Integer, ItemStack> overflow = target.getInventory().addItem(card);
                for (ItemStack extra : overflow.values()) target.getWorld().dropItemNaturally(target.getLocation(), extra);
                left -= stack;
            }
            p.sendMessage(plugin.getMinionService().prefix() + Text.color("&aĐã đưa &f" + amount + " &aThẻ Nâng Cấp cho &f" + target.getName() + "&a."));
            target.sendMessage(plugin.getMinionService().prefix() + Text.color("&aBạn nhận được &f" + amount + " &aThẻ Nâng Cấp Đệ Tử."));
            return true;
        }
        if (args[0].equalsIgnoreCase("giveofflinecard")) {
            if (!p.hasPermission("detu.give")) { noPermission(p); return true; }
            if (args.length < 3) {
                p.sendMessage(plugin.getMinionService().prefix() + Text.color("&eDùng: &f/detu giveofflinecard <player> <bậc> [số lượng] [năng-lượng-phút]"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                p.sendMessage(plugin.getMinionService().prefix() + Text.color("&cNgười chơi không online."));
                return true;
            }
            int tier;
            try { tier = Integer.parseInt(args[2]); } catch (NumberFormatException ex) { tier = -1; }
            if (!plugin.getOfflineCards().validTier(tier)) {
                p.sendMessage(plugin.getMinionService().prefix() + Text.color("&cBậc hợp lệ: &f" + plugin.getOfflineCards().tierNumbers()));
                return true;
            }
            int amount = 1;
            if (args.length >= 4) {
                try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[3]))); } catch (Exception ignored) {}
            }
            long energyMs = plugin.getOfflineCards().initialEnergyMs();
            if (args.length >= 5) {
                try { energyMs = Math.max(0L, Long.parseLong(args[4])) * 60000L; } catch (Exception ignored) {}
            }
            for (int i = 0; i < amount; i++) {
                vn.disciples.minions.util.ItemUtil.giveOrDrop(target, plugin.getOfflineCards().create(tier, energyMs));
            }
            p.sendMessage(plugin.getMinionService().prefix() + Text.color("&aĐã đưa &f" + amount + " &aThẻ Offline bậc &f" + tier + " &acho &f" + target.getName() + "&a."));
            target.sendMessage(plugin.getMinionService().prefix() + Text.color("&aBạn nhận được &f" + amount + " &aThẻ Offline Đệ Tử."));
            return true;
        }
        if (args[0].equalsIgnoreCase("give")) {
            if (!p.hasPermission("detu.give")) { noPermission(p); return true; }
            if (args.length < 3) {
                p.sendMessage(plugin.getMinionService().prefix() + Text.color("&eDùng: &f/detu give <player> <farm|miner> [số lượng]"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                p.sendMessage(plugin.getMinionService().prefix() + Text.color("&cNgười chơi không online."));
                return true;
            }
            MinionType type;
            try { type = MinionType.valueOf(args[2].toUpperCase()); }
            catch (Exception ex) {
                p.sendMessage(plugin.getMinionService().prefix() + Text.color("&cLoại hợp lệ: &ffarm &choặc &fminer"));
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
                    p.sendMessage(plugin.getMinionService().prefix() + Text.color("&c" + target.getName() + " đã đạt giới hạn &f" + max + " &cĐệ Tử."));
                    target.sendMessage(plugin.getMinionService().prefix() + Text.color("&cBạn đã đạt giới hạn &f" + max + " &cĐệ Tử có thể sở hữu."));
                    return true;
                }
                allowed = Math.min(amount, remaining);
            }
            for (int i = 0; i < allowed; i++) {
                MinionData d = plugin.getMinionService().create(target.getUniqueId(), type);
                ItemStack item = plugin.getItemService().create(d);
                HashMap<Integer, ItemStack> overflow = target.getInventory().addItem(item);
                for (ItemStack left : overflow.values()) target.getWorld().dropItemNaturally(target.getLocation(), left);
            }
            p.sendMessage(plugin.getMinionService().prefix() + Text.color("&aĐã tạo &f" + allowed + " &aĐệ Tử cho &f" + target.getName() + "&a."));
            if (allowed < amount) {
                p.sendMessage(plugin.getMinionService().prefix() + Text.color("&eChỉ tạo được &f" + allowed + "&e/&f" + amount + " &evì giới hạn sở hữu của người chơi."));
            }
            return true;
        }

        sendHelp(p);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Text.color("&8&m------------------------------------------"));
        sender.sendMessage(Text.color("&6&l        ✦ HỆ THỐNG ĐỆ TỬ ✦"));
        sender.sendMessage(Text.color("&7Quản lý, nâng cấp bằng thẻ và chuyển quyền sở hữu Đệ Tử."));
        sender.sendMessage("");
        sender.sendMessage(Text.color("&e/detu menu &8- &fMở danh sách Đệ Tử của bạn"));
        if (sender.hasPermission("detu.admin")) {
            sender.sendMessage(Text.color("&c/detu admin &8- &fMở bảng quản trị toàn server"));
            sender.sendMessage(Text.color("&c/detu give <player> <farm|miner> [số lượng] &8- &fTạo Đệ Tử"));
            sender.sendMessage(Text.color("&c/detu givecard <player> [số lượng] &8- &fTạo Thẻ Nâng Cấp"));
            sender.sendMessage(Text.color("&c/detu giveofflinecard <player> <bậc> [số lượng] [phút] &8- &fTạo Thẻ Offline"));
            sender.sendMessage(Text.color("&c/detu reload &8- &fTải lại cấu hình"));
        }
        sender.sendMessage(Text.color("&8&m------------------------------------------"));
    }

    private void noPermission(Player p) {
        p.sendMessage(plugin.getMinionService().prefix() + Text.color(plugin.getConfig().getString("messages.no-permission", "&cBạn không có quyền sử dụng lệnh này.")));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<String>(Arrays.asList("help", "menu"));
            if (sender.hasPermission("detu.admin")) values.addAll(Arrays.asList("admin", "give", "givecard", "giveofflinecard", "reload"));
            return filter(values, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("givecard") || args[0].equalsIgnoreCase("giveofflinecard"))) {
            List<String> names = new ArrayList<String>();
            for (Player online : Bukkit.getOnlinePlayers()) names.add(online.getName());
            return filter(names, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return filter(Arrays.asList("farm", "miner"), args[2]);
        if (args.length == 3 && args[0].equalsIgnoreCase("giveofflinecard")) return filter(plugin.getOfflineCards().tierNumbers(), args[2]);
        return Collections.emptyList();
    }

    private List<String> filter(List<String> values, String prefix) {
        List<String> out = new ArrayList<String>();
        String p = prefix.toLowerCase();
        for (String value : values) if (value.toLowerCase().startsWith(p)) out.add(value);
        return out;
    }
}
