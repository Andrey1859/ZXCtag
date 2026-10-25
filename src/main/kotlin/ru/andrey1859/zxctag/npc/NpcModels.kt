package ru.andrey1859.zxctag.npc

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Locale

internal enum class NpcPose {
    STANDING,
    CROUCHING,
    SWIMMING,
    FALL_FLYING,
}

internal enum class NpcHand(val slotName: String) {
    MAIN_HAND("MAINHAND"),
    OFF_HAND("OFFHAND"),
}

internal data class NpcRecord(
    val id: String,
    val name: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val bodyYaw: Float,
    val headYaw: Float,
    val pitch: Float,
    val pose: NpcPose,
    val className: String,
    val status: String,
    val level: String,
    val us: String,
    val skin: MojangSkin,
    val mainHandItem: ItemStack? = null,
    val offHandItem: ItemStack? = null,
)

internal data class NpcTransform(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val bodyYaw: Float,
    val headYaw: Float,
    val pitch: Float,
    val pose: NpcPose,
) {
    companion object {
        fun capture(player: Player): NpcTransform {
            val location = player.location
            val handle = runCatching {
                player.javaClass.methods
                    .firstOrNull { it.name == "getHandle" && it.parameterCount == 0 }
                    ?.invoke(player)
            }.getOrNull()

            val bodyYaw = handle?.readFloatField("yBodyRot") ?: location.yaw
            val headYaw = handle?.readFloatField("yHeadRot") ?: location.yaw
            val pitch = handle?.readFloatField("xRot") ?: location.pitch
            val pose = when {
                player.isSneaking -> NpcPose.CROUCHING
                player.isSwimming -> NpcPose.SWIMMING
                player.isGliding -> NpcPose.FALL_FLYING
                else -> NpcPose.STANDING
            }

            return NpcTransform(
                world = location.world?.name ?: error("У игрока отсутствует мир"),
                x = location.x,
                y = location.y,
                z = location.z,
                bodyYaw = normalizeYaw(bodyYaw),
                headYaw = normalizeYaw(headYaw),
                pitch = pitch.coerceIn(-90f, 90f),
                pose = pose,
            )
        }

        private fun normalizeYaw(yaw: Float): Float {
            if (!yaw.isFinite()) return 0f
            var normalized = yaw % 360f
            if (normalized > 180f) normalized -= 360f
            if (normalized < -180f) normalized += 360f
            return normalized
        }

        private fun Any.readFloatField(name: String): Float? {
            var type: Class<*>? = javaClass
            while (type != null) {
                val currentType = type
                val field = runCatching { currentType.getDeclaredField(name) }.getOrNull()
                if (field != null) {
                    return runCatching {
                        field.trySetAccessible()
                        (field.get(this) as? Number)?.toFloat()
                    }.getOrNull()?.takeIf { it.isFinite() }
                }
                type = type.superclass
            }
            return null
        }
    }
}

internal fun npcKey(name: String): String = name.lowercase(Locale.ROOT)
