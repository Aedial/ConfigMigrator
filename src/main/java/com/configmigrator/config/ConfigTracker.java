package com.configmigrator.config;

import com.configmigrator.ConfigMigrator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Tracks configuration file changes and manages the migrations file.
 *
 * Design:
 * - Stores default key-value pairs per config file
 * - Uses modification time first, then MD5 hash for efficient change detection
 * - Migrations are applied by searching for keys anywhere in the file
 * - Handles config file reorganization between mod versions
 * - Stores defaults in zip format for fast I/O
 *
 * Performance:
 * - Enable profiling with JVM arg: -Dconfigmigrator.profile=true
 */
public class ConfigTracker {
    private static final String MIGRATIONS_FILE = "configs.json";
    private static final String DEFAULTS_DIR = "configmigrator";
    private static final String DEFAULTS_ZIP = "defaults.zip";
    private static final boolean PROFILING = Boolean.getBoolean("configmigrator.profile");

    private final File configDir;
    private final File migrationsFile;
    private final File defaultsDir;
    private final File defaultsZip;

    private final Gson gson;

    /**
     * In-memory metadata: MD5 hash and last known modification time.
     * Built at startup, maintained during runtime. Never persisted to disk.
     */
    private final Map<String, FileMetadata> fileMetadata = new ConcurrentHashMap<>();

    /**
     * Default key-value pairs per config file.
     * Map of relative path -> Map of key -> value
     */
    private final Map<String, Map<String, String>> defaultConfigs = new ConcurrentHashMap<>();

    /**
     * User modifications: key-value pairs that differ from defaults.
     * Map of relative path -> Map of key -> value
     */
    private final Map<String, Map<String, String>> modifiedConfigs = new ConcurrentHashMap<>();

    /**
     * Pending migrations loaded from configs.json
     */
    private Map<String, Map<String, String>> pendingMigrations = null;

    public ConfigTracker(File minecraftDir, File configDir) {
        this.configDir = configDir;
        this.migrationsFile = new File(minecraftDir, MIGRATIONS_FILE);
        this.defaultsDir = new File(configDir, DEFAULTS_DIR);
        this.defaultsZip = new File(defaultsDir, DEFAULTS_ZIP);

        this.gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

        // Ensure defaults directory exists
        if (!defaultsDir.exists()) defaultsDir.mkdirs();
        if (PROFILING) ConfigMigrator.LOGGER.info("Config profiling enabled (use -Dconfigmigrator.profile=true)");
    }

    private double profileStart() {
        return PROFILING ? (double) System.nanoTime() : 0.0;
    }

    private void profileEnd(double start, String operation) {
        if (PROFILING) {
            double duration =  Math.round(((double) System.nanoTime() - start) / 10_000.0) / 100.0;
            ConfigMigrator.LOGGER.info("[PROFILE] {} took {}ms", operation, duration);
        }
    }

    /**
     * Loads the configs.json migrations file if it exists.
     * Called early in preInit to prepare for applying migrations.
     */
    public void loadMigrationsFile() {
        if (!migrationsFile.exists()) {
            ConfigMigrator.LOGGER.info("No migrations file found at {}", migrationsFile.getAbsolutePath());
            return;
        }

        try (Reader reader = new InputStreamReader(new FileInputStream(migrationsFile), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, Map<String, String>>>(){}.getType();
            pendingMigrations = gson.fromJson(reader, type);

            if (pendingMigrations != null) {
                ConfigMigrator.LOGGER.info("Loaded {} config migrations from {}", pendingMigrations.size(), migrationsFile.getName());
            }
        } catch (Exception e) {
            ConfigMigrator.LOGGER.error("Failed to load migrations file: {}", e.getMessage());
            pendingMigrations = null;
        }
    }

    /**
     * Captures the current state of all config files as defaults.
     * Only captures NEW files that haven't been captured yet.
     * Existing defaults are IMMUTABLE and never overwritten.
     */
    public void captureDefaults() {
        double start = profileStart();
        loadDefaults();
        profileEnd(start, "Load defaults");

        int newFilesCaptured = 0;

        try {
            for (Path path : (Iterable<Path>) Files.walk(configDir.toPath())
                .filter(Files::isRegularFile)
                .filter(this::isConfigFile)
                .filter(this::isNotInDefaultsDir)::iterator) {

                if (captureDefaultIfNeeded(path)) newFilesCaptured++;
            }
        } catch (IOException e) {
            ConfigMigrator.LOGGER.error("Failed to walk config directory: {}", e.getMessage());
        }

        if (newFilesCaptured > 0) {
            start = profileStart();
            saveDefaults();
            profileEnd(start, "Save defaults");
            ConfigMigrator.LOGGER.info("Captured defaults for {} new config files", newFilesCaptured);
        }
    }

    private boolean isConfigFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".cfg") || name.endsWith(".toml") || name.endsWith(".properties") || name.endsWith(".conf");
    }

    private boolean isNotInDefaultsDir(Path path) {
        return !path.startsWith(defaultsDir.toPath());
    }

    private boolean captureDefaultIfNeeded(Path configPath) {
        String relativePath = configDir.toPath().relativize(configPath).toString().replace('\\', '/');

        try {
            String currentHash = computeMD5(configPath);
            long currentModTime = Files.getLastModifiedTime(configPath).toMillis();

            FileMetadata existing = fileMetadata.get(relativePath);
            Map<String, String> existingDefaults = defaultConfigs.get(relativePath);

            // If we have no defaults for this file, it's new - capture it
            if (existingDefaults == null) {
                Map<String, String> keyValues = parseConfigFile(configPath, relativePath);
                defaultConfigs.put(relativePath, keyValues);
                fileMetadata.put(relativePath, new FileMetadata(currentHash, currentModTime));
                ConfigMigrator.LOGGER.debug("Captured defaults for new file: {}", relativePath);
                return true;
            }

            // Just update metadata if it's missing
            if (existing == null) {
                fileMetadata.put(relativePath, new FileMetadata(currentHash, currentModTime));
            } else if (!existing.md5Hash.equals(currentHash)) {
                // File changed - update metadata only, defaults stay immutable
                fileMetadata.put(relativePath, new FileMetadata(currentHash, currentModTime));
            }

            return false;

        } catch (IOException e) {
            ConfigMigrator.LOGGER.warn("Failed to process config file {}: {}", relativePath, e.getMessage());
            return false;
        }
    }

    /**
     * Applies pending migrations from configs.json to the actual config files.
     * Searches for keys anywhere in the file.
     * 
     * @return true if any migrations were applied
     */
    public boolean applyMigrations() {
        if (pendingMigrations == null || pendingMigrations.isEmpty()) return false;

        ConfigMigrator.LOGGER.info("Applying config migrations...");

        boolean anyMigrated = false;

        for (Map.Entry<String, Map<String, String>> entry : pendingMigrations.entrySet()) {
            String relativePath = entry.getKey();
            Map<String, String> keyValueChanges = entry.getValue();

            // Special handling for our own config: merge ignored keys instead of replacing
            if (relativePath.equals(ConfigMigrator.MODID + ".cfg")) {
                applyModConfigMigrations(keyValueChanges);
                continue;
            }

            Path configPath = configDir.toPath().resolve(relativePath);

            if (!Files.exists(configPath)) {
                ConfigMigrator.LOGGER.warn("Config file does not exist, skipping: {}", relativePath);
                continue;
            }

            try {
                List<String> lines = new ArrayList<>(Files.readAllLines(configPath, StandardCharsets.UTF_8));
                boolean modified = false;
                Set<String> appliedKeys = new HashSet<>();

                // Track section hierarchy for .cfg files
                List<String> sectionStack = new ArrayList<>();

                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String trimmed = line.trim();

                    // Track section hierarchy for .cfg files
                    if (relativePath.endsWith(".cfg")) {
                        // Section start: "sectionName {"
                        if (trimmed.endsWith("{") && !trimmed.startsWith("#")) {
                            String sectionName = trimmed.substring(0, trimmed.length() - 1).trim();
                            if (!sectionName.isEmpty()) {
                                sectionStack.add(sectionName);
                                continue;
                            }
                        }

                        // Section end: "}"
                        if (trimmed.equals("}") && !sectionStack.isEmpty()) {
                            sectionStack.remove(sectionStack.size() - 1);
                            continue;
                        }
                    }

                    // Handle Forge multi-line arrays: S:key <
                    if (relativePath.endsWith(".cfg") && trimmed.endsWith("<")) {
                        int eqIndex = trimmed.indexOf('=');
                        if (eqIndex < 0) continue;

                        String key = trimmed.substring(0, eqIndex).trim();
                        String fullKey = buildFullKey(sectionStack, key);
                        if (!keyValueChanges.containsKey(fullKey)) continue;

                        String newValue = keyValueChanges.get(fullKey);
                        if (!newValue.startsWith("<")) continue; // Not a multi-line array value

                        // Find the end of the current array (line with just >)
                        int arrayStart = i;
                        int arrayEnd = i + 1;
                        while (arrayEnd < lines.size() && !lines.get(arrayEnd).trim().equals(">")) arrayEnd++;

                        // Build the new array lines with proper indentation
                        String indent = line.substring(0, line.indexOf(trimmed.charAt(0)));
                        String innerIndent = indent + "    "; // Standard 4-space indent for array values
                        List<String> newArrayLines = new ArrayList<>();
                        newArrayLines.add(line); // Keep the key=< line

                        String[] valueParts = newValue.split("\n");
                        for (int j = 1; j < valueParts.length; j++) {
                            String part = valueParts[j].trim();
                            if (part.equals(">")) {
                                newArrayLines.add(indent + ">");
                            } else {
                                newArrayLines.add(innerIndent + part);
                            }
                        }

                        // Replace the old array lines with new ones
                        for (int j = arrayEnd; j >= arrayStart; j--) lines.remove(j);
                        lines.addAll(arrayStart, newArrayLines);

                        // Adjust index to skip past the new array
                        i = arrayStart + newArrayLines.size() - 1;

                        modified = true;
                        appliedKeys.add(fullKey);
                        ConfigMigrator.LOGGER.debug("Applied migration for array key '{}' in {}", fullKey, relativePath);
                        continue;
                    }

                    // Handle regular key=value lines
                    String key = extractKey(line, relativePath);
                    if (key == null) continue;

                    String fullKey = relativePath.endsWith(".cfg") ? buildFullKey(sectionStack, key) : key;
                    if (!keyValueChanges.containsKey(fullKey)) continue;

                    String newValue = keyValueChanges.get(fullKey);
                    if (newValue.startsWith("<")) continue; // Multi-line array, handled above

                    String newLine = replaceValue(line, newValue, relativePath);

                    if (newLine != null && !newLine.equals(line)) {
                        lines.set(i, newLine);
                        modified = true;
                        appliedKeys.add(fullKey);
                        ConfigMigrator.LOGGER.debug("Applied migration for key '{}' in {}", fullKey, relativePath);
                    }
                }

                // Log keys that couldn't be applied (key no longer exists in file)
                for (String key : keyValueChanges.keySet()) {
                    if (!appliedKeys.contains(key)) {
                        ConfigMigrator.LOGGER.warn("Could not apply migration for key '{}' in {} - key not found", key, relativePath);
                    }
                }

                if (modified) {
                    Files.write(configPath, lines, StandardCharsets.UTF_8);
                    ConfigMigrator.LOGGER.info("Migrated config: {} ({} keys)", relativePath, appliedKeys.size());

                    // Update metadata for the modified file
                    String newHash = computeMD5(configPath);
                    long newModTime = Files.getLastModifiedTime(configPath).toMillis();
                    fileMetadata.put(relativePath, new FileMetadata(newHash, newModTime));

                    anyMigrated = true;
                }
            } catch (IOException e) {
                ConfigMigrator.LOGGER.error("Failed to apply migration to {}: {}", relativePath, e.getMessage());
            }
        }

        pendingMigrations = null;

        return anyMigrated;
    }

    /**
     * Special handling for our own mod config migrations.
     * For ignored keys arrays, we merge and dedupe instead of replacing.
     */
    private void applyModConfigMigrations(Map<String, String> keyValueChanges) {
        for (Map.Entry<String, String> entry : keyValueChanges.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            // Check if this is the ignoredKeys array
            if (key.equals("general.ignoredKeys") || key.endsWith(".ignoredKeys")) {
                // Parse the array value and merge into config
                if (value.startsWith("<") && value.endsWith(">")) {
                    String[] lines = value.split("\n");
                    List<String> newKeys = new ArrayList<>();

                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (!trimmed.equals("<") && !trimmed.equals(">") && !trimmed.isEmpty()) {
                            newKeys.add(trimmed);
                        }
                    }

                    if (!newKeys.isEmpty()) {
                        ModConfig.mergeIgnoredKeys(newKeys.toArray(new String[0]));
                        ConfigMigrator.LOGGER.info("Merged {} ignored key patterns into mod config", newKeys.size());
                    }
                }
            }
        }
    }

    /**
     * Detects changes from defaults and saves to configs.json.
     * Call with forceFullScan=true on initial startup to build the baseline.
     */
    public synchronized void detectChangesAndSave() {
        detectChangesAndSave(false, null);
    }

    /**
     * Detects changes from defaults and saves to configs.json.
     * 
     * @param forceFullScan if true, forces full re-parsing of all files (used on initial startup)
     */
    public synchronized void detectChangesAndSave(boolean forceFullScan) {
        detectChangesAndSave(forceFullScan, null);
    }

    /**
     * Detects changes for specific files plus checks for new files, then saves.
     * 
     * @param dirtyFiles set of relative paths to files that changed
     */
    public synchronized void detectChangesAndSave(Set<String> dirtyFiles) {
        detectChangesAndSave(false, dirtyFiles);
    }

    /**
     * Detects changes from defaults and saves to configs.json.
     * 
     * @param forceFullScan if true, forces full re-parsing of all files
     * @param dirtyFiles if non-null, only check these specific files (plus detect new files)
     */
    private synchronized void detectChangesAndSave(boolean forceFullScan, Set<String> dirtyFiles) {
        double start = profileStart();
        
        // Preserve previous modifications for unchanged files (timestamp fast-path)
        Map<String, Map<String, String>> previousModifications = forceFullScan ? 
            new ConcurrentHashMap<>() : new ConcurrentHashMap<>(modifiedConfigs);
        
        // If checking specific dirty files, preserve all non-dirty files' modifications
        if (dirtyFiles != null && !forceFullScan) {
            modifiedConfigs.clear();
            // Restore all non-dirty files immediately
            for (Map.Entry<String, Map<String, String>> entry : previousModifications.entrySet()) {
                if (!dirtyFiles.contains(entry.getKey())) {
                    modifiedConfigs.put(entry.getKey(), entry.getValue());
                }
            }
        } else {
            modifiedConfigs.clear();
        }

        try {
            if (dirtyFiles != null) {
                // Check only dirty files + scan for new files
                for (String relativePath : dirtyFiles) {
                    Path configPath = configDir.toPath().resolve(relativePath);
                    if (Files.exists(configPath)) {
                        detectChanges(configPath, previousModifications, forceFullScan);
                    }
                }
                // Also check for new files not in defaults
                checkForNewFiles(previousModifications, forceFullScan);
            } else {
                // Full scan of all files
                Files.walk(configDir.toPath())
                    .filter(Files::isRegularFile)
                    .filter(this::isConfigFile)
                    .filter(this::isNotInDefaultsDir)
                    .forEach(path -> detectChanges(path, previousModifications, forceFullScan));
            }
        } catch (IOException e) {
            ConfigMigrator.LOGGER.error("Failed to walk config directory: {}", e.getMessage());
            return;
        }

        profileEnd(start, "Detect config changes");

        start = profileStart();
        saveMigrationsFile();
        profileEnd(start, "Save migrations file");
    }

    /**
     * Checks for new config files that don't have defaults yet.
     */
    private void checkForNewFiles(Map<String, Map<String, String>> previousModifications, boolean forceFullScan) {
        try {
            Files.walk(configDir.toPath())
                .filter(Files::isRegularFile)
                .filter(this::isConfigFile)
                .filter(this::isNotInDefaultsDir)
                .forEach(path -> {
                    String relativePath = configDir.toPath().relativize(path).toString().replace('\\', '/');
                    // If we don't have defaults for this file, it's new
                    if (!defaultConfigs.containsKey(relativePath)) {
                        ConfigMigrator.LOGGER.info("Detected new config file: {}", relativePath);
                        captureDefaultIfNeeded(path);
                        // Also check it for changes
                        detectChanges(path, previousModifications, forceFullScan);
                    }
                });
        } catch (IOException e) {
            ConfigMigrator.LOGGER.warn("Failed to check for new files: {}", e.getMessage());
        }
    }

    private void detectChanges(Path configPath, Map<String, Map<String, String>> previousModifications, boolean forceFullScan) {
        String relativePath = configDir.toPath().relativize(configPath).toString().replace('\\', '/');

        // Skip our own files
        if (relativePath.startsWith(DEFAULTS_DIR + "/")) return;

        Map<String, String> defaults = defaultConfigs.get(relativePath);

        // Only track changes for files we have defaults for
        if (defaults == null) return;

        try {
            long currentModTime = Files.getLastModifiedTime(configPath).toMillis();
            FileMetadata metadata = fileMetadata.get(relativePath);

            // Fast path: if modification time hasn't changed AND not forcing full scan, file is definitely unchanged
            if (!forceFullScan && metadata != null && metadata.lastModified == currentModTime) {
                // File hasn't been touched - preserve any existing tracked changes
                // This prevents losing keys when another config file changes but this one doesn't
                Map<String, String> existingChanges = previousModifications.get(relativePath);
                if (existingChanges != null && !existingChanges.isEmpty()) {
                    modifiedConfigs.put(relativePath, existingChanges);
                }
                return;
            }

            // Timestamp changed (or forcing full scan) - verify with MD5 to catch timestamp-only changes
            String currentHash = computeMD5(configPath);
            if (!forceFullScan && metadata != null && metadata.md5Hash.equals(currentHash)) {
                // Just update the timestamp, content hasn't changed
                fileMetadata.put(relativePath, new FileMetadata(currentHash, currentModTime));
                // Still preserve existing changes
                Map<String, String> existingChanges = previousModifications.get(relativePath);
                if (existingChanges != null && !existingChanges.isEmpty()) {
                    modifiedConfigs.put(relativePath, existingChanges);
                }
                return;
            }

            // File actually changed, need to re-parse and detect modifications
            Map<String, String> current = parseConfigFile(configPath, relativePath);
            Map<String, String> changes = new LinkedHashMap<>();

            for (Map.Entry<String, String> entry : current.entrySet()) {
                String key = entry.getKey();
                String currentValue = entry.getValue();
                String defaultValue = defaults.get(key);

                // Skip ignored keys (caches, timestamps, etc.)
                if (ModConfig.shouldIgnoreKey(key)) continue;

                if (defaultValue != null && !defaultValue.equals(currentValue)) changes.put(key, currentValue);
            }

            if (!changes.isEmpty()) {
                modifiedConfigs.put(relativePath, changes);
                ConfigMigrator.LOGGER.debug("Detected {} changes in {}", changes.size(), relativePath);
            }

            // Update metadata with new hash and timestamp
            fileMetadata.put(relativePath, new FileMetadata(currentHash, currentModTime));

        } catch (IOException e) {
            ConfigMigrator.LOGGER.warn("Failed to read config file {}: {}", relativePath, e.getMessage());
        }
    }

    /**
     * Parses a config file and extracts key-value pairs.
     * Handles Forge's multi-line array syntax: key <\n value1\n value2\n >
     */
    private Map<String, String> parseConfigFile(Path configPath, String relativePath) throws IOException {
        List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
        return parseConfigLines(lines, relativePath);
    }

    /**
     * Extracts the key from a config line.
     */
    private String extractKey(String line, String filePath) {
        String trimmed = line.trim();

        // Skip comments and empty lines
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//") || trimmed.startsWith(";")) return null;

        // Skip section headers, braces, and multi-line array markers
        if (trimmed.startsWith("[") || trimmed.startsWith("{") || trimmed.startsWith("}") ||
            trimmed.equals("]") || trimmed.equals(")") || trimmed.equals(">") || trimmed.equals("<")) return null;

        if (filePath.endsWith(".cfg") || filePath.endsWith(".properties")) {
            // Forge cfg format: key=value or S:key=value or I:key=value etc.
            // Skip lines that are part of multi-line arrays (no = sign, or ends with <)
            int eqIndex = trimmed.indexOf('=');
            if (eqIndex < 0) return null;
            if (trimmed.endsWith("<")) return null; // Handled separately in parseConfigFile

            return trimmed.substring(0, eqIndex).trim();

        } else if (filePath.endsWith(".toml")) {
            // TOML format: key = value (but not [[section]])
            if (trimmed.startsWith("[[") || trimmed.startsWith("[")) return null;

            int eqIndex = trimmed.indexOf('=');
            if (eqIndex > 0) return trimmed.substring(0, eqIndex).trim();
        } else if (filePath.endsWith(".conf")) {
            // HOCON/conf format: key = value or key: value
            int eqIndex = trimmed.indexOf('=');
            int colonIndex = trimmed.indexOf(':');
            int sepIndex = (eqIndex > 0 && colonIndex > 0) ? Math.min(eqIndex, colonIndex) :
                           (eqIndex > 0 ? eqIndex : colonIndex);

            if (sepIndex > 0) return trimmed.substring(0, sepIndex).trim();
        }

        return null;
    }

    /**
     * Extracts the value from a config line.
     */
    private String extractValue(String line, String filePath) {
        String trimmed = line.trim();

        if (filePath.endsWith(".cfg") || filePath.endsWith(".properties")) {
            int eqIndex = trimmed.indexOf('=');
            if (eqIndex > 0) {
                // Handle empty values: key= should return ""
                if (eqIndex == trimmed.length() - 1) return "";
                return trimmed.substring(eqIndex + 1).trim();
            }
        } else if (filePath.endsWith(".toml")) {
            int eqIndex = trimmed.indexOf('=');
            if (eqIndex > 0) {
                if (eqIndex == trimmed.length() - 1) return "";
                return trimmed.substring(eqIndex + 1).trim();
            }
        } else if (filePath.endsWith(".conf")) {
            int eqIndex = trimmed.indexOf('=');
            int colonIndex = trimmed.indexOf(':');
            int sepIndex = (eqIndex > 0 && colonIndex > 0) ? Math.min(eqIndex, colonIndex) :
                           (eqIndex > 0 ? eqIndex : colonIndex);

            if (sepIndex > 0) {
                if (sepIndex == trimmed.length() - 1) return "";
                return trimmed.substring(sepIndex + 1).trim();
            }
        }

        return null;
    }

    /**
     * Replaces the value in a config line while preserving formatting.
     */
    private String replaceValue(String line, String newValue, String filePath) {
        if (filePath.endsWith(".cfg") || filePath.endsWith(".properties")) {
            int eqIndex = line.indexOf('=');
            if (eqIndex > 0) return line.substring(0, eqIndex + 1) + newValue;

        } else if (filePath.endsWith(".toml")) {
            int eqIndex = line.indexOf('=');
            if (eqIndex > 0) {
                // Preserve space after = if it existed
                String afterEq = line.substring(eqIndex + 1);
                String spacing = afterEq.startsWith(" ") ? " " : "";
                return line.substring(0, eqIndex + 1) + spacing + newValue;
            }
        } else if (filePath.endsWith(".conf")) {
            int eqIndex = line.indexOf('=');
            int colonIndex = line.indexOf(':');
            int sepIndex = (eqIndex > 0 && colonIndex > 0) ? Math.min(eqIndex, colonIndex) :
                           (eqIndex > 0 ? eqIndex : colonIndex);

            if (sepIndex > 0) return line.substring(0, sepIndex + 1) + " " + newValue;
        }

        return null;
    }

    /**
     * Computes MD5 hash of a file.
     */
    private String computeMD5(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = Files.readAllBytes(path);
            byte[] digest = md.digest(bytes);

            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));

            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 algorithm not available", e);
        }
    }

    private void saveMigrationsFile() {
        // Check if there are actual changes compared to what's on disk
        Map<String, Map<String, String>> existingMigrations = null;

        if (migrationsFile.exists()) {
            try (Reader reader = new InputStreamReader(new FileInputStream(migrationsFile), StandardCharsets.UTF_8)) {
                Type type = new TypeToken<Map<String, Map<String, String>>>(){}.getType();
                existingMigrations = gson.fromJson(reader, type);
            } catch (Exception e) {
                ConfigMigrator.LOGGER.warn("Failed to read existing migrations file for comparison: {}", e.getMessage());
            }
        }

        // Compare current state with existing state
        if (existingMigrations != null && existingMigrations.equals(modifiedConfigs)) {
            // No changes, don't save
            return;
        }

        // State has changed, save it
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(migrationsFile), StandardCharsets.UTF_8)) {
            gson.toJson(modifiedConfigs, writer);
            if (!modifiedConfigs.isEmpty()) {
                ConfigMigrator.LOGGER.info("Saved {} modified configs to {}", modifiedConfigs.size(), MIGRATIONS_FILE);
            }
        } catch (IOException e) {
            ConfigMigrator.LOGGER.error("Failed to save migrations file: {}", e.getMessage());
        }
    }

    private void loadDefaults() {
        // Try loading from zip
        if (defaultsZip.exists()) {
            try {
                loadDefaultsFromZip();
                return;
            } catch (Exception e) {
                ConfigMigrator.LOGGER.warn("Failed to load defaults from zip, trying JSON fallback: {}", e.getMessage());
            }
        }
    }

    private void loadDefaultsFromZip() throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(defaultsZip))) {
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                String relativePath = entry.getName();

                // Read the file content from zip
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int len;

                while ((len = zis.read(buffer)) > 0) baos.write(buffer, 0, len);

                // Parse the config file
                List<String> lines = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new ByteArrayInputStream(baos.toByteArray()), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) lines.add(line);
                }

                // Parse key-value pairs
                Map<String, String> keyValues = parseConfigLines(lines, relativePath);
                defaultConfigs.put(relativePath, keyValues);

                zis.closeEntry();
            }

            ConfigMigrator.LOGGER.debug("Loaded {} default configs from zip", defaultConfigs.size());
        }
    }

    private void saveDefaults() {
        // Save as zip
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(defaultsZip))) {
            zos.setLevel(6);
            zos.setMethod(ZipOutputStream.DEFLATED);

            for (Map.Entry<String, Map<String, String>> entry : defaultConfigs.entrySet()) {
                String relativePath = entry.getKey();
                Path configPath = configDir.toPath().resolve(relativePath);

                // Only save if the file still exists
                if (!Files.exists(configPath)) continue;

                ZipEntry zipEntry = new ZipEntry(relativePath);
                zos.putNextEntry(zipEntry);

                // Write the actual file content
                Files.copy(configPath, zos);

                zos.closeEntry();
            }

            ConfigMigrator.LOGGER.debug("Saved {} default configs to zip", defaultConfigs.size());
        } catch (IOException e) {
            ConfigMigrator.LOGGER.error("Failed to save defaults to zip: {}", e.getMessage());
        }
    }

    private Map<String, String> parseConfigLines(List<String> lines, String relativePath) {
        Map<String, String> keyValues = new LinkedHashMap<>();

        // Track section hierarchy for Forge .cfg files
        List<String> sectionStack = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();

            // Track section hierarchy for .cfg files
            if (relativePath.endsWith(".cfg")) {
                // Section start: "sectionName {"
                if (trimmed.endsWith("{") && !trimmed.startsWith("#")) {
                    String sectionName = trimmed.substring(0, trimmed.length() - 1).trim();
                    if (!sectionName.isEmpty()) {
                        sectionStack.add(sectionName);
                        continue;
                    }
                }

                // Section end: "}"
                if (trimmed.equals("}") && !sectionStack.isEmpty()) {
                    sectionStack.remove(sectionStack.size() - 1);
                    continue;
                }
            }

            // Handle Forge multi-line arrays: S:key <
            if (relativePath.endsWith(".cfg") && trimmed.endsWith("<")) {
                int eqIndex = trimmed.indexOf('=');
                if (eqIndex < 0) continue;

                String key = trimmed.substring(0, eqIndex).trim();
                StringBuilder arrayValue = new StringBuilder("<");

                // Collect lines until we find the closing >
                i++;
                while (i < lines.size()) {
                    String arrayLine = lines.get(i).trim();
                    arrayValue.append("\n").append(arrayLine);

                    if (arrayLine.equals(">")) break;

                    i++;
                }

                // Build full hierarchical key
                String fullKey = buildFullKey(sectionStack, key);
                keyValues.put(fullKey, arrayValue.toString());
                continue;
            }

            String key = extractKey(line, relativePath);
            if (key == null) continue;

            String value = extractValue(line, relativePath);
            if (value != null) {
                // Build full hierarchical key for .cfg files
                String fullKey = relativePath.endsWith(".cfg") ? buildFullKey(sectionStack, key) : key;
                keyValues.put(fullKey, value);
            }
        }

        return keyValues;
    }

    private String buildFullKey(List<String> sectionStack, String key) {
        if (sectionStack.isEmpty()) return key;

        StringBuilder fullKey = new StringBuilder();
        for (String section : sectionStack) {
            fullKey.append(section).append(".");
        }
        fullKey.append(key);
        return fullKey.toString();
    }

    /**
     * Forces an immediate update of the migrations file.
     */
    public void forceUpdate() {
        detectChangesAndSave();
    }

    public File getMigrationsFile() {
        return migrationsFile;
    }

    public Map<String, Map<String, String>> getModifiedConfigs() {
        return Collections.unmodifiableMap(modifiedConfigs);
    }

    /**
     * Metadata about a config file for change detection.
     */
    private static class FileMetadata {
        String md5Hash;
        long lastModified;

        FileMetadata() {} // For Gson

        FileMetadata(String md5Hash, long lastModified) {
            this.md5Hash = md5Hash;
            this.lastModified = lastModified;
        }
    }
}
