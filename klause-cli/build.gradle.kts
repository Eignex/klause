plugins {
    // kbuild CLI conventions: KMP + lint + kover, JVM dist + native executables,
    // and the `releaseAssets` packaging task.
    id("com.eignex.cli") version "1.2.6"
}

eignexCli {
    mainClass.set("com.eignex.klause.cli.MainKt")
    entryPoint.set("com.eignex.klause.cli.main")
}

kotlin {
    jvm()
    // Host-linkable native executables; Windows is served by the JVM dist for now
    // (MinGW needs its own filesystem actuals). Full matrix is opt-in via -Ptargets.full,
    // as in :klause; default stays jvm + linuxX64 so local builds and PR CI are fast.
    linuxX64()
    if (providers.gradleProperty("targets.full").isPresent) {
        linuxArm64()
        macosArm64()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":klause"))
            // KMP logger for `-v` progress output (custom stderr writer).
            implementation("co.touchlab:kermit:2.1.0")
            // SolveStats exposes kumulant summary types (SumResult/MaxResult); needed to
            // render them as %%%mzn-stat lines.
            implementation("com.eignex:kumulant:0.3.2")
        }
    }
}
