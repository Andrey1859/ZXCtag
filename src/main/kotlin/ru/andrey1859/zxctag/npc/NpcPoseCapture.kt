package ru.andrey1859.zxctag.npc

import org.bukkit.entity.Player
import java.util.UUID

/**
 * Stores a short-lived pose snapshot sent by a client mod immediately before a spawn
 * command. The server cannot observe the client's rendered body yaw from vanilla
 * movement packets, so a client-side capture is the only way to reproduce the exact
 * rotation the player sees.
 *
 * `receive` is called from the client-mod packet handler; `consume` is called when
 * `/spawnnpc` runs. When no fresh snapshot exists (no client mod, or it was not
 * sent), the server-side rotations are used, so spawning still works without any
 * client-side code.
 */
internal object NpcPoseCapture {
    data class Angles(
        val body: Float,
        val head: Float,
        val pitch: Float,
    )

    private data class Pending(
        val angles: Angles,
        val at: Long,
        val world: UUID,
        val x: Double,
        val z: Double,
    )

    private val pending = HashMap<UUID, Pending>()

    fun receive(
        player: Player,
        body: Float,
        head: Float,
        pitch: Float,
    ) {
        if (
            !body.isFinite() ||
            !head.isFinite() ||
            !pitch.isFinite() ||
            pitch !in -90f..90f ||
            kotlin.math.abs(body) > 1_000_000f ||
            kotlin.math.abs(head) > 1_000_000f
        ) return

        val now = System.currentTimeMillis()

        pending.entries.removeIf {
            now - it.value.at > SNAPSHOT_TTL_MILLIS
        }

        val location = player.location

        pending[player.uniqueId] = Pending(
            Angles(body, head, pitch),
            now,
            player.world.uid,
            location.x,
            location.z,
        )
    }

    fun consume(player: Player): Angles {
        val location = player.location
        val capture = pending.remove(player.uniqueId)

        return if (
            capture != null &&
            System.currentTimeMillis() - capture.at <= SNAPSHOT_TTL_MILLIS &&
            capture.world == player.world.uid &&
            (location.x - capture.x) * (location.x - capture.x) +
            (location.z - capture.z) * (location.z - capture.z) <= MAX_SPAWN_DRIFT_SQUARED
        ) {
            capture.angles
        } else {
            Angles(player.bodyYaw, location.yaw, location.pitch)
        }
    }

    private const val SNAPSHOT_TTL_MILLIS = 2_000L
    private const val MAX_SPAWN_DRIFT_SQUARED = 4.0
}
