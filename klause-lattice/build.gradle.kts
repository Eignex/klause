plugins {
    id("com.eignex.kmp") version "1.3.3"
}

eignexPublish {
    description.set("Exact integer matrix reductions and triangular bounds for Klause.")
    githubRepo.set("Eignex/klause")
}

kotlin {
    compilerOptions {
        optIn.add("com.eignex.klause.lp.lattice.LatticeApi")
    }
    jvm()
    linuxX64()
    if (providers.gradleProperty("targets.full").isPresent) {
        linuxArm64()
        macosArm64()
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":klause-numerics"))
        }
    }
}

dokka {
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(projectDir.resolve("src"))
            remoteUrl("https://github.com/Eignex/klause/blob/main/klause-lattice/src")
            remoteLineSuffix.set("#L")
        }
    }
}
