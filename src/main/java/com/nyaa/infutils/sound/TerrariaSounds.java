package com.nyaa.infutils.sound;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Registers the real Terraria weapon attack sounds that replace the server-sent
 * attack sounds on the client.
 * <p>
 * The {@code magic_itemN} / {@code summon_itemN} ids correspond 1:1 to the
 * Terraria Sound IDs documented at https://terraria.wiki.gg/wiki/Sound_IDs
 * (the wiki's {@code Item N} sounds whose Source column lists a player magic or
 * summon weapon). The five {@code wand_*} constants are friendly aliases used by
 * {@link com.nyaa.infutils.sound.InfernalSoundFilter}; each points at the most
 * representative real clip via {@code sounds.json}.
 */
public final class TerrariaSounds {

    private static final Map<String, SoundEvent> REGISTRY = new HashMap<>();

    // --- Friendly aliases (used by InfernalSoundFilter) -> real Terraria clips ---
    public static final SoundEvent WAND_SMALL  = reg("wand_small");   // basic magic bolt
    public static final SoundEvent WAND_MEDIUM = reg("wand_medium");  // Crystal Storm / Magic Missile
    public static final SoundEvent WAND_BIG    = reg("wand_big");     // Last Prism
    public static final SoundEvent SPELL_CAST  = reg("spell_cast");   // Ice / Rainbow Rod
    public static final SoundEvent MAGIC_ZAP   = reg("magic_zap");    // Heat Ray / Laser Rifle

    // --- Real Terraria MAGIC-weapon attack clips ---
    public static final SoundEvent MAGIC_ITEM8   = reg("magic_item8");   // Book of Skulls, Vilethorn, Wand of Sparking, Dark Caster / Goblin Sorcerer / Fire Imp casts
    public static final SoundEvent MAGIC_ITEM9   = reg("magic_item9");   // Crystal Storm, Magic Missile, Sky Fracture, Star Cannon
    public static final SoundEvent MAGIC_ITEM12  = reg("magic_item12");  // Heat Ray, Laser Rifle, Zapinator, Probe / Gastropod / Nebula Floater lasers
    public static final SoundEvent MAGIC_ITEM13  = reg("magic_item13");  // Aqua Scepter, Golden Shower, Last Prism
    public static final SoundEvent MAGIC_ITEM15  = reg("magic_item15");  // Phaseblades / Phasesabers, Last Prism, Medusa Head
    public static final SoundEvent MAGIC_ITEM20  = reg("magic_item20");  // Cursed Flames, Flamelash, Flower of Fire/Frost, Frost Staff, Magnet Sphere, Nebula Blaze, Unholy Trident
    public static final SoundEvent MAGIC_ITEM21  = reg("magic_item21");  // Water Bolt
    public static final SoundEvent MAGIC_ITEM27  = reg("magic_item27");  // Ice Rod, Rainbow Rod, Rune Wizard
    public static final SoundEvent MAGIC_ITEM32  = reg("magic_item32");  // Bat Scepter
    public static final SoundEvent MAGIC_ITEM43  = reg("magic_item43");  // gem staves, Poison / Spectre / Resonance Scepter / Thunder Zapper / Venom Staff
    public static final SoundEvent MAGIC_ITEM67  = reg("magic_item67");  // Nimbus Rod
    public static final SoundEvent MAGIC_ITEM68  = reg("magic_item68");  // Rainbow Gun
    public static final SoundEvent MAGIC_ITEM72  = reg("magic_item72");  // Shadowbeam Staff
    public static final SoundEvent MAGIC_ITEM73  = reg("magic_item73");  // Inferno Fork
    public static final SoundEvent MAGIC_ITEM85  = reg("magic_item85");  // Razorblade Typhoon
    public static final SoundEvent MAGIC_ITEM86  = reg("magic_item86");  // Bubble Gun
    public static final SoundEvent MAGIC_ITEM89  = reg("magic_item89");  // Meteor Staff, Lunar Flare
    public static final SoundEvent MAGIC_ITEM100 = reg("magic_item100"); // Clinger Staff
    public static final SoundEvent MAGIC_ITEM101 = reg("magic_item101"); // Crystal Vile Shard
    public static final SoundEvent MAGIC_ITEM118 = reg("magic_item118"); // Nebula Arcanum, Spirit Flame
    public static final SoundEvent MAGIC_ITEM158 = reg("magic_item158"); // Space Gun
    public static final SoundEvent MAGIC_ITEM159 = reg("magic_item159"); // Gray / Orange Zapinator

    // --- Real Terraria SUMMON-weapon attack clips ---
    public static final SoundEvent SUMMON_ITEM44  = reg("summon_item44");  // Pirate / Pygmy / Raven / Slime / Stardust Cell / Stardust Dragon / Tempest / Vampire Frog / Xeno Staff
    public static final SoundEvent SUMMON_ITEM45  = reg("summon_item45");  // Houndius Shootius Fireball Impact
    public static final SoundEvent SUMMON_ITEM46  = reg("summon_item46");  // Queen Spider Staff, Staff of the Frost Hydra
    public static final SoundEvent SUMMON_ITEM77  = reg("summon_item77");  // Hornet Staff
    public static final SoundEvent SUMMON_ITEM78  = reg("summon_item78");  // Imp Staff
    public static final SoundEvent SUMMON_ITEM79  = reg("summon_item79");  // Queen Spider / Lunar Portal / Rainbow Crystal Staff
    public static final SoundEvent SUMMON_ITEM83  = reg("summon_item83");  // Optic Staff, Nightglow, Terraprisma
    public static final SoundEvent SUMMON_ITEM84  = reg("summon_item84");  // Spider Staff
    public static final SoundEvent SUMMON_ITEM114 = reg("summon_item114"); // Deadly Sphere Staff
    public static final SoundEvent SUMMON_ITEM120 = reg("summon_item120"); // Phantasm Dragon summoned

    private TerrariaSounds() {
    }

    private static SoundEvent reg(String name) {
        Identifier id = Identifier.of(NyaaInfiniteInfernalUtils.MOD_ID, name);
        SoundEvent ev = Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
        REGISTRY.put(name, ev);
        return ev;
    }

    /** Resolve a sound id (e.g. {@code "magic_item9"} or {@code "wand_small"}) to its event. */
    public static SoundEvent byId(String name) {
        return REGISTRY.get(name);
    }

    /** @return true once our Terraria SoundEvents have been registered (in onInitialize). */
    public static boolean isRegistered() {
        return !REGISTRY.isEmpty();
    }

    public static void init() {
        NyaaInfiniteInfernalUtils.LOGGER.info("[infutils] Terraria sounds registered ({})", REGISTRY.size());
    }
}
