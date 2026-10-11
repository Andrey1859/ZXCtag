package ru.andrey1859.zxctag

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.logging.Level

object HpDisplayTracker : Listener {
    /** Just below the vanilla nametag (nametag sits ~0.5 above the hitbox). */
    private const val UNDER_NAMETAG = 0.42

    private val displays = HashMap<UUID, TextDisplay>()
    private val hpByPlayer = HashMap<UUID, String>()
    private var task: BukkitTask? = null
    private var plugin: JavaPlugin? = null

    fun start(plugin: JavaPlugin) {
        this.plugin = plugin
        Bukkit.getPluginManager().registerEvents(this, plugin)
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            hpByPlayer.keys.toList().forEach { uuid ->
                val player = Bukkit.getPlayer(uuid) ?: return@forEach
                val hp = hpByPlayer[uuid] ?: return@forEach
                val display = displays[uuid]
                if (display == null || !display.isValid) {
                    try {
                        spawn(player, hp)
                    } catch (t: Throwable) {
                        plugin.logger.log(Level.SEVERE, "HP spawn tick", t)
                    }
                    return@forEach
                }
                keepWithPlayer(player, display)
            }
        }, 1L, 1L)
    }

    fun stop() {
        task?.cancel()
        task = null
        displays.values.forEach { if (it.isValid) it.remove() }
        displays.clear()
        hpByPlayer.clear()
    }

    fun spawnOrUpdate(player: Player, hp: String) {
        hpByPlayer[player.uniqueId] = hp
        spawn(player, hp)
    }

    fun remove(player: Player) {
        hpByPlayer.remove(player.uniqueId)
        displays.remove(player.uniqueId)?.let { if (it.isValid) it.remove() }
    }

    @Suppress("DEPRECATION")
    private fun spawn(player: Player, hp: String) {
        displays.remove(player.uniqueId)?.let { if (it.isValid) it.remove() }

        val display = player.world.spawn(aboveHead(player), TextDisplay::class.java)
        display.setText("§f$hp §c❤")
        try {
            display.billboard = Display.Billboard.CENTER
        } catch (_: Throwable) {
        }
        try {
            display.backgroundColor = Color.fromARGB(0, 0, 0, 0)
        } catch (_: Throwable) {
        }
        try {
            display.teleportDuration = 0
        } catch (_: Throwable) {
        }

        hideFromOwner(player, display)
        displays[player.uniqueId] = display
    }

    private fun keepWithPlayer(player: Player, display: TextDisplay) {
        if (!display.isValid) return
        if (display.vehicle != null) {
            display.leaveVehicle()
        }
        display.teleport(aboveHead(player))
        hideFromOwner(player, display)
    }

    private fun aboveHead(player: Player) = player.location.clone().apply {
        y = player.location.y + player.height + UNDER_NAMETAG
    }

    /**
     * Same rule as the nametag: the owner must not see their own HP label.
     */
    private fun hideFromOwner(player: Player, display: TextDisplay) {
        val plugin = plugin ?: return
        try {
            player.hideEntity(plugin, display)
        } catch (_: Throwable) {
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        remove(event.player)
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val hp = hpByPlayer[event.player.uniqueId] ?: return
        val plugin = plugin ?: return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (event.player.isOnline) {
                try {
                    spawn(event.player, hp)
                } catch (t: Throwable) {
                    plugin.logger.log(Level.SEVERE, "HP spawn respawn", t)
                }
            }
        }, 1L)
    }
}
