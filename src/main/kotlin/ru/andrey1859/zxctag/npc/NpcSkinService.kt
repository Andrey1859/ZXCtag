package ru.andrey1859.zxctag.npc

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.UUID

internal data class MojangSkin(
    val ownerName: String,
    val ownerUuid: UUID,
    /** The original signed Mojang `textures` property. Do not modify either field. */
    val textureValue: String,
    val textureSignature: String,
    val textureUrl: String,
    val model: SkinModel,
)

internal enum class SkinModel {
    CLASSIC,
    SLIM,
}

internal class SkinLookupException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Resolves a Java username to its signed Mojang skin property. Calls are made off the server thread.
 * Every failure is reported as [SkinLookupException] so async callers can fall back to [NpcDefaultSkin].
 */
internal class MojangSkinService {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun lookup(username: String): MojangSkin {
        if (!USERNAME_PATTERN.matches(username)) {
            throw SkinLookupException("Ник скина должен содержать 1–16 латинских букв, цифр или знаков _")
        }

        val encodedName = URLEncoder.encode(username, StandardCharsets.UTF_8)
        val userJson = getJson(
            URI("https://api.mojang.com/users/profiles/minecraft/$encodedName"),
            "Аккаунт Minecraft «$username» не найден",
        )
        val uuidText = userJson["id"] as? String
            ?: throw SkinLookupException("Mojang вернул некорректный UUID игрока «$username»")
        val ownerUuid = parseMojangUuid(uuidText)
        val uuidWithoutDashes = ownerUuid.toString().replace("-", "")

        val profileJson = getJson(
            URI("https://sessionserver.mojang.com/session/minecraft/profile/$uuidWithoutDashes?unsigned=false"),
            "Не удалось получить профиль скина игрока «$username»",
        )
        val properties = profileJson["properties"] as? List<*>
            ?: throw SkinLookupException("В профиле игрока «$username» нет данных скина")
        val texturesProperty = properties
            .asSequence()
            .mapNotNull { it as? Map<*, *> }
            .firstOrNull { it["name"] == "textures" }
            ?: throw SkinLookupException("У игрока «$username» не найдено свойство textures")
        val textureValue = texturesProperty["value"] as? String
            ?: throw SkinLookupException("Mojang вернул пустое значение скина для «$username»")
        val textureSignature = texturesProperty["signature"] as? String
        if (textureSignature.isNullOrBlank()) {
            throw SkinLookupException("Mojang не вернул подпись скина для «$username»")
        }

        val decodedTextures = try {
            String(Base64.getDecoder().decode(textureValue), StandardCharsets.UTF_8)
        } catch (exception: IllegalArgumentException) {
            throw SkinLookupException("Mojang вернул некорректные данные скина для «$username»", exception)
        }
        val textureRoot = parseObject(decodedTextures, "Mojang вернул некорректные данные скина для «$username»")
        val textures = textureRoot["textures"] as? Map<*, *>
            ?: throw SkinLookupException("В профиле игрока «$username» отсутствует текстура скина")
        val skin = textures["SKIN"] as? Map<*, *>
            ?: throw SkinLookupException("У игрока «$username» не назначен скин")
        val textureUrl = skin["url"] as? String
            ?: throw SkinLookupException("У игрока «$username» не найден адрес текстуры")
        validateTextureUrl(textureUrl)

        val metadata = skin["metadata"] as? Map<*, *>
        val model = if ((metadata?.get("model") as? String).equals("slim", ignoreCase = true)) {
            SkinModel.SLIM
        } else {
            SkinModel.CLASSIC
        }
        val actualName = profileJson["name"] as? String ?: username

        return MojangSkin(actualName, ownerUuid, textureValue, textureSignature, textureUrl, model)
    }

    /** Checks a skin loaded from `npcs.yml` before it is used to build the GameProfile. */
    fun validateStoredSkin(skin: MojangSkin) {
        if (skin.textureValue.length > MAX_TEXTURE_VALUE_LENGTH || skin.textureSignature.length > MAX_SIGNATURE_LENGTH) {
            throw SkinLookupException("Сохранённые данные скина превышают допустимый размер")
        }
        val decoded = try {
            String(Base64.getDecoder().decode(skin.textureValue), StandardCharsets.UTF_8)
        } catch (exception: IllegalArgumentException) {
            throw SkinLookupException("Сохранённое значение textures повреждено", exception)
        }
        val root = parseObject(decoded, "Сохранённые данные скина повреждены")
        val signedOwnerId = root["profileId"] as? String
        if (signedOwnerId != null && parseMojangUuid(signedOwnerId) != skin.ownerUuid) {
            throw SkinLookupException("UUID подписанного профиля не совпадает с владельцем скина")
        }
        val textures = root["textures"] as? Map<*, *> ?: throw SkinLookupException("В сохранённом профиле нет текстур")
        val skinTexture = textures["SKIN"] as? Map<*, *> ?: throw SkinLookupException("В сохранённом профиле нет скина")
        val url = skinTexture["url"] as? String ?: throw SkinLookupException("В сохранённом профиле нет адреса текстуры")
        validateTextureUrl(url)
        if (url != skin.textureUrl) {
            throw SkinLookupException("Адрес сохранённой текстуры не совпадает с подписанным свойством")
        }
        // The default NPC skin is synthetic and carries no signature, so its texture value is exempt.
        if (skin.textureSignature.isBlank() && skin.textureValue != NpcDefaultSkin.textureValue) {
            throw SkinLookupException("В сохранённом профиле отсутствует подпись Mojang")
        }
    }

    private fun getJson(uri: URI, notFoundMessage: String): Map<String, Any?> {
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(6))
            .header("Accept", "application/json")
            .header("User-Agent", "ZXCtag/1.0.0 (Minecraft server NPC skin lookup)")
            .GET()
            .build()

        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SkinLookupException("Запрос к Mojang был прерван", exception)
        } catch (exception: Exception) {
            throw SkinLookupException("Не удалось связаться с Mojang; попробуйте позже", exception)
        }

        val bytes = try {
            response.body().use { input -> input.readNBytes(MAX_RESPONSE_BYTES + 1) }
        } catch (exception: Exception) {
            throw SkinLookupException("Не удалось прочитать ответ Mojang; попробуйте позже", exception)
        }
        if (bytes.size > MAX_RESPONSE_BYTES) {
            throw SkinLookupException("Ответ Mojang превышает допустимый размер")
        }

        when (response.statusCode()) {
            200 -> Unit
            204, 404 -> throw SkinLookupException(notFoundMessage)
            429 -> throw SkinLookupException("Mojang ограничил частоту запросов; попробуйте позже")
            else -> throw SkinLookupException("Mojang ответил кодом ${response.statusCode()}; попробуйте позже")
        }

        return parseObject(String(bytes, StandardCharsets.UTF_8), "Mojang вернул некорректный ответ")
    }

    private fun parseMojangUuid(raw: String): UUID {
        val compact = raw.replace("-", "")
        if (!UUID_PATTERN.matches(compact)) {
            throw SkinLookupException("Mojang вернул некорректный UUID игрока")
        }
        val dashed = "${compact.substring(0, 8)}-${compact.substring(8, 12)}-" +
            "${compact.substring(12, 16)}-${compact.substring(16, 20)}-${compact.substring(20)}"
        return UUID.fromString(dashed)
    }

    private fun validateTextureUrl(raw: String) {
        val uri = try {
            URI(raw)
        } catch (exception: Exception) {
            throw SkinLookupException("Mojang вернул некорректный адрес текстуры", exception)
        }
        val trustedPath = uri.rawPath?.matches(Regex("^/texture/[0-9a-fA-F]{1,128}$")) == true
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("textures.minecraft.net", ignoreCase = true) ||
            uri.port != -1 || uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null || !trustedPath
        ) {
            throw SkinLookupException("Mojang вернул недоверенный адрес текстуры скина")
        }
    }

    private fun parseObject(json: String, errorMessage: String): Map<String, Any?> {
        return try {
            JsonReader(json).read() as? Map<String, Any?>
                ?: throw IllegalArgumentException("JSON root is not an object")
        } catch (exception: Exception) {
            throw SkinLookupException(errorMessage, exception)
        }
    }

    private class JsonReader(private val source: String) {
        private var cursor = 0

        fun read(): Any? {
            skipWhitespace()
            val value = readValue()
            skipWhitespace()
            require(cursor == source.length) { "Trailing JSON content" }
            return value
        }

        private fun readValue(): Any? {
            skipWhitespace()
            require(cursor < source.length) { "Unexpected end of JSON" }
            return when (source[cursor]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                '-', in '0'..'9' -> readNumber()
                else -> throw IllegalArgumentException("Unexpected JSON character at $cursor")
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val result = linkedMapOf<String, Any?>()
            if (take('}')) return result
            while (true) {
                skipWhitespace()
                require(cursor < source.length && source[cursor] == '"') { "Expected object key" }
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue()
                skipWhitespace()
                if (take('}')) return result
                expect(',')
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            val result = mutableListOf<Any?>()
            if (take(']')) return result
            while (true) {
                result += readValue()
                skipWhitespace()
                if (take(']')) return result
                expect(',')
            }
        }

        private fun readString(): String {
            expect('"')
            val result = StringBuilder()
            while (cursor < source.length) {
                val character = source[cursor++]
                when (character) {
                    '"' -> return result.toString()
                    '\\' -> {
                        require(cursor < source.length) { "Incomplete JSON escape" }
                        when (val escaped = source[cursor++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                require(cursor + 4 <= source.length) { "Incomplete unicode escape" }
                                val code = source.substring(cursor, cursor + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Invalid unicode escape")
                                result.append(code.toChar())
                                cursor += 4
                            }
                            else -> throw IllegalArgumentException("Invalid JSON escape")
                        }
                    }
                    else -> {
                        require(character.code >= 0x20) { "Control character in JSON string" }
                        result.append(character)
                    }
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun readNumber(): Number {
            val start = cursor
            take('-')
            if (!take('0')) {
                readDigits()
            }
            if (take('.')) readDigits()
            if (take('e') || take('E')) {
                if (!take('+')) take('-')
                readDigits()
            }
            val number = source.substring(start, cursor)
            return number.toLongOrNull() ?: number.toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid JSON number")
        }

        private fun readDigits() {
            val start = cursor
            while (cursor < source.length && source[cursor] in '0'..'9') cursor++
            require(cursor > start) { "Expected JSON digit" }
        }

        private fun <T> readLiteral(literal: String, value: T): T {
            require(source.startsWith(literal, cursor)) { "Invalid JSON literal" }
            cursor += literal.length
            return value
        }

        private fun expect(expected: Char) {
            require(take(expected)) { "Expected '$expected' at $cursor" }
        }

        private fun take(expected: Char): Boolean {
            if (cursor >= source.length || source[cursor] != expected) return false
            cursor++
            return true
        }

        private fun skipWhitespace() {
            while (cursor < source.length && source[cursor] in charArrayOf(' ', '\n', '\r', '\t')) cursor++
        }
    }

    private companion object {
        val USERNAME_PATTERN = Regex("^[A-Za-z0-9_]{1,16}$")
        val UUID_PATTERN = Regex("^[0-9a-fA-F]{32}$")
        const val MAX_RESPONSE_BYTES = 64 * 1024
        const val MAX_TEXTURE_VALUE_LENGTH = 48 * 1024
        const val MAX_SIGNATURE_LENGTH = 8 * 1024
    }
}

/**
 * Hardcoded skin applied to every NPC spawned through `/spawnnpc` when the Mojang lookup fails.
 *
 * The texture is served from textures.minecraft.net; its SHA-1 is
 * `35ec06f18b1c1400f30d8671abdddb1254e9c36a`. The profile is synthetic
 * (`profileId` is all zeros), so the texture signature cannot be recomputed —
 * it is stored empty and [MojangSkinService.validateStoredSkin] skips the
 * signature check for this skin.
 */
internal object NpcDefaultSkin {
    val ownerUuid: UUID = UUID(0, 0)
    val ownerName: String = "ZXCNPC"
    val textureUrl: String =
        "https://textures.minecraft.net/texture/35ec06f18b1c1400f30d8671abdddb1254e9c36a"
    val textureValue: String =
        "eyJ0aW1lc3RhbXAiOiAxNzMzMDAwMDAwLCAicHJvZmlsZUlkIjogIjAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMCIsICJwcm9maWxlTmFtZSI6ICJaWENOUEMiLCAidGV4dHVyZXMiOiB7IlNLSU4iOiB7InVybCI6ICJodHRwczovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8zNWVjMDZmMThiMWMxNDAwZjMwZDg2NzFhYmRkZGIxMjU0ZTljMzZhIn19fQ=="
    val textureSignature: String = ""

    val skin: MojangSkin = MojangSkin(
        ownerName = ownerName,
        ownerUuid = ownerUuid,
        textureValue = textureValue,
        textureSignature = textureSignature,
        textureUrl = textureUrl,
        model = SkinModel.CLASSIC,
    )
}
