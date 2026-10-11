pluginManagement {
    repositories {
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "PaperMC"
        }
        maven("https://repo1.maven.org/maven2/") {
            name = "MavenCentralRepo1"
        }
        maven("https://maven.aliyun.com/repository/public") {
            name = "AliyunPublic"
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "ZXCtag"
