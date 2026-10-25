package ru.andrey1859.zxctag.npc

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.TabCompleteEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.andrey1859.zxctag.ZXCCommand
import java.util.Locale

internal class NpcCommand(
    private val plugin: JavaPlugin,
    private val manager: NpcManager,
) : CommandExecutor, TabCompleter, Listener {
    private val skinService = MojangSkinService()

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean = if (command.name.equals("npcitem", ignoreCase = true)) {
        giveNpcItem(sender, args.toList())
    } else {
        createNpc(sender, args.toList())
    }

    /** Intercepts only our /rpg subcommands so other plugins keep their own /rpg commands. */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onRpgNpcCommand(event: PlayerCommandPreprocessEvent) {
        val parts = event.message.trim().removePrefix("/").split(Regex("\\s+"))
        if (parts.size < 2 || !parts[0].equals("rpg", ignoreCase = true)) return

        val player = event.player
        when {
            parts[1].equals("spawnnpc", ignoreCase = true) -> {
                event.isCancelled = true
                if (!player.hasPermission("zxctag.spawnnpc")) {
                    player.sendMessage("§cУ вас нет права zxctag.spawnnpc")
                    return
                }
                createNpc(player, parts.drop(2))
            }
            parts[1].equals("npcitem", ignoreCase = true) -> {
                event.isCancelled = true
                if (!player.hasPermission("zxctag.npcitem")) {
                    player.sendMessage("§cУ вас нет права zxctag.npcitem")
                    return
                }
                giveNpcItem(player, parts.drop(2))
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onRpgNpcTabComplete(event: TabCompleteEvent) {
        val buffer = event.buffer.removePrefix("/").trimStart()
        val hasTrailingSpace = buffer.lastOrNull()?.isWhitespace() == true
        val parts = buffer.trim().split(Regex("\\s+"))
        if (parts.size < 2 || !parts[0].equals("rpg", ignoreCase = true)) return

        val commandArgs = parts.drop(2)
        when {
            parts[1].equals("spawnnpc", ignoreCase = true) -> {
                if (!event.sender.hasPermission("zxctag.spawnnpc")) return
                val argumentPosition = if (hasTrailingSpace) commandArgs.size + 1 else commandArgs.size
                val current = if (hasTrailingSpace) "" else commandArgs.lastOrNull().orEmpty()
                val options = when (argumentPosition) {
                    2 -> Bukkit.getOnlinePlayers().map { it.name }
                    3 -> ZXCCommand.CLASSES
                    4 -> ZXCCommand.STATUSES
                    5 -> ZXCCommand.LEVELS
                    6 -> ZXCCommand.US_VALUES
                    else -> emptyList()
                }
                if (options.isNotEmpty()) {
                    event.completions = options.filter { it.startsWith(current, ignoreCase = true) }
                }
            }
            parts[1].equals("npcitem", ignoreCase = true) -> {
                if (!event.sender.hasPermission("zxctag.npcitem")) return
                val itemArgs = commandArgs + if (hasTrailingSpace) listOf("") else emptyList()
                event.completions = npcItemTabComplete(event.sender, itemArgs)
            }
        }
    }

    private fun createNpc(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cСоздавать NPC можно только в игре — место и поза берутся у игрока.")
            return true
        }
        if (args.size != 6) {
            sender.sendMessage("§eИспользование: /spawnnpc <имя_NPC> <ник_скина> <класс> <статус> <лвл> <ус>")
            sender.sendMessage("§7Пример: /spawnnpc Guard Notch лучник premium 50 10")
            return true
        }

        val npcName = args[0]
        val skinName = args[1]
        if (!USERNAME_PATTERN.matches(npcName)) {
            sender.sendMessage("§cИмя NPC должно содержать 1–16 латинских букв, цифр или знаков _.")
            return true
        }
        if (!USERNAME_PATTERN.matches(skinName)) {
            sender.sendMessage("§cНик скина должен содержать 1–16 латинских букв, цифр или знаков _.")
            return true
        }
        val className = ZXCCommand.CLASSES.firstOrNull { it.equals(args[2], ignoreCase = true) }
        if (className == null) {
            sender.sendMessage("§cНеизвестный класс. Доступны: §e${ZXCCommand.CLASSES.joinToString(", ")}")
            return true
        }
        val status = ZXCCommand.STATUSES.firstOrNull { it.equals(args[3], ignoreCase = true) }
        if (status == null) {
            sender.sendMessage("§cНеизвестный статус. Доступны: §e${ZXCCommand.STATUSES.joinToString(", ")}")
            return true
        }
        val level = ZXCCommand.LEVELS.firstOrNull { it == args[4] }
        if (level == null) {
            sender.sendMessage("§cНеизвестный уровень. Доступны: §e${ZXCCommand.LEVELS.joinToString(", ")}")
            return true
        }
        val us = ZXCCommand.US_VALUES.firstOrNull { it == args[5] }
        if (us == null) {
            sender.sendMessage("§cНеизвестное значение УС. Доступны: §e${ZXCCommand.US_VALUES.joinToString(", ")}")
            return true
        }
        if (!manager.reserve(npcName)) {
            sender.sendMessage("§cИмя $npcName занято NPC, игроком или другой командой табло.")
            return true
        }

        val transform = try {
            NpcTransform.capture(sender)
        } catch (exception: Exception) {
            manager.release(npcName)
            sender.sendMessage("§cНе удалось сохранить положение игрока: ${exception.message}")
            return true
        }
        val normalizedId = npcKey(npcName)
        sender.sendMessage("§7Получаю подписанный скин игрока $skinName…")

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                val skin = skinService.lookup(skinName)
                plugin.server.scheduler.runTask(plugin, Runnable {
                    try {
                        val record = NpcRecord(
                            id = normalizedId,
                            name = npcName,
                            world = transform.world,
                            x = transform.x,
                            y = transform.y,
                            z = transform.z,
                            bodyYaw = transform.bodyYaw,
                            headYaw = transform.headYaw,
                            pitch = transform.pitch,
                            pose = transform.pose,
                            className = className,
                            status = status,
                            level = level,
                            us = us,
                            skin = skin,
                        )
                        manager.create(record)
                        sender.sendMessage("§aNPC $npcName создан со скином $skinName и префиксом $className/$status/$level/$us.")
                    } catch (exception: Exception) {
                        manager.release(npcName)
                        plugin.logger.severe("Не удалось создать NPC $npcName: ${exception.message}")
                        plugin.logger.fine(exception.stackTraceToString())
                        sender.sendMessage("§cНе удалось создать NPC: ${exception.message ?: "ошибка сервера"}")
                    }
                })
            } catch (exception: SkinLookupException) {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    manager.release(npcName)
                    sender.sendMessage("§c${exception.message}")
                })
            } catch (exception: Exception) {
                plugin.logger.warning("Ошибка запроса скина $skinName: ${exception.message}")
                plugin.server.scheduler.runTask(plugin, Runnable {
                    manager.release(npcName)
                    sender.sendMessage("§cНе удалось получить скин Mojang; попробуйте позже.")
                })
            }
        })
        return true
    }

    private fun giveNpcItem(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cКоманду можно использовать только в игре.")
            return true
        }
        if (!sender.hasPermission("zxctag.npcitem")) {
            sender.sendMessage("§cУ вас нет права zxctag.npcitem")
            return true
        }

        val selection = when (args.size) {
            0 -> NpcHand.MAIN_HAND to null
            1 -> {
                val hand = parseNpcHand(args[0])
                if (hand != null) hand to null else NpcHand.MAIN_HAND to args[0]
            }
            2 -> {
                val firstHand = parseNpcHand(args[0])
                val secondHand = parseNpcHand(args[1])
                when {
                    firstHand != null && secondHand == null -> firstHand to args[1]
                    secondHand != null && firstHand == null -> secondHand to args[0]
                    else -> null
                }
            }
            else -> null
        }
        if (selection == null) {
            sender.sendMessage("§eИспользование: /npcitem [main|off] [имя_NPC]")
            sender.sendMessage("§7Без имени выберется ближайший NPC; main/off копирует соответствующую руку.")
            return true
        }

        val (hand, requestedName) = selection
        val heldItem = when (hand) {
            NpcHand.MAIN_HAND -> sender.inventory.itemInMainHand
            NpcHand.OFF_HAND -> sender.inventory.itemInOffHand
        }
        if (heldItem.type.isAir || heldItem.amount <= 0) {
            sender.sendMessage("§cВ выбранной руке нет предмета.")
            return true
        }

        val npc = manager.findNearbyNpc(sender, requestedName)
        if (npc == null) {
            val target = requestedName?.let { "NPC «$it» не найден рядом" } ?: "Рядом не найден NPC"
            sender.sendMessage("§c$target (максимальная дистанция — ${NPC_INTERACTION_RADIUS_BLOCKS.toInt()} блоков).")
            return true
        }

        try {
            manager.setHandItem(npc.id, hand, heldItem)
        } catch (exception: Exception) {
            plugin.logger.warning("Не удалось изменить предмет в руке NPC ${npc.name}: ${exception.message}")
            sender.sendMessage("§cНе удалось обновить NPC: ${exception.message ?: "ошибка сервера"}")
            return true
        }

        val handName = if (hand == NpcHand.MAIN_HAND) "основную" else "вторую"
        sender.sendMessage("§aПредмет скопирован из вашей руки в $handName руку NPC ${npc.name}.")
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (command.name.equals("npcitem", ignoreCase = true)) {
            if (!sender.hasPermission("zxctag.npcitem")) return emptyList()
            return npcItemTabComplete(sender, args.toList())
        }

        val current = args.lastOrNull().orEmpty()
        val options = when (args.size) {
            2 -> Bukkit.getOnlinePlayers().map { it.name }
            3 -> ZXCCommand.CLASSES
            4 -> ZXCCommand.STATUSES
            5 -> ZXCCommand.LEVELS
            6 -> ZXCCommand.US_VALUES
            else -> emptyList()
        }
        return options.filter { it.startsWith(current, ignoreCase = true) }
    }

    private fun npcItemTabComplete(sender: CommandSender, args: List<String>): List<String> {
        val current = args.lastOrNull().orEmpty()
        val previous = args.dropLast(1)
        val hands = listOf("main", "off")
        val nearbyNames = (sender as? Player)?.let(manager::nearbyNpcNames).orEmpty()
        val options = when {
            previous.isEmpty() -> hands + nearbyNames
            previous.size == 1 && parseNpcHand(previous[0]) != null -> nearbyNames
            previous.size == 1 -> hands
            else -> emptyList()
        }
        return options.filter { it.startsWith(current, ignoreCase = true) }
    }

    private fun parseNpcHand(value: String): NpcHand? = when (value.lowercase(Locale.ROOT)) {
        "main", "mainhand", "main_hand", "основная", "основная_рука" -> NpcHand.MAIN_HAND
        "off", "offhand", "off_hand", "вторая", "вторая_рука" -> NpcHand.OFF_HAND
        else -> null
    }

    private companion object {
        val USERNAME_PATTERN = Regex("^[A-Za-z0-9_]{1,16}$")
    }
}
