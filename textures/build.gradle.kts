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

tasks.processResources {
    val modVersion = project.version.toString()
    inputs.property("version", modVersion)
    filteringCharset = "UTF-8"
    exclude("**/.gitkeep")
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}
