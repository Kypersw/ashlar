package org.craftrank.ashlar;

import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginBase;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Owner for the built-in listeners and scheduled tasks. It is never put into the plugin list,
 * it only exists so the event bus and scheduler have a plugin to attach work to.
 */
@NullMarked
final class AshlarInternal extends PluginBase {

    private final PluginDescriptionFile desc = new PluginDescriptionFile("Ashlar", "1.0", AshlarInternal.class.getName());
    private final Logger logger = Logger.getLogger("Ashlar");

    @Override public File getDataFolder() { return new File("."); }
    @Override public PluginDescriptionFile getDescription() { return this.desc; }
    @Override public PluginMeta getPluginMeta() { return this.desc; }
    @Override public FileConfiguration getConfig() { return new YamlConfiguration(); }
    @Override public @Nullable InputStream getResource(String filename) { return null; }
    @Override public void saveConfig() { }
    @Override public void saveDefaultConfig() { }
    @Override public void saveResource(String resourcePath, boolean replace) { }
    @Override public void reloadConfig() { }
    @SuppressWarnings("removal")
    @Override public PluginLoader getPluginLoader() { throw new UnsupportedOperationException("Ashlar is built in"); }
    @Override public Server getServer() { return Bukkit.getServer(); }
    @Override public boolean isEnabled() { return true; }
    @Override public void onDisable() { }
    @Override public void onLoad() { }
    @Override public void onEnable() { }
    @Override public boolean isNaggable() { return false; }
    @Override public void setNaggable(boolean canNag) { }
    @Override public @Nullable ChunkGenerator getDefaultWorldGenerator(String worldName, @Nullable String id) { return null; }
    @Override public @Nullable BiomeProvider getDefaultBiomeProvider(String worldName, @Nullable String id) { return null; }
    @Override public Logger getLogger() { return this.logger; }
    @Override public LifecycleEventManager<Plugin> getLifecycleManager() { throw new UnsupportedOperationException("Ashlar is built in"); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) { return false; }
    @Override public @Nullable List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { return null; }
}
