rootProject.name = "klause"

pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":klause", ":klause-logicng", ":klause-smt", ":klause-choco", ":klause-ortools", ":klause-bench", ":klause-fzn-cli", ":klause-xcsp-cli")
