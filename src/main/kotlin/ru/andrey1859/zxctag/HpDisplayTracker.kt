package ru.andrey1859.zxctag

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
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

object HpDisplayTracker : Listener {
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
                    spawn(player, hp)
                    return@forEach
                }
                if (!player.passengers.contains(display)) {
                    player.addPassenger(display)
                }
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

    private fun spawn(player: Player, hp: String) {
        displays.remove(player.uniqueId)?.let { if (it.isValid) it.remove() }

        val display = player.world.spawn(player.location, TextDisplay::class.java) { ent ->
            ent.text(
                Component.text("$hp ").color(NamedTextColor.WHITE)
                    .append(Component.text("❤", NamedTextColor.RED))
            )
            ent.billboard = Display.Billboard.CENTER
            ent.backgroundColor = Color.fromARGB(0, 0, 0, 0)
            ent.isShadowed = false
            ent.teleportDuration = 0
            val trans = ent.transformation
            trans.translation.set(0f, -0.4f, 0f)
            ent.transformation = trans
        }
        player.addPassenger(display)
        displays[player.uniqueId] = display
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
                spawn(event.player, hp)
            }
        }, 1L)
    }
}
