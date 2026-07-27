@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("com.eignex.kmp") version "1.2.6"
    kotlin("plugin.serialization")
}

eignexPublish {
    description.set("Kotlin solver for Boolean and integer constraint problems. Finds and samples satisfying solutions, picks the best under a weighted objective, and exports to CNF for external SAT engines.")
    githubRepo.set("Eignex/klause")
}

// Default is host-only (jvm + linuxX64): the targets whose tests run on the linux runner, so
// local `./gradlew build`/`check` and PR/main CI stay fast and mirror each other. Only the
// release does the full sweep — it opts in via -Ptargets.full.
val fullTargets = providers.gradleProperty("targets.full").isPresent

kotlin {
    // The parallel Portfolio needs real threads, which commonMain (shared with the single-threaded
    // js/wasm targets) has none of. A `jvmAndNative` hierarchy group gives it a first-class
    // intermediate source set (`jvmAndNativeMain`) shared by jvm + every native target — and, unlike
    // a hand-created source set, one published as a consumable metadata variant so downstream KMP
    // modules (klause-cli's native targets) resolve it. js/wasm get only SequentialPortfolio.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndNative") {
                withJvm()
                // Re-home the standard `native` group under jvmAndNative so the shared `nativeMain`
                // source set (the native parallelRun/parallelStream actuals) sees the expects there.
                group("native") { withNative() }
            }
        }
    }

    jvm()
    linuxX64()
    if (fullTargets) {
        // Solver tests are compute-heavy; Mocha's default 2s timeout is far too tight for
        // the single-threaded JS/wasm targets. Tests must also avoid multi-second busy
        // loops — ChromeHeadless kills the page.
        js(IR) {
            browser { testTask { useMocha { timeout = "120s" } } }
            nodejs { testTask { useMocha { timeout = "120s" } } }
        }
        wasmJs {
            browser { testTask { useMocha { timeout = "120s" } } }
            nodejs { testTask { useMocha { timeout = "120s" } } }
        }
        wasmWasi { nodejs { testTask { useMocha { timeout = "120s" } } } }
        linuxArm64()
        macosArm64(); mingwX64()
        iosX64(); iosArm64(); iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            api("com.eignex:skema:0.3.0")
            implementation("com.eignex:koblas:0.1.0")
            implementation("com.eignex:kumulant:0.3.3")
            implementation("com.eignex:kpermute:1.1.2")
            implementation("com.ionspin.kotlin:bignum:0.3.10")
        }
        commonTest.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.10.0")
        }
    }
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

