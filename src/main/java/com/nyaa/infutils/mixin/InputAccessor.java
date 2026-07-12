package com.nyaa.infutils.mixin;

import net.minecraft.client.input.Input;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for the protected movement fields declared in {@link Input}.
 * These are inherited by {@link net.minecraft.client.input.KeyboardInput}, so
 * they cannot be {@code @Shadow}ed directly in a KeyboardInput mixin.
 */
@Mixin(Input.class)
public interface InputAccessor {

    @Accessor("playerInput")
    PlayerInput infutils_getPlayerInput();

    @Accessor("playerInput")
    void infutils_setPlayerInput(PlayerInput value);

    @Accessor("movementVector")
    Vec2f infutils_getMovementVector();

    @Accessor("movementVector")
    void infutils_setMovementVector(Vec2f value);
}
