// nodewire_scripting — optional addon mod.
//
// It exists solely to carry the Kotlin compiler (kotlin-scripting /
// kotlin-compiler-embeddable, ~50-90 MB) so the core `nodewire` mod stays
// small. Core declares the `ScriptCompiler` SPI + a registry; this module
// implements it (ScriptHost) and registers itself on mod init. When this
// addon is absent, core's Script Node is read-only.
//
// Stage 1 of the split: module compiles + depends on core. Shading the
// compiler into the jar (extractShadedLibs-style) and the JPMS wiring is
// Stage 3 (Layer B); the multi-mod dev run is Stage 4.

plugins {
    id("net.neoforged.moddev")
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

val neoForgeVer: String by project
val kffVer: String by project
val modGroup: String by project
val modVersion: String by project

group = modGroup
version = modVersion
base.archivesName.set("nodewire_scripting")

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    jvmToolchain(21)
}

repositories {
    maven("https://maven.neoforged.net/releases/")
    maven("https://maven.parchmentmc.org/")
    maven("https://thedarkcolour.github.io/KotlinForForge/")
    mavenCentral()
}

neoForge {
    version = neoForgeVer

    parchment {
        minecraftVersion = "1.21.1"
        mappingsVersion = "2024.11.17"
    }

    mods {
        register("nodewire_scripting") {
            sourceSet(sourceSets["main"])
        }
    }
}

dependencies {
    // The core mod: ScriptModule, ScriptType, the ScriptCompiler SPI + registry.
    // Non-transitive: the moved backend only links core's own classes (+ Minecraft,
    // which this module gets independently via its neoForge{} block). Pulling
    // core's transitive 3rd-party mod deps (Create/JEI/EMI/CC/…) would drag in
    // repos this module doesn't declare and isn't needed to compile/run scripts.
    implementation(project(":")) { isTransitive = false }

    // Kotlin for NeoForge — language loader (KFF) shared with core.
    implementation("thedarkcolour:kotlinforforge-neoforge:$kffVer")

    // The reason this module exists: the runtime Kotlin compiler + scripting
    // host. Pinned to the Kotlin plugin version (2.0.20), in lockstep with
    // KFF's runtime stdlib. These get shaded into our module output in Stage 3.
    val kotlinScriptVer = "2.0.20"
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinScriptVer")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinScriptVer")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinScriptVer")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Make the core mod's compile/runtime classpath (Minecraft, graph types, etc.)
// available to this module's unit tests — same trick core uses. The smoke test
// imports net.minecraft.nbt.CompoundTag + dev.nitka.nodewire.graph.PinValue.
configurations.named("testCompileClasspath").configure {
    extendsFrom(configurations.compileClasspath.get())
}
configurations.named("testRuntimeClasspath").configure {
    extendsFrom(configurations.runtimeClasspath.get())
}

// The moved ScriptHostSmokeTest exercises ScriptModule's `internal` surface
// (specsIn/specsOut/tickBlock + the internal pushInputs/pullOutputs/loadState/
// saveState extensions in PinBridge). `internal` is Gradle-module-scoped, so a
// test in THIS module can't see core's internals by default. Wire core's main
// Kotlin output as a friend path — the canonical Kotlin mechanism (-Xfriend-paths)
// for granting internal visibility across a compilation boundary, without
// widening core's API or duplicating any class.
val coreMainKotlin = project(":").tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class)
tasks.named("compileTestKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class).configure {
    friendPaths.from(coreMainKotlin.flatMap { it.destinationDirectory })
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
