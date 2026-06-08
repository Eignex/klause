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
    // (MinGW needs its own filesystem actuals). -Ptargets.hostOnly trims as in :klause.
    linuxX64()
    if (!providers.gradleProperty("targets.hostOnly").isPresent) {
        linuxArm64()
        macosArm64()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":klause"))
            // runBlocking bridge for the suspend Portfolio API from the (synchronous) CLI.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            // KMP logger for `-v` progress output (custom stderr writer).
            implementation("co.touchlab:kermit:2.1.0")
            // SolveStats exposes kumulant summary types (SumResult/MaxResult); needed to
            // render them as %%%mzn-stat lines.
            implementation("com.eignex:kumulant:0.3.2")
        }
    }
}
