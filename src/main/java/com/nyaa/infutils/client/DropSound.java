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
import java.util.Locale;
import java.util.Map;

/**
 * 掉落音效：开启后屏蔽原版经验增加/升级音效，改为在背包获得矿物块时播放对应音效。
 * <p>
 * 1.21.9+ 移除了 {@code ItemPickupS2CPacket}，物品拾取改为通过背包同步下发，
 * 因此这里采用「背包数量差分」检测：每 tick 比较各矿物块的总数，发现增加时播放音效。
 * <p>
 * 关键：服务器以 {@code entity.experience_orb.pickup} / {@code entity.player.levelup}
 * 音效作为「发放矿物」的发放信号（该音效会被本模组屏蔽）。只有在该信号后的短时窗口内、
 * 且背包矿物数确实增加时，才播放矿物音效。这样玩家「自己手动往背包里放矿物/魔矿」
 * （无此发放信号）不会误触发「获得」音效。
 * <ul>
 *   <li>粗铁矿物块(polished_diorite)、精铁矿物块(iron_block)、纯金矿物块(gold_block)、
 *       蓝钻矿物块(diamond_block) → 经验增加音效</li>
 *   <li>天界魔矿(quartz) → 经验升5级音效</li>
 * </ul>
 * 重新播放的音效直接走 SoundManager，不经过 ClientWorld 的 mixin 拦截，避免被再次屏蔽。
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

    /**
     * 服务器「获得矿物」发放信号的剩余窗口（tick）。
     * 服务器以 {@code entity.experience_orb.pickup} / {@code entity.player.levelup}
     * 音效作为「发放矿物」的发放信号（该音效会被本模组屏蔽）。收到该信号时置为
     * {@link #OBTAIN_WINDOW}；仅在此窗口内、且背包矿物数确实增加时，才播放矿物音效。
     * 这样「玩家自己手动往背包里放矿物/魔矿」（无此发放信号）不会误触发「获得」音效。
     */
    private static int obtainSignalTicks = 0;
    private static final int OBTAIN_WINDOW = 8;

    private DropSound() {
    }

    private static boolean enabled() {
        ModConfig cfg = NyaaInfiniteInfernalUtils.CONFIG;
        return cfg != null && cfg.dropSound.enabled && FeatureGate.active();
    }

    /** 该原版音效是否应被屏蔽（经验增加 / 升级）。 */
    public static boolean shouldBlock(String serverSoundId) {
        if (!enabled()) {
            return false;
        }
        String lower = serverSoundId.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        String path = colon >= 0 ? lower.substring(colon + 1) : lower;
        boolean block = path.equals("entity.experience_orb.pickup")
                || path.equals("entity.player.levelup");
        if (block) {
            // 记下「服务器发放矿物」信号：仅在此窗口内、背包矿物数确实增加时
            // 才播放矿物音效，避免玩家手动往背包里放矿物/魔矿时误触发。
            obtainSignalTicks = OBTAIN_WINDOW;
        }
        return block;
    }

    /** 每 tick 调用：检测背包矿物块数量增加并播放对应音效。 */
    public static void tick(MinecraftClient client) {
        if (!enabled()) {
            prevCounts.clear();
            lastWorld = null;
            lastPlayer = null;
            obtainSignalTicks = 0;
            return;
        }
        ClientWorld world = client.world;
        net.minecraft.client.network.ClientPlayerEntity player = client.player;
        if (world == null || player == null) {
            return;
        }
        // 发放信号窗口递减：每 tick 减 1，归零即视为「无发放信号」。
        // 网络包（含被屏蔽的经验/升级音效）在 END_CLIENT_TICK 之前已处理，
        // 因此本帧收到的发放信号在 tick 时仍处窗口内。
        if (obtainSignalTicks > 0) {
            obtainSignalTicks--;
        }
        // 切换世界/玩家时重新基线，避免把重新下发的物品误判为「获得」。
        if (world != lastWorld || player != lastPlayer) {
            lastWorld = world;
            lastPlayer = player;
            prevCounts.clear();
            obtainSignalTicks = 0;
            for (Item item : MINERALS.keySet()) {
                prevCounts.put(item, countOf(player, item));
            }
            return;
        }
        for (Map.Entry<Item, MineralInfo> e : MINERALS.entrySet()) {
            Item item = e.getKey();
            int before = prevCounts.getOrDefault(item, 0);
            int after = countOf(player, item);
            // 仅在「服务器发放信号」窗口内、且背包矿物确实增加时播放，
            // 避免玩家手动整理/移动矿物与魔矿时误触发「获得」音效。
            if (after > before && obtainSignalTicks > 0) {
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
