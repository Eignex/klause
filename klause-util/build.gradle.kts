plugins {
    id("com.eignex.kmp") version "1.3.1"
}

eignexPublish {
    description.set("Primitive collections, bit and interval helpers, checked 64/128-bit arithmetic and cooperative cancellation shared by the klause solver modules.")
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
