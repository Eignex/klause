plugins {
    // kbuild CLI conventions: KMP + lint + kover, JVM dist + native executables,
    // and the `releaseAssets` packaging task.
    id("com.eignex.cli") version "1.2.7"
}

eignexCli {
    mainClass.set("com.eignex.klause.cli.MainKt")
    entryPoint.set("com.eignex.klause.cli.main")
    // Feeds the generated BuildInfo that `--version` reports. `version` must agree with the
    // `version` field of the MiniZinc solver configuration at
    // klause-mzn-lib/share/minizinc/solvers/klause.msc: MiniZinc reads the solver version from
    // the .msc and the CLI reports it here, so the two have to match.
    appName.set("Klause")
    appId.set("com.eignex.klause")
    version.set("0.1.0")
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
