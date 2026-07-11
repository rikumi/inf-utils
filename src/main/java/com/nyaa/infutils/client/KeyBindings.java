package com.nyaa.infutils.client;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class KeyBindings {

    public static KeyBinding spawnKey;
    public static KeyBinding backKey;
    public static KeyBinding healthPotionKey;
    public static KeyBinding manaPotionKey;

    private KeyBindings() {
    }

    /** Must be called from onInitializeClient (before GameOptions init). */
    public static void register() {
        KeyBinding.Category category = new KeyBinding.Category(
                Identifier.of(NyaaInfiniteInfernalUtils.MOD_ID, "commands")
        );
        spawnKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.nyaa-infinite-infernal-utils.spawn",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                category
        ));
        backKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.nyaa-infinite-infernal-utils.back",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_C,
                category
        ));
        healthPotionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.nyaa-infinite-infernal-utils.healthPotion",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                category
        ));
        manaPotionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.nyaa-infinite-infernal-utils.manaPotion",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                category
        ));
    }
}
