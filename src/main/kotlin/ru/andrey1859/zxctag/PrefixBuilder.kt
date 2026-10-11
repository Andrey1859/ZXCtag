package ru.andrey1859.zxctag

import net.minecraft.text.Text
import net.minecraft.util.Formatting

object PrefixBuilder {
    private val ZXC_Z = 0xFF0000
    private val ZXC_X = 0xAA0000
    private val ZXC_C = 0x540000
    private val GOLD = 0xFFAA00

    private val classColors = mapOf(
        "бард" to Formatting.DARK_GREEN,
        "лучник" to Formatting.DARK_GREEN,
        "заклинатель" to Formatting.DARK_GREEN,
        "некромант" to Formatting.DARK_GREEN,
        "жрец" to Formatting.DARK_GREEN,
        "шут" to Formatting.LIGHT_PURPLE,
        "джокер" to Formatting.LIGHT_PURPLE,
        "мушкетер" to Formatting.AQUA,
        "хранитель" to Formatting.BLUE,
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

    fun build(className: String, status: String, lvl: String, us: String): Text {
        val clazz = className.lowercase()
        val bracketColor = classColors[clazz] ?: Formatting.WHITE
        val icon = classIcons[clazz] ?: "?"
        val statusIcon = when (status.lowercase()) {
            "premium" -> "⒮ "
            "admin" -> "erni "
            else -> ""
        }

        return Text.empty()
            .append(TextStyles.named("[", bracketColor))
            .append(TextStyles.named(icon, Formatting.WHITE))
            .append(TextStyles.named("] ", bracketColor))
            .append(TextStyles.named(statusIcon, Formatting.WHITE))
            .append(TextStyles.rgb("Z", ZXC_Z, bold = true))
            .append(TextStyles.rgb("X", ZXC_X, bold = true))
            .append(TextStyles.rgb("C", ZXC_C, bold = true))
            .append(TextStyles.named(" | ", Formatting.AQUA))
            .append(TextStyles.rgb("◤", GOLD))
            // Vanilla nametag flattening dropped custom colours on these parts
            // in the old plugin — force named yellow on brackets AND level.
            .append(TextStyles.named("[", Formatting.YELLOW))
            .append(TextStyles.named(lvl, Formatting.YELLOW))
            .append(TextStyles.named("]", Formatting.YELLOW))
            .append(TextStyles.rgb("◥ ", GOLD))
            .append(TextStyles.named("УС: ", Formatting.AQUA, bold = true))
            .append(TextStyles.named(us, Formatting.AQUA))
            .append(TextStyles.named("奤 ", Formatting.WHITE))
    }
}
