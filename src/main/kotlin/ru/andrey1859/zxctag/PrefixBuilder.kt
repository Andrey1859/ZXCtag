package ru.andrey1859.zxctag

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

object PrefixBuilder {
    private const val ZXC_Z = 0xFF0000
    private const val ZXC_X = 0xAA0000
    private const val ZXC_C = 0x540000
    private const val GOLD = 0xFFAA00

    private val classColors = mapOf(
        "бард" to NamedTextColor.DARK_GREEN,
        "лучник" to NamedTextColor.DARK_GREEN,
        "заклинатель" to NamedTextColor.DARK_GREEN,
        "некромант" to NamedTextColor.DARK_GREEN,
        "жрец" to NamedTextColor.DARK_GREEN,
        "шут" to NamedTextColor.LIGHT_PURPLE,
        "джокер" to NamedTextColor.LIGHT_PURPLE,
        "мушкетер" to NamedTextColor.AQUA,
        "хранитель" to NamedTextColor.BLUE,
    )

    private val classIcons = mapOf(
        "джокер" to "Ⴌ",
        "шут" to "Ⴌ",
        "жрец" to "❤",
        "хранитель" to "✠",
        "заклинатель" to "☠",
        "некромант" to "▭",
        "бард" to "ⁿ",
        "мушкетер" to "ὅ",
        "лучник" to "🏹",
    )

    fun build(className: String, status: String, lvl: String, us: String): Component {
        val clazz = className.lowercase()
        val bracketColor = classColors[clazz] ?: NamedTextColor.WHITE
        val icon = classIcons[clazz] ?: "?"
        val statusIcon = when (status.lowercase()) {
            "premium" -> "⒮ "
            "admin" -> "erni "
            else -> ""
        }

        return Component.empty()
            .append(TextStyles.named("[", bracketColor))
            .append(TextStyles.named(icon, NamedTextColor.WHITE))
            .append(TextStyles.named("] ", bracketColor))
            .append(TextStyles.named(statusIcon, NamedTextColor.WHITE))
            .append(TextStyles.rgb("Z", ZXC_Z, bold = true))
            .append(TextStyles.rgb("X", ZXC_X, bold = true))
            .append(TextStyles.rgb("C", ZXC_C, bold = true))
            .append(TextStyles.named(" | ", NamedTextColor.AQUA))
            .append(TextStyles.rgb("◤", GOLD))
            .append(TextStyles.named("[", NamedTextColor.YELLOW))
            .append(TextStyles.named(lvl, NamedTextColor.YELLOW))
            .append(TextStyles.named("]", NamedTextColor.YELLOW))
            .append(TextStyles.rgb("◥ ", GOLD))
            .append(TextStyles.named("УС: ", NamedTextColor.AQUA, bold = true))
            .append(TextStyles.named(us, NamedTextColor.AQUA))
            .append(TextStyles.named("奤 ", NamedTextColor.WHITE))
    }
}
