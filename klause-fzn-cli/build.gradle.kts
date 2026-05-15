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
}

application {
    mainClass.set("com.eignex.klause.fzn.MainKt")
}

kotlin {
    jvmToolchain(21)
}
