package com.wasteofplastic.multiworldmoney;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.onarandombox.MultiverseCore.MultiverseCore;
import com.onarandombox.MultiverseCore.api.MultiverseWorld;

public class MultiWorldMoney extends JavaPlugin {

    private final HashMap<String,List<World>> worldGroups = new HashMap<>();
    private final HashMap<World,String> reverseWorldGroups = new HashMap<>();
    private PlayerCache players;
    private MultiverseCore core;
    private VaultHelper vh;
    private Settings settings;


    @Override
    public void onEnable() {
        // Load Settings
        settings = new Settings();

        // Load cache
        players = new PlayerCache(this);

        // Load MVCore if it is available
        core = (MultiverseCore) this.getServer().getPluginManager().getPlugin("Multiverse-Core");
        if (core != null) {
            getLogger().info("Multiverse-Core found.");
        } else {
            getLogger().info("Multiverse-Core not found.");
        }
        // Check if this is an upgrade
        File userDataFolder = new File(getDataFolder(),"userdata");
        if (userDataFolder.exists()) {
            getLogger().info("Upgrading old database to UUID's. This may take some time.");
            File playersFolder = new File(getDataFolder(), "players");
            if (!playersFolder.exists()) {
                playersFolder.mkdir();
            }
            for (File playerFile : Objects.requireNonNull(userDataFolder.listFiles())) {
                if (playerFile.getName().endsWith(".yml")) {
                    // Load the file
                    YamlConfiguration player = new YamlConfiguration();
                    try {
                        player.load(playerFile);
                        // Extract the data
                        String uuidString = player.getString("playerinfo.uuid");
                        if (uuidString != null) {
                            UUID uuid = UUID.fromString(uuidString);
                            File convert = new File(playersFolder,uuid.toString() + ".yml");
                            if (convert.exists()) {
                                getLogger().severe(uuid.toString() + ".yml exists already! Skipping import...");
                            } else {
                                YamlConfiguration newPlayer = new YamlConfiguration();
                                String name = player.getString("playerinfo.name");
                                newPlayer.set("name", name);
                                newPlayer.set("uuid", uuidString);
                                newPlayer.set("logoffworld", player.getString("offline_world.name"));
                                // Get the balances
                                for (String key : player.getKeys(false)) {
                                    if (!key.equalsIgnoreCase("offline_world") && !key.equalsIgnoreCase("playerinfo")) {
                                        World world = getServer().getWorld(key);
                                        if (world == null && core != null) {
                                            MultiverseWorld mvWorld = core.getMVWorldManager().getMVWorld(key);
                                            if (mvWorld != null) {
                                                world = mvWorld.getCBWorld();
                                            }
                                        }
                                        if (world != null) {
                                            // It's a recognized world
                                            newPlayer.set("balances." + world.getName(), roundDown(player.getDouble(key + ".money",0D), 2));
                                        } else {
                                            getLogger().severe("Could not recognize world: " + key + ". Skipping...");
                                        }
                                    }
                                }
                                newPlayer.save(convert);
                                if (name != null) {
                                    players.addName(name, uuid);
                                }
                                getLogger().info("Converted " + name);
                            }
                        } else {
                            getLogger().severe("Could not import " + playerFile.getName() + " - no known UUID. Skipping...");
                        }
                    } catch (Exception e) {
                        getLogger().severe("Could not import " + playerFile.getName() + ". Skipping...");
                        e.printStackTrace();
                    }
                }
            }
            // Save names (just in case)
            players.savePlayerNames();
            // Rename the folder
            File newName = new File(getDataFolder(),"userdata.old");
            userDataFolder.renameTo(newName);
        }

        saveDefaultConfig();
        loadConfig();
        // Load locale
        new Lang(this);

        // Hook into the Vault economy system
        vh = new VaultHelper();
        if (!vh.setupEconomy()) {
            getLogger().severe("Could not link to Vault and Economy!");
        } else {
            getLogger().info("Set up economy successfully");
        }
        if (!vh.setupPermissions()) {
            getLogger().severe("Could not set up permissions!");
        } else {
            getLogger().info("Set up permissions successfully");
        }
        // Load world groups
        loadGroups();

        // Load online player balances
        for (Player player: getServer().getOnlinePlayers()) {
            players.addPlayer(player);
        }

        // Register events
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new LogInOutListener(this), this);
        pm.registerEvents(new WorldChangeListener(this), this);

        // Register commands
        getCommand("pay").setExecutor(new PayCommand(this));
        getCommand("balance").setExecutor(new BalanceCommand(this));
        getCommand("mwm").setExecutor(new AdminCommands(this));

    }


    /**
     * Load the settings for the plugin from config.yml and set up defaults.
     */
    private void loadConfig() {
        FileConfiguration config = getConfig();
        settings.setShowBalance(config.getBoolean("shownewworldmessage", true));
        settings.setNewWorldMessage(ChatColor.translateAlternateColorCodes('&', config.getString("newworldmessage", "Your balance in this world is [balance].")));
    }


    @Override
    public void onDisable() {
        players.savePlayerNames();
        // Remove and save each player
        for (Player player : getServer().getOnlinePlayers()) {
            players.removePlayer(player);
        }
    }

    /**
     * Loads the world groups from groups.yml into the hashmap worldgroups
     */
    public void loadGroups() {
        worldGroups.clear();
        YamlConfiguration groups = loadYamlFile();
        Set<String> keys = groups.getKeys(false);
        // Each key is a group name
        for(String group : keys) {
            List<String> worlds = groups.getStringList(group);
            List<World> worldList = new ArrayList<>();
            for(String world : worlds) {
                // Try to parse world into a known world
                World importedWorld = getServer().getWorld(world);
                if (importedWorld != null) {
                    // Build the world list
                    worldList.add(importedWorld);
                    // Add immediately to the reverse hashmap
                    reverseWorldGroups.put(importedWorld, group);
                } else {
                    getLogger().warning("Could not recognize world " + world + " in groups.yml. Skipping...");
                }
            }
            worldGroups.put(group,worldList);
        }
    }

    /**
     * Tries to load a YML file. If it does not exist, it looks in the jar for it and tries to create it.
     * @return config object
     */
    private YamlConfiguration loadYamlFile() {
        File dataFolder = getDataFolder();
        File yamlFile = new File(dataFolder, "groups.yml");
        YamlConfiguration config = new YamlConfiguration();
        if(yamlFile.exists()) {
            try {
                config.load(yamlFile);
            } catch (FileNotFoundException e) {
                getLogger().severe("File not found: " + "groups.yml");
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InvalidConfigurationException e) {
                getLogger().severe("YAML file has errors: " + "groups.yml");
                e.printStackTrace();
            }
        } else {
            // Try to make it from a built-in file
            if (getResource("groups.yml") != null) {
                saveResource("groups.yml",true);
                try {
                    config.load(yamlFile);
                } catch (FileNotFoundException e) {
                    getLogger().severe("File not found: " + "groups.yml");
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InvalidConfigurationException e) {
                    getLogger().severe("YAML file has errors: " + "groups.yml");
                    e.printStackTrace();
                }
            } else {
                getLogger().severe("File not found: " + "groups.yml");
            }
        }
        return config;
    }
    /**
     * @param yamlFile - config file
     * @param fileLocation - file name location
     */
    public void saveYamlFile(YamlConfiguration yamlFile, String fileLocation) {
        File dataFolder = getDataFolder();
        File file = new File(dataFolder, fileLocation);
        try {
            yamlFile.save(file);
        } catch(Exception e) {
            e.printStackTrace();
        }
    }



    /**
     * Finds what other worlds are in the group this world is in, or just itself
     * @param world - world
     * @return List of worlds in group
     */
    public List<World> getGroupWorlds(World world) {
        if (reverseWorldGroups.containsKey(world)) {
            return worldGroups.get(reverseWorldGroups.get(world));
        } else {
            List<World> result = new ArrayList<>();
            result.add(world);
            return result;
        }
    }

    /**
     * Rounds a double down to a set number of places
     * @param value - value
     * @param places - number of placed
     * @return - rounded value
     */
    public double roundDown(double value, int places) {
        BigDecimal bd = new BigDecimal(value);
        bd = bd.setScale(places, RoundingMode.HALF_DOWN);
        return bd.doubleValue();
    }


    /**
     * @return player cache
     */
    public PlayerCache getPlayers() {
        return players;
    }

    /**
     * Returns the name of world, uses MultiverseCore alias if available
     * @param world - world
     * @return world name or alias
     */
    public String getWorldName(World world) {
        // Grab the Multiverse world name if it is available
        if (core != null) {
            try {
                return core.getMVWorldManager().getMVWorld(world).getAlias();
            } catch (Exception e) {
                // do nothing if it does not work
                e.printStackTrace();
            }
        }
        //getLogger().info("DEBUG: core is null");
        return world.getName();
    }


    /**
     * @return the core
     */
    public MultiverseCore getCore() {
        return core;
    }


    /**
     * Reload locale
     */
    public void reloadLocale() {
        new Lang(this);
    }


    /**
     * Get VaultHelper
     * @return the vh
     */
    public VaultHelper getVh() {
        return vh;
    }


    /**
     * @return the settings
     */
    public Settings getSettings() {
        return settings;
    }
}
