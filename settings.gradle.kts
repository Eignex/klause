rootProject.name = "klause"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":klause", ":klause-logicng", ":klause-smt", ":klause-choco", ":klause-ortools", ":klause-bench", ":klause-cli")

// Local kumulant checkout (when present) substitutes the published com.eignex:kumulant for
// all subprojects — develop against the in-progress library (e.g. ArgMinStat, kumulant#19)
// without waiting for a Maven Central release. CI and clones without the sibling checkout
// fall back to the published version.
if (file("../kumulant").exists()) {
    includeBuild("../kumulant")
}
