pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("fabric-loom") version "1.15-SNAPSHOT"
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.7.11"
}

stonecutter {
    kotlinController = true
    centralScript = "build.gradle.kts"

    create(rootProject) {
        versions("1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.9", "1.21.11")
        vcsVersion = "1.21.11"
    }
}
