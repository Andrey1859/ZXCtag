package ru.andrey1859.zxctag

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

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
        if (args.size != 6) {
            sender.sendMessage(Component.text("Использование: /zxc <ник> <класс> <статус> <лвл> <ус> <хп>", NamedTextColor.RED))
            sender.sendMessage(
                Component.text("Классы: ", NamedTextColor.GRAY)
                    .append(Component.text(CLASSES.joinToString(", "), NamedTextColor.YELLOW))
            )
            sender.sendMessage(
                Component.text("Статус: ", NamedTextColor.GRAY)
                    .append(Component.text(STATUSES.joinToString(", "), NamedTextColor.YELLOW))
            )
            return true
        }

        val target = Bukkit.getPlayerExact(args[0])
        if (target == null) {
            sender.sendMessage(Component.text("Игрок не найден", NamedTextColor.RED))
            return true
        }

        applyPrefix(target, args[1], args[2], args[3], args[4])
        HpDisplayTracker.spawnOrUpdate(target, args[5])
        sender.sendMessage(Component.text("Префикс и HP выданы игроку ${target.name}", NamedTextColor.GREEN))
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

    private fun applyPrefix(player: Player, className: String, status: String, lvl: String, us: String) {
        val board = Bukkit.getScoreboardManager().mainScoreboard
        val name = teamName(player)
        val team = board.getTeam(name) ?: board.registerNewTeam(name)

        player.scoreboard.teams
            .filter { it != team && it.hasEntry(player.name) }
            .forEach { it.removeEntry(player.name) }

        team.prefix(PrefixBuilder.build(className, status, lvl, us))
        team.suffix(Component.empty())
        if (!team.hasEntry(player.name)) {
            team.addEntry(player.name)
        }
        if (player.scoreboard != board) {
            player.scoreboard = board
        }
    }

    private fun teamName(player: Player): String {
        val hex = player.uniqueId.toString().replace("-", "")
        return "z" + hex.substring(0, 15)
    }
}
