package com.nyaa.infutils.mixin;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import com.nyaa.infutils.config.ModConfig;
import com.nyaa.infutils.util.EliteGlow;
import com.nyaa.infutils.util.MonsterGlow;
import com.nyaa.infutils.util.SummonGlow;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In BRIGHTNESS mode, highlighted entities (怪物 mobs and summoned creatures)
 * are rendered with their own brightness boosted (instead of receiving a vanilla
 * glow outline). We do this by forcing the light level returned for the entity to
 * full bright, which brightens the entity's own model without adding an outline
 * around it.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityLightMixin {

    // Packed lightmap for full brightness (blockLight = 15, skyLight = 15).
    private static final int FULL_BRIGHT = 15728880;

    @Inject(method = "getLight", at = @At("HEAD"), cancellable = true)
    private void infutils_getLight(Entity entity, float tickDelta, CallbackInfoReturnable<Integer> cir) {
        if (!com.nyaa.infutils.client.FeatureGate.active()) {
            return;
        }
        ModConfig config = NyaaInfiniteInfernalUtils.CONFIG;
        if (config == null) {
            return;
        }

        // 精英怪 (elite mobs) always get full-bright — they also receive a
        // vanilla glow outline from EntityGlowMixin, so both effects apply.
        if (config.eliteGlow.enabled && EliteGlow.isElite(entity)) {
            cir.setReturnValue(FULL_BRIGHT);
            return;
        }

        // 怪物 mobs in BRIGHTNESS mode.
        if (config.monsterGlow.enabled
                && config.monsterGlow.glowMode == ModConfig.MonsterGlowSettings.MonsterGlowMode.BRIGHTNESS
                && MonsterGlow.isMonster(entity)) {
            cir.setReturnValue(FULL_BRIGHT);
            return;
        }

        // Summoned creatures in BRIGHTNESS mode.
        if (config.summonGlow.enabled
                && config.summonGlow.glowMode == ModConfig.SummonGlowSettings.SummonGlowMode.BRIGHTNESS
                && SummonGlow.isSpecial(entity)) {
            cir.setReturnValue(FULL_BRIGHT);
        }
    }
}
