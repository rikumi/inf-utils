package com.nyaa.infutils.mixin;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import com.nyaa.infutils.client.AttackCurveRenderer;
import com.nyaa.infutils.config.ModConfig;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures server-sent particles and sounds at the network boundary.
 * <p>
 * In 1.21.9+ the client no longer routes server packets through
 * {@code ParticleManager.addParticle(Particle)} or {@code SoundManager.play(...)}.
 * Instead {@code ClientPlayNetworkHandler} forwards particles to
 * {@code ClientWorld.addParticleClient(...)} and sounds to
 * {@code ClientWorld.playSound(...)} / {@code playSoundFromEntity(...)}.
 * Those are the real entry points we must hook.
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {

    // ---- Particle capture (server particle packets land here) ----

    @Inject(method = "addParticleClient(Lnet/minecraft/particle/ParticleEffect;DDDDDD)V", at = @At("HEAD"), cancellable = true)
    private void infutils_captureParticle6(ParticleEffect effect, double x, double y, double z,
                                         double vx, double vy, double vz, CallbackInfo ci) {
        // 6-arg overload = a LOCAL (client-side) particle, e.g. the End gateway beam,
        // eating / drinking particles, player sprint clouds, etc. These are NOT sent by
        // the InfiniteInfernal server (its ability particles all use force=true and arrive
        // on the 8-arg overload). Treat them as non-attack and never turn them into curves.
        if (suppressParticle(effect, x, y, z, false)) {
            ci.cancel();
        }
    }

    @Inject(method = "addParticleClient(Lnet/minecraft/particle/ParticleEffect;ZZDDDDDD)V", at = @At("HEAD"), cancellable = true)
    private void infutils_captureParticle8(ParticleEffect effect, boolean force, boolean important,
                                         double x, double y, double z,
                                         double vx, double vy, double vz, CallbackInfo ci) {
        // 8-arg overload = a SERVER-SENT particle (ClientPlayNetworkHandler forwards
        // ParticleS2CPacket here). InfiniteInfernal's attack / ability particles all
        // arrive on this path. We flag isServer=true so the renderer can treat every
        // server particle as a candidate curve — the `force`/`forced` flag is NOT a
        // reliable attack discriminator (many attack particles arrive with force=false),
        // but "came from the server (8-arg)" is.
        if (suppressParticle(effect, x, y, z, true)) {
            ci.cancel();
        }
    }

    /** Capture the particle for curve building; cancel the sprite if replacement is on. */
    private boolean suppressParticle(ParticleEffect effect, double x, double y, double z, boolean isServer) {
        if (!com.nyaa.infutils.client.FeatureGate.active()) {
            return false;
        }
        boolean captured = AttackCurveRenderer.onServerParticle(effect, x, y, z, isServer);
        if (!captured) {
            return false;
        }
        ModConfig config = NyaaInfiniteInfernalUtils.CONFIG;
        return config != null && config.attackCurve.enabled && config.attackCurve.replaceParticleRendering;
    }

    // ---- Sound capture (server sound packets land here) ----

    @Inject(method = "playSound(Lnet/minecraft/entity/Entity;DDDLnet/minecraft/registry/entry/RegistryEntry;Lnet/minecraft/sound/SoundCategory;FFJ)V",
            at = @At("HEAD"), cancellable = true)
    private void infutils_captureSound(Entity source, double x, double y, double z,
                                     RegistryEntry<SoundEvent> sound, SoundCategory category,
                                     float volume, float pitch, long seed, CallbackInfo ci) {
        if (handleSound(sound, category, x, y, z, volume, pitch)) {
            ci.cancel();
        }
    }

    @Inject(method = "playSoundFromEntity(Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/Entity;Lnet/minecraft/registry/entry/RegistryEntry;Lnet/minecraft/sound/SoundCategory;FFJ)V",
            at = @At("HEAD"), cancellable = true)
    private void infutils_captureSoundFromEntity(Entity source, Entity target,
                                              RegistryEntry<SoundEvent> sound, SoundCategory category,
                                              float volume, float pitch, long seed, CallbackInfo ci) {
        double x = 0, y = 0, z = 0;
        if (target != null) {
            x = target.getX();
            y = target.getY();
            z = target.getZ();
        } else if (source != null) {
            x = source.getX();
            y = source.getY();
            z = source.getZ();
        }
        if (handleSound(sound, category, x, y, z, volume, pitch, target)) {
            ci.cancel();
        }
    }

    /**
     * @return true if the sound was replaced by a Terraria SFX (caller should cancel).
     *         Both registered and unregistered sounds are candidates — the InfiniteInfernal
     *         server plugin uses Bukkit {@code Sound} enum constants (registered vanilla IDs)
     *         for its weapon attack effects. SoundReplacer.matches() decides which ones to
     *         replace based on the known Infernal sound catalog and category.
     */
    private static boolean handleSound(RegistryEntry<SoundEvent> sound, SoundCategory category,
                                   double x, double y, double z,
                                   float volume, float pitch) {
        return handleSound(sound, category, x, y, z, volume, pitch, null);
    }

    private static boolean handleSound(RegistryEntry<SoundEvent> sound, SoundCategory category,
                                   double x, double y, double z,
                                   float volume, float pitch, Entity targetEntity) {
        // Determine the sound id and whether it is registered in the client's
        // SoundEvent registry. Inf weapon attack sounds are unregistered — the
        // server sends a custom sound id that the client has no sounds.json entry
        // for. Registered sounds (vanilla Sound enum constants) are real game
        // sounds and must not be replaced.
        String id;
        boolean unregistered;
        try {
            Identifier soundId = sound.value().id();
            id = soundId.toString();
            unregistered = !Registries.SOUND_EVENT.containsId(soundId);
        } catch (Throwable t) {
            // If we can't even resolve the id, treat it as unregistered.
            id = sound.getIdAsString();
            if (id == null) {
                id = "";
            }
            unregistered = true;
        }

        // Try to replace the server sound with a Terraria wand SFX.
        boolean replaced = false;
        try {
            replaced = com.nyaa.infutils.sound.SoundReplacer.tryReplace(
                    id, category, x, y, z, volume, pitch, targetEntity, unregistered);
        } catch (Throwable t) {
            NyaaInfiniteInfernalUtils.LOGGER.warn("[infutils][snd-replace] failed for '{}': {}", id, t.toString());
        }
        // Debug overlay: record every captured sound with its diagnostics.
        String catName = category != null ? category.getName() : "null";
        boolean candidate = com.nyaa.infutils.sound.SoundReplacer.isInfSoundCandidate(id, unregistered);
        com.nyaa.infutils.client.SoundDebug.record(
                id, catName, !unregistered, candidate, replaced,
                com.nyaa.infutils.sound.SoundReplacer.lastResolvedReplacementId,
                com.nyaa.infutils.sound.SoundReplacer.debugWeaponInfo());
        if (replaced) {
            NyaaInfiniteInfernalUtils.LOGGER.info("[infutils][snd-capture] replaced '{}' (unregistered={}) -> Terraria SFX", id, unregistered);
        }
        return replaced;
    }
}
