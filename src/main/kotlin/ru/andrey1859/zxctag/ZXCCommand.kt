package ru.andrey1859.zxctag

import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.scoreboard.Team
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.concurrent.CompletableFuture

object ZXCCommand {
    private val CLASSES = listOf(
        Hint("бард", "класс · тёмно-зелёный"),
        Hint("лучник", "класс · тёмно-зелёный"),
        Hint("заклинатель", "класс · тёмно-зелёный"),
        Hint("некромант", "класс · тёмно-зелёный"),
        Hint("жрец", "класс · тёмно-зелёный"),
        Hint("шут", "класс · розовый"),
        Hint("джокер", "класс · розовый"),
        Hint("мушкетер", "класс · голубой"),
        Hint("хранитель", "класс · синий"),
    )
    private val STATUSES = listOf(
        Hint("none", "без статуса"),
        Hint("premium", "premium"),
        Hint("admin", "admin"),
    )
    private val LEVELS = listOf("1", "5", "10", "20", "30", "50", "100").map { Hint(it, "уровень") }
    private val US_VALUES = listOf("0", "1", "5", "10", "20").map { Hint(it, "УС") }
    private val HP_VALUES = listOf("10", "20", "50", "100", "200").map { Hint(it, "хп") }

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                literal("zxc")
                    .requires { it.hasPermissionLevel(2) }
                    .executes { sendUsage(it) }
                    .then(
                        argument("ник", EntityArgumentType.player())
                            .executes { sendUsage(it) }
                            .then(
                                argument("класс", UnquotedStringArgumentType.unquoted())
                                    .suggests(hints(CLASSES))
                                    .executes { sendUsage(it) }
                                    .then(
                                        argument("статус", UnquotedStringArgumentType.unquoted())
                                            .suggests(hints(STATUSES))
                                            .executes { sendUsage(it) }
                                            .then(
                                                argument("лвл", UnquotedStringArgumentType.unquoted())
                                                    .suggests(hints(LEVELS))
                                                    .executes { sendUsage(it) }
                                                    .then(
                                                        argument("ус", UnquotedStringArgumentType.unquoted())
                                                            .suggests(hints(US_VALUES))
                                                            .executes { sendUsage(it) }
                                                            .then(
                                                                argument("хп", UnquotedStringArgumentType.unquoted())
                                                                    .suggests(hints(HP_VALUES))
                                                                    .executes { execute(it) }
                                                            )
                                                    )
                                            )
                                    )
                            )
                    )
            )
        }
    }

    private fun sendUsage(context: CommandContext<ServerCommandSource>): Int {
        context.source.sendError(
            Text.literal("Использование: /zxc <ник> <класс> <статус> <лвл> <ус> <хп>")
                .formatted(Formatting.RED)
        )
        context.source.sendFeedback(
            {
                Text.literal("Классы: ").formatted(Formatting.GRAY)
                    .append(Text.literal(CLASSES.joinToString(", ") { it.value }).formatted(Formatting.YELLOW))
            },
            false,
        )
        context.source.sendFeedback(
            {
                Text.literal("Статус: ").formatted(Formatting.GRAY)
                    .append(Text.literal("none, premium, admin").formatted(Formatting.YELLOW))
            },
            false,
        )
        return 0
    }

    private fun execute(context: CommandContext<ServerCommandSource>): Int {
        val player = EntityArgumentType.getPlayer(context, "ник")
        val className = context.getArgument("класс", String::class.java)
        val status = context.getArgument("статус", String::class.java)
        val lvl = context.getArgument("лвл", String::class.java)
        val us = context.getArgument("ус", String::class.java)
        val hp = context.getArgument("хп", String::class.java)

        applyPrefix(player, className, status, lvl, us)
        HpDisplayTracker.spawnOrUpdate(player, hp)
        context.source.sendFeedback(
            {
                Text.literal("Префикс и HP выданы игроку ${player.name.string}")
                    .formatted(Formatting.GREEN)
            },
            true,
        )
        return Command.SINGLE_SUCCESS
    }

    private fun applyPrefix(
        player: ServerPlayerEntity,
        className: String,
        status: String,
        lvl: String,
        us: String,
    ) {
        val scoreboard = player.server.scoreboard
        val teamName = teamName(player)
        val team: Team = scoreboard.getTeam(teamName) ?: scoreboard.addTeam(teamName)

        player.scoreboardTeam?.let { current ->
            if (current !== team) {
                scoreboard.removeScoreHolderFromTeam(player.nameForScoreboard, current)
            }
        }

        team.color = Formatting.RESET
        team.prefix = PrefixBuilder.build(className, status, lvl, us)
        team.suffix = Text.empty()
        scoreboard.addScoreHolderToTeam(player.nameForScoreboard, team)
    }

    /**
     * Vanilla team names are capped at 16 characters, so the player name
     * cannot be used directly.
     */
    private fun teamName(player: ServerPlayerEntity): String {
        val hex = player.uuid.toString().replace("-", "")
        return "z" + hex.substring(0, 15)
    }

    private fun hints(values: List<Hint>): SuggestionProvider<ServerCommandSource> {
        return SuggestionProvider { _, builder -> suggest(builder, values) }
    }

    private fun suggest(builder: SuggestionsBuilder, values: List<Hint>): CompletableFuture<Suggestions> {
        val remaining = builder.remaining.lowercase()
        values.forEach { hint ->
            if (remaining.isEmpty() || hint.value.lowercase().startsWith(remaining)) {
                builder.suggest(hint.value, Text.literal(hint.tooltip))
            }
        }
        return builder.buildFuture()
    }

    private data class Hint(val value: String, val tooltip: String)
}
