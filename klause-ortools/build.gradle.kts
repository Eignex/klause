plugins {
    id("com.eignex.kmp") version "1.2.2"
}

eignexPublish {
    description.set("Google OR-Tools CP-SAT reference / anytime adapter for klause.")
    githubRepo.set("Eignex/klause")
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":klause"))
            // OR-Tools Java bindings are JNI-backed; the platform-specific native jar is
            // pulled transitively by ortools-java for the host OS/arch.
            implementation("com.google.ortools:ortools-java:9.14.6206")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":klause"))
        }
    }
}

dokka {
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(projectDir.resolve("src"))
            val sub = projectDir.relativeTo(rootDir).invariantSeparatorsPath
            val prefix = if (sub.isEmpty()) "src" else "$sub/src"
            remoteUrl("https://github.com/Eignex/${rootProject.name}/blob/main/$prefix")
            remoteLineSuffix.set("#L")
        }
    }
}
