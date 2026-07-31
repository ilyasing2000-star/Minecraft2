package me.macro.blockhit;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * Настройки лежат в config/autoblockhit.cfg и правятся командой /bh.
 */
public class BlockHitConfig {

    private static Configuration cfg;

    /** Общий выключатель. */
    public static boolean enabled = true;

    /** Блокировать только когда в руке меч. */
    public static boolean requireSword = true;

    /**
     * Умный режим: блок поднимается только когда рядом есть чужой игрок,
     * повёрнутый в твою сторону. Если выключить, блок стоит постоянно.
     */
    public static boolean smart = true;

    /** Радиус, в котором игрок считается угрозой. Дальность удара в 1.8.9 около 3. */
    public static double range = 4.0;

    /** Ширина конуса взгляда противника в градусах. 360 отключает проверку. */
    public static double fov = 110.0;

    /** Сколько тиков держать блок после того, как угроза пропала. */
    public static int holdTicks = 10;

    /** Снимать блок на свой удар, чтобы замах не был съеден блоком. */
    public static boolean unblockOnHit = true;

    /** Не блокировать, когда прицел наведён на блок: иначе откроется дверь или сундук. */
    public static boolean skipOnBlock = true;

    /** Писать в чат о включении, выключении и смене настроек. */
    public static boolean notify = true;

    public static void load(File file) {
        cfg = new Configuration(file);
        cfg.load();
        read();
        if (cfg.hasChanged()) cfg.save();
    }

    private static void read() {
        final String c = "blockhit";
        enabled      = cfg.getBoolean("enabled", c, enabled, "Включён ли блокхит");
        requireSword = cfg.getBoolean("requireSword", c, requireSword, "Только с мечом в руке");
        smart        = cfg.getBoolean("smart", c, smart, "Блок только при игроке рядом");
        range        = cfg.getFloat("range", c, (float) range, 1.0F, 10.0F, "Радиус угрозы");
        fov          = cfg.getFloat("fov", c, (float) fov, 10.0F, 360.0F, "Конус взгляда противника");
        holdTicks    = cfg.getInt("holdTicks", c, holdTicks, 0, 100, "Тиков удержания после угрозы");
        unblockOnHit = cfg.getBoolean("unblockOnHit", c, unblockOnHit, "Снимать блок на свой удар");
        skipOnBlock  = cfg.getBoolean("skipOnBlock", c, skipOnBlock, "Не блокировать глядя на блок");
        notify       = cfg.getBoolean("notify", c, notify, "Сообщения в чат");
    }

    public static void save() {
        if (cfg == null) return;
        final String c = "blockhit";
        cfg.get(c, "enabled", true).set(enabled);
        cfg.get(c, "requireSword", true).set(requireSword);
        cfg.get(c, "smart", true).set(smart);
        cfg.get(c, "range", 4.0D).set(range);
        cfg.get(c, "fov", 110.0D).set(fov);
        cfg.get(c, "holdTicks", 10).set(holdTicks);
        cfg.get(c, "unblockOnHit", true).set(unblockOnHit);
        cfg.get(c, "skipOnBlock", true).set(skipOnBlock);
        cfg.get(c, "notify", true).set(notify);
        cfg.save();
    }
}
