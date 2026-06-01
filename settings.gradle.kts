rootProject.name = "klause"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":klause", ":klause-logicng", ":klause-smt", ":klause-choco", ":klause-ortools", ":klause-bench", ":klause-fzn-cli")
