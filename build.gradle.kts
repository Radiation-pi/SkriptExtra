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
val jdbcDriver = configurations.create("jdbcDriver")
configurations.compileOnly { extendsFrom(jdbcDriver) }
dependencies {
    val localServerDir = providers.gradleProperty("localServerDir").orNull
    if (localServerDir != null) {
        compileOnly(files("$localServerDir/libraries/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar"))
        testRuntimeOnly(files("$localServerDir/libraries/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar"))
        // Optional offline compatibility build against the exact server installation.
        compileOnly(fileTree("$localServerDir/libraries") { include("**/*.jar") })
        compileOnly(fileTree("$localServerDir/plugins") { include("*.jar") })
        jdbcDriver(files("$localServerDir/libraries/org/mariadb/jdbc/mariadb-java-client/3.5.6/mariadb-java-client-3.5.6.jar"))
    } else {
    compileOnly("com.google.code.gson:gson:2.14.0")
    testRuntimeOnly("com.google.code.gson:gson:2.14.0")
    compileOnly(libs.adventure.api)
    compileOnly(libs.paper.api)
    compileOnly(libs.skript)
    compileOnly(libs.fawe.core)
    compileOnly(libs.fawe.bukkit)
    compileOnly("com.github.retrooper:packetevents-spigot:2.10.1")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.github.SkriptLang:skript-reflect:2.6.3")
    jdbcDriver("org.mariadb.jdbc:mariadb-java-client:3.5.6")
    }
}

group = "re.imc"
version = "1.3.0-paper"
description = "SkriptExtra"

tasks {
    jar {
        from(jdbcDriver.map { zipTree(it) })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/**/module-info.class")
    }
    processResources {
        val pluginVersion = project.version.toString()
        inputs.property("version", pluginVersion)
        filesMatching("paper-plugin.yml") {
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

val journalTest = tasks.register<JavaExec>("journalTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + jdbcDriver
    mainClass.set("re.imc.skriptextra.storage.JournalTest")
}
tasks.check { dependsOn(journalTest) }
// Standalone main-based checks run through journalTest, without a JUnit dependency.
tasks.test { failOnNoDiscoveredTests = false }

val avatarTest = tasks.register<JavaExec>("avatarTest") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("re.imc.skriptextra.avatar.SkinAvatarTest")
    systemProperty("avatar.test.root", project.layout.buildDirectory.get().asFile.absolutePath)
}
tasks.check { dependsOn(avatarTest) }
