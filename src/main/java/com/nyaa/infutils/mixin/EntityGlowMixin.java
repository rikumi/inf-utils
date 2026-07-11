package com.nyaa.infutils.mixin;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import com.nyaa.infutils.config.ModConfig;
import com.nyaa.infutils.util.EliteGlow;
import com.nyaa.infutils.util.MonsterGlow;
import com.nyaa.infutils.util.SummonGlow;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces a glow outline on selected entities:
 *  - "summoned creatures" (invisible armor stands), and
 *  - "怪物" mobs whose custom name starts with the configured prefix.
 * Also overrides the glow (team) color for both.
 */
@Mixin(Entity.class)
public abstract class EntityGlowMixin {

    private static int infutils_glowLogCount = 0;

    @Inject(method = "isGlowing", at = @At("HEAD"), cancellable = true)
    private void infutils_isGlowing(CallbackInfoReturnable<Boolean> cir) {
        if (!com.nyaa.infutils.client.FeatureGate.active()) {
            return;
        }
        Entity self = (Entity) (Object) this;
        ModConfig config = NyaaInfiniteInfernalUtils.CONFIG;

        // 精英怪 (elite mobs) always get BOTH outline glow AND brightness.
        // Check elite first — it takes priority over regular monster glow.
        if (EliteGlow.isElite(self)) {
            cir.setReturnValue(true);
            return;
        }

        // 怪物 mobs are highlighted. In OUTLINE mode they get the vanilla glow
        // outline; in BRIGHTNESS mode the brightness is boosted elsewhere, so we
        // leave the vanilla (non-glowing) behaviour here.
        if (MonsterGlow.isMonster(self)) {
            if (config != null
                    && config.monsterGlow.glowMode == ModConfig.MonsterGlowSettings.MonsterGlowMode.OUTLINE) {
                cir.setReturnValue(true);
            }
            return;
        }

        if (self instanceof ArmorStandEntity armorStand) {
            boolean invisible = armorStand.isInvisible();
            boolean hasEquip = SummonGlow.hasEquipment(armorStand);
            boolean special = SummonGlow.isSpecial(armorStand);
            if (infutils_glowLogCount < 30) {
                infutils_glowLogCount++;
                NyaaInfiniteInfernalUtils.LOGGER.info(
                        "[infutils][glow] isGlowing armorstand name={} invisible={} hasEquip={} special={}",
                        armorStand.getName().getString(), invisible, hasEquip, special);
            }
            if (special) {
                // In OUTLINE mode emit the vanilla glow outline; in BRIGHTNESS mode
                // the brightness is boosted by EntityLightMixin, so leave vanilla
                // (non-glowing) behaviour here.
                if (config != null
                        && config.summonGlow.glowMode == ModConfig.SummonGlowSettings.SummonGlowMode.OUTLINE) {
                    cir.setReturnValue(true);
                }
                return;
            } else if (invisible && !hasEquip) {
                // An empty (no items) invisible armor stand must never glow, even if
                // the server flagged it as glowing — only the armed "summoned
                // weapon" stands should display the outline.
                cir.setReturnValue(false);
            }
            // Otherwise leave the vanilla behaviour untouched.
        } else if (SummonGlow.isSpecial(self)) {
            if (config != null
                    && config.summonGlow.glowMode == ModConfig.SummonGlowSettings.SummonGlowMode.OUTLINE) {
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "getTeamColorValue", at = @At("HEAD"), cancellable = true)
    private void infutils_glowColor(CallbackInfoReturnable<Integer> cir) {
        if (!com.nyaa.infutils.client.FeatureGate.active()) {
            return;
        }
        Entity self = (Entity) (Object) this;
        // Elite mobs always use their own configured glow color
        if (EliteGlow.isElite(self) && NyaaInfiniteInfernalUtils.CONFIG != null) {
            cir.setReturnValue(NyaaInfiniteInfernalUtils.CONFIG.eliteGlow.glowColor);
            return;
        }
        if (MonsterGlow.isMonster(self) && NyaaInfiniteInfernalUtils.CONFIG != null
                && NyaaInfiniteInfernalUtils.CONFIG.monsterGlow.glowMode == ModConfig.MonsterGlowSettings.MonsterGlowMode.OUTLINE) {
            cir.setReturnValue(NyaaInfiniteInfernalUtils.CONFIG.monsterGlow.glowColor);
        } else if (SummonGlow.isSpecial(self) && NyaaInfiniteInfernalUtils.CONFIG != null
                && NyaaInfiniteInfernalUtils.CONFIG.summonGlow.glowMode == ModConfig.SummonGlowSettings.SummonGlowMode.OUTLINE) {
            cir.setReturnValue(NyaaInfiniteInfernalUtils.CONFIG.summonGlow.glowColor);
        }
    }
}
