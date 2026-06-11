package org.traccar.driver;

import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches all driver scripts from the {@code drivers/} directory.
 * Watches for file changes and hot-reloads individual drivers without a restart.
 */
@Singleton
public class DriverRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(DriverRegistry.class);

    private static final String DRIVERS_DIR = "drivers";

    private final Map<String, DriverDefinition> drivers = new ConcurrentHashMap<>();
    private final CompilerConfiguration compilerConfig;

    public DriverRegistry() {
        compilerConfig = new CompilerConfiguration();
        compilerConfig.setScriptBaseClass(DriverDSL.class.getName());

        File dir = new File(DRIVERS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        loadAll(dir);
        startWatcher(dir.toPath());
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns all loaded driver definitions. */
    public Collection<DriverDefinition> all() {
        return drivers.values();
    }

    /** Returns the driver with the given name, or null. */
    public DriverDefinition get(String name) {
        return drivers.get(name);
    }

    /**
     * Finds the first driver+variant pair that matches the given raw message.
     * Returns null if no driver handles the message.
     */
    public DriverMatch match(String message) {
        for (DriverDefinition driver : drivers.values()) {
            VariantDefinition variant = driver.matchVariant(message);
            if (variant != null) {
                return new DriverMatch(driver, variant);
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    private void loadAll(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".groovy"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            loadFile(file);
        }
        LOGGER.info("Loaded {} driver(s) from {}", drivers.size(), dir.getAbsolutePath());
    }

    private void loadFile(File file) {
        try (GroovyClassLoader gcl = new GroovyClassLoader(
                Thread.currentThread().getContextClassLoader(), compilerConfig)) {
            Class<?> scriptClass = gcl.parseClass(file);
            DriverDSL script = (DriverDSL) scriptClass.getDeclaredConstructor().newInstance();
            script.run();
            DriverDefinition def = script.getDefinition();
            if (def == null) {
                LOGGER.warn("Driver script {} did not call protocol() — skipping", file.getName());
                return;
            }
            drivers.put(def.getName(), def);
            LOGGER.info("Loaded driver '{}' from {}", def.getName(), file.getName());
        } catch (Exception e) {
            LOGGER.error("Failed to load driver script {}: {}", file.getName(), e.getMessage(), e);
        }
    }

    private void unloadFile(String fileName) {
        // Remove by matching the file name stem to the driver name
        String stem = fileName.replaceFirst("\\.groovy$", "");
        drivers.entrySet().removeIf(entry -> {
            if (entry.getKey().equals(stem)) {
                LOGGER.info("Unloaded driver '{}'", entry.getKey());
                return true;
            }
            return false;
        });
    }

    // -------------------------------------------------------------------------
    // Hot-reload via WatchService
    // -------------------------------------------------------------------------

    private void startWatcher(Path dir) {
        Thread watchThread = new Thread(() -> {
            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                dir.register(watcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);

                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key;
                    try {
                        key = watcher.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    List<WatchEvent<?>> events = new ArrayList<>(key.pollEvents());
                    key.reset();

                    for (WatchEvent<?> event : events) {
                        String fileName = event.context().toString();
                        if (!fileName.endsWith(".groovy")) {
                            continue;
                        }
                        if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            unloadFile(fileName);
                        } else {
                            // CREATE or MODIFY — small delay to let the write complete
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            loadFile(dir.resolve(fileName).toFile());
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Driver watcher failed: {}", e.getMessage(), e);
            }
        }, "driver-watcher");

        watchThread.setDaemon(true);
        watchThread.start();
    }

    // -------------------------------------------------------------------------
    // Match result
    // -------------------------------------------------------------------------

    public record DriverMatch(DriverDefinition driver, VariantDefinition variant) {
    }
}
