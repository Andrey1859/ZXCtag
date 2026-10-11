import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import javax.imageio.ImageIO

// Мод без кода: в jar попадают только fabric.mod.json, шрифт и картинки.
// Поэтому Fabric Loom, Minecraft и Kotlin для сборки не нужны,
// и Gradle ничего не скачивает, кроме самого себя.
plugins {
    java
}

group = providers.gradleProperty("maven_group").get()
version = providers.gradleProperty("mod_version").get()

base {
    archivesName.set(providers.gradleProperty("archives_base_name"))
}

val assetsDir = layout.projectDirectory.dir("src/main/resources/assets")
val glyphList = assetsDir.file("minecraft/font/default.json")
val generatedFont = layout.buildDirectory.dir("generated/font")

// ВАЖНО: если в шрифте игры хотя бы одна запись не загрузится (пустой file, нет картинки, битый PNG),
// игра выбрасывает ВЕСЬ шрифт ("Default font failed to load") и показывает чёрный экран.
// Поэтому default.json в jar не кладётся как есть: эта задача проверяет записи
// и собирает настоящий шрифт только из готовых (с заполненным file).
val generateFont by tasks.registering {
    description = "Собирает шрифт мода из default.json (только записи с готовой картинкой)."
    inputs.dir(assetsDir)
    outputs.dir(generatedFont)

    doLast {
        val outDir = generatedFont.get().asFile
        outDir.deleteRecursively()

        val text = glyphList.asFile.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        val root = try {
            JsonSlurper().parseText(text) as Map<*, *>
        } catch (e: Exception) {
            throw GradleException("default.json: ошибка в JSON (проверь запятые и кавычки): ${e.message}")
        }
        val entries = (root["glyphs"] ?: root["providers"]) as? List<*>
            ?: throw GradleException("default.json: не найден список \"glyphs\"")

        // Эмодзи занимают две единицы UTF-16, поэтому считаем по кодовым точкам, а не по длине строки.
        fun codePointsOf(s: String): List<Int> {
            val result = ArrayList<Int>()
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                result.add(cp)
                i += Character.charCount(cp)
            }
            return result
        }

        val ready = ArrayList<Map<String, Any?>>()
        var waiting = 0

        entries.forEachIndexed { index, item ->
            val entry = item as? Map<*, *>
                ?: throw GradleException("default.json: запись №${index + 1} должна быть объектом { ... }")

            val rows = (entry["chars"] as? List<*>).orEmpty().map { it as? String ?: "" }
            val codes = rows.joinToString(" ") { row ->
                codePointsOf(row).joinToString("+") { "U+%04X".format(it) }
            }
            val label = "default.json, запись №${index + 1} ($codes)"

            if (entry["type"] != "bitmap") {
                throw GradleException("$label: type должен быть \"bitmap\"")
            }
            val lengths = rows.map { codePointsOf(it).size }.toSet()
            if (rows.isEmpty() || lengths.size != 1 || lengths.first() == 0) {
                throw GradleException("$label: chars должен быть списком строк одинаковой непустой длины")
            }

            // Пустое имя: картинки пока нет, запись просто пропускаем.
            val name = (entry["file"] as? String).orEmpty().trim()
            if (name.isEmpty()) {
                waiting++
                return@forEachIndexed
            }

            val height = (entry["height"] as? Number)?.toInt() ?: 8
            val ascent = (entry["ascent"] as? Number)?.toInt()
                ?: throw GradleException("$label: нет поля ascent")
            if (ascent > height) {
                throw GradleException("$label: ascent ($ascent) не может быть больше height ($height)")
            }

            val match = Regex("([a-z0-9_.-]+):([a-z0-9_./-]+\\.png)").matchEntire(name)
                ?: throw GradleException(
                    "$label: file \"$name\" должен быть вида zxctextures:font/имя.png " +
                        "(только латиница в нижнем регистре, цифры, _ - . /)"
                )
            val relative = "${match.groupValues[1]}/textures/${match.groupValues[2]}"
            val png = File(assetsDir.asFile, relative)
            val exactCase = png.isFile && png.canonicalPath.replace('\\', '/').endsWith("/$relative")
            if (!exactCase) {
                throw GradleException(
                    "$label: нет файла src/main/resources/assets/$relative " +
                        "(проверь имя и регистр букв; в Проводнике включи показ расширений: не должно быть .png.png)"
                )
            }
            val image = try {
                ImageIO.read(png)
            } catch (e: Exception) {
                null
            }
            if (image == null) {
                throw GradleException("$label: ${png.name} не открывается как картинка (нужен настоящий PNG)")
            }
            if (image.width > 256 || image.height > 256) {
                throw GradleException("$label: ${png.name} ${image.width}x${image.height}, максимум 256x256")
            }

            // Служебные поля _symbol и _note в готовый шрифт не попадают.
            val clean = LinkedHashMap<String, Any?>()
            for ((key, value) in entry) {
                if (key is String && !key.startsWith("_")) clean[key] = value
            }
            clean["file"] = name
            ready.add(clean)
        }

        if (ready.isNotEmpty()) {
            val out = File(outDir, "assets/minecraft/font/default.json")
            out.parentFile.mkdirs()
            val json = JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("providers" to ready)))
            out.writeText(json + "\n", Charsets.UTF_8)
        }
        logger.lifecycle("ZXC Textures: символов в моде — ${ready.size}, ждут картинку — $waiting")
    }
}

sourceSets {
    main {
        resources {
            // default.json — это список для сборки, готовый шрифт делает generateFont
            exclude("assets/minecraft/font/default.json")
        }
    }
}

tasks.processResources {
    dependsOn(generateFont)
    from(generatedFont)

    val modVersion = project.version.toString()
    inputs.property("version", modVersion)
    filteringCharset = "UTF-8"
    exclude("**/.gitkeep")
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}
