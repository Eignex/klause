plugins {
    id("com.eignex.kmp") version "1.1.5"
}

eignexPublish {
    description.set("Z3 SMT-engine adapter for klause.")
    githubRepo.set("Eignex/klause")
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":klause"))
            // z3-turnkey bundles the native libz3 binaries for Linux/macOS/Windows.
            implementation("tools.aqua:z3-turnkey:4.13.0")
            // klause uses kotlinx-serialization compileOnly; reify the runtime dep.
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":klause"))
        }
    }
}
