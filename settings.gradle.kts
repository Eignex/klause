rootProject.name = "klause"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":klause", ":klause-bench", ":klause-cli")
