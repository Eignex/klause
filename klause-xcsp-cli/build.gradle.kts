plugins {
    kotlin("jvm") version "2.3.0"
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(project(":klause"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.eignex.klause.xcsp.MainKt")
}

kotlin {
    jvmToolchain(24)
}
