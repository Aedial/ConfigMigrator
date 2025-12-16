package com.configmigrator.config;

import com.configmigrator.ConfigMigrator;

import net.minecraft.util.text.translation.I18n;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration for the Config Migrator mod itself.
 */
public class ModConfig {
    private static final String CATEGORY_GENERAL = "general";
    private static final String IGNORED_KEYS_DESC = I18n.translateToLocal("config.configmigrator.ignoredKeys.comment");

    private static Configuration config;
    private static List<String> ignoredKeys;

    public ModConfig() {
    }

    public static void init(File configFile) {
        if (config == null) config = new Configuration(configFile);

        syncFromFile();
    }

    public static void syncFromFile() {
        config.load();
        // After loading from disk, sync into memory
        syncFromConfigInternal(false);
    }

    /**
     * Sync the in-memory config (called after GUI changes). Does not reload from disk so it won't clobber edits.
     */
    public static void syncFromConfig() {
        syncFromConfigInternal(true);
    }

    private static void syncFromConfigInternal(boolean avoidReload) {
        try {
            config.setCategoryLanguageKey(CATEGORY_GENERAL, "config.configmigrator.general");

            Property prop = config.get(CATEGORY_GENERAL, "ignoredKeys", new String[0], IGNORED_KEYS_DESC);
            prop.setLanguageKey("config.configmigrator.ignoredKeys");

            ignoredKeys = new ArrayList<>(Arrays.asList(prop.getStringList()));

            if (config.hasChanged()) config.save();
        } catch (Exception e) {
            ConfigMigrator.LOGGER.warn("Failed to sync mod config: {}", e.getMessage());
        }
    }

    public static Configuration getConfig() {
        return config;
    }

    public static List<String> getIgnoredKeys() {
        return new ArrayList<>(ignoredKeys);
    }

    /**
     * Adds new keys to the ignore list without removing existing ones.
     * Deduplicates automatically.
     */
    public static void mergeIgnoredKeys(String... newKeys) {
        boolean modified = false;

        for (String key : newKeys) {
            if (!ignoredKeys.contains(key)) {
                ignoredKeys.add(key);
                modified = true;
            }
        }

        if (modified) {
            // Update the config with merged list
            config.get(
                CATEGORY_GENERAL,
                "ignoredKeys",
                ignoredKeys.toArray(new String[0])
            ).set(ignoredKeys.toArray(new String[0]));

            config.save();
            ConfigMigrator.LOGGER.info("Added {} new ignored key patterns", newKeys.length);
        }
    }

    /**
     * Checks if a key should be ignored based on the ignore list.
     * Supports exact matches and wildcard patterns.
     */
    public static boolean shouldIgnoreKey(String key) {
        for (String pattern : ignoredKeys) {
            // Exact match
            if (pattern.equals(key)) return true;

            // Wildcard support: pattern.* matches pattern.anything
            if (pattern.endsWith(".*")) {
                String prefix = pattern.substring(0, pattern.length() - 2);
                if (key.startsWith(prefix + ".")) return true;
            }

            // Wildcard at start: *.suffix matches anything.suffix
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1);
                if (key.endsWith(suffix)) return true;
            }

            // Full wildcard in middle: prefix.*.suffix
            if (pattern.contains(".*")) {
                String regex = pattern.replace(".", "\\.").replace("*", ".*");
                if (key.matches(regex)) return true;
            }
        }

        return false;
    }
}
