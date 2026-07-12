package com.nyaa.infutils.mixin;

import com.nyaa.infutils.client.AutoUse;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * During the backpack auto-charge cycle the player may still be holding WASD.
 * Some server-side checks (or the client/server movement state) prevent the
 * sneak+left-click charge from going through while movement keys are active.
 * Zero out the movement input after KeyboardInput.tick() so the charge behaves
 * like the player manually released the movement keys.
 */
@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void infutils_lockMovementDuringBackpackCharge(CallbackInfo ci) {
        if (!AutoUse.isBackpackMovementLocked()) {
            return;
        }
        InputAccessor input = (InputAccessor) (Object) this;
        PlayerInput pi = input.infutils_getPlayerInput();
        input.infutils_setPlayerInput(new PlayerInput(
                false, false, false, false,
                pi.jump(), pi.sneak(), pi.sprint()));
        input.infutils_setMovementVector(Vec2f.ZERO);
    }
}
