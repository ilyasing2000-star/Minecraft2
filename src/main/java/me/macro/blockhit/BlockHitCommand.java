package me.macro.blockhit;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Клиентская команда /bh (она же /blockhit).
 * Регистрируется в ClientCommandHandler, поэтому на сервер ничего не уходит.
 */
public class BlockHitCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "bh";
    }

    @Override
    public List<String> getCommandAliases() {
        return Arrays.asList("blockhit");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/bh help";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help();
            return;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("on") || sub.equals("off") || sub.equals("toggle")) {
            BlockHitConfig.enabled = sub.equals("toggle") ? !BlockHitConfig.enabled : sub.equals("on");
            if (!BlockHitConfig.enabled && AutoBlockHit.handler != null) {
                AutoBlockHit.handler.forceRelease();
            }
            say(BlockHitConfig.enabled ? "§aблокхит включён" : "§cблокхит выключен");
            return;
        }

        if (sub.equals("status")) {
            status();
            return;
        }

        if (sub.equals("save")) {
            BlockHitConfig.save();
            say("§aнастройки сохранены в config/autoblockhit.cfg");
            return;
        }

        if (args.length < 2) {
            say("§cнужно значение: §f/bh " + sub + " <значение>");
            return;
        }

        String v = args[1];

        if (sub.equals("smart")) {
            BlockHitConfig.smart = parseBoolean(v);
            say("умный режим: " + onOff(BlockHitConfig.smart)
                    + (BlockHitConfig.smart ? "" : " §7(блок стоит постоянно)"));

        } else if (sub.equals("sword")) {
            BlockHitConfig.requireSword = parseBoolean(v);
            say("только с мечом: " + onOff(BlockHitConfig.requireSword));

        } else if (sub.equals("unblock")) {
            BlockHitConfig.unblockOnHit = parseBoolean(v);
            say("снимать блок на свой удар: " + onOff(BlockHitConfig.unblockOnHit));

        } else if (sub.equals("skipblock")) {
            BlockHitConfig.skipOnBlock = parseBoolean(v);
            say("не блокировать глядя на блок: " + onOff(BlockHitConfig.skipOnBlock));

        } else if (sub.equals("notify")) {
            BlockHitConfig.notify = parseBoolean(v);
            // через raw, иначе подтверждение выключения само себя и съест
            raw("§8[§bBH§8] §rсообщения в чат: " + onOff(BlockHitConfig.notify));

        } else if (sub.equals("range")) {
            double d = parseDouble(v, 1.0D, 10.0D);
            BlockHitConfig.range = d;
            say(String.format("радиус угрозы: §f%.1f §7блоков", d));

        } else if (sub.equals("fov")) {
            double d = parseDouble(v, 10.0D, 360.0D);
            BlockHitConfig.fov = d;
            say(String.format("конус взгляда противника: §f%.0f°", d));

        } else if (sub.equals("hold")) {
            int i = parseInt(v, 0, 100);
            BlockHitConfig.holdTicks = i;
            say("удержание после угрозы: §f" + i + " §7тиков (" + (i * 50) + " мс)");

        } else {
            say("§cнеизвестная подкоманда: §f" + sub);
            return;
        }

        BlockHitConfig.save();
    }

    // ==================== вывод ====================

    private void help() {
        raw("§8───── §bAuto BlockHit §8─────");
        raw("§f/bh on §8| §foff §8| §ftoggle §7— включить или выключить");
        raw("§f/bh status §7— текущие настройки и статистика");
        raw("§f/bh smart <true|false> §7— блок только при игроке рядом");
        raw("§f/bh range <1-10> §7— радиус угрозы в блоках");
        raw("§f/bh fov <10-360> §7— конус взгляда противника");
        raw("§f/bh hold <0-100> §7— тиков удержания после ухода угрозы");
        raw("§f/bh sword <true|false> §7— требовать меч в руке");
        raw("§f/bh unblock <true|false> §7— снимать блок на свой удар");
        raw("§f/bh skipblock <true|false> §7— не блокировать глядя на блок");
        raw("§f/bh notify <true|false> §7— сообщения в чат");
        raw("§7Клавиша переключения настраивается в Управлении, по умолчанию B");
    }

    private void status() {
        raw("§8───── §bAuto BlockHit §8─────");
        raw("§7состояние: " + onOff(BlockHitConfig.enabled)
                + "§7, сейчас блок: "
                + (AutoBlockHit.handler != null && AutoBlockHit.handler.isBlocking() ? "§aда" : "§cнет"));
        raw("§7умный режим: " + onOff(BlockHitConfig.smart)
                + String.format("§7, радиус §f%.1f§7, конус §f%.0f°", BlockHitConfig.range, BlockHitConfig.fov));
        raw("§7удержание: §f" + BlockHitConfig.holdTicks + " тиков§7, меч: "
                + onOff(BlockHitConfig.requireSword)
                + "§7, снятие на удар: " + onOff(BlockHitConfig.unblockOnHit));
        if (AutoBlockHit.handler != null) {
            raw("§7за сессию: §f" + AutoBlockHit.handler.hits + "§7 ударов, §f"
                    + AutoBlockHit.handler.blocks + "§7 постановок блока");
        }
    }

    private String onOff(boolean b) {
        return b ? "§aвкл" : "§cвыкл";
    }

    private void say(String s) {
        BlockHitHandler.msg(s);
    }

    /** В обход флага notify: справку и статус всегда надо показать. */
    private void raw(String s) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(s));
    }

    // ==================== автодополнение ====================

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args,
                    "on", "off", "toggle", "status", "smart", "range", "fov",
                    "hold", "sword", "unblock", "skipblock", "notify", "save", "help");
        }
        if (args.length == 2) {
            String s = args[0].toLowerCase();
            if (s.equals("smart") || s.equals("sword") || s.equals("unblock")
                    || s.equals("skipblock") || s.equals("notify")) {
                return getListOfStringsMatchingLastWord(args, "true", "false");
            }
        }
        return new ArrayList<String>();
    }
}
