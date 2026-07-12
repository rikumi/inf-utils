package com.nyaa.infutils.sound;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import com.nyaa.infutils.config.ModConfig;
import com.nyaa.infutils.sound.InfernalSoundFilter;
import com.nyaa.infutils.util.SummonGlow;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces server attack sounds with Terraria-style wand / wizard SFX.
 * <p>
 * Driven by {@link net.minecraft.client.world.ClientWorld} sound injections:
 * when a server sound is captured and matches the configured filters, the
 * original sound is cancelled and one of our registered
 * {@link TerrariaSounds} is played instead (at the same position / volume).
 */
public final class SoundReplacer {

    /** Maps a server sound id substring to a Terraria sound. */
    private static final Map<String, SoundEvent> DEFAULT_MAP = new ConcurrentHashMap<>();

    static {
        // Map by obvious keywords; auto-mapping also handles "cast" / "spell" etc.
        DEFAULT_MAP.put("wand_small", TerrariaSounds.WAND_SMALL);
        DEFAULT_MAP.put("wand_medium", TerrariaSounds.WAND_MEDIUM);
        DEFAULT_MAP.put("wand_big", TerrariaSounds.WAND_BIG);
        DEFAULT_MAP.put("spell_cast", TerrariaSounds.SPELL_CAST);
        DEFAULT_MAP.put("magic_zap", TerrariaSounds.MAGIC_ZAP);
        // Per-weapon real Terraria clips are also addressable via the config `mapping`
        // (e.g. "crystal_storm=magic_item9", "pygmy=summon_item44").
    }

    private static final List<SoundEvent> ALL = List.of(
            TerrariaSounds.WAND_SMALL, TerrariaSounds.WAND_MEDIUM,
            TerrariaSounds.WAND_BIG, TerrariaSounds.SPELL_CAST, TerrariaSounds.MAGIC_ZAP);

    /** Whether the custom ogg files are actually present in the mod jar. */
    private static Boolean soundsAvailable = null;

    /** Last replacement SoundEvent id resolved by {@link #resolve} (for the debug overlay). */
    public static String lastResolvedReplacementId = "";

    // ---- Weapon lore parsing ----
    /** Lore line pattern: "42 基础近战伤害", "100 魔法伤害", "80 召唤伤害", "50 单次伤害", etc. */
    private static final Pattern DAMAGE_LORE = Pattern.compile("^\\d+ (基础)?(近战|远程|魔法|召唤|单次)伤害$");
    /** 武器使用方式。LEFT = 只能左键(攻击), RIGHT = 只能右键(使用), BOTH = 左右键都可以。 */
    private enum UseMode { LEFT, RIGHT, BOTH }

    /** 解析后的武器信息缓存。 */
    private static final class WeaponInfo {
        final String name;          // 去色后的物品名
        final String weaponType;    // "近战"/"远程"/"魔法"/"召唤"/"单次" — 来自 lore 伤害行
        final UseMode useMode;      // 左键/右键/左右键 — 来自 lore 操作描述

        WeaponInfo(String name, String weaponType, UseMode useMode) {
            this.name = name;
            this.weaponType = weaponType;
            this.useMode = useMode;
        }

        /** 是否是 Inf 武器（有伤害行 lore）。 */
        boolean isInfWeapon() {
            return weaponType != null;
        }

        /** 是否是召唤武器。 */
        boolean isSummon() {
            return "召唤".equals(weaponType);
        }
    }

    /** 按物品名+类型字符串缓存的 WeaponInfo（避免每 tick 重复解析 lore）。 */
    private static final Map<String, WeaponInfo> weaponCache = new ConcurrentHashMap<>();

    /** 解析物品的 lore，提取武器类型和使用方式。 */
    private static WeaponInfo parseWeapon(ItemStack stack) {
        String cacheKey = stack.getItem().toString() + "|" + Formatting.strip(stack.getName().getString());
        WeaponInfo cached = weaponCache.get(cacheKey);
        if (cached != null) return cached;

        String name = Formatting.strip(stack.getName().getString());
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        String weaponType = null;
        UseMode useMode = UseMode.LEFT; // 默认只能左键

        if (lore != null) {
            boolean hasLeftKey = false;
            boolean hasRightKey = false;
            for (Text line : lore.lines()) {
                String plain = Formatting.strip(line.getString());
                Matcher m = DAMAGE_LORE.matcher(plain);
                if (m.find()) {
                    weaponType = m.group(2); // 近战/远程/魔法/召唤/单次
                }
                if (plain.contains("左键")) hasLeftKey = true;
                if (plain.contains("右键")) hasRightKey = true;
            }
            // 使用方式判断：同时有"左键"和"右键"→左右键都可以; 只有"右键"→只能右键; 否则默认只能左键
            if (hasLeftKey && hasRightKey) {
                useMode = UseMode.BOTH;
            } else if (hasRightKey) {
                useMode = UseMode.RIGHT;
            } else {
                useMode = UseMode.LEFT;
            }
        }

        WeaponInfo info = new WeaponInfo(name, weaponType, useMode);
        weaponCache.put(cacheKey, info);
        return info;
    }

    /** 清除武器缓存（物品可能被修改了 lore，需要重新解析）。 */
    public static void clearWeaponCache() {
        weaponCache.clear();
    }

    // ---- Weapon use timing for context-based sound classification ----
    // Tracks recent player key presses so unregistered sounds can be classified
    // by weapon name even when no summon stand is nearby.
    // 现在区分左键和右键使用记录，并根据武器的 UseMode 过滤不匹配的按键。
    private static long realTick = 0;          // absolute game-tick counter (synced with AutoUse)
    private static WeaponInfo recentWeaponInfo = null;  // 最近使用武器时的 WeaponInfo
    private static long recentWeaponUseTick = -99999L;  // tick when player last pressed a valid weapon key
    private static final long WEAPON_USE_WINDOW = 20L;  // ticks within which a sound is considered from that weapon

    private SoundReplacer() {
    }

    /** Called by AutoUse each tick to sync the absolute tick counter and
     *  detect player weapon-use key presses for sound context classification.
     *  按键记录现在会根据武器的 UseMode 过滤：只能左键的武器不记录右键操作，
     *  只能右键的武器不记录左键操作。 */
    public static void syncTick(long tick) {
        realTick = tick;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        boolean attack = client.options.attackKey.isPressed();
        boolean use = client.options.useKey.isPressed();
        if (!attack && !use) return;

        ItemStack held = client.player.getMainHandStack();
        if (held.isEmpty()) return;
        WeaponInfo info = parseWeapon(held);
        if (!info.isInfWeapon()) return; // 非 Inf 武器（没有伤害行 lore），不记录

        // 根据武器的使用方式过滤按键
        boolean validPress = switch (info.useMode) {
            case LEFT  -> attack;       // 只能左键的武器：只记录左键
            case RIGHT -> use;           // 只能右键的武器：只记录右键
            case BOTH  -> attack || use; // 左右键都可以：记录任意按键
        };
        if (validPress) {
            recentWeaponInfo = info;
            recentWeaponUseTick = tick;
        }
    }

    /**
     * The feature is available once our Terraria SoundEvents have been registered
     * (done in {@code onInitialize}). We deliberately do NOT probe the raw .ogg
     * files via {@code ResourceManager.getResource}: that probe is fragile — it can
     * wrongly return false on its very first call and then stay cached false forever,
     * which silently disables every attack sound for the rest of the session. Since
     * the ogg files are now bundled in the jar, the registration check is both
     * necessary and sufficient. If a specific clip were somehow missing,
     * {@code play()} would simply be silent for that one sound, never a crash.
     */
    private static boolean soundsAvailable() {
        if (soundsAvailable != null) {
            return soundsAvailable;
        }
        soundsAvailable = com.nyaa.infutils.sound.TerrariaSounds.isRegistered();
        NyaaInfiniteInfernalUtils.LOGGER.info(
                "[infutils][snd-replace] replacement available = {}", soundsAvailable);
        return soundsAvailable;
    }

    /**
     * @return true if the original server sound should be suppressed (we played
     *         our own replacement); false if the original should still play.
     */
    public static boolean tryReplace(String serverSoundId, net.minecraft.sound.SoundCategory category,
                                     double x, double y, double z, float volume, float pitch, boolean unregistered) {
        return tryReplace(serverSoundId, category, x, y, z, volume, pitch, null, unregistered);
    }

    /**
     * @param targetEntity the entity the sound originated from (from playSoundFromEntity),
     *                     or null (from playSound at a fixed position).
     * @return true if the original server sound should be suppressed (we played
     *         our own replacement); false if the original should still play.
     */
    public static boolean tryReplace(String serverSoundId, net.minecraft.sound.SoundCategory category,
                                     double x, double y, double z, float volume, float pitch,
                                     Entity targetEntity, boolean unregistered) {
        if (!com.nyaa.infutils.client.FeatureGate.active()) {
            return false;
        }
        ModConfig config = NyaaInfiniteInfernalUtils.CONFIG;
        if (config == null || !config.soundReplace.enabled) {
            return false;
        }
        // Manual ogg placement required: without the files, leave the sound alone.
        if (!soundsAvailable()) {
            return false;
        }
        // Never replace music / records / ambient / weather / voice.
        if (category != null && isExcluded(config, category)) {
            return false;
        }
        if (!matches(config, serverSoundId, category, unregistered)) {
            return false;
        }

        SoundEvent replacement = resolve(config, serverSoundId, targetEntity, x, y, z);
        if (replacement == null) {
            lastResolvedReplacementId = "";
            return false;
        }
        lastResolvedReplacementId = replacement.id().toString();

        float v = config.soundReplace.volume * volume;
        float p = config.soundReplace.pitch * pitch;

        MinecraftClient client = MinecraftClient.getInstance();
        SoundManager soundManager = client.getSoundManager();
        net.minecraft.sound.SoundCategory cat = net.minecraft.sound.SoundCategory.PLAYERS;
        // 每次播放都用独立身份：同一武器快速连发时，后一次不会顶掉前一次的声音。
        // 1) 唯一 token 让 equals/hashCode 永不相等（SoundSystem.sources 以对象为 key）；
        // 2) 极小位置抖动避免任何基于“同位置同 id”的引擎层去重。
        double jx = x + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.02;
        double jy = y + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.02;
        double jz = z + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.02;
        PositionedSoundInstance instance = new IndependentSoundInstance(
                replacement, cat, v, p,
                net.minecraft.util.math.random.Random.create(), jx, jy, jz);

        soundManager.play(instance);
        return true;
    }

    /**
     * 每次播放都拥有唯一身份的 {@link PositionedSoundInstance}。
     * 默认 {@code PositionedSoundInstance} 不重写 equals/hashCode，本应按对象身份互不干扰；
     * 但为绝对保证“多次同音效独立播放、互不顶替”，这里强制以每次 new 的自增 token 作为相等性依据。
     */
    private static final class IndependentSoundInstance extends PositionedSoundInstance {
        private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();
        private final long token = SEQ.getAndIncrement();

        IndependentSoundInstance(net.minecraft.sound.SoundEvent event,
                                net.minecraft.sound.SoundCategory category,
                                float volume, float pitch,
                                net.minecraft.util.math.random.Random random,
                                double x, double y, double z) {
            super(event, category, volume, pitch, random, x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            return this == o;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(token);
        }
    }

    private static boolean matches(ModConfig config, String id, net.minecraft.sound.SoundCategory category, boolean unregistered) {
        // User-defined filters override everything.
        if (!config.soundReplace.matchFilters.isEmpty()) {
            String lower = id.toLowerCase(Locale.ROOT);
            for (String f : config.soundReplace.matchFilters) {
                if (!f.isBlank() && lower.contains(f.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
        // Default matching:
        //  - Unregistered (custom server) sounds: these can ONLY be Inf weapon sounds,
        //    because the client has no vanilla sound with an unregistered id. They may
        //    arrive in ANY category (the plugin emits summon / ranged / magic weapon
        //    sounds via World.playSound at a location, which the client receives in the
        //    MASTER category, NOT PLAYERS). We therefore no longer require PLAYERS and
        //    only gate on weapon context below.
        //  - Registered vanilla sounds: only replace those in the curated InfernalSoundFilter
        //    catalog (the exact ids the Inf plugin emits), and only while the player is
        //    wielding / recently used an Inf weapon (avoids replacing continuous ambient
        //    ability hums that play with no weapon in hand).
        if (!unregistered) {
            if (InfernalSoundFilter.isInfernalSound(id) && hasInfWeaponContext()) {
                return true;
            }
            return false;
        }
        // Unregistered path: gate on weapon context.
        return hasInfWeaponContext();
    }

    /** True when the player is currently holding an Inf weapon or recently used one. */
    private static boolean hasInfWeaponContext() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return false;
        // Recent weapon-use within the timing window is strongest evidence.
        if (recentWeaponInfo != null && recentWeaponInfo.isInfWeapon()
                && (realTick - recentWeaponUseTick) <= WEAPON_USE_WINDOW) {
            return true;
        }
        // No recent use — check held item.
        ItemStack held = client.player.getMainHandStack();
        if (!held.isEmpty()) {
            WeaponInfo heldInfo = parseWeapon(held);
            return heldInfo.isInfWeapon();
        }
        return false;
    }

    private static boolean isExcluded(ModConfig config, net.minecraft.sound.SoundCategory category) {
        String name = category.getName().toLowerCase(Locale.ROOT);
        for (String ex : config.soundReplace.excludeCategories) {
            if (!ex.isBlank() && name.contains(ex.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static SoundEvent resolve(ModConfig config, String serverSoundId,
                                      Entity targetEntity, double x, double y, double z) {
        // 1) explicit user mapping: "substring=wand_small"
        for (String entry : config.soundReplace.mapping) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String sub = entry.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String target = entry.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
            if (serverSoundId.toLowerCase(Locale.ROOT).contains(sub)) {
                SoundEvent ev = byName(target);
                if (ev != null) {
                    return ev;
                }
            }
        }

        // 2) Context-based: unregistered sounds have no identifiable ID, so we classify
        //    them by origin — nearest summon (armor stand) or player's held weapon name.
        String contextName = resolveContextName(targetEntity, x, y, z);
        if (contextName != null) {
            SoundEvent byContext = byContextName(contextName);
            if (byContext != null) {
                NyaaInfiniteInfernalUtils.LOGGER.info(
                        "[infutils][snd-replace] '{}' -> context '{}' -> {}",
                        serverSoundId, contextName, byContext.id().toString());
                return byContext;
            }
        }

        // 2b) Curated Infernal sound catalog (registered vanilla ids the plugin emits).
        SoundEvent infSound = InfernalSoundFilter.lookup(serverSoundId);
        if (infSound != null) {
            return infSound;
        }

        // 3) keyword auto map (fallback for IDs that contain recognizable fragments)
        String lower = serverSoundId.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, SoundEvent> e : DEFAULT_MAP.entrySet()) {
            if (lower.contains(e.getKey())) {
                return e.getValue();
            }
        }
        if (lower.contains("cast") || lower.contains("spell") || lower.contains("magic")) {
            return TerrariaSounds.SPELL_CAST;
        }
        if (lower.contains("beam") || lower.contains("laser") || lower.contains("zap")) {
            return TerrariaSounds.MAGIC_ZAP;
        }
        if (lower.contains("big") || lower.contains("heavy") || lower.contains("ultimate")) {
            return TerrariaSounds.WAND_BIG;
        }

        // 4) random pick among all variants
        if (config.soundReplace.randomize) {
            return ALL.get(ThreadLocalRandom.current().nextInt(ALL.size()));
        }
        return TerrariaSounds.WAND_MEDIUM;
    }

    /** Determine a "context name" for an unregistered sound:
     *  - If the sound originates from a summon (invisible armor stand), use its custom name.
     *  - If not from a summon, find the nearest summon within radius of the sound position.
     *  - If no summon nearby, use the most recent weapon-use WeaponInfo (filtered by key type).
     *  - If no recent weapon use, use the player's held weapon name.
     */
    private static String resolveContextName(Entity targetEntity, double x, double y, double z) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            return null;
        }
        ModConfig cfg = NyaaInfiniteInfernalUtils.CONFIG;
        double radius = cfg != null ? cfg.autoUse.summonResummon.radius : 16.0;
        boolean requireEquipment = cfg != null && cfg.summonGlow.requireEquipment;

        // If the sound came directly from a summon stand, use its name.
        if (targetEntity instanceof ArmorStandEntity as && as.isInvisible()) {
            if (!requireEquipment || SummonGlow.hasEquipment(as)) {
                String name = Formatting.strip(as.getDisplayName().getString());
                if (!name.isEmpty()) {
                    return name;
                }
            }
        }

        // Search for the nearest summon stand within radius of the sound position.
        double bestDist = radius * radius;
        String bestName = null;
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof ArmorStandEntity as)) continue;
            if (!as.isInvisible()) continue;
            if (requireEquipment && !SummonGlow.hasEquipment(as)) continue;
            double dx = e.getX() - x, dy = e.getY() - y, dz = e.getZ() - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                String name = Formatting.strip(as.getDisplayName().getString());
                if (!name.isEmpty()) {
                    bestName = name;
                }
            }
        }
        if (bestName != null) {
            return bestName;
        }

        // No nearby summon — check recent weapon use within the timing window.
        if (recentWeaponInfo != null && recentWeaponInfo.isInfWeapon()
                && (realTick - recentWeaponUseTick) <= WEAPON_USE_WINDOW) {
            return recentWeaponInfo.name;
        }

        // No recent weapon use — use the player's held weapon name.
        ItemStack held = client.player.getMainHandStack();
        if (!held.isEmpty()) {
            WeaponInfo heldInfo = parseWeapon(held);
            if (heldInfo.isInfWeapon()) {
                return heldInfo.name;
            }
        }
        return null;
    }

    // ============ 武器名称 -> Terraria 音效 显式覆盖表 ============
    // 依据 docs/inf2/items 中的武器名，将「发出声音的武器」匹配到最合适的 Terraria 音效。
    // 优先级高于按武器类别(byWeaponType)与通用关键词匹配，用于：
    //   1) 修正类别默认音效不合适的武器（如魔法灯笼虽属召唤类别，声音却偏魔法）；
    //   2) 为知名武器指定更贴合的 Terraria 原版音效（括号内为对应 Terraria 武器）。
    // 匹配规则：武器名包含某关键词即命中，多个命中时取「最长关键词」优先（更具体的武器胜出）。
    private static final Map<String, SoundEvent> WEAPON_OVERRIDES = new LinkedHashMap<>();
    static {
        // ---- 魔法武器（按 Terraria 原版音效细分）----
        WEAPON_OVERRIDES.put("魔法灯笼", TerrariaSounds.MAGIC_ITEM9);    // 召唤光灵，声音偏魔法 (Crystal Storm / Magic Missile)
        WEAPON_OVERRIDES.put("灯笼",     TerrariaSounds.MAGIC_ITEM9);    // 灯笼类兜底（如光灵上下文）
        WEAPON_OVERRIDES.put("水晶风暴", TerrariaSounds.MAGIC_ITEM9);    // Crystal Storm
        WEAPON_OVERRIDES.put("黄金雨",   TerrariaSounds.MAGIC_ITEM13);   // Golden Shower
        WEAPON_OVERRIDES.put("狱火叉",   TerrariaSounds.MAGIC_ITEM73);   // Inferno Fork
        WEAPON_OVERRIDES.put("绿宝石法杖", TerrariaSounds.MAGIC_ITEM43); // gem staves
        WEAPON_OVERRIDES.put("毒液枪",   TerrariaSounds.MAGIC_ITEM43);   // Venom Staff
        WEAPON_OVERRIDES.put("星云奥秘", TerrariaSounds.MAGIC_ITEM118);  // Nebula Arcanum
        WEAPON_OVERRIDES.put("夢想封印", TerrariaSounds.MAGIC_ITEM118);  // Spirit Flame
        WEAPON_OVERRIDES.put("星云闪耀", TerrariaSounds.MAGIC_ITEM118);  // Nebula
        WEAPON_OVERRIDES.put("星云水晶法杖", TerrariaSounds.MAGIC_ITEM118);
        WEAPON_OVERRIDES.put("月曜",     TerrariaSounds.MAGIC_ITEM89);   // Lunar Flare
        WEAPON_OVERRIDES.put("终极棱镜", TerrariaSounds.MAGIC_ITEM13);   // Last Prism
        WEAPON_OVERRIDES.put("霜冰法杖", TerrariaSounds.MAGIC_ITEM27);   // Ice Rod
        WEAPON_OVERRIDES.put("暴雪法杖", TerrariaSounds.MAGIC_ITEM27);   // Ice Rod
        WEAPON_OVERRIDES.put("奇妙法杖", TerrariaSounds.MAGIC_ITEM27);   // Rainbow Rod
        WEAPON_OVERRIDES.put("雾雨火花·绽放", TerrariaSounds.MAGIC_ITEM27);
        WEAPON_OVERRIDES.put("七彩画笔", TerrariaSounds.MAGIC_ITEM68);   // Rainbow Gun
        WEAPON_OVERRIDES.put("暗影焰刀", TerrariaSounds.MAGIC_ITEM72);   // Shadowbeam Staff
        WEAPON_OVERRIDES.put("暗影束法杖", TerrariaSounds.MAGIC_ITEM72);
        WEAPON_OVERRIDES.put("暗影利刃", TerrariaSounds.MAGIC_ITEM72);
        WEAPON_OVERRIDES.put("腐化法杖", TerrariaSounds.MAGIC_ITEM100);  // Clinger Staff
        WEAPON_OVERRIDES.put("恶魔触手", TerrariaSounds.MAGIC_ITEM100);
        WEAPON_OVERRIDES.put("水龙弹",   TerrariaSounds.MAGIC_ITEM21);   // Water Bolt
        WEAPON_OVERRIDES.put("山荷叶",   TerrariaSounds.MAGIC_ITEM21);
        WEAPON_OVERRIDES.put("雷神之戟", TerrariaSounds.MAGIC_ITEM12);   // Heat Ray / Laser Rifle
        WEAPON_OVERRIDES.put("闪电链法杖", TerrariaSounds.MAGIC_ITEM12);
        WEAPON_OVERRIDES.put("克雷贝尔狙击枪", TerrariaSounds.MAGIC_ITEM12);
        WEAPON_OVERRIDES.put("激光机枪", TerrariaSounds.MAGIC_ITEM158);  // Space Gun
        WEAPON_OVERRIDES.put("超电磁炮", TerrariaSounds.MAGIC_ITEM158);  // Space Gun
        WEAPON_OVERRIDES.put("魔法烟花", TerrariaSounds.MAGIC_ITEM89);   // Meteor Staff
        WEAPON_OVERRIDES.put("维度之杖", TerrariaSounds.MAGIC_ITEM89);
        WEAPON_OVERRIDES.put("花火",     TerrariaSounds.MAGIC_ITEM9);    // 火花弹 (generic magic projectile)
        // ---- 召唤武器（按 Terraria 原版音效细分）----
        WEAPON_OVERRIDES.put("七彩调色盘", TerrariaSounds.SUMMON_ITEM79); // Rainbow Crystal Staff
        WEAPON_OVERRIDES.put("万华镜",   TerrariaSounds.SUMMON_ITEM79);
        WEAPON_OVERRIDES.put("精密线控仪", TerrariaSounds.SUMMON_ITEM79); // Lunar Portal Staff
        WEAPON_OVERRIDES.put("星尘精灵", TerrariaSounds.SUMMON_ITEM120);  // Stardust Dragon
        WEAPON_OVERRIDES.put("星尘风暴法杖", TerrariaSounds.SUMMON_ITEM120);
        WEAPON_OVERRIDES.put("寒霜球法杖", TerrariaSounds.SUMMON_ITEM46); // Staff of the Frost Hydra
        WEAPON_OVERRIDES.put("箭雨法杖", TerrariaSounds.SUMMON_ITEM46);   // Queen Spider Staff
        WEAPON_OVERRIDES.put("爆炸机关法杖", TerrariaSounds.SUMMON_ITEM45);// Houndius Shootius Fireball Impact
    }

    /** 在显式覆盖表中按「最长关键词优先」匹配武器名，返回对应的 Terraria 音效；无命中返回 null。 */
    private static SoundEvent weaponOverride(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String bestKw = null;
        for (String kw : WEAPON_OVERRIDES.keySet()) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                if (bestKw == null || kw.length() > bestKw.length()) {
                    bestKw = kw;
                }
            }
        }
        return bestKw == null ? null : WEAPON_OVERRIDES.get(bestKw);
    }

    /** Maps a summon / weapon name to a fitting Terraria sound.
     *  优先使用已知 WeaponInfo 的 weaponType（来自 lore 伤害行）来精确分类，
     *  其次再用名称关键词匹配作为 fallback。 */
    private static SoundEvent byContextName(String name) {
        // 显式武器覆盖表（依据 docs/inf2/items）：最长关键词优先，先于类别/通用匹配
        SoundEvent ov = weaponOverride(name);
        if (ov != null) return ov;
        // 优先：查找 WeaponInfo 中是否有匹配此名称的武器（按名称匹配）
        WeaponInfo match = null;
        for (WeaponInfo info : weaponCache.values()) {
            if (info.isInfWeapon() && info.name.equals(name)) {
                match = info;
                break;
            }
        }
        // 如果有匹配的 WeaponInfo，优先按 weaponType 分类
        if (match != null) {
            SoundEvent byType = byWeaponType(match.weaponType);
            if (byType != null) return byType;
        }

        // Fallback: 名称关键词匹配（用于召唤物盔甲架等无法直接获取 lore 的实体）
        String lower = name.toLowerCase(Locale.ROOT);
        // Rainbow / prism / flower fire → Rainbow Rod / Last Prism sound
        if (containsAny(lower, "花火", "彩虹", "棱镜", "last prism", "rainbow", "crystal", "flower fire", "花")) {
            return TerrariaSounds.MAGIC_ITEM27;
        }
        // Summon / minion keywords → summon sounds
        if (containsAny(lower, "召唤", "仆从", "小兵", "史莱姆", "蜘蛛", "蜜蜂", "蝙蝠", "骷骼", "僵尸", "恶魔", "凤凰", "龙", "飞蛾", "幽灵", "乌鸦", "海盗", "吸血鬼", "图腾", "法杖", "圣杖")) {
            return TerrariaSounds.SUMMON_ITEM44;
        }
        if (containsAny(lower, "蜘蛛", "蛛")) {
            return TerrariaSounds.SUMMON_ITEM84;
        }
        if (containsAny(lower, "黄蜂", "大黄蜂", "蜂", "hornet")) {
            return TerrariaSounds.SUMMON_ITEM77;
        }
        if (containsAny(lower, "小恶魔", "恶魔", "imp")) {
            return TerrariaSounds.SUMMON_ITEM78;
        }
        // Star / stardust → fire/magic
        if (containsAny(lower, "星", "星尘", "stardust", "nebula")) {
            return TerrariaSounds.MAGIC_ITEM20;
        }
        // Magic weapon keywords → magic sounds
        if (containsAny(lower, "火", "焰", "炎", "flame", "fire", "inferno")) {
            return TerrariaSounds.MAGIC_ITEM20;
        }
        if (containsAny(lower, "冰", "霜", "寒", "frost", "ice")) {
            return TerrariaSounds.MAGIC_ITEM27;
        }
        if (containsAny(lower, "雷", "电", "激光", "thunder", "lightning", "laser", "zap")) {
            return TerrariaSounds.MAGIC_ITEM12;
        }
        if (containsAny(lower, "水", "雨", "波", "water", "rain", "bolt")) {
            return TerrariaSounds.MAGIC_ITEM21;
        }
        if (containsAny(lower, "影", "暗", "虚", "shadow", "dark", "void", "abyss")) {
            return TerrariaSounds.MAGIC_ITEM100;
        }
        if (containsAny(lower, "毒", "酸", "venom", "poison", "toxic")) {
            return TerrariaSounds.MAGIC_ITEM43;
        }
        if (containsAny(lower, "光", "圣", "神", "holy", "light", "divine", "sacred")) {
            return TerrariaSounds.MAGIC_ITEM9;
        }
        if (containsAny(lower, "弓", "枪", "炮", "bow", "gun", "cannon", "rifle")) {
            return TerrariaSounds.MAGIC_ITEM12;
        }
        // Big / ultimate weapons
        if (containsAny(lower, "终", "极", "究", "大", "ultimate", "mega", "supreme")) {
            return TerrariaSounds.WAND_BIG;
        }
        // Default medium for everything else
        return TerrariaSounds.WAND_MEDIUM;
    }

    /** 按 weaponType（从 lore 伤害行解析）选择对应的 Terraria 声音。 */
    private static SoundEvent byWeaponType(String weaponType) {
        if (weaponType == null) return null;
        switch (weaponType) {
            case "召唤": return TerrariaSounds.SUMMON_ITEM44;
            case "近战": return TerrariaSounds.WAND_MEDIUM;   // 近战 → 通用中档
            case "远程": return TerrariaSounds.MAGIC_ITEM12;  // 远程 → 激光/射击
            case "魔法": return TerrariaSounds.SPELL_CAST;    // 魔法 → 法术释放
            case "单次": return TerrariaSounds.WAND_SMALL;    // 单次 → 小型短促
            default: return null;
        }
    }

    private static boolean containsAny(String s, String... keywords) {
        for (String kw : keywords) {
            if (s.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static SoundEvent byName(String name) {
        return TerrariaSounds.byId(name);
    }

    /**
     * Snapshot of the current weapon context for the debug overlay.
     * Returns e.g. "held=焰之法杖(魔法) recent=yes" or "none".
     */
    public static String debugWeaponInfo() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return "none";
        StringBuilder sb = new StringBuilder();
        ItemStack held = client.player.getMainHandStack();
        if (!held.isEmpty()) {
            WeaponInfo info = parseWeapon(held);
            if (info.isInfWeapon()) {
                sb.append("held=").append(info.name).append("(").append(info.weaponType).append(")");
            } else {
                sb.append("held=").append(info.name).append("(non-Inf)");
            }
        } else {
            sb.append("held=empty");
        }
        boolean recent = recentWeaponInfo != null && recentWeaponInfo.isInfWeapon()
                && (realTick - recentWeaponUseTick) <= WEAPON_USE_WINDOW;
        sb.append(" recent=").append(recent ? "yes" : "no");
        return sb.toString();
    }

    /** True if this sound is an Inf weapon-sound candidate (would be eligible for replacement). */
    public static boolean isInfSoundCandidate(String id, boolean unregistered) {
        return unregistered || InfernalSoundFilter.isInfernalSound(id);
    }
}
