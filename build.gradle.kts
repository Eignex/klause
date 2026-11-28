plugins {
    // Pinned once in the root classloader: the Kotlin/Native bundle build service cannot span per-project
    // classloaders, so subprojects apply the plugins without a version.
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
    "koblas" to "0.1.1-20261116.224320-351",
    "koblas-jvm" to "0.1.1-20261116.224320-354",
    "koblas-linuxx64" to "0.1.1-20261116.224320-349",
    "koblas-linuxarm64" to "0.1.1-20261116.224320-351",
    "koblas-macosarm64" to "0.1.1-20261116.224320-373",
)

val skemaPins = listOf("skema", "skema-jvm", "skema-linuxx64", "skema-linuxarm64", "skema-macosarm64")
    .associateWith { "0.4.1-20261116.152019-3" }

val kumulantPins = mapOf(
    "kumulant" to "0.3.4-20261116.231003-146",
    "kumulant-jvm" to "0.3.4-20261116.231003-146",
    "kumulant-linuxx64" to "0.3.4-20261116.231003-146",
    "kumulant-linuxarm64" to "0.3.4-20261116.231003-146",
    "kumulant-macosarm64" to "0.3.4-20261116.231003-145",
)

val utilPins = listOf("util", "util-jvm", "util-linuxx64", "util-linuxarm64", "util-macosarm64")
    .associateWith { "0.0.1-ci.43969094073.1-20261116.222329-1" }

val kpermutePins = listOf("kpermute", "kpermute-jvm", "kpermute-linuxx64", "kpermute-linuxarm64", "kpermute-macosarm64")
    .associateWith { "1.3.1-20261116.152032-8" }

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

// Eignex libraries use timestamped snapshots; this endpoint is outside the conventions'
// default repository set.
allprojects {
    val targetProject = this
    // Direct consumers need platform constraints because timestamped KMP roots redirect to mutable snapshots.
    configurations.matching {
        it.name == "commonMainApi" || it.name == "commonMainImplementation" || it.name == "implementation"
    }.all {
        val pins = if (name == "commonMainApi") utilPins else koblasPins + skemaPins + kumulantPins + utilPins + kpermutePins
        for ((module, pinned) in pins) {
            targetProject.dependencies.constraints.add(name, "com.eignex:$module:$pinned") {
                version { strictly(pinned) }
            }
        }
    }
    repositories {
        ivy {
            url = uri("https://github.com/Eignex/kbuild/releases/download")
            patternLayout { artifact("v[revision]/[artifact]-[revision](-[classifier]).[ext]") }
            metadataSources { gradleMetadata() }
            content { includeModule("com.eignex", "kbuild-platform") }
        }
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
    // The conventions plugin assigns its own defaultJvmOpts after this block, so append once the task runs.
    tasks.withType<CreateStartScripts>().configureEach {
        // Held in a local: referencing the script's own property from the action would put a script
        // object reference in the configuration cache, which cannot be serialized.
        val extraJvmArgs = koblasJvmArgs
        inputs.property("koblasJvmArgs", extraJvmArgs)
        doFirst { defaultJvmOpts = ((defaultJvmOpts ?: emptyList()) + extraJvmArgs).distinct() }
    }
}
