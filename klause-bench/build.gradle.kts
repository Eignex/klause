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
    implementation(project(":klause-z3"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.eignex.klause.bench.MainKt")
}

kotlin {
    jvmToolchain(24)
}
