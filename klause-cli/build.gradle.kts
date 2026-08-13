plugins {
    // kbuild CLI conventions: KMP + lint + kover, JVM dist + native executables,
    // and the `releaseAssets` packaging task.
    id("com.eignex.cli") version "1.2.10"
}

// Build identity, mirrored by the MiniZinc solver configuration checked below. These feed the
// generated BuildInfo that `--version` reports.
val cliAppName = "Klause"
val cliAppId = "com.eignex.klause"
val cliVersion = "0.0.1"

eignexCli {
    mainClass.set("com.eignex.klause.cli.MainKt")
    entryPoint.set("com.eignex.klause.cli.main")
    appName.set(cliAppName)
    appId.set(cliAppId)
    version.set(cliVersion)
}

// MiniZinc reports the solver id, name and version from the .msc while the CLI reports them from
// BuildInfo, so the two can disagree with nothing failing until someone compares `--version`
// against `minizinc --solvers`. Assert the pair here instead.
val mznSolverConfig =
    rootProject.layout.projectDirectory.file("klause-mzn-lib/share/minizinc/solvers/klause.msc")

val checkMznSolverConfig = tasks.register("checkMznSolverConfig") {
    group = "verification"
    description = "Asserts the MiniZinc solver configuration agrees with the CLI build identity."
    val msc = mznSolverConfig
    val expected = mapOf("id" to cliAppId, "name" to cliAppName, "version" to cliVersion)
    inputs.file(msc)
    inputs.property("expected", expected)
    doLast {
        val file = msc.asFile
        @Suppress("UNCHECKED_CAST")
        val config = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
        val drift = expected.mapNotNull { (field, want) ->
            val got = config[field]
            if (got == want) null else "  $field: .msc has \"$got\", the build declares \"$want\""
        }
        if (drift.isNotEmpty()) {
            throw GradleException(
                "${file.name} disagrees with the CLI build identity:\n" + drift.joinToString("\n"),
            )
        }
    }
}

tasks.named("check") { dependsOn(checkMznSolverConfig) }

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
            implementation("com.eignex:kumulant:0.3.3")
        }
    }
}
