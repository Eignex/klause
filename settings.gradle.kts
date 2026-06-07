rootProject.name = "klause"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provision Java toolchains (e.g. :klause-bench's JDK 24) on machines that only
    // have the CI-installed JDK.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":klause", ":klause-logicng", ":klause-smt", ":klause-choco", ":klause-ortools", ":klause-yuck", ":klause-bench", ":klause-cli")
