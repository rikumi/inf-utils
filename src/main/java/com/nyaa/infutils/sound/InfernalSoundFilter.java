package com.nyaa.infutils.sound;

import net.minecraft.sound.SoundEvent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in sound filter derived from the server plugin repository
 * (<i>InfiniteInfernal</i>, {@code cat.nyaa.infiniteinfernal}).
 * <p>
 * The plugin emits attack / ability sounds via {@code World.playSound(loc, Sound, vol, pitch)}
 * where {@code Sound} is a Bukkit {@link org.bukkit.Sound org.bukkit.Sound} enum constant
 * (config-driven {@code Sound.valueOf(NAME)} plus a handful of hardcoded ones). On the wire
 * those become vanilla {@code minecraft:} sound ids in dotted form, e.g. the Bukkit constant
 * {@code ENTITY_ELDER_GUARDIAN_CURSE} arrives on the client as
 * {@code minecraft:entity.elder_guardian.curse}.
 * <p>
 * This class hardcodes the <b>complete</b> set of ids the plugin can emit (extracted from
 * every ability's {@code *Sound} config field and inline {@code world.playSound(..., Sound.X, ...)}
 * call in the server repo) and maps each to the most fitting Terraria wand SFX. It is used as
 * the <b>default</b> filter when the user leaves {@code soundReplace.matchFilters} empty: only
 * these known ability sounds are replaced, so unrelated sounds (footsteps, doors, ambient,
 * eating, etc.) are left untouched.
 * <p>
 * A keyword safety-net ({@code isAttackLike}) still exists for the case where the server uses a
 * sound id we haven't catalogued, but it is intentionally narrow: only clearly combat / projectile
 * / spell words that would never appear in ambient or utility sounds.
 */
public final class InfernalSoundFilter {

    /** Dotted client sound id (without namespace) -> Terraria replacement. Insertion-ordered. */
    private static final Map<String, SoundEvent> MAP = new LinkedHashMap<>();

    private static void spell(String id)  { MAP.put(id, TerrariaSounds.SPELL_CAST); }
    private static void zap(String id)    { MAP.put(id, TerrariaSounds.MAGIC_ZAP); }
    private static void big(String id)    { MAP.put(id, TerrariaSounds.WAND_BIG); }
    private static void medium(String id) { MAP.put(id, TerrariaSounds.WAND_MEDIUM); }
    private static void small(String id)  { MAP.put(id, TerrariaSounds.WAND_SMALL); }

    static {
        // === Cast / prepare / mirror telegraphs -> airy spell cast ===
        // AbilityEchoStrike.markSound = ENTITY_ELDER_GUARDIAN_CURSE
        spell("entity.elder_guardian.curse");
        // AbilityMirrorImage.spawnSound = ENTITY_ILLUSIONER_CAST_SPELL
        spell("entity.illusioner.cast_spell");
        // AbilityPhaseShift.shiftInSound = ENTITY_ILLUSIONER_PREPARE_MIRROR
        spell("entity.illusioner.prepare_mirror");
        // AbilityShadowStep.revealSound = ENTITY_ILLUSIONER_PREPARE_BLINDNESS
        spell("entity.illusioner.prepare_blindness");
        // TemporalRift / MirrorImage / ShadowStep = ENTITY_ILLUSIONER_MIRROR_MOVE
        spell("entity.illusioner.mirror_move");

        // === Projectiles / beams / zaps -> sharp zap ===
        // AbilityEchoStrike.releaseSound = ENTITY_WITHER_SHOOT
        zap("entity.wither.shoot");
        // boss_shining_needle_castle.yml arrow.shoot
        zap("entity.arrow.shoot");
        // boss_shining_needle_castle.yml arrow.hit_player
        zap("entity.arrow.hit_player");
        // boss_shining_needle_castle.yml player.attack.sweep
        zap("entity.player.attack.sweep");

        // === Big impacts / explosions / heavy telegraphs -> big wand ===
        // ChainLightning.circuitSound = ENTITY_GENERIC_EXPLODE
        big("entity.generic.explode");
        // AbilityChainLightning.strikeSound = ENTITY_LIGHTNING_BOLT_THUNDER
        big("entity.lightning_bolt.thunder");
        // AbilitySoulHarvest.harvestSound = ENTITY_WITHER_SPAWN ; AbilityPoisonTrail death
        big("entity.wither.spawn");
        // AbilityEnrage.enrageSound = ENTITY_RAVAGER_ROAR
        big("entity.ravager.roar");
        // AbilityLeapSlam.leapSound = ENTITY_RAVAGER_ATTACK
        big("entity.ravager.attack");
        // AbilityEnrage.enrageSound = ENTITY_WARDEN_ANGRY
        big("entity.warden.angry");
        // boss_shining_needle_castle.yml ender_dragon.growl
        big("entity.ender_dragon.growl");

        // === Teleports / zones / ambient hums -> medium wand ===
        // GravityWell.spawnSound / Blink.blinkSound = ENTITY_ENDERMAN_TELEPORT
        medium("entity.enderman.teleport");
        // AbilityPhaseShift.warningSound = ENTITY_ENDERMAN_STARE
        medium("entity.enderman.stare");
        // (block.beacon.* removed: they are environment / utility hums from GravityWell /
        //  SoulHarvest / TemporalRift windows, NOT attack / ability sounds, so they must
        //  not be replaced even while wielding an Inf weapon.)
        // AbilityTemporalRift.activeSound = BLOCK_PORTAL_AMBIENT
        medium("block.portal.ambient");
        // AbilityX.useSound = BLOCK_ENCHANTMENT_TABLE_USE (magical use hum -> spell cast)
        spell("block.enchantment_table.use");
        // AbilityX.useSound = NOTE_BLOCK_BELL (bell chime telegraph -> wand swish)
        medium("block.note_block.bell");

        // === Light pings / pickups / minor fx -> small wand ===
        // ChainLightning.chainSound / EchoStrike flash = BLOCK_NOTE_BLOCK_PLING
        small("block.note_block.pling");
        // AbilityUltraStrike.telegraph / ParticleShield = BLOCK_NOTE_BLOCK_CHIME
        small("block.note_block.chime");
        // SoulHarvest.recoverySound = ENTITY_EXPERIENCE_ORB_PICKUP
        small("entity.experience_orb.pickup");
        // AbilityPhaseShift.thresholdSound = ENTITY_PLAYER_LEVELUP
        small("entity.player.levelup");
        // AbilityNirvana.respawn = ITEM_TOTEM_USE
        small("item.totem.use");
        // AbilityThorns.reflect = ENCHANT_THORNS_HIT
        small("enchant.thorns.hit");
        // AbilityWebShot.shootSound = ENTITY_SPIDER_AMBIENT
        small("entity.spider.ambient");
        // AbilityWebShot.hitSound = BLOCK_COBWEB_PLACE
        small("block.cobweb.place");
        // AbilityLifeSteal = ENTITY_ENDERMITE_AMBIENT
        small("entity.endermite.ambient");
        // AbilityPoisonTrail.deathSound = ENTITY_PUFFER_FISH_BLOW_UP
        small("entity.puffer_fish.blow_up");
    }

    private InfernalSoundFilter() {
    }

    /** @return true if the given client sound id is one the InfiniteInfernal plugin emits. */
    public static boolean isInfernalSound(String serverSoundId) {
        return lookup(serverSoundId) != null;
    }

    /**
     * @return the curated Terraria replacement for this server sound id, or {@code null} if it
     *         is not one of the plugin's known sounds.
     */
    public static SoundEvent lookup(String serverSoundId) {
        if (serverSoundId == null) {
            return null;
        }
        String lower = serverSoundId.toLowerCase(Locale.ROOT);
        // Strip a leading "minecraft:" (or any "namespace:") so we match on the dotted path.
        int colon = lower.indexOf(':');
        String path = colon >= 0 ? lower.substring(colon + 1) : lower;
        SoundEvent direct = MAP.get(path);
        if (direct != null) {
            return direct;
        }
        // Robust fallback: substring match (handles slight id variations across versions).
        for (Map.Entry<String, SoundEvent> e : MAP.entrySet()) {
            if (path.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }
}
