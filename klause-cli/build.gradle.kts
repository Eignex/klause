plugins {
    kotlin("jvm") version "2.3.0"
    application
    // Shared Eignex detekt/ktlint setup (same rules the library modules run via com.eignex.kmp).
    id("com.eignex.lint") version "1.2.2"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":klause"))
    // runBlocking bridge for the suspend Portfolio API from the (synchronous) CLI.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // KMP logger for `-v` progress output (custom stderr writer; survives the planned
    // KMP conversion of this module).
    implementation("co.touchlab:kermit:2.1.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.eignex.klause.cli.MainKt")
}

kotlin {
    // 21, not 24: MiniZinc invokes the installDist launcher through the klause-mzn-lib
    // wrappers on the *system* JVM, so the bytecode must stay runnable there.
    jvmToolchain(21)
}
