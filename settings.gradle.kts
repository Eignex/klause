rootProject.name = "klause"

plugins {
    // Auto-provision Java toolchains (e.g. :klause-bench's JDK 24) on machines that only
    // have the CI-installed JDK.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":klause", ":klause-logicng", ":klause-smt", ":klause-choco", ":klause-ortools", ":klause-bench", ":klause-cli")
