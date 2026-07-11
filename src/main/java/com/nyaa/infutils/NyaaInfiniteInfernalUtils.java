package com.nyaa.infutils;

import com.nyaa.infutils.config.ModConfig;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NyaaInfiniteInfernalUtils implements ModInitializer {

    public static final String MOD_ID = "nyaa-infinite-infernal-utils";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static ModConfig CONFIG;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing NyaaCat Infinite Infernal Utils...");

        // Register config
        AutoConfig.register(ModConfig.class, GsonConfigSerializer::new);
        CONFIG = AutoConfig.getConfigHolder(ModConfig.class).getConfig();

        // Register custom Terraria-style wand / wizard attack sounds.
        com.nyaa.infutils.sound.TerrariaSounds.init();

        LOGGER.info("NyaaCat Infinite Infernal Utils initialized successfully!");
    }
}
