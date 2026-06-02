plugins {
    kotlin("jvm") version "2.3.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":klause"))
    implementation(project(":klause-logicng"))
    // runBlocking bridge for the suspend Portfolio API from the (synchronous) CLI.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

application {
    mainClass.set("com.eignex.klause.fzn.MainKt")
}

kotlin {
    jvmToolchain(21)
}
