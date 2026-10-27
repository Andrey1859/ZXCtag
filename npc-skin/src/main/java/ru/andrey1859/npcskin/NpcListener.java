package ru.andrey1859.npcskin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Shows NPCs to players: on join, and on world change (hide old world, show new).
 */
public final class NpcListener implements Listener {

    private final NpcPlugin plugin;

    public NpcListener(NpcPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getManager().showTo(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            return;
        }
        plugin.getManager().hideWorld(event.getPlayer(), event.getFrom().getWorld());
        plugin.getManager().showTo(event.getPlayer());
    }
}
