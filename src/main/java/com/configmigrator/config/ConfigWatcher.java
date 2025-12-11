package com.configmigrator.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
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
    // Debounce time in seconds - wait this long after last change before updating
    private static final int DEBOUNCE_SECONDS = 30;

    // Maximum time between forced updates in minutes
    private static final int MAX_UPDATE_INTERVAL_MINUTES = 10;

    private final File configDir;
    private final ConfigTracker tracker;

    private WatchService watchService;
    private Thread watchThread;
    private ScheduledExecutorService scheduler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean changeDetected = new AtomicBoolean(false);
    private volatile long lastChangeTime = 0;
    private volatile long lastUpdateTime = System.currentTimeMillis();

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

        // Check for pending updates every 30 seconds
        scheduler.scheduleAtFixedRate(this::checkForPendingUpdate, DEBOUNCE_SECONDS, DEBOUNCE_SECONDS, TimeUnit.SECONDS);

        ConfigMigrator.LOGGER.info("Config watcher started");
    }

    public void stop() {
        if (!running.getAndSet(false)) return;

        // Perform final update
        if (changeDetected.get()) tracker.detectChangesAndSave();
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
                    ConfigMigrator.LOGGER.debug("Config change detected: {} ({})", fileName, kind.name());
                    changeDetected.set(true);
                    lastChangeTime = System.currentTimeMillis();
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

    private void checkForPendingUpdate() {
        if (!running.get()) return;

        long now = System.currentTimeMillis();
        long timeSinceLastChange = now - lastChangeTime;
        long timeSinceLastUpdate = now - lastUpdateTime;

        // Update if:
        // 1. Changes detected AND debounce time has passed since last change
        // 2. OR max update interval has passed (forced periodic update)
        boolean shouldUpdate = false;

        if (changeDetected.get() && timeSinceLastChange >= DEBOUNCE_SECONDS * 1000) {
            shouldUpdate = true;
            ConfigMigrator.LOGGER.debug("Updating migrations file (debounce complete)");
        } else if (timeSinceLastUpdate >= MAX_UPDATE_INTERVAL_MINUTES * 60 * 1000) {
            shouldUpdate = true;
            ConfigMigrator.LOGGER.debug("Updating migrations file (periodic)");
        }

        if (shouldUpdate) {
            try {
                tracker.detectChangesAndSave();
                changeDetected.set(false);
                lastUpdateTime = now;
            } catch (Exception e) {
                ConfigMigrator.LOGGER.error("Error during migrations update: {}", e.getMessage());
            }
        }
    }

    /**
     * Forces an immediate check and update.
     */
    public void forceUpdate() {
        changeDetected.set(true);
        lastChangeTime = 0; // Ensures debounce check passes
        checkForPendingUpdate();
    }
}
