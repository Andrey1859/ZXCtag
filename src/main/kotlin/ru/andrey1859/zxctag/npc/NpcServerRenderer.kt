package ru.andrey1859.zxctag.npc

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.EnumSet
import java.util.UUID
import java.util.function.Consumer

/**
 * Builds a packet-only ServerPlayer for each stored NPC and sends its player-info / spawn packets.
 * No real account or client connection is created for the NPC.
 */
internal class NpcServerRenderer(private val plugin: JavaPlugin) {
    fun create(record: NpcRecord): RenderedNpc {
        val world = Bukkit.getWorld(record.world)
            ?: throw IllegalStateException("Мир «${record.world}» не загружен")
        val craftServer = Bukkit.getServer()
        val minecraftServer = invokeNoArgs(craftServer, "getServer")
            ?: throw IllegalStateException("Не удалось получить MinecraftServer")
        val serverLevel = invokeNoArgs(world, "getHandle")
            ?: throw IllegalStateException("Не удалось получить внутренний мир ${record.world}")

        val profileId = UUID.nameUUIDFromBytes("zxctag:npc:${record.id}".toByteArray(Charsets.UTF_8))
        val gameProfileClass = nmsClass("com.mojang.authlib.GameProfile")
        val gameProfile = gameProfileClass
            .getConstructor(UUID::class.java, String::class.java)
            .newInstance(profileId, record.name)
        addSignedSkinProperty(gameProfile, record.skin)

        val clientInformationClass = nmsClass("net.minecraft.server.level.ClientInformation")
        val clientInformation = createDefaultClientInformation(clientInformationClass)
        val serverPlayerClass = nmsClass("net.minecraft.server.level.ServerPlayer")
        val constructor = serverPlayerClass.declaredConstructors.firstOrNull { candidate ->
            val parameters = candidate.parameterTypes
            parameters.size == 4 &&
                parameters[0].isInstance(minecraftServer) &&
                parameters[1].isInstance(serverLevel) &&
                parameters[2].isInstance(gameProfile) &&
                parameters[3].isInstance(clientInformation)
        } ?: throw IllegalStateException("Внутренний конструктор ServerPlayer не найден")

        val npcPlayer = instantiate(constructor, minecraftServer, serverLevel, gameProfile, clientInformation)
        setPlayerTransform(npcPlayer, record)
        val entityId = (invokeNoArgs(npcPlayer, "getId") as? Number)?.toInt()
            ?: throw IllegalStateException("Не удалось получить ID сущности NPC")

        return RenderedNpc(record, profileId, entityId, npcPlayer)
    }

    fun showTo(viewer: Player, npc: RenderedNpc) {
        val viewerHandle = playerHandle(viewer)
        sendPacket(viewerHandle, createPlayerInfoUpdatePacket(npc.handle, "ADD_PLAYER"))
        sendPacket(viewerHandle, createAddEntityPacket(npc.handle))
        createEntityDataPacket(npc.handle)?.let { sendPacket(viewerHandle, it) }
        createHeadRotationPacket(npc.handle)?.let { sendPacket(viewerHandle, it) }
        createEquipmentPacket(npc.entityId, npc.record.mainHandItem, npc.record.offHandItem)
            ?.let { sendPacket(viewerHandle, it) }

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (viewer.isOnline) {
                runCatching {
                    sendPacket(playerHandle(viewer), createPlayerInfoRemovePacket(npc.profileId))
                }.onFailure { plugin.logger.fine("Не удалось убрать NPC из списка игроков: ${it.message}") }
            }
        }, PLAYER_INFO_REMOVE_DELAY_TICKS)
    }

    fun hideFrom(viewer: Player, npc: RenderedNpc) {
        val viewerHandle = playerHandle(viewer)
        sendPacket(viewerHandle, createRemoveEntitiesPacket(npc.entityId))
        sendPacket(viewerHandle, createPlayerInfoRemovePacket(npc.profileId))
    }

    fun updateEquipment(viewer: Player, npc: RenderedNpc) {
        val packet = createEquipmentPacket(npc.entityId, npc.record.mainHandItem, npc.record.offHandItem) ?: return
        sendPacket(playerHandle(viewer), packet)
    }

    private fun setPlayerTransform(npc: Any, record: NpcRecord) {
        invokeCompatible(npc, "setPos", record.x, record.y, record.z)
        invokeCompatible(npc, "setYRot", record.bodyYaw)
        invokeCompatible(npc, "setXRot", record.pitch)
        invokeCompatible(npc, "setYHeadRot", record.headYaw)
        setFloatField(npc, "yBodyRot", record.bodyYaw)
        setFloatField(npc, "yBodyRotO", record.bodyYaw)
        setFloatField(npc, "yHeadRot", record.headYaw)
        setFloatField(npc, "yHeadRotO", record.headYaw)

        val poseClass = nmsClass("net.minecraft.world.entity.Pose")
        val pose = poseClass.enumConstants
            ?.firstOrNull { (it as? Enum<*>)?.name == record.pose.name }
            ?: throw IllegalStateException("Неподдерживаемая поза NPC: ${record.pose}")
        invokeCompatible(npc, "setPose", pose)

        // Pose is tracked separately from the player crouch/swim/fall-flying flags on some clients.
        runCatching { invokeCompatible(npc, "setShiftKeyDown", record.pose == NpcPose.CROUCHING) }
        if (record.pose == NpcPose.SWIMMING) {
            runCatching { invokeCompatible(npc, "setSwimming", true) }
        }
        if (record.pose == NpcPose.FALL_FLYING) {
            runCatching { invokeCompatible(npc, "setSharedFlag", 7, true) }
        }
    }

    private fun addSignedSkinProperty(profile: Any, skin: MojangSkin) {
        val propertyClass = nmsClass("com.mojang.authlib.properties.Property")
        val constructor = propertyClass.getConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
        )
        val property = constructor.newInstance("textures", skin.textureValue, skin.textureSignature)
        val properties = invokeNoArgs(profile, "getProperties")
            ?: throw IllegalStateException("GameProfile не предоставляет коллекцию свойств")
        val put = allMethods(properties.javaClass).firstOrNull { method ->
            method.name == "put" && method.parameterCount == 2 &&
                method.parameterTypes[0].isAssignableFrom(String::class.java) &&
                method.parameterTypes[1].isInstance(property)
        } ?: throw IllegalStateException("Не удалось добавить подписанное свойство textures в GameProfile")
        invoke(put, properties, "textures", property)
    }

    private fun createDefaultClientInformation(type: Class<*>): Any {
        val factory = type.methods.firstOrNull { it.name == "createDefault" && it.parameterCount == 0 }
        if (factory != null) return invoke(factory, null)
        throw IllegalStateException("ClientInformation.createDefault() не найден")
    }

    private fun createPlayerInfoUpdatePacket(npc: Any, actionName: String): Any {
        val packetClass = nmsClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket")
        val actionClass = packetClass.declaredClasses.firstOrNull { it.simpleName == "Action" }
            ?: nmsClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket\$Action")
        val action = actionClass.enumConstants
            ?.firstOrNull { (it as? Enum<*>)?.name == actionName }
            ?: throw IllegalStateException("PlayerInfo action $actionName не найден")

        val candidates = packetClass.declaredConstructors
            .sortedBy { it.parameterCount }
            .mapNotNull { constructor ->
                buildPlayerInfoArguments(constructor.parameterTypes, action, npc)?.let { constructor to it }
            }
        val (constructor, arguments) = candidates.firstOrNull()
            ?: throw IllegalStateException("Конструктор ClientboundPlayerInfoUpdatePacket не найден")
        return instantiate(constructor, *arguments)
    }

    private fun buildPlayerInfoArguments(parameters: Array<Class<*>>, action: Any, npc: Any): Array<Any?>? {
        val enumSet = createEnumSet(action)
        val values = arrayOfNulls<Any>(parameters.size)
        var assignedAction = false
        var assignedPlayers = false

        for (index in parameters.indices) {
            val parameter = parameters[index]
            when {
                parameter.isInstance(action) -> {
                    values[index] = action
                    assignedAction = true
                }
                EnumSet::class.java.isAssignableFrom(parameter) -> {
                    values[index] = enumSet
                    assignedAction = true
                }
                parameter.isInstance(npc) -> {
                    values[index] = npc
                    assignedPlayers = true
                }
                java.util.Set::class.java.isAssignableFrom(parameter) -> {
                    values[index] = Collections.singleton(npc)
                    assignedPlayers = true
                }
                java.util.Collection::class.java.isAssignableFrom(parameter) -> {
                    values[index] = listOf(npc)
                    assignedPlayers = true
                }
                parameter.isArray && parameter.componentType.isInstance(npc) -> {
                    val array = java.lang.reflect.Array.newInstance(parameter.componentType, 1)
                    java.lang.reflect.Array.set(array, 0, npc)
                    values[index] = array
                    assignedPlayers = true
                }
                else -> return null
            }
        }
        return values.takeIf { assignedAction && assignedPlayers }
    }

    private fun createEnumSet(value: Any): EnumSet<*> {
        val set = EnumSet::class.java.getMethod("noneOf", Class::class.java).invoke(null, value.javaClass)
        @Suppress("UNCHECKED_CAST")
        (set as MutableSet<Any>).add(value)
        return set as EnumSet<*>
    }

    private fun createAddEntityPacket(npc: Any): Any {
        val packetClass = nmsClass("net.minecraft.network.protocol.game.ClientboundAddEntityPacket")
        val rawPacketConstructor = packetClass.declaredConstructors.firstOrNull { constructor ->
            val parameters = constructor.parameterTypes
            parameters.size == 11 &&
                parameters[0] == Int::class.javaPrimitiveType &&
                parameters[1] == UUID::class.java &&
                parameters.sliceArray(2..4).all { it == Double::class.javaPrimitiveType } &&
                parameters[5] == Float::class.javaPrimitiveType &&
                parameters[6] == Float::class.javaPrimitiveType &&
                parameters[7].name == "net.minecraft.world.entity.EntityType" &&
                parameters[8] == Int::class.javaPrimitiveType &&
                parameters[9].name == "net.minecraft.world.phys.Vec3" &&
                parameters[10] == Double::class.javaPrimitiveType
        }
        if (rawPacketConstructor != null) {
            val args = arrayOf(
                invokeNoArgs(npc, "getId"),
                invokeNoArgs(npc, "getUUID"),
                invokeNoArgs(npc, "getX"),
                invokeNoArgs(npc, "getY"),
                invokeNoArgs(npc, "getZ"),
                invokeNoArgs(npc, "getXRot"),
                invokeNoArgs(npc, "getYRot"),
                invokeNoArgs(npc, "getType"),
                0,
                invokeNoArgs(npc, "getDeltaMovement"),
                invokeNoArgs(npc, "getYHeadRot"),
            )
            return instantiate(rawPacketConstructor, *args)
        }

        val constructors = packetClass.declaredConstructors
            .filter { constructor -> constructor.parameterTypes.any { it.isInstance(npc) } }
            .sortedBy { it.parameterCount }
        for (constructor in constructors) {
            val args = buildAddEntityArguments(constructor, npc) ?: continue
            runCatching { return instantiate(constructor, *args) }
        }

        // 1.21.4 uses ServerEntity in the packet constructors instead of the direct field constructor.
        return createTrackedAddEntityPacket(packetClass, npc)
    }

    private fun createTrackedAddEntityPacket(packetClass: Class<*>, npc: Any): Any {
        val serverEntityClass = nmsClass("net.minecraft.server.level.ServerEntity")
        val level = invokeNoArgs(npc, "serverLevel") ?: invokeNoArgs(npc, "level")
            ?: throw IllegalStateException("Не удалось получить мир ServerPlayer для spawn-пакета")
        val tracker = serverEntityClass.declaredConstructors
            .sortedBy { it.parameterCount }
            .firstNotNullOfOrNull { constructor ->
                val args = arrayOfNulls<Any>(constructor.parameterCount)
                var hasLevel = false
                var hasEntity = false
                var hasReceiver = false
                var supported = true
                for (index in constructor.parameterTypes.indices) {
                    val parameter = constructor.parameterTypes[index]
                    when {
                        parameter.isInstance(level) -> {
                            args[index] = level
                            hasLevel = true
                        }
                        parameter.isInstance(npc) -> {
                            args[index] = npc
                            hasEntity = true
                        }
                        parameter == Int::class.javaPrimitiveType -> args[index] = 0
                        parameter == Boolean::class.javaPrimitiveType -> args[index] = false
                        Consumer::class.java.isAssignableFrom(parameter) -> {
                            args[index] = Consumer<Any> { _ -> }
                            hasReceiver = true
                        }
                        else -> supported = false
                    }
                }
                if (!supported || !hasLevel || !hasEntity || !hasReceiver) null
                else runCatching { instantiate(constructor, *args) }.getOrNull()
            } ?: throw IllegalStateException("Конструктор ServerEntity не найден")

        val packetConstructor = packetClass.declaredConstructors
            .sortedBy { it.parameterCount }
            .firstNotNullOfOrNull { constructor ->
                val args = arrayOfNulls<Any>(constructor.parameterCount)
                var hasEntity = false
                var hasTracker = false
                var supported = true
                for (index in constructor.parameterTypes.indices) {
                    val parameter = constructor.parameterTypes[index]
                    when {
                        parameter.isInstance(npc) -> {
                            args[index] = npc
                            hasEntity = true
                        }
                        parameter.isInstance(tracker) -> {
                            args[index] = tracker
                            hasTracker = true
                        }
                        parameter == Int::class.javaPrimitiveType -> args[index] = 0
                        else -> supported = false
                    }
                }
                if (!supported || !hasEntity || !hasTracker) null
                else runCatching { instantiate(constructor, *args) }.getOrNull()
            }
        return packetConstructor ?: throw IllegalStateException("Конструктор ClientboundAddEntityPacket не найден")
    }

    private fun buildAddEntityArguments(constructor: Constructor<*>, npc: Any): Array<Any?>? {
        val position = doubleArrayOf(
            number(invokeNoArgs(npc, "getX")),
            number(invokeNoArgs(npc, "getY")),
            number(invokeNoArgs(npc, "getZ")),
        )
        val rotations = floatArrayOf(
            number(invokeNoArgs(npc, "getXRot")).toFloat(),
            number(invokeNoArgs(npc, "getYRot")).toFloat(),
        )
        val args = arrayOfNulls<Any>(constructor.parameterCount)
        var positionIndex = 0
        var rotationIndex = 0

        for (index in constructor.parameterTypes.indices) {
            val parameter = constructor.parameterTypes[index]
            when {
                parameter.isInstance(npc) -> args[index] = npc
                parameter == Int::class.javaPrimitiveType || parameter == Int::class.javaObjectType -> args[index] = 0
                parameter == Double::class.javaPrimitiveType -> {
                    if (positionIndex >= position.size) return null
                    args[index] = position[positionIndex++]
                }
                parameter == Float::class.javaPrimitiveType -> {
                    if (rotationIndex >= rotations.size) return null
                    args[index] = rotations[rotationIndex++]
                }
                parameter == UUID::class.java -> args[index] = invokeNoArgs(npc, "getUUID")
                parameter.name == "net.minecraft.world.phys.Vec3" -> args[index] = invokeNoArgs(npc, "getDeltaMovement")
                parameter.name == "net.minecraft.core.BlockPos" -> args[index] = createBlockPosition(position)
                parameter.name == "net.minecraft.world.entity.EntityType" -> args[index] = invokeNoArgs(npc, "getType")
                else -> return null
            }
        }
        return args
    }

    private fun createBlockPosition(position: DoubleArray): Any? {
        val type = nmsClass("net.minecraft.core.BlockPos")
        val factory = type.methods.firstOrNull { method ->
            method.name == "containing" && method.parameterCount == 3 &&
                method.parameterTypes.all { it == Double::class.javaPrimitiveType }
        }
        return factory?.let { invoke(it, null, position[0], position[1], position[2]) }
    }

    private fun createEntityDataPacket(npc: Any): Any? {
        val data = invokeNoArgs(npc, "getEntityData") ?: return null
        val packed = invokeNoArgs(data, "packAll") as? List<*> ?: return null
        val entityId = number(invokeNoArgs(npc, "getId")).toInt()
        val packetClass = nmsClass("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket")
        val constructor = packetClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterCount == 2 &&
                candidate.parameterTypes[0] == Int::class.javaPrimitiveType &&
                java.util.List::class.java.isAssignableFrom(candidate.parameterTypes[1])
        } ?: return null
        return instantiate(constructor, entityId, packed)
    }

    private fun createHeadRotationPacket(npc: Any): Any? {
        val packetClass = nmsClass("net.minecraft.network.protocol.game.ClientboundRotateHeadPacket")
        val constructor = packetClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterCount == 2 &&
                candidate.parameterTypes[0].isInstance(npc) &&
                candidate.parameterTypes[1] == Byte::class.javaPrimitiveType
        } ?: return null
        val headYaw = number(invokeNoArgs(npc, "getYHeadRot")).toFloat()
        val packedYaw = (headYaw * 256f / 360f).toInt().toByte()
        return instantiate(constructor, npc, packedYaw)
    }

    private fun createEquipmentPacket(
        entityId: Int,
        mainHand: ItemStack?,
        offHand: ItemStack?,
    ): Any? {
        val items = buildList {
            mainHand?.takeUnless { it.type.isAir || it.amount <= 0 }?.let { add(NpcHand.MAIN_HAND to it) }
            offHand?.takeUnless { it.type.isAir || it.amount <= 0 }?.let { add(NpcHand.OFF_HAND to it) }
        }
        if (items.isEmpty()) return null

        val equipmentSlotClass = nmsClass("net.minecraft.world.entity.EquipmentSlot")
        val pairClass = nmsClass("com.mojang.datafixers.util.Pair")
        val pairFactory = pairClass.getMethod("of", Any::class.java, Any::class.java)
        val equipment = items.map { (hand, bukkitItem) ->
            val slot = equipmentSlotClass.enumConstants
                ?.firstOrNull { (it as? Enum<*>)?.name == hand.slotName }
                ?: throw IllegalStateException("Ячейка экипировки ${hand.slotName} не найдена")
            val nmsItem = toNmsItemStack(bukkitItem)
            invoke(pairFactory, null, slot, nmsItem)
                ?: throw IllegalStateException("Не удалось создать запись экипировки NPC")
        }

        val packetClass = nmsClass("net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket")
        val constructor = packetClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterCount == 2 &&
                candidate.parameterTypes[0] == Int::class.javaPrimitiveType &&
                java.util.List::class.java.isAssignableFrom(candidate.parameterTypes[1])
        } ?: throw IllegalStateException("Конструктор ClientboundSetEquipmentPacket не найден")
        return instantiate(constructor, entityId, equipment)
    }

    private fun toNmsItemStack(item: ItemStack): Any {
        val craftItemStack = nmsClass("org.bukkit.craftbukkit.inventory.CraftItemStack")
        val converter = allMethods(craftItemStack).firstOrNull { method ->
            method.name == "asNMSCopy" && Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 1 && method.parameterTypes[0].isAssignableFrom(ItemStack::class.java)
        } ?: throw IllegalStateException("CraftItemStack.asNMSCopy(ItemStack) не найден")
        return invoke(converter, null, item.clone())
            ?: throw IllegalStateException("Не удалось преобразовать предмет в формат сервера")
    }

    private fun createPlayerInfoRemovePacket(profileId: UUID): Any {
        val packetClass = nmsClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket")
        val constructor = packetClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterCount == 1 && java.util.Collection::class.java.isAssignableFrom(candidate.parameterTypes[0])
        } ?: throw IllegalStateException("Конструктор ClientboundPlayerInfoRemovePacket не найден")
        return instantiate(constructor, listOf(profileId))
    }

    private fun createRemoveEntitiesPacket(entityId: Int): Any {
        val packetClass = nmsClass("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket")
        val arrayConstructor = packetClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterCount == 1 && candidate.parameterTypes[0].isArray &&
                candidate.parameterTypes[0].componentType == Int::class.javaPrimitiveType
        }
        if (arrayConstructor != null) {
            val ids = intArrayOf(entityId)
            return instantiate(arrayConstructor, ids)
        }

        val singleIdConstructor = packetClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterCount == 1 && candidate.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: throw IllegalStateException("Конструктор ClientboundRemoveEntitiesPacket не найден")
        return instantiate(singleIdConstructor, entityId)
    }

    private fun sendPacket(viewerHandle: Any, packet: Any) {
        val connection = invokeNoArgs(viewerHandle, "getConnection")
            ?: readField(viewerHandle, "connection")
            ?: throw IllegalStateException("У игрока отсутствует сетевое соединение")
        val sendMethod = allMethods(connection.javaClass).firstOrNull { method ->
            method.name == "send" && method.parameterCount == 1 && method.parameterTypes[0].isInstance(packet)
        } ?: throw IllegalStateException("Не найден метод отправки сетевого пакета")
        invoke(sendMethod, connection, packet)
    }

    private fun playerHandle(player: Player): Any {
        return invokeNoArgs(player, "getHandle")
            ?: throw IllegalStateException("Не удалось получить ServerPlayer для ${player.name}")
    }

    private fun nmsClass(name: String): Class<*> = try {
        Class.forName(name, true, Bukkit.getServer().javaClass.classLoader)
    } catch (exception: ClassNotFoundException) {
        throw IllegalStateException("Класс $name не найден. NPC-адаптер рассчитан на Paper 1.21.4.", exception)
    }

    private fun invokeNoArgs(target: Any, name: String): Any? {
        val method = allMethods(target.javaClass).firstOrNull { it.name == name && it.parameterCount == 0 }
            ?: return null
        return invoke(method, target)
    }

    private fun invokeCompatible(target: Any, name: String, vararg arguments: Any?): Any? {
        val method = allMethods(target.javaClass).firstOrNull { candidate ->
            candidate.name == name && candidate.parameterCount == arguments.size &&
                candidate.parameterTypes.indices.all { index -> compatible(candidate.parameterTypes[index], arguments[index]) }
        } ?: throw IllegalStateException("Метод ${target.javaClass.name}.$name не найден")
        return invoke(method, target, *arguments)
    }

    private fun compatible(type: Class<*>, value: Any?): Boolean {
        if (value == null) return !type.isPrimitive
        return boxed(type).isAssignableFrom(value.javaClass)
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> type
    }

    private fun setFloatField(target: Any, name: String, value: Float) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val currentType = type
            val field = runCatching { currentType.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                runCatching {
                    field.trySetAccessible()
                    field.setFloat(target, value)
                }
                return
            }
            type = currentType.superclass
        }
    }

    private fun readField(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val currentType = type
            val field: Field? = runCatching { currentType.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.trySetAccessible()
                    field.get(target)
                }.getOrNull()
            }
            type = currentType.superclass
        }
        return null
    }

    private fun allMethods(type: Class<*>): List<Method> {
        val methods = linkedMapOf<String, Method>()
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.forEach { method ->
                methods.putIfAbsent(method.toGenericString(), method)
            }
            current = current.superclass
        }
        type.methods.forEach { method -> methods.putIfAbsent(method.toGenericString(), method) }
        return methods.values.toList()
    }

    private fun invoke(method: Method, target: Any?, vararg arguments: Any?): Any? {
        method.trySetAccessible()
        return method.invoke(target, *arguments)
    }

    private fun instantiate(constructor: Constructor<*>, vararg arguments: Any?): Any {
        constructor.trySetAccessible()
        return constructor.newInstance(*arguments)
    }

    private fun number(value: Any?): Number = value as? Number
        ?: throw IllegalStateException("Неожиданный тип числового значения в NMS")

    internal data class RenderedNpc(
        val record: NpcRecord,
        val profileId: UUID,
        val entityId: Int,
        val handle: Any,
    )

    private companion object {
        const val PLAYER_INFO_REMOVE_DELAY_TICKS = 40L
    }
}
