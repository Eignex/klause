plugins {
    id("com.eignex.kmp") version "1.3.5"
    kotlin("plugin.serialization")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val recording = layout.buildDirectory.file("reports/tests/plain-jvm-profile/worker.jfr").get().asFile
    val recordingSettings = layout.buildDirectory.file("reports/tests/plain-jvm-profile/events.jfc").get().asFile
    jvmArgs("-XX:FlightRecorderOptions=stackdepth=256", "-XX:StartFlightRecording=filename=" +
        recording.absolutePath + ",settings=" + recordingSettings.absolutePath + ",dumponexit=true")
    doFirst {
        recording.parentFile.mkdirs()
        recordingSettings.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <configuration version="2.0" label="Ordinary worker costs" description="Execution and allocation cost diagnosis" provider="Klause">
              <event name="jdk.ExecutionSample">
                <setting name="enabled">true</setting>
                <setting name="period">5 ms</setting>
              </event>
              <event name="jdk.NativeMethodSample">
                <setting name="enabled">true</setting>
                <setting name="period">20 ms</setting>
              </event>
              <event name="jdk.ObjectAllocationSample">
                <setting name="enabled">true</setting>
                <setting name="throttle">300/s</setting>
                <setting name="stackTrace">true</setting>
              </event>
              <event name="jdk.ClassLoad">
                <setting name="enabled">true</setting>
                <setting name="threshold">0 ms</setting>
                <setting name="stackTrace">true</setting>
              </event>
              <event name="jdk.Compilation">
                <setting name="enabled">true</setting>
                <setting name="threshold">100 ms</setting>
              </event>
              <event name="jdk.ActiveSetting">
                <setting name="enabled">true</setting>
              </event>
              <event name="jdk.InitialEnvironmentVariable"><setting name="enabled">false</setting></event>
              <event name="jdk.InitialSystemProperty"><setting name="enabled">false</setting></event>
              <event name="jdk.JVMInformation"><setting name="enabled">false</setting></event>
              <event name="jdk.SystemProcess"><setting name="enabled">false</setting></event>
              <event name="jdk.ProcessStart"><setting name="enabled">false</setting></event>
            </configuration>
            """.trimIndent() + "\n",
        )
    }
}

kover {
    currentProject {
        instrumentation {
            disabledForAll.set(true)
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    doFirst {
        val testTask = this as org.gradle.api.tasks.testing.Test
        val arguments = testTask.allJvmArgs + testTask.jvmArgumentProviders.flatMap { it.asArguments() }
        val plainJvm = arguments.none { it.startsWith("-javaagent:") || it.startsWith("-agentpath:") }
        check(plainJvm) { "Ordinary JVM timing requires no instrumentation agents" }
        logger.lifecycle("Ordinary JVM timing: no instrumentation agents = " + plainJvm)
    }
}

tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask>().configureEach {
    testLogging.events = testLogging.events + org.gradle.api.tasks.testing.logging.TestLogEvent.STARTED
    testLogging.showStandardStreams = true
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
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
// local build/check and CI use the same target set. The release builds every target via -Ptargets.full.
val fullTargets = providers.gradleProperty("targets.full").isPresent

val skemaPin = "0.4.0"

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
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            api("com.eignex:skema:$skemaPin") {
                version { strictly(skemaPin) }
            }
            implementation("com.eignex:koblas:0.1.1-20261023.051622-264") {
                version { strictly("0.1.1-20261023.051622-264") }
            }
            implementation("com.eignex:kumulant:0.3.4-20261101.114432-127")
            implementation("com.eignex:kpermute:1.3.0")
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

// Bench-owned sources need internal engine access on each compute target without a published artifact.
val lpProfileSources = kotlin.sourceSets.create("lpProfile") {
    kotlin.srcDir(rootProject.file("klause-bench/src/lpProfileMain/kotlin"))
}
kotlin.targets.withType<org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget>().configureEach {
    val main = compilations.getByName("main")
    main.compileTaskProvider.configure {
        mustRunAfter("linkLpProfileReleaseExecutableLinuxX64")
    }
    val profile = compilations.create("lpProfile") {
        associateWith(main)
        defaultSourceSet.dependsOn(lpProfileSources)
        defaultSourceSet.kotlin.srcDir(rootProject.file("klause-bench/src/lpProfileJvm/kotlin"))
    }
    tasks.register<Sync>("stageLpProfileJvm") {
        dependsOn(profile.compileTaskProvider)
        into(layout.buildDirectory.dir("lp-profile/jvm"))
        from(profile.output.allOutputs) { into("classes") }
        from(main.output.allOutputs) { into("classes") }
        from(profile.runtimeDependencyFiles) { into("lib") }
    }
}
kotlin.targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
    val main = compilations.getByName("main")
    val profile = compilations.create("lpProfile") {
        associateWith(main)
        defaultSourceSet.dependsOn(lpProfileSources)
        defaultSourceSet.kotlin.srcDir(rootProject.file("klause-bench/src/lpProfileNative/kotlin"))
    }
    binaries.executable("lpProfile", listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE)) {
        compilation = profile
        baseName = "klause-lp-profile"
        entryPoint = "com.eignex.klause.bench.profile.main"
    }
}

dependencies {
    constraints {
        for (module in listOf("jvm", "linuxx64", "linuxarm64", "macosarm64")) {
            add("commonMainApi", "com.eignex:skema-$module:$skemaPin") {
                version { strictly(skemaPin) }
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
