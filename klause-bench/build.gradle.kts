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
    // klause-z3 wired in step 3 of the plan.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.eignex.klause.bench.MainKt")
}

kotlin {
    jvmToolchain(24)
}
