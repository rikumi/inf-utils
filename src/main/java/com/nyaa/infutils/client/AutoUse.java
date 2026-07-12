package com.nyaa.infutils.client;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import com.nyaa.infutils.config.ModConfig;
import com.nyaa.infutils.client.RegionOverlay;
import com.nyaa.infutils.sound.SoundReplacer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.lang.reflect.Field;

/**
 * Automatic item usage. Every client tick (when no screen is open) it looks for
 * the configured items in the player's inventory, moves the chosen one into the
 * dedicated hotbar slot, selects it and simulates the right/left click that the
 * server-side custom item expects.
 *
 * <p>The interaction is driven by {@link KeyBinding#setPressed(boolean)}: the press
 * set here is consumed on the NEXT tick by {@code MinecraftClient.handleInputEvents}
 * (which reads {@code wasPressed()}), so one call = exactly one use. We release the
 * key at the start of the following tick.</p>
 */
public final class AutoUse {

    // ---- persisted (cross-tick) state ----
    private static Integer mana = null;            // latest MANA parsed from the bottom HUD
    private static long realTick = 0;              // absolute game-tick counter (increments every tick, never reset)
    private static int globalCooldown = 0;        // ticks until the next non-piggy action
    private static int piggyCooldown = 0;        // ticks until the next piggy-bank use
    // ---- piggy-bank phase state (pipeline: swap→RELEASE→press→wait→verify) ----
    // piggyPhase: 0 = inactive, 1 = piggy swapped into use-slot, waiting for server sync,
    //             25 = ATTACK RELEASE FRAME: force attackKey=false to reset wasPressed() edge detector,
    //             3 = left-click pressed (timesPressed+1), waiting for server inventory refresh,
    //             4 = refresh received; verify coin count changed → loop back to 25 or finish/error
    private static int piggyPhase = 0;
    private static int piggySwapSyncTicks = 0;   // countdown for phase 1
    private static int piggyOriginalSlot = -1;    // player's original selected slot
    private static int piggyPreCoins = 0;         // coin count BEFORE the last click (for verification)
    private static int piggyStaleCount = 0;       // consecutive no-change detections (abort threshold)

    // ---- towns where the piggy bank should NOT auto-deposit (when disableInTown) ----
    private static final Set<String> TOWN_REGIONS = Set.of(
            "月耀城", "千仞台", "远梦华镇", "枫之彼岸", "上野神社", "枫栖小镇", "峭崖灵城");

    // ---- auto-spawn (low-health flee) state ----
    // 已发送过 /spawn 且尚未恢复到阈值以上：不再重复发送，避免刷指令被服务端剔除。
    // 血量回到阈值以上时清除，允许下次掉血重新触发。
    private static boolean spawnPending = false;

    // ---- login guard (suppress auto-spawn briefly after joining) ----
    private static int loginGuardTicks = 0;      // ticks left where auto-spawn is suppressed after joining
    private static ClientWorld lastWorld = null;  // last seen world (re-trigger guard on a fresh join / world switch)
    private static ClientPlayerEntity lastPlayer = null; // last seen player (re-trigger on respawn)

    // ---- damage-gated health potion ----
    private static float lastHealth = Float.NaN;  // previous tick's health; NaN means "unknown / just joined"

    // ---- soul-brush phase state (5-phase pipeline: swap→RELEASE→press→wait→verify+loop) ----
    // brushPhase: 0 = inactive, 1 = brush swapped into use-slot, waiting for server sync before attack,
    //             25 = ATTACK RELEASE FRAME: force attackKey=false to reset wasPressed() edge detector,
    //             3 = left-click pressed (attackKey=true), waiting for server inventory refresh,
    //             4 = refresh received; verify inventory changed → loop back to 25 or finish/error
    private static int brushPhase = 0;
    private static int brushSwapSyncTicks = 0;   // countdown for phase 1
    private static int brushOriginalSlot = -1;    // player's original selected slot
    private static int brushPreEmptySlots = 0;    // empty slot count BEFORE the last click (for verification)
    private static int brushStaleCount = 0;       // consecutive no-change detections
    // Server-reported cooldown for the soul brush (in ticks, parsed from chat).
    private static int brushServerCooldownTicks = 0;

    // ---- charge/repair throttle (wait for server inventory refresh) ----
    private static int chargeRepairPendingTicks = 0; // ticks to wait after a charge/repair use before re-checking durability

    // ---- armor repair state (mid-repair: the piece is off the body, in the use slot) ----
    private static int armoraPendingSlot = -1; // armor inventory index (36..39) to return the piece to
    private static int armoraHotbarSlot = -1;   // hotbar slot used during the repair
    private static int armoraOriginalSlot = -1; // player's original selected slot (saved so releasePending won't undo it)
    private static int armoraSavedHotbarSlot = -1; // inventory slot where we stashed the original hotbar item (-1 if hotbar was empty)
    // armoraPhase: 0 = inactive, 1 = armor swapped into hotbar, waiting for server swap sync before entering sneak,
    //              2 = sneak pressed, waiting for server sneak sync before left-clicking,
    //              3 = left-click done, waiting for server durability/inventory refresh,
    //              4 = refresh received; check if still needs repair + consumed item still present → loop back to 3, else finish
    private static int armoraPhase = 0;
    private static int armoraSwapSyncTicks = 0; // countdown for phase 1 (swap-sync)
    private static int armoraSneakSyncTicks = 0; // countdown for phase 2 (sneak-sync)
    private static String armoraConsumed = null;   // consumed item name fragment (e.g. "金块"), for re-checking in phase 4
    private static int armoraPreDamage = -1;        // damage value BEFORE the last click (for verification)
    private static int armoraStaleCount = 0;        // consecutive no-change detections

    // ---- backpack charge state (backpack is in the hotbar — just select it and sneak+left-click) ----
    private static int bpInvSlot = -1;     // inventory index (0..35) of the backpack in the hotbar
    private static int bpOriginalSlot = -1; // player's original selected slot (saved so releasePending won't undo it)
    // bpPhase: 0 = inactive, 1 = backpack slot selected, waiting for server slot sync before entering sneak,
    //          2 = sneak pressed, waiting for server sneak sync before left-clicking,
    //          3 = left-click done, waiting for server energy/inventory refresh,
    //          4 = refresh received; check if energy still ≤1 + battery still present → loop back to 3, else finish
    private static int bpPhase = 0;
    private static boolean bpLockMovement = false; // true while charging: zero out WASD movement input
    private static int bpSelectSyncTicks = 0; // countdown for phase 1 (select-sync)
    private static int bpSneakSyncTicks = 0;  // countdown for phase 2 (sneak-sync)
    private static int bpPreEnergy = -1;       // energy value BEFORE the last click (for verification)
    private static int bpStaleCount = 0;        // consecutive no-change detections

    // ---- handheld charge/repair phase state (5-phase pipeline, same as armor) ----
    // crPhase: 0 = inactive, 1 = item swapped into use-slot → sneak sync → left-click → wait → verify
    private static int crPhase = 0;
    private static int crSwapSyncTicks = 0;
    private static int crSneakSyncTicks = 0;
    private static int crOriginalSlot = -1;
    private static int crHotbarSlot = -1;
    private static boolean crIsCharge = false;
    private static String crConsumed = null;
    private static int crPreDamage = -1;         // damage BEFORE last click
    private static int crStaleCount = 0;          // consecutive no-change detections

    // ---- auto re-summon state (heuristic bind + re-summon on disappearance) ----
    private static final Map<Integer, SummonRecord> knownSummons = new HashMap<>();
    // The item bound to a fresh right-click (used to bind a newly-appeared creature).
    private static Item lastUseItem = null;
    private static String lastUseName = null;     // stripped display name of the held item
    private static long lastUseTick = -99999L;
    // A creature expected to appear from OUR OWN re-summon: bind the next new stand to it.
    private static Item pendingBindItem = null;
    private static String pendingBindName = null;
    private static long pendingBindTick = -99999L;
    // Per-weapon throttle so we don't spam re-summons of the same item.
    private static final Map<String, Long> weaponLastResummonTick = new HashMap<>();
    // Entity IDs of currently-visible summon stands (set by scanSummons, read by updateSummons).
    private static Set<Integer> currentSummonIds = null;

    // ---- one-tick key-press bookkeeping ----
    private static int pendingUse = 0;            // 0 = none, 1 = right (use), 2 = left (attack)
    private static boolean pendingSneak = false;

    // ---- left-click cycle guard ----
    // When true, releasePending() will NOT release attack/sneak keys. This prevents
    // the attack key from being dropped mid-cycle (e.g., during Phase 3→4 countdown).
    // Set to true when entering any left-click phase, false only when finishing/aborting.
    private static boolean inLeftClickCycle = false;

    // ---- eating hold (food takes several ticks to finish) ----
    private static int eatingTicks = 0;           // >0 while the use key is held to eat a food
    private static int restoreSlot = -1;          // hotbar slot to select again after an auto-use (player's slot before we swapped the item in)
    private static int foodRetryCooldown = 0;     // ticks to skip food checks when blacklist blocks (e.g. holding backpack)
    private static int piggyHotbarSlot = -1;      // hotbar slot where the piggy bank is during a cycle
    private static int brushHotbarSlot = -1;       // hotbar slot where the soul brush is during a cycle

    // ---- matching patterns ----
    private static final Pattern MANA_PATTERN = Pattern.compile("MANA\\s+(\\d+)");
    private static final Pattern CHARGE_REPAIR_PATTERN =
            Pattern.compile("潜行[+＋]左键(充能|修复)\\s+消耗(.+)");

    private static final Pattern ENERGY_PATTERN = Pattern.compile("能量剩余\\n■\\s+(\\d+)");

    /** Server chat message: "冷却时间还没有结束, 请等待 X 秒再使用残魂收集刷" */
    private static final Pattern SOUL_BRUSH_COOLDOWN_PATTERN =
            Pattern.compile("请等待\\s*(\\d+(?:\\.\\d+)?)\\s*秒");

    // ---- timing constants ----
    private static final int LOGIN_GUARD_TICKS = 200;          // 10s @ 20tps: suppress potions right after login
    private static final int CHARGE_REPAIR_PENDING_TICKS = 6;  // wait for the server to refresh the inventory before re-checking durability
    private static final int SWAP_SYNC_TICKS = 8;              // wait for server to register a slot swap before the next action (e.g. sneak+left-click)
    private static final int POST_CLICK_TICKS = 4;             // brief wait after a click before resetting (so the server processes the action)
    private static final int SUMMON_BIND_WINDOW = 20;           // ticks after a right-click within which a new creature binds to the used item
    private static final int SUMMON_PENDING_BIND_WINDOW = 30;   // ticks an expected (own) re-summon stays "pending" before being discarded
    private static final int MAX_STALE_COUNT = 3;              // consecutive no-effect clicks before error/abort
    private static final int FOOD_RETRY_COOLDOWN = 40;         // ticks to skip food retry when blacklist blocks (2s)

    // Health potion name -> heal amount (iron_nugget).
    private static final String[][] HEALTH_POTIONS = {
            {"小型生命药剂", "8"},
            {"中型生命药剂", "16"},
            {"大型生命药剂", "32"},
    };
    // Mana potion name -> dose rank (iron_nugget). 1 = smallest ... 4 = strongest.
    private static final String[][] MANA_POTIONS = {
            {"小型魔力药剂", "1"},
            {"中型魔力药剂", "2"},
            {"大型魔力药剂", "3"},
            {"强效魔力药剂", "4"},
    };

    // Auto-eat foods (matching ignores text colour via Formatting.strip). Vanilla
    // foods with no custom lore are also allowed (handled separately in tryFood).
    private static final String[] FOOD_NAMES = {
            "超级素肉凤凰卷", "惠方卷", "铁板牛排", "板烧猪排", "香酥鸡腿",
            "椒盐烤土豆", "母亲的南瓜派", "法式面包", "浆果糖",
    };

    private AutoUse() {
    }

    /** Called from the action-bar (overlay message) mixin. */
    public static void onActionBar(String text) {
        if (text == null) {
            return;
        }
        Matcher m = MANA_PATTERN.matcher(text);
        if (m.find()) {
            try {
                mana = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                // keep previous value
            }
        }
    }

    /**
     * Called from the game-message (system chat) mixin. The server sends a
     * cooldown notice for the soul brush like
     * "冷却时间还没有结束, 请等待 3.5 秒再使用残魂收集刷"; we parse the
     * seconds and wait that long before trying to use the brush again.
     */
    public static void onGameMessage(String text) {
        if (text == null) {
            return;
        }
        Matcher m = SOUL_BRUSH_COOLDOWN_PATTERN.matcher(text);
        if (m.find()) {
            try {
                float seconds = Float.parseFloat(m.group(1));
                brushServerCooldownTicks = Math.max(1, (int) Math.ceil((seconds + 1) * 20));
                log("[残魂刷] 等待 " + seconds + " 秒再执行");
            } catch (NumberFormatException ignored) {
                // keep previous cooldown
            }
        }
    }

    /** Main entry point, registered on END_CLIENT_TICK. */
    public static void tick(MinecraftClient client) {
        realTick++;  // absolute tick counter — must advance before any early return
        SoundReplacer.syncTick(realTick);
        ModConfig cfg = NyaaInfiniteInfernalUtils.CONFIG;
        if (cfg == null || !cfg.autoUse.enabled || !FeatureGate.active()) {
            resetState(client);
            return;
        }
        ClientPlayerEntity player = client.player;
        if (player == null || client.currentScreen != null) {
            // Don't leave simulated keys stuck down while a menu is open.
            eatingTicks = 0;
            releasePending(client);
            return;
        }

        // Food is eaten by holding right-click for its full duration, so while a food
        // is being eaten we keep the use key pressed and tick the countdown instead of
        // running the normal one-press features.
        if (eatingTicks > 0) {
            // Verify the player hasn't scrolled away from the food item.
            PlayerInventory eatInv = player.getInventory();
            ItemStack heldFood = eatInv.getStack(eatInv.getSelectedSlot());
            if (heldFood.isEmpty() || heldFood.get(DataComponentTypes.FOOD) == null) {
                // Player scrolled to a non-food item — abort eating to avoid misusing the item.
                client.options.useKey.setPressed(false);
                pendingUse = 0;
                eatingTicks = 0;
                restoreSlot(client);
                log("[自动进食] 中止（手持物品已变更）");
                return;
            }
            eatingTicks--;
            if (eatingTicks <= 0) {
                client.options.useKey.setPressed(false);
                pendingUse = 0;
                globalCooldown = Math.max(1, cfg.autoUse.cooldownTicks);
                restoreSlot(client); // food consumed: switch hotbar back to where the player was
                log("[自动进食] 食用完成");
            }
            return;
        }

        releasePending(client);

        // Fresh-world-join guard: suppress potion auto-use AND auto-spawn for a short
        // while after joining (or switching / respawning into) any world. The server
        // ramps the player's max health from 20 up to 40 right after join, so the
        // missing-health check would otherwise misfire and waste potions / trigger a
        // needless /spawn flee. We key off the world/player object identity rather than
        // mere player presence, so switching worlds re-triggers the guard.
        if (client.world != lastWorld || player != lastPlayer) {
            lastWorld = client.world;
            lastPlayer = player;
            loginGuardTicks = LOGIN_GUARD_TICKS;
            lastHealth = Float.NaN; // reset health baseline — don't fire potions on world switch
            // Entity ids from the old world are no longer valid; clear all summon
            // tracking so we don't re-summon phantom (non-existent) creatures.
            knownSummons.clear();
            weaponLastResummonTick.clear();
            pendingBindItem = null;
            pendingBindName = null;
            pendingBindTick = -99999L;
        }
        if (loginGuardTicks > 0) {
            loginGuardTicks--;
        }

        // Charge/repair: count down the wait-for-server-refresh timer so we do not
        // re-trigger on stale (pre-use) durability before the inventory syncs.
        if (chargeRepairPendingTicks > 0) {
            chargeRepairPendingTicks--;
        }
        if (foodRetryCooldown > 0) {
            foodRetryCooldown--;
        }
        if (brushServerCooldownTicks > 0) {
            brushServerCooldownTicks--;
        }

        int slotIdx = clampSlot(cfg.autoUse.slot);
        int cd = Math.max(1, cfg.autoUse.cooldownTicks);

        // ---- Auto-spawn (low-health flee) ----
        // Runs independently of the item-based features below. /spawn is a chat
        // command, so it does not touch the simulated use/attack keys.
        if (loginGuardTicks <= 0 && cfg.autoUse.autoSpawn.enabled && tryAutoSpawn(client, player, cfg)) {
            return;
        }

        // ---- Summon scan: must run every tick, even during phase-based features ----
        // Tracking summon positions and binding new ones cannot be deferred — if we
        // skip this while piggy/brush phase is active, positions go stale and the
        // lifetime check will be delayed or miss the window entirely.
        if (cfg.autoUse.summonResummon.enabled) {
            scanSummons(client, player, cfg);
        }

        // ---- Piggy bank: pipeline (swap→RELEASE→press→wait→verify+loop) ----
        // 存钱猪只需要左键攻击，不需要潜行。重复点击直到没有大钱。
        if (piggyPhase == 1) {
            piggySwapSyncTicks--;
            if (piggySwapSyncTicks <= 0) {
                PlayerInventory pInv = player.getInventory();
                int preCoins = 0;
                for (int i = 0; i < 36; i++) {
                    ItemStack s = pInv.getStack(i);
                    if (!s.isEmpty() && s.getItem() == Items.IRON_NUGGET && Formatting.strip(s.getName().getString()).contains("一文大钱")) {
                        preCoins += s.getCount();
                    }
                }
                piggyPreCoins = preCoins;
                client.options.attackKey.setPressed(false);
                piggyPhase = 25;
            }
            return;
        }
        if (piggyPhase == 25) {
            // Verify the player hasn't scrolled away from the piggy slot.
            if (player.getInventory().getSelectedSlot() != piggyHotbarSlot) {
                log("[存钱猪] 中止（快捷栏已切换）");
                client.options.attackKey.setPressed(false);
                inLeftClickCycle = false;
                if (piggyOriginalSlot >= 0 && player.getInventory().getSelectedSlot() != piggyOriginalSlot) {
                    selectSlot(client, player, piggyOriginalSlot);
                }
                resetPiggyState();
                return;
            }
            pressKeyEdge(client.options.attackKey);
            pendingUse = 2;
            piggyPhase = 3;
            chargeRepairPendingTicks = CHARGE_REPAIR_PENDING_TICKS;
            return;
        }
        if (piggyPhase == 3) {
            if (chargeRepairPendingTicks <= 0) {
                piggyPhase = 4;
            }
            return;
        }
        if (piggyPhase == 4) {
            PlayerInventory pInv = player.getInventory();
            int postCoins = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack s = pInv.getStack(i);
                if (!s.isEmpty() && s.getItem() == Items.IRON_NUGGET && Formatting.strip(s.getName().getString()).contains("一文大钱")) {
                    postCoins += s.getCount();
                }
            }
            boolean changed = postCoins < piggyPreCoins;
            if (!changed) {
                piggyStaleCount++;
                if (piggyStaleCount >= MAX_STALE_COUNT) {
                    log("[存钱猪] 连续 " + MAX_STALE_COUNT + " 次无效果，中止");
                    client.options.attackKey.setPressed(false);
                    inLeftClickCycle = false;
                    if (piggyOriginalSlot >= 0 && pInv.getSelectedSlot() != piggyOriginalSlot) {
                        selectSlot(client, player, piggyOriginalSlot);
                    }
                    resetPiggyState();
                    return;
                }
            } else {
                piggyStaleCount = 0;
            }
            if (postCoins > 0) {
                piggyPreCoins = postCoins;
                client.options.attackKey.setPressed(false);
                piggyPhase = 25;
            } else {
                client.options.attackKey.setPressed(false);
                inLeftClickCycle = false;
                if (piggyOriginalSlot >= 0 && pInv.getSelectedSlot() != piggyOriginalSlot) {
                    selectSlot(client, player, piggyOriginalSlot);
                }
                resetPiggyState();
                piggyCooldown = Math.max(1, cfg.autoUse.piggyBank.intervalTicks);
            }
            return;
        }
        // Not in a piggy cycle — try to start one.
        if (piggyCooldown > 0) {
            piggyCooldown--;
        }
        if (cfg.autoUse.piggyBank.enabled && !inTown(cfg) && piggyCooldown <= 0
                && tryPiggy(client, player, cfg, slotIdx)) {
            return;
        }

        // ---- Soul brush: pipeline (swap→RELEASE→press→wait→verify+loop) ----
        // Note: brush does NOT need sneak — it is a simple left-click attack.
        if (brushPhase == 1) {
            brushSwapSyncTicks--;
            if (brushSwapSyncTicks <= 0) {
                // Swap synced — go directly to attack release frame (no sneak needed).
                client.options.attackKey.setPressed(false);
                brushPhase = 25;
            }
            return;
        }
        if (brushPhase == 25) {
            // Verify the player hasn't scrolled away from the brush slot.
            if (player.getInventory().getSelectedSlot() != brushHotbarSlot) {
                log("[残魂刷] 中止（快捷栏已切换）");
                client.options.attackKey.setPressed(false);
                inLeftClickCycle = false;
                if (brushOriginalSlot >= 0 && player.getInventory().getSelectedSlot() != brushOriginalSlot) {
                    selectSlot(client, player, brushOriginalSlot);
                }
                resetBrushState();
                return;
            }
            // Count empty slots BEFORE the attack (to detect item pickup after successful hit).
            PlayerInventory bInv25 = player.getInventory();
            brushPreEmptySlots = 0;
            for (int i = 0; i < 36; i++) {
                if (bInv25.getStack(i).isEmpty()) brushPreEmptySlots++;
            }
            pressKeyEdge(client.options.attackKey);
            pendingUse = 2;
            brushPhase = 3;
            chargeRepairPendingTicks = CHARGE_REPAIR_PENDING_TICKS;
            return;
        }
        if (brushPhase == 3) {
            if (chargeRepairPendingTicks <= 0) {
                brushPhase = 4;
            }
            return;
        }
        if (brushPhase == 4) {
            PlayerInventory bInv = player.getInventory();
            int postEmpty = 0;
            for (int i = 0; i < 36; i++) {
                if (bInv.getStack(i).isEmpty()) postEmpty++;
            }
            boolean changed = postEmpty < brushPreEmptySlots;
            if (!changed) {
                brushStaleCount++;
                if (brushStaleCount >= MAX_STALE_COUNT) {
                    log("[残魂刷] 连续 " + MAX_STALE_COUNT + " 次无效果，中止");
                    client.options.attackKey.setPressed(false);
                    inLeftClickCycle = false;
                    if (brushOriginalSlot >= 0 && bInv.getSelectedSlot() != brushOriginalSlot) {
                        selectSlot(client, player, brushOriginalSlot);
                    }
                    resetBrushState();
                    return;
                }
            } else {
                brushStaleCount = 0;
            }
            // Check if brush is still usable
            int brushIdx = -1;
            ItemStack currentBrush = null;
            for (int i = 0; i < 9; i++) {
                ItemStack s = bInv.getStack(i);
                if (!s.isEmpty() && s.getItem() == Items.BRUSH && s.getName().getString().contains("残魂收集刷")) {
                    brushIdx = i; currentBrush = s; break;
                }
            }
            if (brushIdx >= 0 && currentBrush != null) {
                int maxDmg = currentBrush.getMaxDamage();
                boolean stillUsable = (maxDmg <= 0 || currentBrush.getDamage() <= 0) && hasFreeSlot(bInv);
                if (stillUsable) {
                    brushPreEmptySlots = postEmpty;
                    client.options.attackKey.setPressed(false);
                    brushPhase = 25;
                } else {
                    client.options.attackKey.setPressed(false);
                    inLeftClickCycle = false;
                    if (brushOriginalSlot >= 0 && bInv.getSelectedSlot() != brushOriginalSlot) {
                        selectSlot(client, player, brushOriginalSlot);
                    }
                    resetBrushState();
                    globalCooldown = cd;
                }
            } else {
                // Brush disappeared from hotbar?
                client.options.attackKey.setPressed(false);
                inLeftClickCycle = false;
                if (brushOriginalSlot >= 0 && bInv.getSelectedSlot() != brushOriginalSlot) {
                    selectSlot(client, player, brushOriginalSlot);
                }
                resetBrushState();
                globalCooldown = cd;
            }
            return;
        }

        // Not in a soul-brush cycle — try to start one.
        if (cfg.autoUse.soulBrush && brushPhase == 0 && tryBrushStart(client, player, slotIdx)) {
            globalCooldown = cd;
            return;
        }

        // ---- Summon re-summon: must run every tick, even during globalCooldown ----
        // Tracking summon positions and binding new ones cannot be deferred — if we
        // skip this while globalCooldown > 0, positions go stale and lifetime checks
        // can never trigger because they only happen inside updateSummons().
        // Re-summon actions (tryResummonWeapon) need to bypass globalCooldown too,
        // because if the player is idle for 2 minutes, globalCooldown is 0 but the
        // re-summon must fire as soon as lifetime elapses regardless of other features.
        if (cfg.autoUse.summonResummon.enabled
                && updateSummons(client, player, cfg, slotIdx)) {
            globalCooldown = cd;
            return;
        }

        if (globalCooldown > 0) {
            globalCooldown--;
            return;
        }

        if (cfg.autoUse.healthPotion.enabled && tryHealth(client, player, cfg, slotIdx)) {
            globalCooldown = cd;
            return;
        }
        if (loginGuardTicks <= 0 && cfg.autoUse.manaPotion.enabled && mana != null && tryMana(client, player, cfg, slotIdx)) {
            globalCooldown = cd;
            return;
        }
        if (cfg.autoUse.food.enabled && foodRetryCooldown <= 0 && tryFood(client, player, cfg, slotIdx)) {
            return;
        }
        // ---- Auto-repair armor: take it off, repair in the use slot, put it back ----
        // Phase-based pipeline with repeated sneak+left-click until the piece is fully repaired:
        //   Phase 1 — armor swapped into hotbar, waiting for server to register the swap
        //              before entering sneak (SWAP_SYNC_TICKS countdown).
        //   Phase 2 — sneak pressed, waiting for server to register the sneak state
        //              before left-clicking (SWAP_SYNC_TICKS countdown).
        //   Phase 3 — left-click done, waiting for server durability/inventory refresh
        //              (CHARGE_REPAIR_PENDING_TICKS countdown). Sneak is KEPT pressed.
        //   Phase 4 — refresh received. Re-check the armor piece's durability and the
        //              consumed item's presence. If still needs repair and consumed still
        //              present → loop back to Phase 3 (another left-click). Otherwise
        //              release sneak, swap armor back onto the body and finish.
        if (cfg.autoUse.armoraRepair.repairEnabled) {
            if (armoraPhase == 1) {
                armoraSwapSyncTicks--;
                if (armoraSwapSyncTicks <= 0) {
                    client.options.sneakKey.setPressed(true);
                    pendingSneak = true;
                    armoraPhase = 2;
                    armoraSneakSyncTicks = SWAP_SYNC_TICKS;
                }
                return;
            }
            if (armoraPhase == 2) {
                armoraSneakSyncTicks--;
                if (armoraSneakSyncTicks <= 0) {
                    ItemStack armorForCheck = player.getInventory().getStack(armoraHotbarSlot);
                    armoraPreDamage = !armorForCheck.isEmpty() ? armorForCheck.getDamage() : -1;
                    armoraStaleCount = 0;
                    client.options.attackKey.setPressed(false);
                    armoraPhase = 25;
                }
                return;
            }
            if (armoraPhase == 25) {
                // Verify the player hasn't scrolled away from the armor hotbar slot.
                if (player.getInventory().getSelectedSlot() != armoraHotbarSlot) {
                    log("[盔甲修复] 中止（快捷栏已切换）");
                    client.options.attackKey.setPressed(false);
                    client.options.sneakKey.setPressed(false);
                    pendingSneak = false;
                    inLeftClickCycle = false;
                    finishArmora(client, player);
                    resetArmoraState();
                    return;
                }
                pressKeyEdge(client.options.attackKey);
                pendingUse = 2;
                armoraPhase = 3;
                chargeRepairPendingTicks = CHARGE_REPAIR_PENDING_TICKS;
                return;
            }
            if (armoraPhase == 3) {
                if (chargeRepairPendingTicks <= 0) {
                    armoraPhase = 4;
                }
                return;
            }
            if (armoraPhase == 4) {
                PlayerInventory inv = player.getInventory();
                ItemStack armorInSlot = inv.getStack(armoraHotbarSlot);
                int postDamage = !armorInSlot.isEmpty() ? armorInSlot.getDamage() : -1;

                boolean improved = (postDamage >= 0 && armoraPreDamage >= 0 && postDamage < armoraPreDamage);

                boolean needsMore = false;
                boolean durabilityFull = false;
                if (!armorInSlot.isEmpty() && armorInSlot.getMaxDamage() > 0) {
                    int max = armorInSlot.getMaxDamage();
                    int dmg = armorInSlot.getDamage();
                    needsMore = dmg >= max / 2;
                    durabilityFull = dmg <= 0;
                }

                boolean consumedPresent = armoraConsumed != null
                        && !armoraConsumed.isEmpty()
                        && hasItemNamed(inv, armoraConsumed);

                // Early exit: durability full
                if (durabilityFull) {
                    log("[盔甲修复] 完成（耐久已满）");
                    finishArmora(client, player);
                    return;
                }

                if (!improved) {
                    armoraStaleCount++;
                if (armoraStaleCount >= MAX_STALE_COUNT) {
                    log("[盔甲修复] 连续 " + MAX_STALE_COUNT + " 次无效果，中止");
                    finishArmora(client, player);
                    resetArmoraState();
                    return;
                }
                } else {
                    armoraStaleCount = 0;
                }

                if (needsMore && consumedPresent) {
                    armoraPreDamage = postDamage;
                    client.options.attackKey.setPressed(false);
                    armoraPhase = 25;
                } else {
                    String reason = !needsMore ? "耐久已满" : ("消耗物「" + armoraConsumed + "」已耗尽");
                    log("[盔甲修复] 完成（" + reason + "）");
                    finishArmora(client, player);
                    resetArmoraState();
                }
                return;
            }
            // Not in a repair cycle yet — scan for armor that needs repair.
            if (chargeRepairPendingTicks <= 0
                    && tryChargeRepairArmora(client, player, cfg, slotIdx)) {
                globalCooldown = cd;
                return;
            }
        }

        // ---- Backpack charge: select the backpack in the hotbar, sneak+left-click ----
        // The 发明家动力背包 is a hotbar item (furnace-based), NOT an armor piece.
        // Repeated-click pipeline (same as armor repair):
        //   Phase 1 — backpack slot selected, waiting for server slot sync
        //              before entering sneak (SWAP_SYNC_TICKS countdown).
        //   Phase 2 — sneak pressed, waiting for server sneak sync
        //              before left-clicking (SWAP_SYNC_TICKS countdown).
        //   Phase 3 — left-click done, waiting for server energy/inventory refresh
        //              (CHARGE_REPAIR_PENDING_TICKS countdown). Sneak is KEPT pressed.
        //   Phase 4 — refresh received. Re-check energy and battery presence.
        //              If energy still ≤ 1 and battery present → loop back to Phase 3.
        //              Otherwise release sneak, restore original slot, finish.
        if (cfg.autoUse.backpackCharge) {
            if (bpPhase == 1) {
                bpSelectSyncTicks--;
                if (bpSelectSyncTicks <= 0) {
                    client.options.sneakKey.setPressed(true);
                    pendingSneak = true;
                    bpPhase = 2;
                    bpSneakSyncTicks = SWAP_SYNC_TICKS;
                }
                return;
            }
            if (bpPhase == 2) {
                bpSneakSyncTicks--;
                if (bpSneakSyncTicks <= 0) {
                    // Record energy BEFORE this click.
                    ItemStack bpForCheck = player.getInventory().getStack(bpInvSlot);
                    String loreCheck = !bpForCheck.isEmpty() ? loreText(bpForCheck) : null;
                    bpPreEnergy = -1;
                    if (loreCheck != null) {
                        Matcher mPre = ENERGY_PATTERN.matcher(loreCheck);
                        if (mPre.find()) { try { bpPreEnergy = Integer.parseInt(mPre.group(1)); } catch (NumberFormatException ignored) {} }
                    }
                    bpStaleCount = 0;

                    client.options.attackKey.setPressed(false);
                    bpPhase = 25;
                }
                return;
            }
            if (bpPhase == 25) {
                // Verify the player hasn't scrolled away from the backpack slot.
                if (player.getInventory().getSelectedSlot() != bpInvSlot) {
                    log("[背包充能] 中止（快捷栏已切换）");
                    client.options.sneakKey.setPressed(false);
                    pendingSneak = false;
                    client.options.attackKey.setPressed(false);
                    inLeftClickCycle = false;
                    if (bpOriginalSlot >= 0 && player.getInventory().getSelectedSlot() != bpOriginalSlot) {
                        selectSlot(client, player, bpOriginalSlot);
                    }
                    resetBackpackState();
                    return;
                }
                // If the player is still holding right-click (e.g. flying with the backpack
                // until energy ran out), vanilla blocks left-click attacks while the use
                // action is active. Release the use key and stop using the item for this
                // frame so the upcoming sneak+left-click charge is processed.
                if (client.options.useKey.isPressed()) {
                    client.options.useKey.setPressed(false);
                    if (client.interactionManager != null) {
                        client.interactionManager.stopUsingItem(player);
                    }
                }
                // Release frame done — trigger attack via timesPressed reflection.
                pressKeyEdge(client.options.attackKey);
                pendingUse = 2;
                bpPhase = 3;
                chargeRepairPendingTicks = CHARGE_REPAIR_PENDING_TICKS;
                return;
            }
            if (bpPhase == 3) {
                if (chargeRepairPendingTicks <= 0) {
                    bpPhase = 4;
                }
                return;
            }
            if (bpPhase == 4) {
                PlayerInventory inv = player.getInventory();
                ItemStack bpInSlot = inv.getStack(bpInvSlot);
                int postEnergy = -1;
                boolean needsMore = false;
                if (!bpInSlot.isEmpty()) {
                    String plainName = Formatting.strip(bpInSlot.getName().getString());
                    if (plainName.contains("发明家动力背包")) {
                        String lore = loreText(bpInSlot);
                        if (lore != null) {
                            Matcher m = ENERGY_PATTERN.matcher(lore);
                            if (m.find()) {
                                try {
                                    postEnergy = Integer.parseInt(m.group(1));
                                    needsMore = postEnergy <= 1;
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                }

                boolean improved = (postEnergy >= 0 && bpPreEnergy >= 0 && postEnergy > bpPreEnergy);

                if (!improved) {
                    bpStaleCount++;
                if (bpStaleCount >= MAX_STALE_COUNT) {
                    log("[背包充能] 连续 " + MAX_STALE_COUNT + " 次无效果，中止");
                    client.options.sneakKey.setPressed(false);
                    pendingSneak = false;
                    client.options.attackKey.setPressed(false);
                    inLeftClickCycle = false;
                    if (bpOriginalSlot >= 0 && player.getInventory().getSelectedSlot() != bpOriginalSlot) {
                        selectSlot(client, player, bpOriginalSlot);
                    }
                    resetBackpackState();
                    return;
                }
                } else {
                    bpStaleCount = 0;
                }

                if (needsMore) {
                    bpPreEnergy = postEnergy;
                    client.options.attackKey.setPressed(false);
                    bpPhase = 25;
                } else {
                    log("[背包充能] 完成（能量已恢复）");
                    client.options.sneakKey.setPressed(false);
                    pendingSneak = false;
                    client.options.attackKey.setPressed(false);
                    inLeftClickCycle = false;
                    if (bpOriginalSlot >= 0 && player.getInventory().getSelectedSlot() != bpOriginalSlot) {
                        selectSlot(client, player, bpOriginalSlot);
                    }
                    resetBackpackState();
                }
                return;
            }
            // Not in a charge cycle yet — scan for backpack that needs charging.
            if (chargeRepairPendingTicks <= 0
                    && tryChargeBackpack(client, player, cfg, slotIdx)) {
                globalCooldown = cd;
                return;
            }
        }

        // ---- Handheld charge/repair: pipeline (swap→sneak→RELEASE→press→wait→verify+loop) ----
        if ((cfg.autoUse.chargeRepair.chargeEnabled || cfg.autoUse.chargeRepair.repairEnabled)) {
            if (crPhase == 1) {
                crSwapSyncTicks--;
                if (crSwapSyncTicks <= 0) {
                    client.options.sneakKey.setPressed(true);
                    pendingSneak = true;
                    crPhase = 2;
                    crSneakSyncTicks = SWAP_SYNC_TICKS;
                }
                return;
            }
            if (crPhase == 2) {
                crSneakSyncTicks--;
                if (crSneakSyncTicks <= 0) {
                    ItemStack crForCheck = player.getInventory().getStack(crHotbarSlot);
                    crPreDamage = !crForCheck.isEmpty() ? crForCheck.getDamage() : -1;
                    crStaleCount = 0;
                    client.options.attackKey.setPressed(false);
                    crPhase = 25;
                }
                return;
            }
            if (crPhase == 25) {
                // Verify the player hasn't scrolled away from the repair slot.
                if (player.getInventory().getSelectedSlot() != crHotbarSlot) {
                    log("[手持修复] 中止（快捷栏已切换）");
                    finishCr(client, player);
                    resetCrState();
                    return;
                }
                pressKeyEdge(client.options.attackKey);
                pendingUse = 2;
                crPhase = 3;
                chargeRepairPendingTicks = CHARGE_REPAIR_PENDING_TICKS;
                return;
            }
            if (crPhase == 3) {
                if (chargeRepairPendingTicks <= 0) {
                    crPhase = 4;
                }
                return;
            }
            if (crPhase == 4) {
                PlayerInventory cInv = player.getInventory();
                ItemStack crInSlot = cInv.getStack(crHotbarSlot);
                int postDmg = !crInSlot.isEmpty() ? crInSlot.getDamage() : -1;
                boolean improved = (postDmg >= 0 && crPreDamage >= 0 && postDmg < crPreDamage);

                boolean needsMore = false;
                boolean durabilityFull = false;
                if (!crInSlot.isEmpty() && crInSlot.getMaxDamage() > 0) {
                    int max = crInSlot.getMaxDamage();
                    int dmg = crInSlot.getDamage();
                    if (crIsCharge) { needsMore = (max - dmg) <= 1; durabilityFull = dmg <= 0; }
                    else { needsMore = dmg >= max / 2; durabilityFull = dmg <= 0; }
                }

                boolean consumedPresent = crConsumed != null && !crConsumed.isBlank()
                        && hasItemNamed(cInv, crConsumed);

                // Early exit: durability already full
                if (durabilityFull) {
                    log("[手持修复] 完成（耐久已满）");
                    finishCr(client, player);
                    resetCrState();
                    globalCooldown = cd;
                    return;
                }

                if (!improved) {
                    crStaleCount++;
                if (crStaleCount >= MAX_STALE_COUNT) {
                    log("[手持修复] 连续 " + MAX_STALE_COUNT + " 次无效果，中止");
                    finishCr(client, player);
                    resetCrState();
                    globalCooldown = cd;
                    return;
                }
                } else {
                    crStaleCount = 0;
                }

                if (needsMore && consumedPresent) {
                    crPreDamage = postDmg;
                    client.options.attackKey.setPressed(false);
                    crPhase = 25;
                } else {
                    String reason = !needsMore ? "已完成" : "消耗物已耗尽";
                    log("[手持修复] 完成（" + reason + "）");
                    finishCr(client, player);
                    resetCrState();
                    globalCooldown = cd;
                }
                return;
            }
            // Not in cycle — try to start one.
            if (chargeRepairPendingTicks <= 0 && tryChargeRepairStart(client, player, cfg, slotIdx)) {
                globalCooldown = cd;
                return;
            }
        }

        // Record health for next frame's damage detection.
        lastHealth = player.getHealth();
    }

    private static void resetState(MinecraftClient client) {
        releasePending(client);
        eatingTicks = 0;
        restoreSlot = -1;
        globalCooldown = 0;
        piggyCooldown = 0;
        foodRetryCooldown = 0;
        brushServerCooldownTicks = 0;
        piggyHotbarSlot = -1;
        brushHotbarSlot = -1;
        inLeftClickCycle = false;  // safety: clear guard on full state reset
        resetPiggyState();
        spawnPending = false;
        loginGuardTicks = 0;
        chargeRepairPendingTicks = 0;
        lastHealth = Float.NaN; // reset health baseline on full state reset
        // If an armor piece was taken off for repair and the feature is now off, put
        // it back on the body instead of leaving it stuck in the hotbar.
        if (armoraPendingSlot >= 0 && client != null && client.player != null) {
            swapSlots(client, client.player, armoraPendingSlot, armoraHotbarSlot);
            if (armoraOriginalSlot >= 0 && client.player.getInventory().getSelectedSlot() != armoraOriginalSlot) {
                selectSlot(client, client.player, armoraOriginalSlot);
            }
        }
        // If armor repair was in progress, swap back.
        if (armoraPendingSlot >= 0 && client != null && client.player != null) {
            swapSlots(client, client.player, armoraPendingSlot, armoraHotbarSlot);
            if (armoraOriginalSlot >= 0 && client.player.getInventory().getSelectedSlot() != armoraOriginalSlot) {
                selectSlot(client, client.player, armoraOriginalSlot);
            }
        }
        resetArmoraState();
        // If a backpack charge cycle was in progress, just restore original slot.
        if (bpOriginalSlot >= 0 && client != null && client.player != null) {
            if (client.player.getInventory().getSelectedSlot() != bpOriginalSlot) {
                selectSlot(client, client.player, bpOriginalSlot);
            }
        }
        resetBackpackState();
        knownSummons.clear();
        weaponLastResummonTick.clear();
        lastUseItem = null;
        lastUseName = null;
        lastUseTick = -99999L;
        pendingBindItem = null;
        pendingBindName = null;
        pendingBindTick = -99999L;
    }

    // ===================================================================
    // 0. Auto-spawn (low-health flee)
    // ===================================================================
    private static boolean tryAutoSpawn(MinecraftClient client, ClientPlayerEntity player, ModConfig cfg) {
        ModConfig.AutoUseSettings.AutoSpawnSettings spawn = cfg.autoUse.autoSpawn;
        float health = player.getHealth();

        // NEVER run /spawn once the player is already dead. The death tick leaves
        // health at 0 (which is below the threshold) with a "dropped" signal, so
        // without this guard we would send /spawn AFTER death — which corrupts the
        // server's recorded death location and breaks /back (can't return to where
        // you died). The flee must only happen while the player is still alive.
        if (player.isDead() || health <= 0) {
            spawnPending = false;
            return false;
        }

        // 血量恢复到阈值以上：清除待定标志，允许下次掉血重新触发。
        if (health >= spawn.threshold) {
            spawnPending = false;
            return false;
        }

        // 仅在「本 tick 掉血」时检查（下次掉血再检查），
        // 而不是每 tick 反复发指令。lastHealth 为上一 tick 的血量。
        boolean dropped = !Float.isNaN(lastHealth) && health < lastHealth;
        if (!dropped) {
            return false;
        }

        // 已成功发过一次 /spawn 且尚未恢复：不再重复发送。
        if (spawnPending) {
            return false;
        }

        // 有可用血药则优先喝药，不回城。
        boolean couldUsePotion = cfg.autoUse.healthPotion.enabled
                && healthPotionAvailable(player, cfg);
        if (couldUsePotion) {
            return false;
        }

        // 已在月耀城：不触发回城。
        String region = RegionOverlay.currentRegion();
        if (region != null && region.contains("月耀城")) {
            return false;
        }

        // 发送一次 /spawn；成功后置 spawnPending，本轮不再重复发送，
        // 直到血量恢复到阈值以上，下次掉血再检查。
        try {
            client.getNetworkHandler().sendChatCommand("spawn");
        } catch (Throwable t) {
            NyaaInfiniteInfernalUtils.LOGGER.warn("[infutils][autouse] /spawn failed: {}", t.getMessage());
            return false;
        }
        spawnPending = true;
        log("[自动逃跑] 生命值过低（" + (int) health + " < " + spawn.threshold + "），执行 /spawn");
        return true;
    }

    private static boolean healthPotionAvailable(ClientPlayerEntity player, ModConfig cfg) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || s.getItem() != Items.IRON_NUGGET) {
                continue;
            }
            if (matchValue(s, HEALTH_POTIONS) >= 0) {
                return true;
            }
        }
        return false;
    }

    // ===================================================================
    // 1. Health potions
    // ===================================================================
    private static boolean tryHealth(MinecraftClient client, ClientPlayerEntity player,
                                    ModConfig cfg, int slotIdx) {
        float health = player.getHealth();
        // Only trigger when health has decreased (took damage this tick).
        // NaN means unknown (just joined / world switch) — skip until we have a valid baseline.
        // No damage = health >= lastHealth — also skip.
        if (Float.isNaN(lastHealth) || health >= lastHealth) {
            return false;
        }
        float missing = player.getMaxHealth() - health;
        if (missing < cfg.autoUse.healthPotion.threshold) {
            return false;
        }
        int[] sel = selectHealthPotion(player, cfg, missing);
        if (sel[0] < 0) {
            return false;
        }
        ItemStack placed = prepareSlot(client, player, sel[0], slotIdx);
        if (placed.isEmpty() || placed.getItem() != Items.IRON_NUGGET) {
            return false;
        }
        press(client, false, false);
        log("[生命药剂] 使用了治疗量 " + sel[1] + "（缺失 " + (int) missing + "）");
        return true;
    }

    /** Picks the best health potion the same way auto-use would (by the configured
     *  priority), ignoring the missing-health threshold. Returns {invIndex, heal}
     *  or {-1, -1} when none is in the inventory. Used by both auto-use and the
     *  manual H hotkey, so the selection order is identical. */
    private static int[] selectHealthPotion(ClientPlayerEntity player, ModConfig cfg, float missing) {
        PlayerInventory inv = player.getInventory();
        int bestInv = -1;
        int bestHeal = -1;
        boolean useSmallestAbove = cfg.autoUse.healthPotion.preferLargestPotion;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || s.getItem() != Items.IRON_NUGGET) {
                continue;
            }
            int heal = matchValue(s, HEALTH_POTIONS);
            if (heal < 0) {
                continue;
            }
            if (bestInv == -1) {
                bestInv = i;
                bestHeal = heal;
                continue;
            }
            if (!useSmallestAbove) { // CLOSEST: prefer closest to missing
                if (Math.abs(heal - missing) < Math.abs(bestHeal - missing)
                        || (Math.abs(heal - missing) == Math.abs(bestHeal - missing) && heal < bestHeal)) {
                    bestInv = i;
                    bestHeal = heal;
                }
            } else { // SMALLEST_ABOVE
                boolean better;
                if (heal >= missing && bestHeal >= missing) {
                    better = heal < bestHeal;
                } else if (heal >= missing) {
                    better = true;
                } else if (bestHeal >= missing) {
                    better = false;
                } else {
                    better = heal > bestHeal; // fallback: at least the largest available
                }
                if (better) {
                    bestInv = i;
                    bestHeal = heal;
                }
            }
        }
        return new int[]{bestInv, bestHeal};
    }

    /** Manual hotkey: use the best health potion exactly like auto-use would, but
     *  without the missing-health threshold (the press is a deliberate action).
     *  Not gated on adventure mode — hotkeys work in any game mode. */
    public static void useHealthPotion(MinecraftClient client) {
        ModConfig cfg = NyaaInfiniteInfernalUtils.CONFIG;
        if (cfg == null || !cfg.autoUse.enabled) {
            return;
        }
        ClientPlayerEntity player = client.player;
        if (player == null || client.currentScreen != null) {
            return;
        }
        int[] sel = selectHealthPotion(player, cfg, player.getMaxHealth() - player.getHealth());
        if (sel[0] < 0) {
            return;
        }
        ItemStack placed = prepareSlot(client, player, sel[0], clampSlot(cfg.autoUse.slot));
        if (placed.isEmpty() || placed.getItem() != Items.IRON_NUGGET) {
            return;
        }
        press(client, false, false);
        log("[生命药剂] 手动使用（治疗量 " + sel[1] + "）");
    }

    // ===================================================================
    // 2. Mana potions
    // ===================================================================
    private static boolean tryMana(MinecraftClient client, ClientPlayerEntity player,
                                   ModConfig cfg, int slotIdx) {
        if (mana >= cfg.autoUse.manaPotion.threshold) {
            return false;
        }
        int[] sel = selectManaPotion(player, cfg);
        if (sel[0] < 0) {
            return false;
        }
        ItemStack placed = prepareSlot(client, player, sel[0], slotIdx);
        if (placed.isEmpty() || placed.getItem() != Items.IRON_NUGGET) {
            return false;
        }
        press(client, false, false);
        log("[魔力药剂] 使用了剂量 " + sel[1] + "（当前魔力 " + mana + "）");
        return true;
    }

    private static int[] selectManaPotion(ClientPlayerEntity player, ModConfig cfg) {
        PlayerInventory inv = player.getInventory();
        int bestInv = -1;
        int bestDose = -1;
        boolean preferLarge = cfg.autoUse.manaPotion.preferLargestPotion;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || s.getItem() != Items.IRON_NUGGET) {
                continue;
            }
            int dose = matchValue(s, MANA_POTIONS);
            if (dose < 0) {
                continue;
            }
            if (bestInv == -1) {
                bestInv = i;
                bestDose = dose;
                continue;
            }
            if (preferLarge) {
                if (dose > bestDose) {
                    bestInv = i;
                    bestDose = dose;
                }
            } else {
                if (dose < bestDose) {
                    bestInv = i;
                    bestDose = dose;
                }
            }
        }
        return new int[]{bestInv, bestDose};
    }

    /** Manual hotkey: use the best mana potion exactly like auto-use would.
     *  Not gated on adventure mode — hotkeys work in any game mode. */
    public static void useManaPotion(MinecraftClient client) {
        ModConfig cfg = NyaaInfiniteInfernalUtils.CONFIG;
        if (cfg == null || !cfg.autoUse.enabled) {
            return;
        }
        ClientPlayerEntity player = client.player;
        if (player == null || client.currentScreen != null) {
            return;
        }
        int[] sel = selectManaPotion(player, cfg);
        if (sel[0] < 0) {
            return;
        }
        ItemStack placed = prepareSlot(client, player, sel[0], clampSlot(cfg.autoUse.slot));
        if (placed.isEmpty() || placed.getItem() != Items.IRON_NUGGET) {
            return;
        }
        press(client, false, false);
        log("[魔力药剂] 手动使用（剂量 " + sel[1] + "）");
    }

    // ===================================================================
    // 2b. Food (right-click, held for the full eat duration)
    // ===================================================================
    private static boolean tryFood(MinecraftClient client, ClientPlayerEntity player,
                                   ModConfig cfg, int slotIdx) {
        int foodLevel = player.getHungerManager().getFoodLevel(); // 0..20
        int missing = 20 - foodLevel;
        if (missing < cfg.autoUse.food.threshold) {
            return false;
        }
        PlayerInventory inv = player.getInventory();
        // Don't auto-eat while the player is holding a blacklisted item (match ignores
        // text colour). Lets the player keep functional items (e.g. the inventor's
        // power backpack) in hand without the mod force-feeding them.
        ItemStack held = inv.getStack(inv.getSelectedSlot());
        if (!held.isEmpty()) {
            String heldPlain = Formatting.strip(held.getName().getString());
            for (String bl : cfg.autoUse.food.blacklist) {
                if (!bl.isBlank() && heldPlain.contains(bl)) {
                    foodRetryCooldown = FOOD_RETRY_COOLDOWN;
                    return false;
                }
            }
        }
        int bestAboveInv = -1;   // smallest nutrition that is ABOVE the missing amount
        int bestAboveNut = -1;
        int bestLargestInv = -1; // fallback: largest nutrition among all allowed foods
        int bestLargestNut = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) {
                continue;
            }
            int nut = foodNutrition(s);
            // RPG items without nutrition considered as fully saturating
            if (nut == 0) {
                nut = 20;
            }
            if (!isAllowedFood(s)) {
                continue;
            }
            if (nut > missing) {
                if (bestAboveInv == -1 || nut < bestAboveNut) {
                    bestAboveInv = i;
                    bestAboveNut = nut;
                }
            }
            if (bestLargestInv == -1 || nut > bestLargestNut) {
                bestLargestInv = i;
                bestLargestNut = nut;
            }
        }
        int chosen;
        int chosenNut;
        if (bestAboveInv >= 0) {
            chosen = bestAboveInv;
            chosenNut = bestAboveNut;
        } else if (bestLargestInv >= 0) {
            // Nothing covers the missing hunger: still eat the largest available so the
            // player does not starve (still within the allowed-food set).
            chosen = bestLargestInv;
            chosenNut = bestLargestNut;
        } else {
            return false;
        }
        ItemStack placed = prepareSlot(client, player, chosen, slotIdx);
        if (placed.isEmpty()) {
            return false;
        }
        // Hold right-click for the whole eat duration (food is not instant like potions).
        int eatTicks = eatTicksOf(placed);
        client.options.useKey.setPressed(true);
        pendingUse = 1;
        eatingTicks = eatTicks + 1;
        log("[自动进食] 正在食用 " + placed.getName().getString() + "（营养 " + chosenNut + "，缺 " + missing + "）");
        return true;
    }

    /** Nutrition (hunger points) of an edible stack, or -1 if it is not food. */
    private static int foodNutrition(ItemStack s) {
        FoodComponent fc = s.get(DataComponentTypes.FOOD);
        if (fc == null) {
            return -1;
        }
        return fc.nutrition();
    }

    /** True if the stack is an allowed food: a listed RPG food (matched ignoring text
     *  colour) OR a vanilla food with no custom lore. This keeps RPG special items
     *  from ever being eaten by mistake. */
    private static boolean isAllowedFood(ItemStack s) {
        String plain = Formatting.strip(s.getName().getString());
        for (String name : FOOD_NAMES) {
            if (plain.contains(name)) {
                return true;
            }
        }
        Identifier id = Registries.ITEM.getId(s.getItem());
        boolean vanilla = "minecraft".equals(id.getNamespace());
        boolean hasFood = s.get(DataComponentTypes.FOOD) != null;
        boolean hasLore = s.get(DataComponentTypes.LORE) != null;
        return vanilla && hasFood && !hasLore;
    }

    /** Number of ticks to hold right-click so the food finishes eating (+1 buffer).
     *  Standard Minecraft foods take 1.6s (32 ticks); we hold 33 to be safe.
     *  Foods whose name contains "卷" are eaten with a single click (no hold),
     *  so only a brief press is needed. */
    private static int eatTicksOf(ItemStack s) {
        if (Formatting.strip(s.getName().getString()).contains("卷")) {
            return 1;
        }
        return 33;
    }

    // ===================================================================
    // 3. Piggy bank (left-click, gold_nugget, until no 一文大钱)
    // ===================================================================

    /** True when the player is currently in a town and the piggy-bank
     *  "在城镇不生效" toggle is on, so deposits should be suppressed. */
    private static boolean inTown(ModConfig cfg) {
        if (!cfg.autoUse.piggyBank.disableInTown) {
            return false;
        }
        String region = RegionOverlay.currentRegion();
        if (region == null || region.isEmpty()) {
            return false;
        }
        for (String town : TOWN_REGIONS) {
            if (region.contains(town)) {
                return true;
            }
        }
        return false;
    }


    /** Start a piggy-bank deposit cycle. Returns true if started. */
    private static boolean tryPiggy(MinecraftClient client, ClientPlayerEntity player,
                                    ModConfig cfg, int slotIdx) {
        PlayerInventory inv = player.getInventory();
        int piggy = -1;
        int coins = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            String name = Formatting.strip(s.getName().getString());
            if (s.getItem() == Items.GOLD_NUGGET && (name.contains("新年存钱罐") || name.contains("微自动存钱猪"))) {
                piggy = i;
            } else if (s.getItem() == Items.IRON_NUGGET && name.contains("一文大钱")) {
                coins += s.getCount();
            }
        }
        if (piggy == -1 || coins < cfg.autoUse.piggyBank.threshold) return false;

        resetPiggyState();
        piggyOriginalSlot = inv.getSelectedSlot();

        if (piggy < 9) {
            // Already in hotbar — just select it and skip sync phase.
            piggyHotbarSlot = piggy;
            selectSlot(client, player, piggy);
            piggyPhase = 25; // go directly to release→attack
        } else {
            // Not in hotbar — need to swap into the use slot with sync delay.
            ItemStack placed = prepareSlot(client, player, piggy, slotIdx);
            if (placed.isEmpty() || placed.getItem() != Items.GOLD_NUGGET) {
                restoreSlot = piggyOriginalSlot;
                piggyOriginalSlot = -1;
                return false;
            }
            piggyHotbarSlot = slotIdx;
            piggyPhase = 1; // swap-sync countdown
            piggySwapSyncTicks = SWAP_SYNC_TICKS;
        }
        inLeftClickCycle = true;
        log("[存钱猪] 开始（" + coins + " 大钱）");
        return true;
    }

    // ===================================================================
    // 4. Soul collection brush (left-click while durability is full)
    // ===================================================================

    /** Start a soul-brush collection cycle. Returns true if started. */
    private static boolean tryBrushStart(MinecraftClient client, ClientPlayerEntity player, int slotIdx) {
        if (brushServerCooldownTicks > 0) return false;
        PlayerInventory inv = player.getInventory();
        int brush = -1;
        ItemStack brushStack = null;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || s.getItem() != Items.BRUSH) continue;
            if (s.getName().getString().contains("残魂收集刷")) { brush = i; brushStack = s; break; }
        }
        if (brush == -1 || brushStack == null) return false;

        int maxDmg = brushStack.getMaxDamage();
        if (maxDmg > 0 && brushStack.getDamage() > 0) return false;
        if (!hasFreeSlot(inv)) return false;

        resetBrushState();
        brushOriginalSlot = inv.getSelectedSlot();

        if (brush < 9) {
            // Already in hotbar — just select it, go directly to attack release frame.
            brushHotbarSlot = brush;
            selectSlot(client, player, brush);
            client.options.attackKey.setPressed(false);
            brushPhase = 25;
        } else {
            ItemStack placed = prepareSlot(client, player, brush, slotIdx);
            if (placed.isEmpty() || placed.getItem() != Items.BRUSH) {
                restoreSlot = brushOriginalSlot;
                brushOriginalSlot = -1;
                return false;
            }
            brushHotbarSlot = slotIdx;
            brushPhase = 1;
            brushSwapSyncTicks = SWAP_SYNC_TICKS;
        }
        inLeftClickCycle = true;
        log("[残魂刷] 开始");
        return true;
    }

    // ===================================================================
    // 5. Charge / Repair via lore "潜行+左键(充能|修复) 消耗X"
    // ===================================================================

    /** Start a handheld charge/repair cycle. Returns true if started. */
    private static boolean tryChargeRepairStart(MinecraftClient client, ClientPlayerEntity player,
                                                ModConfig cfg, int slotIdx) {
        PlayerInventory inv = player.getInventory();
        int bestInv = -1;
        boolean bestCharge = false;
        String bestConsumed = null;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            String lore = loreText(s);
            if (lore == null) continue;

            Matcher m = CHARGE_REPAIR_PATTERN.matcher(lore);
            if (!m.find()) continue;
            boolean charge = "充能".equals(m.group(1));
            if (charge && !cfg.autoUse.chargeRepair.chargeEnabled) continue;
            if (!charge && !cfg.autoUse.chargeRepair.repairEnabled) continue;

            int max = s.getMaxDamage();
            int dmg = s.getDamage();
            if (max <= 0) continue;

            boolean durabilityOk = dmg >= max / 2;               // 充能与修复统一：损伤 ≥ 50% 触发
            if (!durabilityOk) continue;

            String consumed = m.group(2).trim();
            if (consumed.isEmpty() || !hasItemNamed(inv, consumed)) continue;

            bestInv = i;
            bestCharge = charge;
            bestConsumed = consumed;
            break;
        }
        if (bestInv < 0) return false;

        resetCrState();
        crOriginalSlot = inv.getSelectedSlot();

        if (bestInv < 9 && bestInv == slotIdx) {
            // Already in target hotbar slot — select and go directly to sneak phase.
            selectSlot(client, player, bestInv);
            crHotbarSlot = bestInv;
            client.options.sneakKey.setPressed(true);
            pendingSneak = true;
            crPhase = 2;
            crSneakSyncTicks = SWAP_SYNC_TICKS;
        } else if (bestInv < 9) {
            // In hotbar but different slot — just select it.
            selectSlot(client, player, bestInv);
            crHotbarSlot = bestInv;
            client.options.sneakKey.setPressed(true);
            pendingSneak = true;
            crPhase = 2;
            crSneakSyncTicks = SWAP_SYNC_TICKS;
        } else {
            // Not in hotbar — need swap.
            ItemStack placed = prepareSlot(client, player, bestInv, slotIdx);
            if (placed.isEmpty()) {
                restoreSlot = crOriginalSlot;
                crOriginalSlot = -1;
                return false;
            }
            crHotbarSlot = slotIdx;
            crPhase = 1;
            crSwapSyncTicks = SWAP_SYNC_TICKS;
        }

        crIsCharge = bestCharge;
        crConsumed = bestConsumed;
        inLeftClickCycle = true;
        log("[手持修复] " + (bestCharge ? "充能" : "修复") + "（消耗 " + bestConsumed + "）");
        return true;
    }

    // ===================================================================
    // 5b. Charge / Repair armor (taken off the body, repaired, put back)
    // ===================================================================
    private static boolean tryChargeRepairArmora(MinecraftClient client, ClientPlayerEntity player,
                                                 ModConfig cfg, int slotIdx) {
        PlayerInventory inv = player.getInventory();
        int bestInv = -1;
        String bestConsumed = null;
        // Armor lives in inventory slots 36..39 (helmet / chestplate / leggings / boots).
        for (int a = 0; a < 4; a++) {
            int i = 36 + a;
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) {
                continue;
            }
            String lore = loreText(s);
            if (lore == null) {
                continue;
            }
            Matcher m = CHARGE_REPAIR_PATTERN.matcher(lore);
            if (!m.find()) {
                continue;
            }
            boolean charge = "充能".equals(m.group(1));
            if (charge) {
                continue; // 盔甲不存在充能，跳过
            }
            if (!cfg.autoUse.armoraRepair.repairEnabled) {
                continue;
            }
            int max = s.getMaxDamage();
            int dmg = s.getDamage();
            if (max <= 0) {
                continue;
            }
            boolean durabilityOk = dmg >= max / 2;               // remaining <= half
            if (!durabilityOk) {
                continue;
            }
            String consumed = m.group(2).trim();
            if (consumed.isEmpty() || !hasItemNamed(inv, consumed)) {
                continue;
            }
            bestInv = i;
            bestConsumed = consumed;
            break;
        }
        if (bestInv < 0) {
            return false;
        }
        // Take the piece OFF the body safely: first move the hotbar item out of the way,
        // then swap the armor into the now-empty hotbar slot. This avoids the issue where
        // a direct SWAP would try to put a non-armor item into an armor slot (which fails).
        armoraOriginalSlot = restoreSlot; // save the player's original slot
        restoreSlot = -1; // prevent releasePending from restoring during the cycle
        armoraSavedHotbarSlot = -1; // clear any previous stash reference
        ItemStack placed = prepareArmorToHotbar(client, player, bestInv, slotIdx);
        if (placed.isEmpty()) {
            restoreSlot = armoraOriginalSlot; // undo if preparation failed
            armoraOriginalSlot = -1;
            return false;
        }
        armoraPendingSlot = bestInv;   // remember where to return the piece to
        armoraHotbarSlot = slotIdx;
        armoraConsumed = bestConsumed; // remember consumed item name for re-checking in phase 4
        armoraPhase = 1;               // start phase 1: swap-sync countdown
        armoraSwapSyncTicks = SWAP_SYNC_TICKS;
        inLeftClickCycle = true;  // guard: prevent releasePending from dropping keys mid-cycle
        log("[盔甲修复] （消耗 " + bestConsumed + "）");
        return true;
    }

    // ===================================================================
    // 5c. Charge the 发明家动力背包 (hotbar item, furnace-based, no durability)
    // ===================================================================
    private static boolean tryChargeBackpack(MinecraftClient client, ClientPlayerEntity player,
                                              ModConfig cfg, int slotIdx) {
        // The backpack is a hotbar item (furnace-based), NOT an armor piece.
        // Find it in the hotbar (inventory slots 0..8).
        PlayerInventory inv = player.getInventory();
        int backpackSlot = -1;
        ItemStack backpack = null;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) {
                continue;
            }
            String plainName = Formatting.strip(s.getName().getString());
            if (plainName.contains("发明家动力背包")) {
                backpackSlot = i;
                backpack = s;
                break;
            }
        }
        if (backpackSlot < 0 || backpack == null) {
            return false;
        }
        // Check lore for "能量剩余\n(\d+)" — the backpack has no vanilla durability;
        // its energy is displayed in lore lines.
        String lore = loreText(backpack);
        if (lore == null) {
            return false;
        }
        Matcher m = ENERGY_PATTERN.matcher(lore);
        if (!m.find()) {
            return false;
        }
        int energy;
        try {
            energy = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return false;
        }
        // Only charge when energy <= 1 (essentially depleted).
        if (energy > 1) {
            return false;
        }

        // Select the backpack's slot. No swap needed — it's already in the hotbar.
        bpOriginalSlot = inv.getSelectedSlot(); // save the player's current slot
        selectSlot(client, player, backpackSlot);
        bpInvSlot = backpackSlot;
        bpPhase = 1; // start phase 1: select-sync countdown
        bpLockMovement = true; // zero out WASD movement while charging
        bpSelectSyncTicks = SWAP_SYNC_TICKS;
        inLeftClickCycle = true;
        log("[背包充能] 开始（能量 " + energy + "）");
        return true;
    }

    // ===================================================================
    // 6. Auto re-summon (heuristic bind + re-summon on disappearance)
    // ===================================================================

    /** A tracked summoned creature (invisible armor stand with a model). */
    private static final class SummonRecord {
        int entityId;
        /** The item that was right-clicked just before this creature appeared.
         *  null = not yet bound (pre-existing or appeared outside the bind window). */
        Item weaponItem;
        /** Stripped display name of the weapon item (used to re-find it in the inventory). */
        String weaponName;
        /** Last known world position of this creature (updated every tick while the entity exists). */
        double lastX, lastY, lastZ;
        /** Tick when this summon was bound (or last re-summoned). Used for timer-based re-summon. */
        long boundTick;

        SummonRecord(int entityId, double x, double y, double z, long tick) {
            this.entityId = entityId;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
            this.boundTick = tick;
        }
    }

    /** Every-tick scan: record right-click context, bind new summon stands, update
     *  positions. Must run even during globalCooldown so that positions and timing
     *  stay accurate. Does NOT perform any key-press actions. */
    private static void scanSummons(MinecraftClient client, ClientPlayerEntity player,
                                     ModConfig cfg) {
        ModConfig.AutoUseSettings.SummonResummonSettings s = cfg.autoUse.summonResummon;
        long tick = realTick;

        // 1) Record any active right-click as a potential "summon weapon use".
        boolean curUse = client.options.useKey.isPressed();
        if (curUse) {
            ItemStack held = player.getMainHandStack();
            if (!held.isEmpty()) {
                lastUseTick = tick;
                lastUseItem = held.getItem();
                lastUseName = Formatting.strip(held.getName().getString());
                // 若右键的是召唤武器，提前设置待绑定项。这样即便紧接着右键了
                // 别的东西（吃东西/放方块），lastUseItem 被覆盖，新召唤物仍能
                // 通过 pendingBindItem 正确绑定到武器，避免整条 re-summon 链断裂。
                if (SoundReplacer.isRightClickSummonWeapon(held)) {
                    pendingBindItem = held.getItem();
                    pendingBindName = lastUseName;
                    pendingBindTick = tick;
                }
            }
        }

        // 2) Expire stale pending-bind (our own re-summon expectation).
        if (pendingBindItem != null && (tick - pendingBindTick) > SUMMON_PENDING_BIND_WINDOW) {
            pendingBindItem = null;
            pendingBindName = null;
            pendingBindTick = -99999L;
        }

        // 3) Scan all "summon" armor stands in the world, bind new ones, update positions.
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        currentSummonIds = new HashSet<>();
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof ArmorStandEntity as)) continue;
            if (!isSummonStand(as)) continue;
            int id = e.getId();
            currentSummonIds.add(id);
            if (knownSummons.containsKey(id)) {
                SummonRecord existing = knownSummons.get(id);
                existing.lastX = e.getX();
                existing.lastY = e.getY();
                existing.lastZ = e.getZ();
                continue;
            }

            // New stand appeared — try to bind it.
            SummonRecord rec = new SummonRecord(id, e.getX(), e.getY(), e.getZ(), tick);
            double dx = e.getX() - px, dy = e.getY() - py, dz = e.getZ() - pz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist <= s.radius) {
                if (pendingBindItem != null) {
                    rec.weaponItem = pendingBindItem;
                    rec.weaponName = pendingBindName;
                    pendingBindItem = null;
                    pendingBindName = null;
                    pendingBindTick = -99999L;
                } else if (lastUseItem != null && (tick - lastUseTick) <= SUMMON_BIND_WINDOW) {
                    rec.weaponItem = lastUseItem;
                    rec.weaponName = lastUseName;
                }
            }
            knownSummons.put(id, rec);
        }
    }

    /** Check whether any tracked summon has reached its lifetime and needs re-summoning.
     *  Called every tick before globalCooldown (so re-summon key-press actions don't
     *  conflict with phase-based features like piggy/brush that already returned).
     *  Assumes scanSummons() has already been called this tick to populate currentSummonIds.
     *  Returns true if a re-summon action was performed this tick (caller should set
     *  globalCooldown and return). */
    private static boolean updateSummons(MinecraftClient client, ClientPlayerEntity player,
                                         ModConfig cfg, int slotIdx) {
        ModConfig.AutoUseSettings.SummonResummonSettings s = cfg.autoUse.summonResummon;
        long tick = realTick;
        int lifetimeTicks = s.lifetimeSeconds * 20;

        boolean didResummon = false;
        Set<String> resummonedThisTick = new HashSet<>();
        List<Integer> toRemove = new ArrayList<>();
        double px = player.getX(), py = player.getY(), pz = player.getZ();

        for (Map.Entry<Integer, SummonRecord> en : knownSummons.entrySet()) {
            int id = en.getKey();
            SummonRecord rec = en.getValue();
            boolean stillPresent = currentSummonIds != null && currentSummonIds.contains(id);
            long age = tick - rec.boundTick;

            if (rec.weaponItem == null || rec.weaponName == null) {
                if (!stillPresent) toRemove.add(id);
                continue;
            }

            double rdx = px - rec.lastX, rdy = py - rec.lastY, rdz = pz - rec.lastZ;
            double recDist = Math.sqrt(rdx * rdx + rdy * rdy + rdz * rdz);
            boolean inRange = recDist <= s.radius;

            if (!stillPresent) {
                toRemove.add(id);
                if (age >= lifetimeTicks && inRange) {
                    if (resummonedThisTick.contains(rec.weaponName)) continue;
                    resummonedThisTick.add(rec.weaponName);
                    if (tryResummonWeapon(client, player, cfg, slotIdx, rec, tick)) {
                        didResummon = true;
                    }
                }
                continue;
            }

            if (age >= lifetimeTicks) {
                if (!inRange) {
                    toRemove.add(id);
                    continue;
                }
                if (resummonedThisTick.contains(rec.weaponName)) continue;
                resummonedThisTick.add(rec.weaponName);
                if (tryResummonWeapon(client, player, cfg, slotIdx, rec, tick)) {
                    didResummon = true;
                    rec.boundTick = tick;
                }
            }
        }
        for (int id : toRemove) knownSummons.remove(id);
        return didResummon;
    }

    /** Attempt to re-summon by right-clicking the bound weapon in the inventory.
     *  Checks: weapon still present, durability >= 10% (or auto-repair enabled),
     *  per-weapon cooldown. Returns true if the re-summon action was performed. */
    private static boolean tryResummonWeapon(MinecraftClient client, ClientPlayerEntity player,
                                             ModConfig cfg, int slotIdx,
                                             SummonRecord rec, long tick) {
        String identity = rec.weaponName;

        // Per-weapon cooldown check.
        Long last = weaponLastResummonTick.get(identity);
        if (last != null && (tick - last) < cfg.autoUse.summonResummon.resummonCooldownTicks) {
            return false;
        }

        // Find the weapon in the player's inventory (by name, fall back to item type).
        int invIdx = findSummonWeapon(player.getInventory(), rec.weaponItem, rec.weaponName);
        if (invIdx < 0) {
            log("[自动重放] 召唤武器「" + rec.weaponName + "」不在背包中");
            weaponLastResummonTick.put(identity, tick);
            return false;
        }

        // Durability check: if remaining < 10% and no auto-repair → warn, don't re-summon.
        ItemStack weapon = player.getInventory().getStack(invIdx);
        if (weapon.getMaxDamage() > 0) {
            double remain = (weapon.getMaxDamage() - weapon.getDamage()) / (double) weapon.getMaxDamage();
            boolean autoRepair = cfg.autoUse.chargeRepair.repairEnabled || cfg.autoUse.chargeRepair.chargeEnabled;
            if (remain < 0.10 && !autoRepair) {
                log("[自动重放] 召唤武器「" + rec.weaponName + "」耐久过低（<10%），已停止");
                weaponLastResummonTick.put(identity, tick);
                return false;
            }
        }

        // Turn the player's view toward the original summon's last position before
        // re-summoning, so the weapon fires in the correct direction.
        double dx = rec.lastX - player.getX();
        double dz = rec.lastZ - player.getZ();
        double yaw = Math.toDegrees(Math.atan2(-dx, dz)); // Minecraft yaw convention
        double dy = rec.lastY - (player.getY() + player.getEyeHeight(player.getPose()));
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        double pitch = Math.toDegrees(Math.atan2(-dy, distXZ));
        player.setYaw((float) yaw);
        player.setPitch((float) pitch);

        // Re-summon: right-click the weapon at the current player position.
        ItemStack placed = prepareSlot(client, player, invIdx, slotIdx);
        if (placed.isEmpty()) return false;
        press(client, false, false); // right-click use (no sneak)
        weaponLastResummonTick.put(identity, tick);

        // Set pending-bind so the next new creature that appears is bound to this weapon,
        // regardless of the use-key detection window (our own re-summon is reliable).
        pendingBindItem = rec.weaponItem;
        pendingBindName = rec.weaponName;
        pendingBindTick = tick;

        log("[自动重放] 使用「" + rec.weaponName + "」重新召唤");
        return true;
    }

    /** Find the summon weapon in the player's inventory.
     *  Prefer exact stripped-name match; fall back to same item type if no name match. */
    private static int findSummonWeapon(PlayerInventory inv, Item item, String name) {
        int byItem = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || s.getItem() != item) continue;
            String n = Formatting.strip(s.getName().getString());
            if (n.equals(name)) return i;
            if (byItem < 0) byItem = i;
        }
        return byItem;
    }

    /** True if the armor stand is a "summoned creature": invisible + (optionally) has
     *  equipment. Reuses the summonGlow.requireEquipment setting from the config. */
    private static boolean isSummonStand(ArmorStandEntity as) {
        if (!as.isInvisible()) return false;
        ModConfig cfg = NyaaInfiniteInfernalUtils.CONFIG;
        if (cfg != null && cfg.summonGlow.requireEquipment) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (!as.getEquippedStack(slot).isEmpty()) return true;
            }
            return false;
        }
        return true;
    }

    // ===================================================================
    // Shared helpers
    // ===================================================================

    /** Moves the stack at inventory index {@code invIndex} into the hotbar slot
     *  {@code slotIdx}, selects that slot, and returns the (intended) stack.
     *  If the item is already in the hotbar (index 0..8), simply selects that slot
     *  instead of swapping — we never want to displace an existing hotbar item. */
    private static ItemStack prepareSlot(MinecraftClient client, ClientPlayerEntity player,
                                        int invIndex, int slotIdx) {
        PlayerInventory inv = player.getInventory();
        ItemStack target = inv.getStack(invIndex);
        if (target.isEmpty()) {
            return ItemStack.EMPTY;
        }
        // If the item is already in the hotbar, just switch to its actual slot.
        // Never swap a hotbar item to another hotbar position.
        if (invIndex < 9) {
            int prev = inv.getSelectedSlot();
            if (prev != invIndex) {
                restoreSlot = prev;
            }
            selectSlot(client, player, invIndex);
            return target;
        }
        int prev = inv.getSelectedSlot();
        if (prev != slotIdx) {
            // Remember the slot the player was holding so we can switch back after use.
            restoreSlot = prev;
        }
        if (!swapSlots(client, player, invIndex, slotIdx)) {
            return ItemStack.EMPTY;
        }
        selectSlot(client, player, slotIdx);
        // Return the intended item rather than inv.getStack(slotIdx): the local inventory
        // only updates after the server responds, so reading slotIdx here could report the
        // stale (pre-swap) stack and make callers wrongly think the move failed.
        return target;
    }

    /**
     * Moves an armor piece (from armor slot 36..39) into a hotbar slot safely.
     * Unlike {@link #prepareSlot} which does a direct SWAP (and would fail if the hotbar
     * item cannot be worn as armor), this method:
     *   1. Finds an empty inventory slot (0..35). Errors if none exist.
     *   2. Moves the current hotbar item to that empty slot (saves the slot index).
     *   3. Swaps the armor piece from its body slot into the now-empty hotbar slot.
     *
     * @param client       the Minecraft client
     * @param player       the client player
     * @param armorInvIndex the armor's PlayerInventory index (36..39)
     * @param hotbarSlotIdx the target hotbar slot (0..8)
     * @return the armor ItemStack, or EMPTY on failure
     */
    private static ItemStack prepareArmorToHotbar(MinecraftClient client, ClientPlayerEntity player,
                                                   int armorInvIndex, int hotbarSlotIdx) {
        PlayerInventory inv = player.getInventory();

        // Save player's original selected slot.
        int prev = inv.getSelectedSlot();
        if (prev != hotbarSlotIdx) {
            restoreSlot = prev;
        }

        // Step 1: Find an empty inventory slot (0..35, excluding current hotbar).
        int emptySlot = -1;
        for (int i = 0; i < 36; i++) {
            if (inv.getStack(i).isEmpty()) {
                emptySlot = i;
                break;
            }
        }
        if (emptySlot == -1) {
            log("[盔甲修复] 错误：背包已满，没有空位可以暂存快捷栏物品！无法取下盔甲修复。");
            restoreSlot = armoraOriginalSlot >= 0 ? armoraOriginalSlot : prev;
            return ItemStack.EMPTY;
        }

        // Step 2: Move the current hotbar item out of the way.
        ItemStack hotbarItem = inv.getStack(hotbarSlotIdx);
        if (!hotbarItem.isEmpty()) {
            log("[盔甲猪] 将快捷栏物品「" + Formatting.strip(hotbarItem.getName().getString())
                    + "」移到背包空位 " + emptySlot);
            if (!swapSlots(client, player, hotbarSlotIdx, emptySlot)) {
                log("[盔甲修复] 错误：无法将快捷栏物品移到空位！");
                restoreSlot = armoraOriginalSlot >= 0 ? armoraOriginalSlot : prev;
                return ItemStack.EMPTY;
            }
            armoraSavedHotbarSlot = emptySlot; // remember where we stashed it
        } else {
            armoraSavedHotbarSlot = -1; // hotbar was already empty, nothing to save
        }

        // Step 3: Swap armor piece from body into the (now-empty) hotbar slot.
        ItemStack armorPiece = inv.getStack(armorInvIndex); // capture before swap
        if (!swapSlots(client, player, armorInvIndex, hotbarSlotIdx)) {
            log("[盔甲修复] 错误：无法将盔甲从装备槽换入快捷栏！");
            // Try to undo: move the saved item back to hotbar
            if (armoraSavedHotbarSlot >= 0) {
                swapSlots(client, player, armoraSavedHotbarSlot, hotbarSlotIdx);
            }
            armoraSavedHotbarSlot = -1;
            restoreSlot = armoraOriginalSlot >= 0 ? armoraOriginalSlot : prev;
            return ItemStack.EMPTY;
        }

        selectSlot(client, player, hotbarSlotIdx);
        return armorPiece;
    }

    /** Swaps the stack at inventory index {@code fromInvIndex} with hotbar slot
     *  {@code toHotbarIndex} (SlotActionType.SWAP). Used both to move an item into
     *  the use slot and to move it back (e.g. armor onto / off the body). */
    private static boolean swapSlots(MinecraftClient client, ClientPlayerEntity player,
                                    int fromInvIndex, int toHotbarIndex) {
        int sourceScreen = screenSlotOf(player, fromInvIndex);
        if (sourceScreen < 0) {
            return false;
        }
        try {
            ScreenHandler sh = player.playerScreenHandler;
            // SlotActionType.SWAP: `sourceScreen` is the slot to swap, `toHotbarIndex`
            // (button) is the hotbar index to swap it with. The swap is applied
            // server-side in packet order, so a following click hits THIS item, not a
            // neighbouring one.
            client.interactionManager.clickSlot(
                    sh.syncId, sourceScreen, toHotbarIndex, SlotActionType.SWAP, player);
        } catch (Throwable t) {
            NyaaInfiniteInfernalUtils.LOGGER.warn("[infutils][autouse] swap failed: {}", t.getMessage());
            return false;
        }
        return true;
    }

    /** Resolves the screen-handler slot id for a given {@link PlayerInventory} index.
     *  Layout-independent: we match on the inventory + its local index instead of
     *  hard-coding hotbar/main offsets (which had an off-by-one bug that swapped the
     *  WRONG item into the use slot).
     *  For armor slots (36-39), ArmorSlot in MC 1.21.x may use a local armor list
     *  index (0-3) instead of the combined PlayerInventory index, so the primary
     *  lookup fails. We fall back to matching the slot's stack content against the
     *  expected armor piece. */
    private static int screenSlotOf(ClientPlayerEntity player, int invIndex) {
        PlayerInventory inv = player.getInventory();
        ScreenHandler sh = player.playerScreenHandler;
        for (int i = 0; i < sh.slots.size(); i++) {
            Slot slot = sh.getSlot(i);
            if (slot.inventory == inv && slot.getIndex() == invIndex) {
                return slot.id;
            }
        }
        // Fallback for armor slots (36-39): ArmorSlot may store a local armor-list
        // index (0-3) that doesn't match the combined PlayerInventory index (36-39).
        // Find the slot by matching the stack content with the expected armor piece.
        if (invIndex >= 36 && invIndex <= 39) {
            ItemStack expected = inv.getStack(invIndex);
            if (!expected.isEmpty()) {
                for (int i = 0; i < sh.slots.size(); i++) {
                    Slot slot = sh.getSlot(i);
                    ItemStack inSlot = slot.getStack();
                    if (inSlot == expected || (!inSlot.isEmpty() && ItemStack.areEqual(inSlot, expected))) {
                        log("[盔甲修复] screenSlotOf fallback: invIndex=" + invIndex
                                + " matched slot.id=" + slot.id + " (slot.getIndex()=" + slot.getIndex() + ")");
                        return slot.id;
                    }
                }
            }
        }
        return -1;
    }

    private static void selectSlot(MinecraftClient client, ClientPlayerEntity player, int slotIdx) {
        player.getInventory().setSelectedSlot(slotIdx);
        try {
            client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slotIdx));
        } catch (Throwable ignored) {
            // The client auto-syncs the selected slot anyway.
        }
    }

    private static void press(MinecraftClient client, boolean left, boolean sneak) {
        if (sneak) {
            client.options.sneakKey.setPressed(true);
            pendingSneak = true;
        }
        if (left) {
            // Use reflection to increment timesPressed counter so wasPressed() returns true
            // in the next handleInputEvents() call. setPressed(true) alone only sets the
            // pressed STATE but does NOT trigger wasPressed() edge detection!
            pressKeyEdge(client.options.attackKey);
            pendingUse = 2;
        } else {
            // Right-click (use key): also use timesPressed for consistency
            pressKeyEdge(client.options.useKey);
            pendingUse = 1;
        }
    }

    /**
     * Triggers a key-press event that Minecraft's {@code handleInputEvents()} will detect
     * via {@link KeyBinding#wasPressed()}.
     *
     * <p>IMPORTANT: {@code KeyBinding.setPressed(true)} only sets the <em>pressed state</em>.
     * The method {@code wasPressed()} checks an internal counter ({@code timesPressed}) which
     * is incremented by actual keyboard/mouse events. We must directly increment this counter
     * via reflection to simulate a real keypress.</p>
     *
     * @param key the KeyBinding to "press"
     */
    private static void pressKeyEdge(KeyBinding key) {
        try {
            Field fTimesPressed = KeyBinding.class.getDeclaredField("field_1661"); // timesPressed
            fTimesPressed.setAccessible(true);
            int current = fTimesPressed.getInt(key);
            fTimesPressed.setInt(key, current + 1);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            NyaaInfiniteInfernalUtils.LOGGER.warn("[infutils][autouse] Failed to trigger key-edge via reflection: {}", e.getMessage());
            // Fallback: try setPressed in case field name differs across versions
            key.setPressed(true);
        }
    }

    private static void releasePending(MinecraftClient client) {
        // When a left-click cycle is active (piggy/brush/armor-repair/backpack/cr),
        // do NOT release the keys — they are held across multiple ticks by design.
        if (inLeftClickCycle) {
            pendingUse = 0;  // clear the flag but keep keys pressed
            return;
        }
        if (pendingUse == 1) {
            client.options.useKey.setPressed(false);
        } else if (pendingUse == 2) {
            client.options.attackKey.setPressed(false);
        }
        pendingUse = 0;
        if (pendingSneak) {
            client.options.sneakKey.setPressed(false);
            pendingSneak = false;
        }
        // A single-press use is over: switch the hotbar back to the slot the player
        // was originally holding (the item we swapped in stays where it landed).
        restoreSlot(client);
    }

    /** Selects {@code restoreSlot} again (if set and different) and clears it. */
    private static void restoreSlot(MinecraftClient client) {
        if (restoreSlot < 0) {
            return;
        }
        ClientPlayerEntity p = client.player;
        if (p != null && p.getInventory().getSelectedSlot() != restoreSlot) {
            selectSlot(client, p, restoreSlot);
        }
        restoreSlot = -1;
    }

    private static int matchValue(ItemStack s, String[][] table) {
        String name = s.getName().getString();
        for (String[] entry : table) {
            if (name.contains(entry[0])) {
                try {
                    return Integer.parseInt(entry[1]);
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static boolean hasFreeSlot(PlayerInventory inv) {
        for (int i = 0; i < 36; i++) {
            if (inv.getStack(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasItemNamed(PlayerInventory inv, String fragment) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.getName().getString().contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String loreText(ItemStack s) {
        LoreComponent lore = s.get(DataComponentTypes.LORE);
        if (lore == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Text line : lore.lines()) {
            sb.append(line.getString()).append('\n');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static int clampSlot(int slot) {
        int idx = slot - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx > 8) {
            idx = 8;
        }
        return idx;
    }

    private static void log(String msg) {
        NyaaInfiniteInfernalUtils.LOGGER.info("[infutils][autouse] {}", msg);
        // Mirror the action into the in-game chat so the player can see it.
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.player != null) {
                // Translate the bracket prefix; the dynamic message itself stays as-is.
                String prefix = localize("chat.nyaa-infinite-infernal-utils.autouse.prefix", "[自动使用]");
                client.player.sendMessage(
                        Text.literal("§7" + prefix + " §f" + msg), false);
            }
        } catch (Throwable ignored) {
            // Never let a chat-send failure break the auto-use tick.
        }
    }

    // ===================================================================
    // State reset helpers
    // ===================================================================

    private static void resetPiggyState() {
        piggyPhase = 0;
        piggySwapSyncTicks = 0;
        piggyOriginalSlot = -1;
        piggyHotbarSlot = -1;
        piggyPreCoins = 0;
        piggyStaleCount = 0;
    }

    private static void resetBrushState() {
        brushPhase = 0;
        brushSwapSyncTicks = 0;
        brushOriginalSlot = -1;
        brushHotbarSlot = -1;
        brushPreEmptySlots = 0;
        brushStaleCount = 0;
    }

    private static void resetArmoraState() {
        armoraPendingSlot = -1;
        armoraHotbarSlot = -1;
        armoraOriginalSlot = -1;
        armoraSavedHotbarSlot = -1;
        armoraPhase = 0;
        armoraSwapSyncTicks = 0;
        armoraSneakSyncTicks = 0;
        armoraConsumed = null;
        armoraPreDamage = -1;
        armoraStaleCount = 0;
    }

    /** Release keys + put armor back onto body + restore saved hotbar item. Does NOT call resetArmoraState(). */
    private static void finishArmora(MinecraftClient client, ClientPlayerEntity player) {
        client.options.sneakKey.setPressed(false);
        pendingSneak = false;
        client.options.attackKey.setPressed(false);
        inLeftClickCycle = false;
        if (armoraPendingSlot >= 0 && armoraHotbarSlot >= 0) {
            swapSlots(client, player, armoraPendingSlot, armoraHotbarSlot);
        }
        if (armoraSavedHotbarSlot >= 0 && armoraHotbarSlot >= 0) {
            swapSlots(client, player, armoraSavedHotbarSlot, armoraHotbarSlot);
            armoraSavedHotbarSlot = -1;
        }
        if (armoraOriginalSlot >= 0 && player.getInventory().getSelectedSlot() != armoraOriginalSlot) {
            selectSlot(client, player, armoraOriginalSlot);
        }
    }

    private static void resetBackpackState() {
        bpInvSlot = -1;
        bpOriginalSlot = -1;
        bpPhase = 0;
        bpLockMovement = false;
        bpSelectSyncTicks = 0;
        bpSneakSyncTicks = 0;
        bpPreEnergy = -1;
        bpStaleCount = 0;
    }

    /** Exposed to the KeyboardInput mixin: zero out WASD while backpack is charging. */
    public static boolean isBackpackMovementLocked() {
        return bpLockMovement;
    }

    private static void resetCrState() {
        crPhase = 0;
        crSwapSyncTicks = 0;
        crSneakSyncTicks = 0;
        crOriginalSlot = -1;
        crHotbarSlot = -1;
        crIsCharge = false;
        crConsumed = null;
        crPreDamage = -1;
        crStaleCount = 0;
    }

    /** Release keys + restore original slot for handheld repair. */
    private static void finishCr(MinecraftClient client, ClientPlayerEntity player) {
        client.options.sneakKey.setPressed(false);
        pendingSneak = false;
        client.options.attackKey.setPressed(false);
        inLeftClickCycle = false;
        if (crOriginalSlot >= 0 && player.getInventory().getSelectedSlot() != crOriginalSlot) {
            selectSlot(client, player, crOriginalSlot);
        }
    }

    /** Looks up a translation key, returning {@code fallback} if the active language
     *  pack has no entry (so English / other locales still get a sensible string). */
    private static String localize(String key, String fallback) {
        try {
            Text t = Text.translatable(key);
            String translated = t.getString();
            // Text.translatable returns the raw key when no translation is present.
            if (translated != null && !translated.equals(key)) {
                return translated;
            }
        } catch (Throwable ignored) {
            // Fall through to the fallback below.
        }
        return fallback;
    }
}
