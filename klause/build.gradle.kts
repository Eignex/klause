plugins {
    id("com.eignex.kmp") version "1.3.3"
    kotlin("plugin.serialization")
}

// Coverage belongs to this solver; instrumenting dependencies adds startup work unrelated to its report.
kover {
    currentProject {
        instrumentation {
            includedClasses.add("com.eignex.klause.*")
        }
    }
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
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
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
            implementation("com.eignex:koblas:0.1.1-20261010.042342-251") {
                version { strictly("0.1.1-20261010.042342-251") }
            }
            implementation("com.eignex:kumulant:0.3.4-20260922.073440-66")
            implementation("com.eignex:kpermute:1.2.0")
        }
        // BigInt is java.math.BigInteger on the JVM; native has no platform big integer to map onto.
        nativeMain.dependencies {
            api("com.ionspin.kotlin:bignum:0.3.10")
        }
        commonTest.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.11.0")
        }
    }
}

// A timestamped KMP root still redirects to mutable platform snapshots. Publish constraints too,
// so downstream Gradle consumers resolve the same binaries as this build.
dependencies {
    constraints {
        for ((module, pinned) in mapOf(
            "jvm" to "0.1.1-20261010.042342-254",
            "linuxx64" to "0.1.1-20261010.042342-250",
            "linuxarm64" to "0.1.1-20261010.042342-251",
            "macosarm64" to "0.1.1-20261010.042148-274",
        )) {
            add("commonMainImplementation", "com.eignex:koblas-$module:$pinned") {
                version { strictly(pinned) }
            }
        }
        for ((module, build) in mapOf("jvm" to 66, "linuxx64" to 66, "linuxarm64" to 66, "macosarm64" to 65)) {
            val pinned = "0.3.4-20260922.073440-$build"
            add("commonMainImplementation", "com.eignex:kumulant-$module:$pinned") {
                version { strictly(pinned) }
            }
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
