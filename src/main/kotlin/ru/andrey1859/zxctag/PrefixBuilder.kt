package ru.andrey1859.zxctag

/**
 * Legacy § prefix. Arclight has no Kyori Adventure on the Bukkit classpath,
 * so Paper Components cannot be used at runtime.
 *
 * §r before every fragment kills nametag italic so colours actually apply.
 *
 * The scoreboard team prefix/suffix has a hard length limit (16 raw chars in
 * old CraftBukkit, 64 in newer — § codes count too), so the tab list gets a
 * compact tag: class icon in brackets + status badge. The full tag with the
 * ZXC brand, level and УС is rendered as the NPC name tag above the head.
 */
object PrefixBuilder {
    /** U+E001 is supplied by the client-side ZXC Textures font as the bow icon. */
    private const val PREFIX_ICON = "\uE001"

    private const val DARK_GREEN = "§2"
    private const val LIGHT_PURPLE = "§d"
    private const val AQUA = "§b"
    private const val BLUE = "§9"
    private const val WHITE = "§f"
    private const val YELLOW = "§e"

    /** Worst-case scoreboard team prefix/suffix limit in raw characters (§ codes included). */
    private const val TAB_LIMIT = 16

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
        "лучник" to PREFIX_ICON,
    )

    /** Full tag with the brand, icons, level and УС — for the NPC name tag (no scoreboard limit). */
    fun build(className: String, status: String, lvl: String, us: String, withZxc: Boolean = true): String {
        val clazz = className.lowercase()
        val br = classColors[clazz] ?: WHITE
        val icon = classIcons[clazz] ?: "?"
        val statusIcon = statusIcon(status) + " "

        return buildString {
            append(piece(br, "["))
            append(piece(WHITE, icon))
            append(piece(br, "] "))
            if (statusIcon.isNotEmpty()) append(piece(WHITE, statusIcon))
            if (withZxc) {
                append(piece(hex(0xFF0000), "Z", bold = true))
                append(piece(hex(0xAA0000), "X", bold = true))
                append(piece(hex(0x540000), "C", bold = true))
                append(piece(AQUA, " | "))
            }
            append(piece(hex(0xFFAA00), "◤"))
            append(piece(YELLOW, "["))
            append(piece(YELLOW, lvl))
            append(piece(YELLOW, "]"))
            append(piece(hex(0xFFAA00), "◥ "))
            append(piece(WHITE, "УС: ", bold = true))
            append(piece(AQUA, us))
            append(piece(WHITE, "奤 "))
        }
    }

    /**
     * Full tag for the name tag above the NPC's head. Level/УС accept any values,
     * so they are clipped to keep the tag readable for arbitrarily long input.
     */
    fun nameTag(className: String, status: String, level: String, us: String, npcName: String): String =
        build(className, status, clipText(level), clipText(us), withZxc = true) + piece(WHITE, npcName)

    /**
     * Compact tag for the tab list. `withZxc` shows the brand instead of the
     * brackets — both variants fit the 16-char scoreboard limit.
     */
    fun tabPrefix(className: String, withZxc: Boolean = true): String {
        val clazz = className.lowercase()
        val br = classColors[clazz] ?: WHITE
        val icon = classIcons[clazz] ?: "?"
        return clip(
            if (withZxc) piece(WHITE, "ZXC") + piece(WHITE, icon)
            else piece(br, "[") + piece(WHITE, icon) + piece(br, "] ")
        )
    }

    /** Status badge for the tab list suffix. */
    fun tabSuffix(status: String): String = clip(piece(WHITE, statusIcon(status)))

    private fun statusIcon(status: String): String = when (status.lowercase()) {
        "premium" -> "⒮"
        "admin" -> "ꚍ"
        else -> "\uE002"
    }

    private fun clipText(value: String): String =
        if (value.length <= 24) value else value.substring(0, 24) + "…"

    /** Cut to the worst-case scoreboard limit, never splitting a surrogate pair. */
    private fun clip(value: String): String {
        if (value.length <= TAB_LIMIT) return value
        var end = TAB_LIMIT
        if (end > 0 && value[end - 1].isHighSurrogate()) end--
        return value.substring(0, end)
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
