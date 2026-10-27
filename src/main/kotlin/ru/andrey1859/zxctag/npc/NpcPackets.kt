package ru.andrey1859.zxctag.npc

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ru.andrey1859.zxctag.PrefixBuilder
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.UUID

/**
 * Builds packet-only ServerPlayer entities for NPCs and sends their packets.
 *
 * Targets Paper 1.21.4 exactly: constructors and methods are resolved by exact
 * signature (parameter count + type compatibility), never by guessing. A missing
 * target fails with the exact class/method name so the server log shows what to fix.
 *
 * A ServerPlayer is created with a stub ServerGamePacketListenerImpl so the
 * ClientboundPlayerInfoUpdatePacket entry can read `connection.latency()` while
 * being built — the packet-only player has no real network listener.
 */
internal class NpcPackets(private val plugin: JavaPlugin) {

    fun create(record: NpcRecord): RenderedNpc {
        val world = Bukkit.getWorld(record.world)
            ?: throw IllegalStateException("Мир «${record.world}» не загружен")
        val minecraftServer = invokeNoArgs(Bukkit.getServer(), "getServer")
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
        attachStubConnection(npcPlayer, minecraftServer, gameProfile)
        setPlayerTransform(npcPlayer, record)
        val entityId = (invokeNoArgs(npcPlayer, "getId") as? Number)?.toInt()
            ?: throw IllegalStateException("Не удалось получить ID сущности NPC")

        return RenderedNpc(record, profileId, entityId, npcPlayer)
    }

    fun showTo(viewer: Player, npc: RenderedNpc) {
        val viewerHandle = playerHandle(viewer)
        sendPacket(viewerHandle, createPlayerInfoUpdatePacket(npc.handle))
        sendPacket(viewerHandle, createAddEntityPacket(npc.handle))
        createEntityDataPacket(npc.handle)?.let { sendPacket(viewerHandle, it) }
        createHeadRotationPacket(npc.handle)?.let { sendPacket(viewerHandle, it) }
        createEquipmentPacket(npc.entityId, npc.record.mainHandItem, npc.record.offHandItem)
            ?.let { sendPacket(viewerHandle, it) }
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
        invokeMethod(npc, "setPos", record.x, record.y, record.z)
        invokeMethod(npc, "setYRot", record.bodyYaw)
        invokeMethod(npc, "setXRot", record.pitch)
        invokeMethod(npc, "setYHeadRot", record.headYaw)
        invokeMethod(npc, "setYBodyRot", record.bodyYaw)
        setFloatField(npc, "yRotO", record.bodyYaw)
        setFloatField(npc, "xRotO", record.pitch)
        setFloatField(npc, "yBodyRot", record.bodyYaw)
        setFloatField(npc, "yBodyRotO", record.bodyYaw)
        setFloatField(npc, "yHeadRot", record.headYaw)
        setFloatField(npc, "yHeadRotO", record.headYaw)

        val poseClass = nmsClass("net.minecraft.world.entity.Pose")
        val pose = poseClass.enumConstants
            ?.firstOrNull { (it as? Enum<*>)?.name == record.pose.name }
            ?: throw IllegalStateException("Неподдерживаемая поза NPC: ${record.pose}")
        invokeMethod(npc, "setPose", pose)

        if (record.pose == NpcPose.CROUCHING) invokeMethod(npc, "setShiftKeyDown", true)
        if (record.pose == NpcPose.SWIMMING) invokeMethod(npc, "setSwimming", true)
        if (record.pose == NpcPose.FALL_FLYING) invokeMethod(npc, "setSharedFlag", 7, true)

        // The full tag (brand, icons, level, УС) goes on the name tag above the NPC's head.
        val componentClass = nmsClass("net.minecraft.network.chat.Component")
        val literal = componentClass.methods.firstOrNull { method ->
            method.name == "literal" && method.parameterCount == 1 &&
                method.parameterTypes[0] == String::class.java
        } ?: throw IllegalStateException("Component.literal(String) не найден")
        val component = invoke(literal, null, PrefixBuilder.nameTag(record.className, record.status, record.level, record.us, record.name))
            ?: throw IllegalStateException("Component.literal(String) вернул null")
        invokeMethod(npc, "setCustomName", component)
    }

    /**
     * ClientboundPlayerInfoUpdatePacket's Entry(ServerPlayer) reads connection.latency() while the
     * packet is built, but a packet-only ServerPlayer never gets a network listener. Constructing a
     * ServerGamePacketListenerImpl stub assigns player.connection itself, so the ADD_PLAYER packet
     * serialises cleanly (latency 0) without any real connection being involved.
     */
    private fun attachStubConnection(npc: Any, minecraftServer: Any, gameProfile: Any) {
        val connectionClass = nmsClass("net.minecraft.network.Connection")
        val packetFlowClass = nmsClass("net.minecraft.network.protocol.PacketFlow")
        val packetFlow = packetFlowClass.enumConstants
            ?.firstOrNull { (it as? Enum<*>)?.name == "SERVERBOUND" }
            ?: throw IllegalStateException("PacketFlow.SERVERBOUND не найден")
        val connection = connectionClass.getConstructor(packetFlowClass).newInstance(packetFlow)

        val cookieClass = nmsClass("net.minecraft.server.network.CommonListenerCookie")
        val cookieFactory = cookieClass.getMethod(
            "createInitial",
            gameProfile.javaClass,
            java.lang.Boolean.TYPE,
        )
        val cookie = invoke(cookieFactory, null, gameProfile, false)
            ?: throw IllegalStateException("CommonListenerCookie.createInitial() вернул null")

        val listenerClass = nmsClass("net.minecraft.server.network.ServerGamePacketListenerImpl")
        val listenerConstructor = listenerClass.getConstructor(
            minecraftServer.javaClass,
            connectionClass,
            npc.javaClass,
            cookieClass,
        )
        instantiate(listenerConstructor, minecraftServer, connection, npc, cookie)
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
        val put = properties.javaClass.methods.firstOrNull { method ->
            method.name == "put" && method.parameterCount == 2 &&
                method.parameterTypes[0].isAssignableFrom(String::class.java) &&
                method.parameterTypes[1].isInstance(property)
        } ?: throw IllegalStateException("Не удалось добавить подписанное свойство textures в GameProfile")
        invoke(put, properties, "textures", property)
    }

    private fun createDefaultClientInformation(type: Class<*>): Any {
        val factory = type.methods.firstOrNull { it.name == "createDefault" && it.parameterCount == 0 }
        if (factory != null) {
            return invoke(factory, null)
                ?: throw IllegalStateException("ClientInformation.createDefault() вернул null")
        }
        throw IllegalStateException("ClientInformation.createDefault() не найден")
    }

    /** Same action set vanilla sends in ClientboundPlayerInfoUpdatePacket.createPlayerInitializing (1.21.4). */
    private fun createPlayerInfoUpdatePacket(npc: Any): Any {
        val packetClass = nmsClass("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket")
        val factory = packetClass.methods.firstOrNull { method ->
            method.name == "createPlayerInitializing" && method.parameterCount == 1 &&
                method.parameterTypes[0] == Collection::class.java
        } ?: throw IllegalStateException("ClientboundPlayerInfoUpdatePacket.createPlayerInitializing не найден")
        return invoke(factory, null, listOf(npc))
            ?: throw IllegalStateException("createPlayerInitializing вернул null")
    }

    /** 1.21.4 ClientboundAddEntityPacket: (int, UUID, double×3, float×2, EntityType, int, Vec3, double). */
    private fun createAddEntityPacket(npc: Any): Any {
        val packetClass = nmsClass("net.minecraft.network.protocol.game.ClientboundAddEntityPacket")
        val entityTypeClass = nmsClass("net.minecraft.world.entity.EntityType")
        val vectorClass = nmsClass("net.minecraft.world.phys.Vec3")
        val constructor = packetClass.declaredConstructors.firstOrNull { candidate ->
            val parameters = candidate.parameterTypes
            parameters.size == 11 &&
                parameters[0] == Int::class.javaPrimitiveType &&
                parameters[1] == UUID::class.java &&
                parameters[2] == Double::class.javaPrimitiveType &&
                parameters[3] == Double::class.javaPrimitiveType &&
                parameters[4] == Double::class.javaPrimitiveType &&
                parameters[5] == Float::class.javaPrimitiveType &&
                parameters[6] == Float::class.javaPrimitiveType &&
                parameters[7] == entityTypeClass &&
                parameters[8] == Int::class.javaPrimitiveType &&
                parameters[9] == vectorClass &&
                parameters[10] == Double::class.javaPrimitiveType
        } ?: throw IllegalStateException("Конструктор ClientboundAddEntityPacket(11) не найден")

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
        return instantiate(constructor, *args)
    }

    private fun createEntityDataPacket(npc: Any): Any? {
        val data = invokeNoArgs(npc, "getEntityData") ?: return null
        // 1.21.4 has no packAll(); the spawn packet carries non-default values
        // (custom name, non-standing pose).
        val packed = invokeNoArgs(data, "getNonDefaultValues") as? List<*> ?: return null
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
        val converter = craftItemStack.methods.firstOrNull { method ->
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
        val constructor = packetClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterCount == 1 && candidate.parameterTypes[0] == IntArray::class.java
        } ?: throw IllegalStateException("Конструктор ClientboundRemoveEntitiesPacket не найден")
        return instantiate(constructor, intArrayOf(entityId))
    }

    private fun sendPacket(viewerHandle: Any, packet: Any) {
        val connection = invokeNoArgs(viewerHandle, "getConnection")
            ?: readField(viewerHandle, "connection")
            ?: throw IllegalStateException("У игрока отсутствует сетевое соединение")
        val sendMethod = connection.javaClass.methods.firstOrNull { method ->
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
        val method = findMethod(target, name) ?: return null
        return invoke(method, target)
    }

    /** Finds a method by name and compatible argument types, walking superclasses. */
    private fun invokeMethod(target: Any, name: String, vararg arguments: Any?): Any? {
        val method = findMethod(target, name, *arguments)
            ?: throw IllegalStateException("Метод ${target.javaClass.name}.$name не найден")
        return invoke(method, target, *arguments)
    }

    private fun findMethod(target: Any, name: String, vararg arguments: Any?): Method? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            for (method in type.declaredMethods) {
                if (matches(method, name, arguments)) return method
            }
            for (method in type.methods) {
                if (matches(method, name, arguments)) return method
            }
            type = type.superclass
        }
        return null
    }

    private fun matches(method: Method, name: String, arguments: Array<out Any?>): Boolean {
        if (method.name != name || method.parameterCount != arguments.size) return false
        return method.parameterTypes.indices.all { index -> compatible(method.parameterTypes[index], arguments[index]) }
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
}
