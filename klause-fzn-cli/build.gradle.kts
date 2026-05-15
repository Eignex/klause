plugins {
    kotlin("jvm") version "2.3.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":klause"))
}

application {
    mainClass.set("com.eignex.klause.fzn.MainKt")
}

kotlin {
    jvmToolchain(21)
}
