plugins {
    // Pin the Kotlin plugins once in the root classloader so sibling KMP projects share one
    // instance — the Kotlin/Native bundle build service can't span per-project classloaders.
    // Subprojects apply the kotlin plugins without a version.
    kotlin("multiplatform") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
}

// koblas ships its sparse-LU work as a snapshot ahead of the next release; the snapshot endpoint is
// not part of the conventions' default repository set, so declare it here.
allprojects {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent { snapshotsOnly() }
        }
    }
}
