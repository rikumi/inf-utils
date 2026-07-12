package com.nyaa.infutils.client;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import com.nyaa.infutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.random.Random;

import java.util.HashMap;
import java.util.Map;

/**
 * 掉落音效：开启后，当背包中的矿物块/魔矿数量因服务端发放而增加时，
 * 在本地播放对应的「获得」音效（原版经验增加 / 升级音效）。
 * <p>
 * 服务端发放矿物与魔矿时<b>不会下发任何声音</b>，因此这里采用「背包数量差分」检测：
 * 每 tick 比较各矿物块的总数，发现增加时直接本地播放音效，无需依赖任何服务端信号。
 * <p>
 * 区分「服务端 give」与「玩家手动放入」：服务端发放时玩家通常不在任何 GUI 中，
 * 而玩家手动在背包/容器里拖拽、转移矿物必然处于打开 GUI 的状态。因此本功能
 * 仅在玩家<b>未打开任何界面</b>（{@code client.currentScreen == null}）时检测背包增加并播放；
 * 打开 GUI 期间（手动整理/放入）一律不播放，且退出 GUI 时重新基线，避免关闭瞬间误触发。
 * <ul>
 *   <li>粗铁矿物块(polished_diorite)、精铁矿物块(iron_block)、纯金矿物块(gold_block)、
 *       蓝钻矿物块(diamond_block) → 经验增加音效</li>
 *   <li>天界魔矿(quartz) → 经验升5级音效</li>
 * </ul>
 * 播放的音效直接走 SoundManager，不经过 ClientWorld 的 mixin 拦截。
 */
public final class DropSound {

    private static final SoundEvent XP_SOUND = SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP;
    private static final SoundEvent LEVELUP_SOUND = SoundEvents.ENTITY_PLAYER_LEVELUP;

    /** 物品 → (显示名关键词, 是否播放升级音效) */
    private static final Map<Item, MineralInfo> MINERALS = new HashMap<>();
    static {
        MINERALS.put(Items.POLISHED_DIORITE, new MineralInfo("粗铁矿物块", false));
        MINERALS.put(Items.IRON_BLOCK,      new MineralInfo("精铁矿物块", false));
        MINERALS.put(Items.GOLD_BLOCK,      new MineralInfo("纯金矿物块", false));
        MINERALS.put(Items.DIAMOND_BLOCK,   new MineralInfo("蓝钻矿物块", false));
        MINERALS.put(Items.QUARTZ,          new MineralInfo("天界魔矿", true));
    }

    private static final class MineralInfo {
        final String name;     // 去色后的显示名需包含此关键词
        final boolean levelUp; // true=播放升级(升5级)音效, false=播放经验增加音效

        MineralInfo(String name, boolean levelUp) {
            this.name = name;
            this.levelUp = levelUp;
        }
    }

    /** 上一次各矿物块的总数（用于差分）。 */
    private static final Map<Item, Integer> prevCounts = new HashMap<>();
    private static ClientWorld lastWorld = null;
    private static net.minecraft.client.network.ClientPlayerEntity lastPlayer = null;
    /** 上一 tick 玩家是否处于 GUI 中（用于退出 GUI 时重新基线）。 */
    private static boolean wasInGui = false;

    private DropSound() {
    }

    private static boolean enabled() {
        ModConfig cfg = NyaaInfiniteInfernalUtils.CONFIG;
        return cfg != null && cfg.dropSound.enabled && FeatureGate.active();
    }

    /** 每 tick 调用：检测背包矿物块数量增加并本地播放对应音效。 */
    public static void tick(MinecraftClient client) {
        if (!enabled()) {
            prevCounts.clear();
            lastWorld = null;
            lastPlayer = null;
            wasInGui = false;
            return;
        }
        ClientWorld world = client.world;
        net.minecraft.client.network.ClientPlayerEntity player = client.player;
        if (world == null || player == null) {
            return;
        }
        // 切换世界/玩家时重新基线，避免把重新下发的物品误判为「获得」。
        if (world != lastWorld || player != lastPlayer) {
            lastWorld = world;
            lastPlayer = player;
            prevCounts.clear();
            for (Item item : MINERALS.keySet()) {
                prevCounts.put(item, countOf(player, item));
            }
            wasInGui = client.currentScreen != null;
            return;
        }
        boolean inGui = client.currentScreen != null;
        // 玩家手动在背包/容器里放入、转移矿物必然处于 GUI 中 —— 此时不播放，
        // 且重新基线，使关闭 GUI 的瞬间不会把已存在的矿物误判为「获得」。
        if (inGui) {
            if (!wasInGui) {
                // 刚进入 GUI：用当前数量重设基线，忽略本次进入前的差值。
                for (Item item : MINERALS.keySet()) {
                    prevCounts.put(item, countOf(player, item));
                }
            }
            wasInGui = true;
            return;
        }
        if (wasInGui) {
            // 刚退出 GUI：用当前数量重设基线，避免关闭瞬间把 GUI 内放入的矿物误触发。
            for (Item item : MINERALS.keySet()) {
                prevCounts.put(item, countOf(player, item));
            }
            wasInGui = false;
            return;
        }
        // 服务端发放矿物/魔矿时不会下发任何声音，这里直接检测背包数量增加：
        // 某矿物块总数比上一 tick 多 → 本地播放对应的「获得」音效。
        for (Map.Entry<Item, MineralInfo> e : MINERALS.entrySet()) {
            Item item = e.getKey();
            int before = prevCounts.getOrDefault(item, 0);
            int after = countOf(player, item);
            if (after > before) {
                play(e.getValue());
            }
            prevCounts.put(item, after);
        }
    }

    private static int countOf(net.minecraft.client.network.ClientPlayerEntity player, Item item) {
        int total = 0;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.getItem() == item) {
                total += s.getCount();
            }
        }
        return total;
    }

    private static void play(MineralInfo info) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        double x = client.player.getX();
        double y = client.player.getY();
        double z = client.player.getZ();
        SoundEvent ev = info.levelUp ? LEVELUP_SOUND : XP_SOUND;
        client.getSoundManager().play(new PositionedSoundInstance(
                ev, SoundCategory.PLAYERS, 1.0F, 1.0F, Random.create(), x, y, z));
    }
}
