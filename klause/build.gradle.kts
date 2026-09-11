plugins {
    id("com.eignex.kmp") version "1.3.3"
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
        jvmTest.dependencies {
            // HFactor is an explicit comparison arm; production owns its basis factors on every target.
            implementation("com.eignex:koblas-hfactor:0.1.1-SNAPSHOT")
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

val jvmTestCompilation = kotlin.targets.getByName("jvm").compilations.getByName("test")

tasks.register<JavaExec>("basisTrace") {
    group = "bench"
    description = "Capture, list, replay, or benchmark the persistent real LP basis corpus."
    dependsOn(jvmTestCompilation.compileTaskProvider)
    classpath(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
    mainClass.set("com.eignex.klause.simplex.basis.BasisTraceBenchmarkKt")
    workingDir(rootDir)
    systemProperty("klause.workspace.root", rootDir.absolutePath)
}

tasks.register<JavaExec>("lpTreeIntegration") {
    group = "verification"
    description = "Validate the 300-instance LP primal-search corpus."
    dependsOn(jvmTestCompilation.compileTaskProvider)
    classpath(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
    mainClass.set("com.eignex.klause.backtrack.lp.LpTreeSearchIntegration")
}


tasks.register<JavaExec>("workingModelCoverage") {
    group = "verification"
    description = "Validate permanent integer and mixed-real objective replacement."
    dependsOn(jvmTestCompilation.compileTaskProvider)
    classpath(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
    mainClass.set("com.eignex.klause.lp.engine.WorkingModelCoverage")
}
