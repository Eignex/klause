plugins {
    // kbuild CLI conventions: KMP + lint + kover, JVM dist + native executables,
    // and the `releaseAssets` packaging task.
    id("com.eignex.cli") version "1.2.3"
}

eignexCli {
    mainClass.set("com.eignex.klause.cli.MainKt")
    entryPoint.set("com.eignex.klause.cli.main")
}

kotlin {
    jvm()
    // Host-linkable native executables; Windows is served by the JVM dist for now
    // (MinGW needs its own filesystem actuals).
    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":klause"))
            // runBlocking bridge for the suspend Portfolio API from the (synchronous) CLI.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            // KMP logger for `-v` progress output (custom stderr writer).
            implementation("co.touchlab:kermit:2.1.0")
        }
    }
}
