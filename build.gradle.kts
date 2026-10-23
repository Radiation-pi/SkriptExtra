plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }

    maven {
        url = uri("https://repo.skriptlang.org/releases")
    }

    maven {
        url = uri("https://repo.destroystokyo.com/content/repositories/snapshots/")
    }

    maven {
        url = uri("https://repo.infernalsuite.com/repository/maven-snapshots/")
    }

    maven {
        url = uri("https://repo.codemc.org/repository/maven-public")
    }

    // Skript-PlaceholderAPI
    maven {
        url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots")
    }

    maven {
        url = uri("https://oss.sonatype.org/content/repositories/snapshots")
    }

    maven {
        url = uri("https://jitpack.io" )
    }

    maven {
        url = uri("https://repo.extendedclip.com/content/repositories/placeholderapi/" )
    }

}
dependencies {
    val localServerDir = providers.gradleProperty("localServerDir").orNull
    if (localServerDir != null) {
        // Optional offline compatibility build against the exact server installation.
        compileOnly(fileTree("$localServerDir/libraries") { include("**/*.jar") })
        compileOnly(fileTree("$localServerDir/plugins") { include("*.jar") })
    } else {
    compileOnly(libs.adventure.api)
    compileOnly(libs.paper.api)
    compileOnly(libs.skript)
    compileOnly(libs.fawe.core)
    compileOnly(libs.fawe.bukkit)
    compileOnly("com.github.retrooper:packetevents-spigot:2.10.1")
    compileOnly("me.clip:placeholderapi:2.11.6")
    }
}

group = "re.imc"
version = "1.0.5"
description = "SkriptExtra"

tasks {
    processResources {
        val pluginVersion = project.version.toString()
        inputs.property("version", pluginVersion)
        filesMatching("plugin.yml") {
            expand("version" to pluginVersion)
        }
    }
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

}

publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
    }
}
