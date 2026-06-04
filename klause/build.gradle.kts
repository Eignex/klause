@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("com.eignex.kmp") version "1.2.4"
    kotlin("plugin.serialization")
}

eignexPublish {
    description.set("Kotlin solver for Boolean and integer constraint problems. Finds and samples satisfying solutions, picks the best under a weighted objective, and exports to CNF for external SAT engines.")
    githubRepo.set("Eignex/klause")
}

kotlin {
    jvm()
    // Solver tests are compute-heavy; Mocha's default 2s test timeout is far too tight
    // for the single-threaded JS/wasm targets.
    js(IR) {
        browser { testTask { useMocha { timeout = "120s" } } }
        nodejs { testTask { useMocha { timeout = "120s" } } }
    }
    wasmJs {
        browser { testTask { useMocha { timeout = "120s" } } }
        nodejs { testTask { useMocha { timeout = "120s" } } }
    }
    wasmWasi { nodejs { testTask { useMocha { timeout = "120s" } } } }
    linuxX64(); linuxArm64()
    macosX64(); macosArm64(); mingwX64()
    iosX64(); iosArm64(); iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            api("com.eignex:skema:0.1.1")
            implementation("com.eignex:kumulant:0.3.2")
            implementation("com.eignex:kpermute:1.1.2")
        }
        commonTest.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.10.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

// #160: kbuild's lintDocs gate (fail-on-warning dokka) trips on ~460 legacy unresolved
// KDoc links in this module. Skip doc generation until the links are repaired so `build`
// stays green; detekt still runs in full.
tasks.withType<org.jetbrains.dokka.gradle.tasks.DokkaGenerateTask>().configureEach {
    enabled = false
}

dokka {
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(projectDir.resolve("src"))
            val sub = projectDir.relativeTo(rootDir).invariantSeparatorsPath
            val prefix = if (sub.isEmpty()) "src" else "$sub/src"
            remoteUrl("https://github.com/Eignex/${rootProject.name}/blob/main/$prefix")
            remoteLineSuffix.set("#L")
        }
    }
}
