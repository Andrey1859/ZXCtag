package ru.andrey1859.zxctag

/**
 * Legacy § prefix. Arclight has no Kyori Adventure on the Bukkit classpath,
 * so Paper Components cannot be used at runtime.
 *
 * §r before every fragment kills nametag italic so colours actually apply.
 * Level brackets and the level itself are §e (yellow).
 */
object PrefixBuilder {
    private const val DARK_GREEN = "§2"
    private const val LIGHT_PURPLE = "§d"
    private const val AQUA = "§b"
    private const val BLUE = "§9"
    private const val WHITE = "§f"
    private const val YELLOW = "§e"

    private val classColors = mapOf(
        "бард" to DARK_GREEN,
        "лучник" to DARK_GREEN,
        "заклинатель" to DARK_GREEN,
        "некромант" to DARK_GREEN,
        "жрец" to DARK_GREEN,
        "шут" to LIGHT_PURPLE,
        "джокер" to LIGHT_PURPLE,
        "мушкетер" to AQUA,
        "хранитель" to BLUE,
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

    fun build(className: String, status: String, lvl: String, us: String): String {
        val clazz = className.lowercase()
        val br = classColors[clazz] ?: WHITE
        val icon = classIcons[clazz] ?: "?"
        val statusIcon = when (status.lowercase()) {
            "premium" -> "⒮ "
            "admin" -> "erni "
            else -> ""
        }

        return buildString {
            append(piece(br, "["))
            append(piece(WHITE, icon))
            append(piece(br, "] "))
            if (statusIcon.isNotEmpty()) append(piece(WHITE, statusIcon))
            append(piece(hex(0xFF0000), "Z", bold = true))
            append(piece(hex(0xAA0000), "X", bold = true))
            append(piece(hex(0x540000), "C", bold = true))
            append(piece(AQUA, " | "))
            append(piece(hex(0xFFAA00), "◤"))
            append(piece(YELLOW, "["))
            append(piece(YELLOW, lvl))
            append(piece(YELLOW, "]"))
            append(piece(hex(0xFFAA00), "◥ "))
            append(piece(AQUA, "УС: ", bold = true))
            append(piece(AQUA, us))
            append(piece(WHITE, "奤 "))
        }
    }

    private fun piece(color: String, text: String, bold: Boolean = false): String {
        val b = if (bold) "§l" else ""
        return "§r$color$b$text"
    }

    private fun hex(rgb: Int): String {
        val h = "%06X".format(rgb)
        return "§x" + h.toCharArray().joinToString("") { "§$it" }
    }
}
