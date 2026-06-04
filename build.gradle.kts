plugins {
    // Pin the Kotlin Gradle plugin (and the serialization compiler plugin) once, in the
    // root classloader: sibling KMP projects (:klause via com.eignex.kmp, :klause-cli via
    // com.eignex.cli) must share one plugin instance — the Kotlin/Native bundle build
    // service cannot be shared across split per-project classloaders. Subprojects declare
    // the kotlin plugins WITHOUT a version.
    kotlin("multiplatform") version "2.3.0" apply false
    kotlin("plugin.serialization") version "2.3.0" apply false
}
