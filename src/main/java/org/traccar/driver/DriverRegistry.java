// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.helper.DataConverter;
import org.traccar.model.DriverScript;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final Map<String, String> driverFiles = new ConcurrentHashMap<>();
    private final CompilerConfiguration compilerConfig;
    private final Storage storage;
    private volatile Thread watcherThread;

    @Inject
    public DriverRegistry(Storage storage) {
        this.storage = storage;
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

    public Set<DriverEndpoint> endpoints() {
        Set<DriverEndpoint> endpoints = new LinkedHashSet<>();
        for (DriverDefinition driver : drivers.values()) {
            if (driver.getDefaultPort() > 0) {
                for (DriverTransport transport : driver.getTransports()) {
                    endpoints.add(new DriverEndpoint(transport, driver.getDefaultPort()));
                }
            }
        }
        return endpoints;
    }

    public void reload(String fileName) {
        File file = new File(DRIVERS_DIR, new File(fileName).getName());
        unloadFile(file.getName());
        if (file.isFile()) {
            loadFile(file);
        }
    }

    public void unload(String fileName) {
        unloadFile(new File(fileName).getName());
    }

    /**
     * Finds the first driver+variant pair that matches the given raw message.
     * Returns null if no driver handles the message.
     */
    public DriverMatch match(String message) {
        return match(message, null, null);
    }

    public DriverMatch match(Object message, DriverTransport transport, Integer localPort) {
        for (DriverDefinition driver : drivers.values()) {
            if (!matchesEndpoint(driver, transport, localPort)) {
                continue;
            }
            VariantDefinition variant = driver.matchVariant(message);
            if (variant != null) {
                return new DriverMatch(driver, variant);
            }
        }
        return null;
    }

    private boolean matchesEndpoint(DriverDefinition driver, DriverTransport transport, Integer localPort) {
        if (transport != null && !driver.supportsTransport(transport)) {
            return false;
        }
        return localPort == null || driver.getDefaultPort() == 0 || driver.getDefaultPort() == localPort;
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
        unloadFile(file.getName());
        DriverScript driverScript = null;
        byte[] sourceBytes = null;
        try {
            sourceBytes = Files.readAllBytes(file.toPath());
            driverScript = registerFile(file.getName(), sourceBytes);
            if (!driverScript.getEnabled()) {
                LOGGER.info("Driver script {} with hash {} is pending approval",
                        driverScript.getFileName(), driverScript.getHash());
                return;
            }
            try (GroovyClassLoader gcl = new GroovyClassLoader(
                    Thread.currentThread().getContextClassLoader(), compilerConfig)) {
                Class<?> scriptClass = gcl.parseClass(
                        new String(sourceBytes, StandardCharsets.UTF_8), file.getName());
                DriverDSL script = (DriverDSL) scriptClass.getDeclaredConstructor().newInstance();
                script.run();
                DriverDefinition def = script.getDefinition();
                if (def == null) {
                    LOGGER.warn("Driver script {} did not call protocol() — skipping", file.getName());
                    return;
                }
                drivers.put(def.getName(), def);
                driverFiles.put(file.getName(), def.getName());
                markLoaded(driverScript, null);
                LOGGER.info("Loaded driver '{}' from {}", def.getName(), file.getName());
            }
        } catch (Exception e) {
            if (driverScript == null) {
                try {
                    if (sourceBytes == null) {
                        sourceBytes = Files.readAllBytes(file.toPath());
                    }
                    driverScript = registerFile(file.getName(), sourceBytes);
                } catch (Exception nested) {
                    LOGGER.warn("Failed to register driver script {}", file.getName(), nested);
                }
            }
            if (driverScript != null) {
                try {
                    markLoaded(driverScript, e.getMessage());
                } catch (Exception nested) {
                    LOGGER.warn("Failed to update driver script registry for {}", file.getName(), nested);
                }
            }
            LOGGER.error("Failed to load driver script {}: {}", file.getName(), e.getMessage(), e);
        }
    }

    private DriverScript registerFile(String fileName, byte[] sourceBytes) throws IOException, StorageException, NoSuchAlgorithmException {
        String hash = calculateHash(sourceBytes);
        Date now = new Date();

        DriverScript driverScript = storage.getObject(DriverScript.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("fileName", fileName),
                        new Condition.Equals("hash", hash))));
        if (driverScript == null) {
            driverScript = new DriverScript();
            driverScript.setFileName(fileName);
            driverScript.setHash(hash);
            driverScript.setEnabled(false);
            driverScript.setDiscoveredTime(now);
            driverScript.setLastSeenTime(now);
            driverScript.setId(storage.addObject(driverScript, new Request(new Columns.Exclude("id"))));
        } else {
            driverScript.setLastSeenTime(now);
            storage.updateObject(driverScript, new Request(
                    new Columns.Include("lastSeenTime"),
                    new Condition.Equals("id", driverScript.getId())));
        }
        return driverScript;
    }

    private void markLoaded(DriverScript driverScript, String error) throws StorageException {
        driverScript.setLoadedTime(error == null ? new Date() : null);
        driverScript.setError(error);
        storage.updateObject(driverScript, new Request(
                new Columns.Include("loadedTime", "error"),
                new Condition.Equals("id", driverScript.getId())));
    }

    private String calculateHash(byte[] bytes) throws NoSuchAlgorithmException {
        return DataConverter.printHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void unloadFile(String fileName) {
        String driverName = driverFiles.remove(fileName);
        if (driverName != null && drivers.remove(driverName) != null) {
            LOGGER.info("Unloaded driver '{}'", driverName);
        }
    }

    public void stop() {
        Thread thread = watcherThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Hot-reload via WatchService
    // -------------------------------------------------------------------------

    private void startWatcher(Path dir) {
        watcherThread = new Thread(() -> {
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

        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    // -------------------------------------------------------------------------
    // Match result
    // -------------------------------------------------------------------------

    public record DriverMatch(DriverDefinition driver, VariantDefinition variant) {
    }
}
