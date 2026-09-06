plugins {
    id("com.eignex.kmp") version "1.3.1"
}

eignexPublish {
    description.set("Exact-certified linear programming kernel for klause: revised simplex with basis maintenance, rational feasibility primitives, and integer lattice reduction.")
    githubRepo.set("Eignex/klause")
}

// Same target policy as :klause — host-only (jvm + linuxX64) by default, full sweep on -Ptargets.full.
val fullTargets = providers.gradleProperty("targets.full").isPresent

kotlin {
    jvm()
    linuxX64()
    if (fullTargets) {
        linuxArm64()
        macosArm64()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":klause-util"))
            implementation("com.eignex:koblas:0.1.1-SNAPSHOT")
            implementation("com.ionspin.kotlin:bignum:0.3.10")
        }
        jvmMain.dependencies {
            // The bundled HiGHS HFactor, which koblas offers as a basis-solver backend. Present only
            // here: the binding is JVM-only, and every other target reaches the same seam through
            // koblas's portable product-form solver.
            implementation("com.eignex:koblas-hfactor:0.1.1-SNAPSHOT")
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
