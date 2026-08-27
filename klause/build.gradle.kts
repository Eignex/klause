plugins {
    id("com.eignex.kmp") version "1.3.1"
    kotlin("plugin.serialization")
}

eignexPublish {
    description.set("Kotlin solver for Boolean and integer constraint problems. Finds and samples satisfying solutions, picks the best under a weighted objective, and exports to CNF for external SAT engines.")
    githubRepo.set("Eignex/klause")
}

// Klause is published for the JVM and the Kotlin/Native compute hosts it ships a CLI for; those are
// the platforms where a solver's startup time and thread use are the point. JavaScript, Wasm, Windows
// Native and Apple mobile are not published.
//
// Default is host-only (jvm + linuxX64): the targets whose tests run on the linux runner, so
// local `./gradlew build`/`check` and PR/main CI stay fast and mirror each other. Only the
// release does the full sweep — it opts in via -Ptargets.full.
val fullTargets = providers.gradleProperty("targets.full").isPresent

kotlin {
    jvm()
    linuxX64()
    if (fullTargets) {
        linuxArm64()
        macosArm64()
    }

    sourceSets {
        commonMain.dependencies {
            compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            api("com.eignex:skema:0.3.0")
            implementation("com.eignex:koblas:0.1.1-SNAPSHOT")
            implementation("com.eignex:kumulant:0.3.3")
            implementation("com.eignex:kpermute:1.2.0")
            implementation("com.ionspin.kotlin:bignum:0.3.10")
        }
        commonTest.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.11.0")
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
