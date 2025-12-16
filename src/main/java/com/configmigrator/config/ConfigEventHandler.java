package com.configmigrator.config;

import com.configmigrator.ConfigMigrator;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = ConfigMigrator.MODID)
public final class ConfigEventHandler {

    private ConfigEventHandler() {}

    @SubscribeEvent
    public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (event.getModID() == null) return;
        if (!event.getModID().equals(ConfigMigrator.MODID)) return;

        // Sync in-memory config values (do NOT reload from disk here or we may overwrite GUI edits)
        ModConfig.syncFromConfig();
    }
}
