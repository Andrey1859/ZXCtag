pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        // repo.maven.apache.org often returns 403; these mirrors serve the same artifacts
        maven("https://repo1.maven.org/maven2/") {
            name = "MavenCentralRepo1"
        }
        maven("https://maven.aliyun.com/repository/public") {
            name = "AliyunPublic"
        }
        maven("https://repo.huaweicloud.com/repository/maven/") {
            name = "HuaweiCloud"
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "ZXCtag"
