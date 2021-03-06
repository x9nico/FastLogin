package com.github.games647.fastlogin.core.shared;

import com.github.games647.fastlogin.core.AuthStorage;
import com.github.games647.fastlogin.core.CompatibleCacheBuilder;
import com.github.games647.fastlogin.core.SharedConfig;
import com.github.games647.fastlogin.core.hooks.AuthPlugin;
import com.github.games647.fastlogin.core.hooks.DefaultPasswordGenerator;
import com.github.games647.fastlogin.core.hooks.PasswordGenerator;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * @param <P> Player class
 * @param <C> CommandSender
 * @param <T> Plugin class
 */
public class FastLoginCore<P extends C, C, T extends PlatformPlugin<C>> {

    public static <K, V> ConcurrentMap<K, V> buildCache(int expireAfterWrite, int maxSize) {
        CompatibleCacheBuilder<Object, Object> builder = CompatibleCacheBuilder.newBuilder();

        if (expireAfterWrite > 0) {
            builder.expireAfterWrite(expireAfterWrite, TimeUnit.MINUTES);
        }

        if (maxSize > 0) {
            builder.maximumSize(maxSize);
        }

        return builder.build(CacheLoader.from(() -> {
            throw new UnsupportedOperationException();
        }));
    }

    public static UUID parseId(String withoutDashes) {
        if (withoutDashes == null) {
            return null;
        }

        return UUID.fromString(withoutDashes.substring(0, 8)
                + '-' + withoutDashes.substring(8, 12)
                + '-' + withoutDashes.substring(12, 16)
                + '-' + withoutDashes.substring(16, 20)
                + '-' + withoutDashes.substring(20, 32));
    }

    protected final Map<String, String> localeMessages = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Object> pendingLogin = FastLoginCore.buildCache(5, -1);
    private final Set<UUID> pendingConfirms = Sets.newHashSet();
    private final T plugin;

    private SharedConfig sharedConfig;
    private MojangApiConnector apiConnector;
    private AuthStorage storage;
    private PasswordGenerator<P> passwordGenerator = new DefaultPasswordGenerator<>();
    private AuthPlugin<P> authPlugin;

    public FastLoginCore(T plugin) {
        this.plugin = plugin;
    }

    public void load() {
        saveDefaultFile("messages.yml");
        saveDefaultFile("config.yml");

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(getClass().getClassLoader().getResourceAsStream("config.yml")));
            sharedConfig = new SharedConfig(plugin.loadYamlFile(reader));
            reader.close();

            reader = Files.newBufferedReader(plugin.getDataFolder().toPath().resolve("config.yml"));
            sharedConfig.getConfigValues().putAll(plugin.loadYamlFile(reader));
            reader.close();

            reader = new BufferedReader(new InputStreamReader(getClass().getClassLoader().getResourceAsStream("messages.yml")));
            reader = Files.newBufferedReader(plugin.getDataFolder().toPath().resolve("messages.yml"));
            Map<String, Object> messageConfig = plugin.loadYamlFile(reader);
            reader.close();

            reader = Files.newBufferedReader(plugin.getDataFolder().toPath().resolve("messages.yml"));
            messageConfig.putAll(plugin.loadYamlFile(reader));
            for (Entry<String, Object> entry : messageConfig.entrySet()) {
                String message = plugin.translateColorCodes('&', (String) entry.getValue());
                if (!message.isEmpty()) {
                    localeMessages.put(entry.getKey(), message);
                }
            }

            reader.close();
        } catch (IOException ioEx) {
            plugin.getLogger().log(Level.INFO, "Failed to load yaml files", ioEx);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ex) {
                    plugin.getLogger().log(Level.SEVERE, null, ex);
                }
            }
        }

        List<String> ipAddresses = sharedConfig.get("ip-addresses");
        int requestLimit = sharedConfig.get("mojang-request-limit");
        List<String> proxyList = sharedConfig.get("proxies", Lists.newArrayList());
        Map<String, Integer> proxies = proxyList.stream()
                .collect(Collectors
                        .toMap(line -> line.split(":")[0], line -> Integer.parseInt(line.split(":")[1])));

        this.apiConnector = plugin.makeApiConnector(plugin.getLogger(), ipAddresses, requestLimit, proxies);
    }

    public MojangApiConnector getApiConnector() {
        return apiConnector;
    }

    public AuthStorage getStorage() {
        return storage;
    }

    public T getPlugin() {
        return plugin;
    }

    public void sendLocaleMessage(String key, C receiver) {
        String message = localeMessages.get(key);
        if (message != null) {
            plugin.sendMessage(receiver, message);
        }
    }

    public String getMessage(String key) {
        return localeMessages.get(key);
    }

    public boolean setupDatabase() {
        String driver = sharedConfig.get("driver");
        String host = sharedConfig.get("host", "");
        int port = sharedConfig.get("port", 3306);
        String database = sharedConfig.get("database");

        String user = sharedConfig.get("username", "");
        String password = sharedConfig.get("password", "");

        boolean useSSL = sharedConfig.get("useSSL", false);

        storage = new AuthStorage(this, driver, host, port, database, user, password, useSSL);
        try {
            storage.createTables();
            return true;
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Failed to setup database. Disabling plugin...", ex);
            return false;
        }
    }

    public SharedConfig getConfig() {
        return sharedConfig;
    }

    public PasswordGenerator<P> getPasswordGenerator() {
        return passwordGenerator;
    }

    public void setPasswordGenerator(PasswordGenerator<P> passwordGenerator) {
        this.passwordGenerator = passwordGenerator;
    }

    public ConcurrentMap<String, Object> getPendingLogin() {
        return pendingLogin;
    }

    public Collection<UUID> getPendingConfirms() {
        return pendingConfirms;
    }

    public AuthPlugin<P> getAuthPluginHook() {
        return authPlugin;
    }

    public void setAuthPluginHook(AuthPlugin<P> authPlugin) {
        this.authPlugin = authPlugin;
    }

    public void saveDefaultFile(String fileName) {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdir();
        }

        Path configFile = plugin.getDataFolder().toPath().resolve(fileName);
        if (Files.notExists(configFile)) {
            InputStream in = getClass().getClassLoader().getResourceAsStream(fileName);
            try {
                Files.copy(in, configFile);
            } catch (IOException ioExc) {
                plugin.getLogger().log(Level.SEVERE, "Error saving default " + fileName, ioExc);
            } finally {
                try {
                    in.close();
                } catch (IOException ex) {
                    plugin.getLogger().log(Level.SEVERE, null, ex);
                }
            }
        }
    }

    public void close() {
        if (storage != null) {
            storage.close();
        }
    }
}
