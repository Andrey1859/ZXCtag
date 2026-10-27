package ru.andrey1859.npcskin;

import org.bukkit.command.Command;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main plugin class: registers commands and events, creates the NPC manager
 * and removes all NPC representations on disable.
 */
public final class NpcPlugin extends JavaPlugin {

    private NpcManager manager;

    private final ExecutorService skinExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "npc-skin-skin");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void onEnable() {
        manager = new NpcManager(this);
        Command command = getCommand("npc");
        if (command != null) {
            command.setExecutor(new NpcCommand(this));
        }
        getServer().getPluginManager().registerEvents(new NpcListener(this), this);
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.removeAll();
            manager = null;
        }
        skinExecutor.shutdownNow();
    }

    NpcManager getManager() {
        return manager;
    }

    ExecutorService getSkinExecutor() {
        return skinExecutor;
    }
}
