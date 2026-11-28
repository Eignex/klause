rootProject.name = "klause"

pluginManagement {
    val kbuildVersion = "1.3.8"
    repositories {
        ivy {
            url = uri("https://github.com/Eignex/kbuild/releases/download")
            patternLayout { artifact("v[revision]/[artifact]-[revision](-[classifier]).[ext]") }
            metadataSources { gradleMetadata() }
            content { includeModule("com.eignex", "kbuild") }
        }
        mavenCentral {
            content {
                // The Foojay marker and resolver are published through the Plugin Portal.
                excludeModule(
                    "org.gradle.toolchains.foojay-resolver-convention",
                    "org.gradle.toolchains.foojay-resolver-convention.gradle.plugin",
                )
                excludeModule("org.gradle.toolchains", "foojay-resolver")
            }
        }
        gradlePluginPortal()
    }
    resolutionStrategy.eachPlugin {
        if (requested.id.id in setOf("com.eignex.kmp", "com.eignex.jvm", "com.eignex.cli")) {
            useModule("com.eignex:kbuild:$kbuildVersion")
        }
    }
}

plugins {
    // Provisions the JDK the conventions ask for, so a machine without it does not need one installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":klause", ":klause-bench", ":klause-cli")
