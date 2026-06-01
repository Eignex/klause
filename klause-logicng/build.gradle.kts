plugins {
    id("com.eignex.kmp") version "1.2.2"
}

repositories {
    mavenLocal()
}

eignexPublish {
    description.set("LogicNG SAT-engine adapter for klause.")
    githubRepo.set("Eignex/klause")
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":klause"))
            implementation("org.logicng:logicng:2.6.0")
            // klause uses kotlinx-serialization compileOnly; reify the runtime dep for our
            // bit-blaster path which transitively touches @Serializable AST types.
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
