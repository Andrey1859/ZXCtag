package ru.andrey1859.zxctag

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.exceptions.CommandSyntaxException

/**
 * Reads a single token up to whitespace. Unlike [com.mojang.brigadier.arguments.StringArgumentType.word],
 * this accepts Cyrillic class names without requiring quotes.
 */
class UnquotedStringArgumentType : ArgumentType<String> {
    override fun parse(reader: StringReader): String {
        val start = reader.cursor
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip()
        }
        val value = reader.string.substring(start, reader.cursor)
        if (value.isEmpty()) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedSymbol()
                .createWithContext(reader, "word")
        }
        return value
    }

    override fun getExamples(): Collection<String> = EXAMPLES

    companion object {
        private val EXAMPLES = listOf("бард", "premium", "10")

        fun unquoted(): UnquotedStringArgumentType = UnquotedStringArgumentType()
    }
}
