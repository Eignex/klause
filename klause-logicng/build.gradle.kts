plugins {
    id("com.eignex.kmp") version "1.1.4"
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
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":klause"))
        }
    }
}
