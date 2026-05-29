pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        maven("https://maven.parchmentmc.org/") { name = "ParchmentMC" }
        maven("https://thedarkcolour.github.io/KotlinForForge/") { name = "Kotlin for Forge" }
    }
    // Plugin versions live here so both the root mod and the :scripting
    // addon subproject can apply them with `id(...)` (no version) — the
    // standard multi-project idiom. Keep these in lockstep.
    plugins {
        id("net.neoforged.moddev") version "2.0.141"
        id("org.jetbrains.kotlin.jvm") version "2.0.20"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "nodewire"

// :scripting — optional addon that carries the Kotlin compiler (kotlin-scripting)
// so the core mod stays small. Core delegates to it via the ScriptCompiler SPI.
include(":scripting")
