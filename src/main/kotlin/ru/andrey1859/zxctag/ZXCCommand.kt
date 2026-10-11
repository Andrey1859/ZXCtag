package ru.andrey1859.zxctag

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.logging.Level

object ZXCCommand : CommandExecutor, TabCompleter {
    private val CLASSES = listOf(
        "бард", "лучник", "заклинатель", "некромант", "жрец",
        "шут", "джокер", "мушкетер", "хранитель",
    )
    private val STATUSES = listOf("none", "premium", "admin")
    private val LEVELS = listOf("1", "5", "10", "20", "30", "50", "100")
    private val US_VALUES = listOf("0", "1", "5", "10", "20")
    private val HP_VALUES = listOf("10", "20", "50", "100", "200")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        try {
            return handle(sender, args)
        } catch (t: Throwable) {
            ZXCtagPlugin.instance.logger.log(Level.SEVERE, "Ошибка /zxc", t)
            sender.sendMessage("§cОшибка /zxc: ${t.javaClass.simpleName}: ${t.message}")
            return true
        }
    }

    private fun handle(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size != 6) {
            sender.sendMessage("§cИспользование: /zxc <ник> <класс> <статус> <лвл> <ус> <хп>")
            sender.sendMessage("§7Классы: §e${CLASSES.joinToString(", ")}")
            sender.sendMessage("§7Статус: §e${STATUSES.joinToString(", ")}")
            return true
        }

        val target = Bukkit.getPlayerExact(args[0])
        if (target == null) {
            sender.sendMessage("§cИгрок не найден")
            return true
        }

        applyPrefix(target, args[1], args[2], args[3], args[4])
        HpDisplayTracker.spawnOrUpdate(target, args[5])
        sender.sendMessage("§aПрефикс и HP выданы игроку ${target.name}")
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): List<String> {
        val current = args.lastOrNull() ?: ""
        val options = when (args.size) {
            1 -> Bukkit.getOnlinePlayers().map { it.name }
            2 -> CLASSES
            3 -> STATUSES
            4 -> LEVELS
            5 -> US_VALUES
            6 -> HP_VALUES
            else -> return emptyList()
        }
        return options.filter { it.startsWith(current, ignoreCase = true) }
    }

    @Suppress("DEPRECATION")
    private fun applyPrefix(player: Player, className: String, status: String, lvl: String, us: String) {
        val board = Bukkit.getScoreboardManager()?.mainScoreboard
            ?: error("Scoreboard недоступен")
        val name = teamName(player)
        val team = board.getTeam(name) ?: board.registerNewTeam(name)

        val current = board.getEntryTeam(player.name)
        if (current != null && current !== team) {
            current.removeEntry(player.name)
        }

        team.setPrefix(PrefixBuilder.build(className, status, lvl, us))
        team.setSuffix("")
        if (!team.hasEntry(player.name)) {
            team.addEntry(player.name)
        }
    }

    private fun teamName(player: Player): String {
        val hex = player.uniqueId.toString().replace("-", "")
        return "z" + hex.substring(0, 15)
    }
}
