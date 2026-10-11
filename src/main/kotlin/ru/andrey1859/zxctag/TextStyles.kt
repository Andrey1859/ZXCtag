package ru.andrey1859.zxctag

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

object TextStyles {
    /**
     * Nametags inherit italic. Every sibling must disable it, otherwise
     * colours after the first component get flattened.
     */
    fun named(content: String, color: NamedTextColor, bold: Boolean = false): Component {
        return Component.text(content).style { style ->
            style.color(color)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, bold)
        }
    }

    fun rgb(content: String, color: Int, bold: Boolean = false): Component {
        return Component.text(content).style { style ->
            style.color(TextColor.color(color))
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, bold)
        }
    }
}
