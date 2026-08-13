plugins {
    // Pin the Kotlin plugins once in the root classloader so sibling KMP projects share one
    // instance — the Kotlin/Native bundle build service can't span per-project classloaders.
    // Subprojects apply the kotlin plugins without a version.
    kotlin("multiplatform") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
}

val enableNativeAccess = "--enable-native-access=ALL-UNNAMED"

// koblas ships its sparse-LU work as a snapshot ahead of the next release; the snapshot endpoint is
// not part of the conventions' default repository set, so declare it here.
allprojects {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent { snapshotsOnly() }
        }
    }
    // koblas reaches the host OpenBLAS / SuiteSparse through java.lang.foreign; undeclared, that warns
    // per run and is set to become an error.
    tasks.withType<Test>().configureEach { jvmArgs(enableNativeAccess) }
    tasks.withType<JavaExec>().configureEach { jvmArgs(enableNativeAccess) }
    pluginManager.withPlugin("application") {
        extensions.configure<JavaApplication> {
            applicationDefaultJvmArgs = applicationDefaultJvmArgs + enableNativeAccess
        }
    }
    // The CLI dist's start scripts come from the conventions plugin, which assigns its own
    // defaultJvmOpts after this block configures them — so append once the task actually runs.
    tasks.withType<CreateStartScripts>().configureEach {
        // Held in a local: referencing the script's own property from the action would put a script
        // object reference in the configuration cache, which cannot be serialized.
        val extraJvmArg = enableNativeAccess
        doFirst { defaultJvmOpts = (defaultJvmOpts ?: emptyList()) + extraJvmArg }
    }
}
