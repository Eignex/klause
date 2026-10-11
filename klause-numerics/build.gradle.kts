plugins {
    id("com.eignex.kmp") version "1.3.3"
}

eignexPublish {
    description.set("Exact multiplatform numeric primitives and cooperative numerical work controls for Klause.")
    githubRepo.set("Eignex/klause")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    jvm()
    linuxX64()
    if (providers.gradleProperty("targets.full").isPresent) {
        linuxArm64()
        macosArm64()
    }
    sourceSets {
        nativeMain.dependencies {
            api("com.ionspin.kotlin:bignum:0.3.10")
        }
    }
}

dokka {
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(projectDir.resolve("src"))
            remoteUrl("https://github.com/Eignex/klause/blob/main/klause-numerics/src")
            remoteLineSuffix.set("#L")
        }
    }
}
