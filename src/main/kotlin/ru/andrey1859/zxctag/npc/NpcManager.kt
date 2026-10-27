package ru.andrey1859.zxctag.npc

import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ru.andrey1859.zxctag.PrefixBuilder
import ru.andrey1859.zxctag.ZXCCommand
import java.io.File
import java.util.UUID

internal const val NPC_INTERACTION_RADIUS_BLOCKS = 5.0

/**
 * Holds NPC records, their rendered packet-only ServerPlayer entities and the
 * scoreboard teams that show the NPC prefix in the tab list. Persists to
 * `plugins/ZXCtag/npcs.yml` and restores NPCs after restarts.
 */
internal class NpcManager(
    private val plugin: JavaPlugin,
    private val packets: NpcPackets,
    private val skinValidator: MojangSkinService,
) : Listener {
    private val records = linkedMapOf<String, NpcRecord>()
    private val rendered = linkedMapOf<String, NpcPackets.RenderedNpc>()
    private val pending = mutableSetOf<String>()
    private val storageFile = File(plugin.dataFolder, "npcs.yml")

    fun load() {
        if (!storageFile.exists()) return

        val yaml = YamlConfiguration.loadConfiguration(storageFile)
        val section = yaml.getConfigurationSection("npcs") ?: return
        for (key in section.getKeys(false)) {
            val npcSection = section.getConfigurationSection(key) ?: continue
            val record = runCatching { readRecord(key, npcSection) }
                .onFailure { plugin.logger.warning("Пропускаю некорректную запись NPC '$key': ${it.message}") }
                .getOrNull() ?: continue
            records[record.id] = record
        }

        for (record in records.values) {
            runCatching { applyNpcPrefix(record) }
                .onFailure { plugin.logger.warning("Не удалось восстановить префикс NPC ${record.name}: ${it.message}") }
            renderIfWorldLoaded(record)
        }

        // Usually players join after onEnable; this also covers plugin reloads with online players.
        plugin.server.scheduler.runTask(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) showNpcsInWorld(player)
        })
    }

    /** Reserves a name for a new NPC, or returns the exact reason it is taken. */
    fun reserve(name: String): String? {
        val key = npcKey(name)
        if (key in records) return "Имя $name занято NPC."
        if (key in pending) return "Имя $name занято: предыдущее создание NPC ещё выполняется, подождите и попробуйте снова."
        if (Bukkit.getOnlinePlayers().any { it.name.equals(name, ignoreCase = true) }) return "Имя $name занято игроком."

        val existingTeam = Bukkit.getScoreboardManager()?.mainScoreboard?.getEntryTeam(name)
        if (existingTeam != null && existingTeam.name != npcTeamName(key)) return "Имя $name занято другой командой табло."

        pending += key
        return null
    }

    fun release(name: String) {
        pending.remove(npcKey(name))
    }

    fun nearbyNpcNames(player: Player): List<String> = records.values
        .filter { it.world == player.world.name }
        .filter { distanceSquared(player, it) <= NPC_INTERACTION_RADIUS_BLOCKS * NPC_INTERACTION_RADIUS_BLOCKS }
        .map { it.name }
        .sortedBy { npcKey(it) }

    fun findNearbyNpc(player: Player, requestedName: String? = null): NpcRecord? {
        val candidates = records.values.filter { it.world == player.world.name }
        val target = if (requestedName == null) {
            candidates.minByOrNull { distanceSquared(player, it) }
        } else {
            candidates.firstOrNull { npcKey(it.name) == npcKey(requestedName) }
        } ?: return null

        return target.takeIf {
            distanceSquared(player, it) <= NPC_INTERACTION_RADIUS_BLOCKS * NPC_INTERACTION_RADIUS_BLOCKS
        }
    }

    fun setHandItem(recordId: String, hand: NpcHand, item: ItemStack) {
        val key = npcKey(recordId)
        val current = records[key] ?: error("NPC не найден")
        val currentRendered = rendered[key] ?: renderIfWorldLoaded(current)
            ?: error("Не удалось отобразить NPC в текущем мире")
        val storedItem = item.clone()
        val updated = when (hand) {
            NpcHand.MAIN_HAND -> current.copy(mainHandItem = storedItem)
            NpcHand.OFF_HAND -> current.copy(offHandItem = storedItem)
        }
        val updatedRendered = currentRendered.copy(record = updated)
        records[key] = updated
        rendered[key] = updatedRendered
        try {
            save()
        } catch (exception: Exception) {
            records[key] = current
            rendered[key] = currentRendered
            throw exception
        }

        for (viewer in Bukkit.getOnlinePlayers()) {
            if (viewer.world.name != updated.world) continue
            runCatching { packets.updateEquipment(viewer, updatedRendered) }
                .onFailure { plugin.logger.warning("Не удалось обновить предмет в руках NPC ${updated.name} для ${viewer.name}: ${it.message}") }
        }
    }

    fun create(record: NpcRecord) {
        val key = npcKey(record.name)
        try {
            check(key !in records) { "NPC с именем ${record.name} уже существует" }
            val normalizedRecord = record.copy(id = key)
            val entity = packets.create(normalizedRecord)
            applyNpcPrefix(normalizedRecord)
            records[key] = normalizedRecord
            rendered[key] = entity
            try {
                save()
            } catch (exception: Exception) {
                records.remove(key)
                rendered.remove(key)
                removeNpcPrefix(normalizedRecord)
                throw exception
            }
            for (player in Bukkit.getOnlinePlayers()) {
                if (player.world.name == record.world) {
                    runCatching { packets.showTo(player, entity) }
                        .onFailure { plugin.logger.warning("Не удалось показать NPC ${record.name} игроку ${player.name}: ${it.message}") }
                }
            }
        } finally {
            pending.remove(key)
        }
    }

    fun shutdown() {
        for (player in Bukkit.getOnlinePlayers()) {
            for ((key, entity) in rendered) {
                if (records[key]?.world == player.world.name) {
                    runCatching { packets.hideFrom(player, entity) }
                        .onFailure { plugin.logger.fine("Не удалось скрыть NPC при выключении: ${it.message}") }
                }
            }
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        showNpcsInWorld(event.player)
    }

    @EventHandler
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        plugin.server.scheduler.runTask(plugin, Runnable { showNpcsInWorld(event.player) })
    }

    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        for (record in records.values.filter { it.world == event.world.name }) {
            renderIfWorldLoaded(record)
        }
        for (player in Bukkit.getOnlinePlayers().filter { it.world.name == event.world.name }) {
            showNpcsInWorld(player)
        }
    }

    private fun showNpcsInWorld(player: Player) {
        for (record in records.values) {
            if (record.world != player.world.name) continue
            val entity = rendered[record.id] ?: renderIfWorldLoaded(record) ?: continue
            runCatching { packets.showTo(player, entity) }
                .onFailure { plugin.logger.warning("Не удалось показать NPC ${record.name}: ${it.message}") }
        }
    }

    private fun renderIfWorldLoaded(record: NpcRecord): NpcPackets.RenderedNpc? {
        rendered[record.id]?.let { return it }
        if (Bukkit.getWorld(record.world) == null) return null
        return runCatching {
            packets.create(record).also { rendered[record.id] = it }
        }.onFailure {
            plugin.logger.warning("Не удалось создать NPC ${record.name}: ${it.message}")
            plugin.logger.warning(it.stackTraceToString())
        }.getOrNull()
    }

    private fun distanceSquared(player: Player, record: NpcRecord): Double {
        val location = player.location
        val dx = location.x - record.x
        val dy = location.y - record.y
        val dz = location.z - record.z
        return dx * dx + dy * dy + dz * dz
    }

    private fun applyNpcPrefix(record: NpcRecord) {
        val board = Bukkit.getScoreboardManager()?.mainScoreboard ?: error("Scoreboard недоступен")
        val teamName = npcTeamName(record.id)
        val currentTeam = board.getEntryTeam(record.name)
        check(currentTeam == null || currentTeam.name == teamName) {
            "имя ${record.name} уже используется другой командой табло"
        }
        val team = board.getTeam(teamName) ?: board.registerNewTeam(teamName)
        runCatching { team.setPrefix(PrefixBuilder.tabPrefix(record.className, withZxc = true)) }
            .onFailure { plugin.logger.warning("Не удалось установить префикс таблицы для NPC ${record.name}: ${it.message}") }
        runCatching { team.setSuffix(PrefixBuilder.tabSuffix(record.status)) }
            .onFailure { plugin.logger.warning("Не удалось установить суффикс таблицы для NPC ${record.name}: ${it.message}") }
        if (!team.hasEntry(record.name)) team.addEntry(record.name)
    }

    private fun removeNpcPrefix(record: NpcRecord) {
        val board = Bukkit.getScoreboardManager()?.mainScoreboard ?: return
        val team = board.getTeam(npcTeamName(record.id)) ?: return
        if (team.hasEntry(record.name)) team.removeEntry(record.name)
        if (team.entries.isEmpty()) team.unregister()
    }

    private fun npcTeamName(id: String): String {
        val hex = UUID.nameUUIDFromBytes("zxctag:npc-team:$id".toByteArray(Charsets.UTF_8))
            .toString()
            .replace("-", "")
        return "n" + hex.substring(0, 15)
    }

    private fun readRecord(id: String, section: org.bukkit.configuration.ConfigurationSection): NpcRecord {
        val name = section.getString("name") ?: error("не указано имя")
        require(USERNAME_PATTERN.matches(name)) { "некорректное имя NPC" }
        val world = section.getString("world")?.takeIf { it.isNotBlank() } ?: error("не указан мир")
        val x = section.getDouble("x")
        val y = section.getDouble("y")
        val z = section.getDouble("z")
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "некорректные координаты" }

        val bodyYaw = section.getDouble("bodyYaw").toFloat()
        val headYaw = section.getDouble("headYaw").toFloat()
        val pitch = section.getDouble("pitch").toFloat()
        require(bodyYaw.isFinite() && headYaw.isFinite() && pitch.isFinite()) { "некорректные углы" }
        require(pitch in -90f..90f) { "наклон головы вне диапазона" }

        val pose = section.getString("pose")
            ?.let { saved -> NpcPose.entries.firstOrNull { it.name.equals(saved, ignoreCase = true) } }
            ?: NpcPose.STANDING
        val className = (section.getString("class") ?: section.getString("className"))
            ?.let { saved -> ZXCCommand.CLASSES.firstOrNull { it.equals(saved, ignoreCase = true) } }
            ?: ZXCCommand.CLASSES.first()
        val status = section.getString("status")
            ?.let { saved -> ZXCCommand.STATUSES.firstOrNull { it.equals(saved, ignoreCase = true) } }
            ?: ZXCCommand.STATUSES.first()
        val level = section.getString("level")?.takeIf { it.isNotBlank() } ?: ZXCCommand.LEVELS.first()
        val us = section.getString("us")?.takeIf { it.isNotBlank() } ?: ZXCCommand.US_VALUES.first()
        val mainHandItem = section.getItemStack("equipment.mainHand")
            ?.takeIf { it.amount > 0 && !it.type.isAir }
            ?.clone()
        val offHandItem = section.getItemStack("equipment.offHand")
            ?.takeIf { it.amount > 0 && !it.type.isAir }
            ?.clone()
        val skinSection = section.getConfigurationSection("skin") ?: error("не сохранены данные скина")
        val ownerName = skinSection.getString("ownerName") ?: error("не указан владелец скина")
        require(USERNAME_PATTERN.matches(ownerName)) { "некорректный ник владельца скина" }
        val ownerUuid = UUID.fromString(skinSection.getString("ownerUuid") ?: error("не указан UUID скина"))
        val textureValue = skinSection.getString("textureValue")?.takeIf { it.isNotBlank() }
            ?: error("не сохранено значение textures")
        val textureSignature = skinSection.getString("textureSignature") ?: ""
        val textureUrl = skinSection.getString("textureUrl")?.takeIf { it.isNotBlank() }
            ?: error("не сохранён адрес текстуры")
        val model = skinSection.getString("model")
            ?.let { saved -> SkinModel.entries.firstOrNull { it.name.equals(saved, ignoreCase = true) } }
            ?: SkinModel.CLASSIC
        val skin = MojangSkin(ownerName, ownerUuid, textureValue, textureSignature, textureUrl, model)
        skinValidator.validateStoredSkin(skin)

        return NpcRecord(
            id = npcKey(id),
            name = name,
            world = world,
            x = x,
            y = y,
            z = z,
            bodyYaw = bodyYaw,
            headYaw = headYaw,
            pitch = pitch,
            pose = pose,
            className = className,
            status = status,
            level = level,
            us = us,
            skin = skin,
            mainHandItem = mainHandItem,
            offHandItem = offHandItem,
        )
    }

    private fun save() {
        val yaml = YamlConfiguration()
        for ((id, record) in records) {
            val base = "npcs.$id"
            yaml.set("$base.name", record.name)
            yaml.set("$base.world", record.world)
            yaml.set("$base.x", record.x)
            yaml.set("$base.y", record.y)
            yaml.set("$base.z", record.z)
            yaml.set("$base.bodyYaw", record.bodyYaw.toDouble())
            yaml.set("$base.headYaw", record.headYaw.toDouble())
            yaml.set("$base.pitch", record.pitch.toDouble())
            yaml.set("$base.pose", record.pose.name)
            yaml.set("$base.class", record.className)
            yaml.set("$base.status", record.status)
            yaml.set("$base.level", record.level)
            yaml.set("$base.us", record.us)
            record.mainHandItem?.let { yaml.set("$base.equipment.mainHand", it.clone()) }
            record.offHandItem?.let { yaml.set("$base.equipment.offHand", it.clone()) }
            yaml.set("$base.skin.ownerName", record.skin.ownerName)
            yaml.set("$base.skin.ownerUuid", record.skin.ownerUuid.toString())
            yaml.set("$base.skin.textureValue", record.skin.textureValue)
            yaml.set("$base.skin.textureSignature", record.skin.textureSignature)
            yaml.set("$base.skin.textureUrl", record.skin.textureUrl)
            yaml.set("$base.skin.model", record.skin.model.name)
        }
        plugin.dataFolder.mkdirs()
        yaml.save(storageFile)
    }

    private companion object {
        val USERNAME_PATTERN = Regex("^[A-Za-z0-9_]{1,16}$")
    }
}
