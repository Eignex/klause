@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("com.eignex.kmp") version "1.1.5"
    kotlin("plugin.serialization") version "2.3.0"
}

eignexPublish {
    description.set("Kotlin solver for Boolean and integer constraint problems. Finds and samples satisfying solutions, picks the best under a weighted objective, and exports to CNF for external SAT engines.")
    githubRepo.set("Eignex/klause")
}

kotlin {
    jvm()
    js(IR) { browser(); nodejs() }
    wasmJs { browser(); nodejs() }
    wasmWasi { nodejs() }
    linuxX64(); linuxArm64()
    macosX64(); macosArm64(); mingwX64()
    iosX64(); iosArm64(); iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            api("com.eignex:skema:0.1.1")
            implementation("com.eignex:kumulant:0.1.1")
            implementation("com.eignex:kpermute:1.1.2")
        }
        commonTest.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.10.0")
        }
        // The parallel Portfolio coordinator lives on JVM only — it relies on
        // kotlinx-coroutines (multiplatform but most idiomatic here) plus
        // java.util.concurrent.atomic primitives that don't have direct JS / WASM analogs.
        jvmMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        jvmTest.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}
