rootProject.name = "klause"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":klause", ":klause-logicng", ":klause-z3", ":klause-bench", ":klause-fzn-cli", ":klause-portfolio")
