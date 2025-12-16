package com.configmigrator.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.configmigrator.ConfigMigrator;


/**
 * Watches the config directory for changes and triggers updates to the migrations file.
 * Uses a debounce mechanism to avoid excessive updates.
 */
public class ConfigWatcher {
    // Periodic check interval in seconds - checks for dirty files and new files
    private static final int CHECK_INTERVAL_SECONDS = 30;

    private final File configDir;
    private final ConfigTracker tracker;

    private WatchService watchService;
    private Thread watchThread;
    private ScheduledExecutorService scheduler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<String> dirtyFiles = ConcurrentHashMap.newKeySet();

    public ConfigWatcher(File configDir, ConfigTracker tracker) {
        this.configDir = configDir;
        this.tracker = tracker;
    }

    public void start() {
        if (running.getAndSet(true)) return;

        try {
            watchService = FileSystems.getDefault().newWatchService();
            registerDirectory(configDir.toPath());
        } catch (IOException e) {
            ConfigMigrator.LOGGER.error("Failed to create watch service: {}", e.getMessage());
            running.set(false);
            return;
        }

        // Start the directory watching thread
        watchThread = new Thread(this::watchLoop, "ConfigMigrator-Watcher");
        watchThread.setDaemon(true);
        watchThread.start();

        // Start the periodic update scheduler
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ConfigMigrator-Scheduler");
            t.setDaemon(true);
            return t;
        });

        // Check for updates every 30 seconds
        scheduler.scheduleAtFixedRate(this::checkForUpdates, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);

        ConfigMigrator.LOGGER.info("Config watcher started");
    }

    public void stop() {
        if (!running.getAndSet(false)) return;

        // Perform final update if there are dirty files
        if (!dirtyFiles.isEmpty()) {
            tracker.detectChangesAndSave(dirtyFiles);
        }

        if (watchThread != null) watchThread.interrupt();
        if (scheduler != null) scheduler.shutdownNow();

        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                ConfigMigrator.LOGGER.warn("Error closing watch service: {}", e.getMessage());
            }
        }

        ConfigMigrator.LOGGER.info("Config watcher stopped");
    }

    private void registerDirectory(Path dir) throws IOException {
        dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);

        // Also register subdirectories
        try {
            Files.walk(dir)
                .filter(Files::isDirectory)
                .forEach(subDir -> {
                    try {
                        subDir.register(watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);
                    } catch (IOException e) {
                        ConfigMigrator.LOGGER.warn("Failed to register subdirectory: {}", subDir);
                    }
                });
        } catch (IOException e) {
            ConfigMigrator.LOGGER.warn("Failed to walk config directory for registration: {}", e.getMessage());
        }
    }

    private void watchLoop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }

            if (key == null) continue;

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                Path fileName = pathEvent.context();

                // Check if it's a config file we care about
                String name = fileName.toString().toLowerCase();
                if (name.endsWith(".cfg") || name.endsWith(".toml") || name.endsWith(".properties") || name.endsWith(".conf")) {
                    // Mark file as dirty for next periodic check
                    Path watchable = (Path) key.watchable();
                    Path fullPath = watchable.resolve(fileName);
                    String relativePath = configDir.toPath().relativize(fullPath).toString().replace('\\', '/');
                    dirtyFiles.add(relativePath);
                }

                // If a new directory was created, register it
                if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                    Path watchable = (Path) key.watchable();
                    Path child = watchable.resolve(fileName);
                    if (Files.isDirectory(child)) {
                        try {
                            registerDirectory(child);
                        } catch (IOException e) {
                            ConfigMigrator.LOGGER.warn("Failed to register new directory: {}", child);
                        }
                    }
                }
            }

            boolean valid = key.reset();
            if (!valid) ConfigMigrator.LOGGER.warn("Watch key no longer valid");
        }
    }

    private void checkForUpdates() {
        if (!running.get()) return;

        // Scan dirty files and check for new files
        // This runs every 30 seconds
        try {
            if (!dirtyFiles.isEmpty()) {
                Set<String> filesToCheck = new HashSet<>(dirtyFiles);
                tracker.detectChangesAndSave(filesToCheck);
                dirtyFiles.clear();
            } else {
                // Even if no dirty files, periodically check for new files
                // Pass empty set to trigger new file detection
                tracker.detectChangesAndSave(new HashSet<>());
            }
        } catch (Exception e) {
            ConfigMigrator.LOGGER.error("Error during config update: {}", e.getMessage());
        }
    }

    /**
     * Forces an immediate check and update.
     */
    public void forceUpdate() {
        checkForUpdates();
    }
}
