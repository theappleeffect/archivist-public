plugins {
    id("fabric-loom")
}

version = property("mod_version")!!
group = property("maven_group")!!

base {
    archivesName.set("Archivist-${property("version_range")}_${property("mod_version")}")
}

tasks.withType<org.gradle.jvm.tasks.Jar> {
    archiveVersion.set("")
}

loom {
    mods {
        create("archivist") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${stonecutter.current.project}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

    // Netty SOCKS proxy support (shaded into mod JAR)
    include(implementation("io.netty:netty-handler-proxy:4.1.97.Final")!!)
    include(implementation("io.netty:netty-codec-socks:4.1.97.Final")!!)
}

tasks.processResources {
    val minecraftDep = when (stonecutter.current.project) {
        "1.21.1" -> ">=1.21 <=1.21.1"
        "1.21.3" -> ">=1.21.2 <=1.21.3"
        "1.21.4" -> "~1.21.4"
        "1.21.5" -> ">=1.21.5 <=1.21.8"
        "1.21.9" -> ">=1.21.9 <=1.21.10"
        "1.21.11" -> "~1.21.11"
        else -> "~${stonecutter.current.project}"
    }
    val props = mapOf(
        "version" to project.version,
        "minecraft_dep" to minecraftDep,
        "deps_fabric_loader" to project.property("deps.fabric_loader")
    )
    inputs.properties(props)

    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

tasks.withType<JavaCompile> {
    options.release.set(21)
    options.encoding = "UTF-8"
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
