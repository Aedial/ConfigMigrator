package com.configmigrator;

import com.configmigrator.config.ConfigTracker;
import com.configmigrator.config.ConfigWatcher;
import com.configmigrator.config.ModConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * Config Migrator - Helps migrate modified configuration values between Minecraft instances.
 *
 * Load order strategy:
 * - Uses "before:*" to ensure we load before all other mods
 * - Applies migrations in preInit, before other mods read their configs
 * - Captures defaults in postInit, after all mods have written their default configs
 */
@Mod(
    modid = ConfigMigrator.MODID,
    name = ConfigMigrator.NAME,
    version = ConfigMigrator.VERSION,
    acceptableRemoteVersions = "*",
    dependencies = "before:*",
    guiFactory = "com.configmigrator.config.ConfigGuiFactory"
)
public class ConfigMigrator {
    public static final String MODID = "configmigrator";
    public static final String NAME = "Config Migrator";
    public static final String VERSION = "1.1.0";

    public static Logger LOGGER;
    public static File minecraftDir;
    public static File configDir;

    private static ModConfig modConfig;
    private static ConfigTracker configTracker;
    private static ConfigWatcher configWatcher;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER = event.getModLog();
        minecraftDir = event.getModConfigurationDirectory().getParentFile();
        configDir = event.getModConfigurationDirectory();

        LOGGER.info("Config Migrator initializing...");

        // Load our own config first
        File modConfigFile = new File(configDir, MODID + ".cfg");
        ModConfig.init(modConfigFile);

        configTracker = new ConfigTracker(minecraftDir, configDir);

        // Capture defaults the modpack may be shipping with, before applying any migrations (to avoid capturing migrated values)
        configTracker.captureDefaults();

        // Load existing migrations file and apply them immediately
        // NOTE: Forge loads config files before preInit, so migrations are applied to disk
        // but won't take effect until the next restart
        configTracker.loadMigrationsFile();
        boolean migrationsApplied = configTracker.applyMigrations();

        // Reload our config to ensure any migrated values are reflected in-memory
        ModConfig.syncFromFile();

        if (migrationsApplied) {
            String separator = "================================================================================";
            LOGGER.warn(separator);
            LOGGER.warn("Config migrations have been applied to files on disk.");
            LOGGER.warn("RESTART THE GAME for changes to take effect!");
            LOGGER.warn("(Forge loads configs before mods can modify them)");
            LOGGER.warn(separator);
        }
    }

    @EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        // Re-capture defaults after all mods have initialized their configs
        configTracker.captureDefaults();

        // Perform initial scan: detect all current differences and write configs.json
        // Force full scan to establish the baseline (bypass timestamp optimization)
        configTracker.detectChangesAndSave(true);
        LOGGER.info("Initial config scan complete");

        // Start watching for config changes
        configWatcher = new ConfigWatcher(configDir, configTracker);
        configWatcher.start();

        LOGGER.info("Config Migrator initialized successfully");
    }

    @EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        // Force a final update when server stops
        if (configTracker != null) configTracker.forceUpdate();
    }

    public ConfigMigrator() {
        // Add shutdown hook to save configs on JVM exit (works for both client and server)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (configTracker != null) {
                LOGGER.info("Saving config migrations on shutdown...");
                configTracker.forceUpdate();
            }
        }, "ConfigMigrator-Shutdown"));
    }

    public static ConfigTracker getConfigTracker() {
        return configTracker;
    }

    public static ConfigWatcher getConfigWatcher() {
        return configWatcher;
    }

    public static void forceUpdate() {
        if (configTracker != null) configTracker.detectChangesAndSave();
    }
}
