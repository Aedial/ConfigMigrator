package com.configmigrator;

import com.configmigrator.config.ConfigTracker;
import com.configmigrator.config.ConfigWatcher;
import com.configmigrator.command.ForceUpdateCommand;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
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
    dependencies = "before:*"
)
public class ConfigMigrator {
    public static final String MODID = "configmigrator";
    public static final String NAME = "Config Migrator";
    public static final String VERSION = "1.0.0";

    public static Logger LOGGER;
    public static File minecraftDir;
    public static File configDir;

    private static ConfigTracker configTracker;
    private static ConfigWatcher configWatcher;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER = event.getModLog();
        minecraftDir = event.getModConfigurationDirectory().getParentFile();
        configDir = event.getModConfigurationDirectory();

        LOGGER.info("Config Migrator initializing...");

        configTracker = new ConfigTracker(minecraftDir, configDir);

        // Load existing migrations file and apply them immediately
        // This happens before other mods' preInit, so their config reads will see our changes
        // Safe to call even if config files don't exist yet - those are simply skipped
        configTracker.loadMigrationsFile();
        configTracker.applyMigrations();
    }

    @EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        // Re-capture defaults after all mods have initialized their configs
        configTracker.captureDefaults();

        // Start watching for config changes
        configWatcher = new ConfigWatcher(configDir, configTracker);
        configWatcher.start();

        LOGGER.info("Config Migrator initialized successfully");
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new ForceUpdateCommand());
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
