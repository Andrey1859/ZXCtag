package ru.andrey1859.zxctag

import net.minecraft.text.MutableText
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.text.TextColor
import net.minecraft.util.Formatting

object TextStyles {
    /**
     * Nametags inherit italic from the vanilla renderer. Every sibling must
     * explicitly disable italic, otherwise colours after the first component
     * get flattened and brackets/level stay uncoloured.
     */
    fun named(content: String, color: Formatting, bold: Boolean = false): MutableText {
        return Text.literal(content).setStyle(
            Style.EMPTY
                .withColor(color)
                .withBold(bold)
                .withItalic(false)
        )
    }

    fun rgb(content: String, color: Int, bold: Boolean = false): MutableText {
        return Text.literal(content).setStyle(
            Style.EMPTY
                .withColor(TextColor.fromRgb(color))
                .withBold(bold)
                .withItalic(false)
        )
    }
}
