plugins {
    kotlin("jvm") version "2.3.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":klause"))
    // runBlocking bridge for the suspend Portfolio API from the (synchronous) CLI.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
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
