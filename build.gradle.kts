plugins {
    // Pin the Kotlin plugins once in the root classloader so sibling KMP projects share one
    // instance — the Kotlin/Native bundle build service can't span per-project classloaders.
    // Subprojects apply the kotlin plugins without a version.
    kotlin("multiplatform") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
}

val checkArchitectureTests = tasks.register<Exec>("checkArchitectureTests") {
    group = "verification"
    description = "Test the package boundary checker without compiling Kotlin."
    workingDir(rootDir)
    commandLine("python3", "scripts/test_check_architecture.py")
    inputs.files("scripts/check_architecture.py", "scripts/test_check_architecture.py")
}

val checkArchitecture = tasks.register<Exec>("checkArchitecture") {
    group = "verification"
    description = "Check documented outbound Kotlin package dependencies without compiling Kotlin."
    dependsOn(checkArchitectureTests)
    workingDir(rootDir)
    commandLine("python3", "scripts/check_architecture.py")
    inputs.file("scripts/check_architecture.py")
    inputs.files(fileTree(rootDir) { include("klause*/src/*Main/kotlin/**/*.kt") })
}

val koblasJvmArgs = buildList {
    add("--enable-native-access=ALL-UNNAMED")
    if (providers.gradleProperty("koblas.noSimd").orNull != "true") {
        add("--add-modules=jdk.incubator.vector")
    }
}

// Koblas and Kumulant use timestamped snapshots; this endpoint is outside the conventions'
// default repository set.
allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        tasks.named("check") { dependsOn(checkArchitecture) }
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        tasks.named("check") { dependsOn(checkArchitecture) }
    }
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent { snapshotsOnly() }
        }
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
