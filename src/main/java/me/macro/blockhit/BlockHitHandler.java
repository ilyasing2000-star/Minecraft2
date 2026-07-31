package me.macro.blockhit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Автоблокхит.
 *
 * <p>Главное отличие от варианта на DLL: вся работа идёт в игровом потоке,
 * в известных точках тика, а собственный удар приходит событием, а не
 * обнаруживается опросом флага.
 *
 * <p>Порядок внутри одного {@code runTick()} в 1.8.9:
 * <ol>
 *   <li>ClientTickEvent.START — здесь решаем, нужен ли блок, и ставим его</li>
 *   <li>цикл {@code Mouse.next()} — сюда приходит MouseEvent, снимаем блок</li>
 *   <li>{@code while (keyBindAttack.isPressed()) clickMouse()} — удар уходит</li>
 *   <li>ClientTickEvent.END — ставим блок обратно</li>
 * </ol>
 * То есть последовательность снять блок — ударить — поставить блок укладывается
 * в один тик. Из опрашивающего потока DLL это было невозможно в принципе:
 * там фронт нажатия виден уже после того, как удар обработан.
 *
 * <p>Всё делается через {@link KeyBinding#setKeyBindState(int, boolean)}, то есть
 * через штатное состояние клавиши. Ни одного пакета мод не отправляет:
 * игра сама вызывает rightClickMouse() и сама шлёт то, что считает нужным.
 */
public class BlockHitHandler {

    private boolean blocking = false;
    private boolean restoreAfterHit = false;
    private boolean wantBlock = false;
    private int threatTicks = 0;

    public long hits = 0L;
    public long blocks = 0L;

    // ==================== тик ====================

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();

        if (event.phase == TickEvent.Phase.START) {
            while (AutoBlockHit.keyToggle != null && AutoBlockHit.keyToggle.isPressed()) {
                BlockHitConfig.enabled = !BlockHitConfig.enabled;
                msg(BlockHitConfig.enabled ? "§aблокхит включён" : "§cблокхит выключен");
            }

            if (!canRun(mc)) {
                threatTicks = 0;
                wantBlock = false;
                setBlock(mc, false);
                return;
            }

            // Считается ровно один раз за тик: в умном режиме здесь тикает
            // счётчик удержания, и второй вызов сломал бы его.
            wantBlock = computeWantBlock(mc);
            setBlock(mc, wantBlock);

        } else {
            // END: удар этого тика уже обработан
            if (!canRun(mc)) {
                setBlock(mc, false);
                return;
            }

            if (restoreAfterHit) {
                restoreAfterHit = false;
                if (wantBlock) setBlock(mc, true);
            } else if (blocking) {
                // Повторно подтверждаем флаг: unPressAllKeys сбрасывает его при потере
                // фокуса окна и при открытии любого экрана.
                KeyBinding.setKeyBindState(useKey(mc), true);
            }
        }
    }

    // ==================== свой удар ====================

    /**
     * Срабатывает внутри цикла разбора мыши, то есть гарантированно до того,
     * как игра дойдёт до clickMouse(). Блок снимается ровно на время замаха.
     */
    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (event.button != 0 || !event.buttonstate) return;
        if (!BlockHitConfig.enabled || !BlockHitConfig.unblockOnHit) return;
        if (!blocking) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (!canRun(mc)) return;

        setBlock(mc, false);
        restoreAfterHit = true;
        hits++;
    }

    // ==================== решение ====================

    private boolean computeWantBlock(Minecraft mc) {
        if (!BlockHitConfig.enabled) return false;

        if (BlockHitConfig.requireSword) {
            ItemStack held = mc.thePlayer.getHeldItem();
            if (held == null || !(held.getItem() instanceof ItemSword)) return false;
        }

        if (BlockHitConfig.skipOnBlock && lookingAtBlock(mc)) return false;

        if (!BlockHitConfig.smart) return true;

        if (threatNearby(mc)) {
            threatTicks = BlockHitConfig.holdTicks;
            return true;
        }
        if (threatTicks > 0) {
            threatTicks--;
            return true;
        }
        return false;
    }

    private boolean canRun(Minecraft mc) {
        return mc != null
                && mc.thePlayer != null
                && mc.theWorld != null
                && mc.currentScreen == null
                && mc.inGameHasFocus;
    }

    private boolean lookingAtBlock(Minecraft mc) {
        MovingObjectPosition mop = mc.objectMouseOver;
        return mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
    }

    // ==================== угроза рядом ====================

    /**
     * Это и есть часть "до удара, а не во время": блок поднимается по самому
     * факту сближения и разворота противника к тебе.
     *
     * <p>По анимации замаха реагировать бессмысленно: S0BPacketAnimation приходит
     * после того, как сервер уже посчитал урон, так что блок по замаху защищает
     * уже только следующий удар. Поэтому работает предсказание по геометрии,
     * а не реакция на событие.
     */
    private boolean threatNearby(Minecraft mc) {
        EntityPlayer me = mc.thePlayer;

        for (Object o : mc.theWorld.playerEntities) {
            if (!(o instanceof EntityPlayer)) continue;
            EntityPlayer p = (EntityPlayer) o;

            if (p == me) continue;
            if (p.isDead || p.getHealth() <= 0.0F) continue;
            if (p.isSpectator() || p.isInvisible()) continue;

            if (me.getDistanceToEntity(p) > BlockHitConfig.range) continue;

            if (BlockHitConfig.fov < 360.0D
                    && angleFrom(p, me) > BlockHitConfig.fov / 2.0D) continue;

            return true;
        }
        return false;
    }

    /** На сколько градусов взгляд {@code from} отклоняется от направления на {@code to}. */
    private double angleFrom(EntityPlayer from, EntityPlayer to) {
        double dx = to.posX - from.posX;
        double dz = to.posZ - from.posZ;
        double yawToTarget = Math.toDegrees(Math.atan2(dz, dx)) - 90.0D;
        return Math.abs(MathHelper.wrapAngleTo180_double(yawToTarget - from.rotationYaw));
    }

    // ==================== нажатие ====================

    private int useKey(Minecraft mc) {
        // Берём реальный код клавиши, а не зашитый -99:
        // если ты переназначишь действие, всё продолжит работать.
        return mc.gameSettings.keyBindUseItem.getKeyCode();
    }

    private void setBlock(Minecraft mc, boolean state) {
        if (mc == null || mc.gameSettings == null) return;

        if (state == blocking) {
            if (state) KeyBinding.setKeyBindState(useKey(mc), true);
            return;
        }

        KeyBinding.setKeyBindState(useKey(mc), state);
        blocking = state;
        if (state) blocks++;
    }

    /** Принудительно отпустить блок — вызывается из команды при выключении. */
    public void forceRelease() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) return;
        KeyBinding.setKeyBindState(useKey(mc), false);
        blocking = false;
        wantBlock = false;
        threatTicks = 0;
        restoreAfterHit = false;
    }

    public boolean isBlocking() {
        return blocking;
    }

    // ==================== чат ====================

    public static void msg(String text) {
        if (!BlockHitConfig.notify) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        mc.thePlayer.addChatMessage(new ChatComponentText("§8[§bBH§8] §r" + text));
    }
}
