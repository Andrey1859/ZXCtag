plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
}

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

paperweight {
    // Paper 1.20.5+ runs Mojang-mapped; produce the plugin jar directly in Mojang mappings.
    reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
}
