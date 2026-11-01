plugins {
    // Pin the Kotlin plugins once in the root classloader so sibling KMP projects share one
    // instance — the Kotlin/Native bundle build service can't span per-project classloaders.
    // Subprojects apply the kotlin plugins without a version.
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
}

val koblasJvmArgs = buildList {
    add("--enable-native-access=ALL-UNNAMED")
    if (providers.gradleProperty("koblas.noSimd").orNull != "true") {
        add("--add-modules=jdk.incubator.vector")
    }
}

val koblasPins = mapOf(
    "koblas" to "0.1.1-20261023.051622-264",
    "koblas-jvm" to "0.1.1-20261023.051622-267",
    "koblas-linuxx64" to "0.1.1-20261023.051622-263",
    "koblas-linuxarm64" to "0.1.1-20261023.051622-264",
    "koblas-macosarm64" to "0.1.1-20261023.051354-287",
)

val skemaPins = listOf("skema", "skema-jvm", "skema-linuxx64", "skema-linuxarm64", "skema-macosarm64")
    .associateWith { "0.4.0" }

val kumulantPins = mapOf("jvm" to 127, "linuxx64" to 127, "linuxarm64" to 127, "macosarm64" to 126)
    .mapKeys { (module, _) -> "kumulant-$module" }
    .mapValues { (_, build) -> "0.3.4-20261101.114432-$build" }

if (providers.environmentVariable("LAB_SOURCE_COMMIT").isPresent) {
    // Lab builds retain complete distributions; each fresh tree discards its incremental indexes.
    gradle.projectsEvaluated {
        allprojects {
            tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
                incremental = false
            }
        }
    }
}

// Koblas and Kumulant use timestamped snapshots; this endpoint is outside the conventions'
// default repository set.
allprojects {
    val targetProject = this
    // Direct consumers need platform constraints because timestamped KMP roots redirect to mutable snapshots.
    configurations.matching { it.name == "commonMainImplementation" || it.name == "implementation" }.all {
        for ((module, pinned) in koblasPins + skemaPins + kumulantPins) {
            targetProject.dependencies.constraints.add(name, "com.eignex:$module:$pinned") {
                version { strictly(pinned) }
            }
        }
    }
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent { snapshotsOnly() }
        }
    }
    tasks.withType<AbstractTestTask>().configureEach {
        // Failure policy must separate cached results created with aggregate-only failure reporting.
        inputs.property("kotlin.tests.individualTaskReports", providers.gradleProperty("kotlin.tests.individualTaskReports"))
    }
    // JVM reductions use the Vector API; installed vendor BLAS uses foreign downcalls.
    tasks.withType<Test>().configureEach { jvmArgs(koblasJvmArgs) }
    tasks.withType<JavaExec>().configureEach { jvmArgs(koblasJvmArgs) }
    pluginManager.withPlugin("application") {
        extensions.configure<JavaApplication> {
            applicationDefaultJvmArgs = applicationDefaultJvmArgs + koblasJvmArgs
        }
    }
    // The CLI dist's start scripts come from the conventions plugin, which assigns its own
    // defaultJvmOpts after this block configures them — so append once the task actually runs.
    tasks.withType<CreateStartScripts>().configureEach {
        // Held in a local: referencing the script's own property from the action would put a script
        // object reference in the configuration cache, which cannot be serialized.
        val extraJvmArgs = koblasJvmArgs
        inputs.property("koblasJvmArgs", extraJvmArgs)
        doFirst { defaultJvmOpts = ((defaultJvmOpts ?: emptyList()) + extraJvmArgs).distinct() }
    }
}
