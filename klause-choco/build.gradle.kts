plugins {
    id("com.eignex.kmp") version "1.1.5"
}

eignexPublish {
    description.set("Choco Solver complete-search reference adapter for klause.")
    githubRepo.set("Eignex/klause")
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":klause"))
            implementation("org.choco-solver:choco-solver:4.10.14")
            // klause uses kotlinx-serialization compileOnly; reify the runtime dep since our
            // translation path touches @Serializable AST types transitively.
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
