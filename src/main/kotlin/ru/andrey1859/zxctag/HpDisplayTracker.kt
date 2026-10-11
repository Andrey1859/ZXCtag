package ru.andrey1859.zxctag

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.entity.EntityType
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.AffineTransformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object HpDisplayTracker {
    private const val TAG = "zxctag_hp"
    private val hpByPlayer = ConcurrentHashMap<UUID, String>()
    private val displayByPlayer = ConcurrentHashMap<UUID, UUID>()

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            for (playerId in hpByPlayer.keys) {
                val player = server.playerManager.getPlayer(playerId) ?: continue
                val hp = hpByPlayer[playerId] ?: continue
                val display = displayOf(player)
                if (display == null || display.isRemoved) {
                    spawn(player, hp)
                } else {
                    keepAttached(display, player)
                }
            }
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            remove(handler.player)
        }

        ServerPlayerEvents.AFTER_RESPAWN.register { _, newPlayer, _ ->
            val hp = hpByPlayer[newPlayer.uuid] ?: return@register
            spawn(newPlayer, hp)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            server.playerManager.playerList.forEach { remove(it) }
            hpByPlayer.clear()
            displayByPlayer.clear()
        }

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            server.worlds.forEach { world ->
                world.iterateEntities().forEach { entity ->
                    if (entity.commandTags.contains(TAG)) {
                        entity.discard()
                    }
                }
            }
        }
    }

    fun spawnOrUpdate(player: ServerPlayerEntity, hp: String) {
        hpByPlayer[player.uuid] = hp
        spawn(player, hp)
    }

    fun remove(player: ServerPlayerEntity) {
        hpByPlayer.remove(player.uuid)
        discardTracked(player)
    }

    private fun spawn(player: ServerPlayerEntity, hp: String) {
        discardTracked(player)

        val world = player.serverWorld
        val display = EntityType.TEXT_DISPLAY.create(world) ?: return

        display.refreshPositionAndAngles(player.x, player.y + player.height, player.z, 0f, 0f)
        display.setText(
            Text.literal("$hp ").setStyle(
                Style.EMPTY.withItalic(false).withColor(Formatting.WHITE)
            ).append(TextStyles.named("❤", Formatting.RED))
        )
        display.setBillboardMode(DisplayEntity.BillboardMode.CENTER)
        display.setBackground(0)
        display.setShadowRadius(0f)
        display.setTeleportDuration(0)
        display.setTransformation(
            AffineTransformation(
                Vector3f(0f, -0.4f, 0f),
                Quaternionf(),
                Vector3f(1f, 1f, 1f),
                Quaternionf(),
            )
        )
        display.isInvulnerable = true
        display.setNoGravity(true)
        display.noClip = true
        display.addCommandTag(TAG)

        world.spawnEntity(display)
        displayByPlayer[player.uuid] = display.uuid
        keepAttached(display, player)
    }

    /**
     * TextDisplay is mounted as a passenger so it cannot be left behind
     * at the command location. If riding is rejected (vehicle, sleep),
     * teleport every tick as a fallback.
     */
    private fun keepAttached(display: DisplayEntity.TextDisplayEntity, player: ServerPlayerEntity) {
        if (display.vehicle != player) {
            display.startRiding(player, true)
        }
        if (display.vehicle == player) {
            return
        }
        val y = player.y + player.height + 0.15
        if (display.world.registryKey != player.serverWorld.registryKey) {
            display.discard()
            spawn(player, hpByPlayer[player.uuid] ?: return)
            return
        }
        display.requestTeleport(player.x, y, player.z)
        display.refreshPositionAndAngles(player.x, y, player.z, 0f, 0f)
    }

    private fun displayOf(player: ServerPlayerEntity): DisplayEntity.TextDisplayEntity? {
        player.passengerList
            .filterIsInstance<DisplayEntity.TextDisplayEntity>()
            .firstOrNull { TAG in it.commandTags }
            ?.let { return it }

        val id = displayByPlayer[player.uuid] ?: return null
        return findEntity(player.server, id)
    }

    private fun discardTracked(player: ServerPlayerEntity) {
        player.passengerList
            .filterIsInstance<DisplayEntity.TextDisplayEntity>()
            .filter { TAG in it.commandTags }
            .forEach { it.discard() }

        val id = displayByPlayer.remove(player.uuid) ?: return
        findEntity(player.server, id)?.discard()
    }

    private fun findEntity(server: MinecraftServer, id: UUID): DisplayEntity.TextDisplayEntity? {
        server.worlds.forEach { world ->
            val entity = world.getEntity(id)
            if (entity is DisplayEntity.TextDisplayEntity) {
                return entity
            }
        }
        return null
    }
}
