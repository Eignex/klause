plugins {
    id("com.eignex.kmp") version "1.2.6"
}

eignexPublish {
    description.set("SMT-engine adapter for klause via JavaSMT — one adapter, many backends (Z3, CVC5, MathSAT5, Bitwuzla, SMTInterpol, Yices2, Princess).")
    githubRepo.set("Eignex/klause")
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":klause"))
            // JavaSMT — unified API over SMT solvers, defaulting to its transitive
            // SMTInterpol backend. Princess is excluded for its ~11 MB Scala stdlib; native
            // backends (Z3, CVC5, …) are opt-in via their own JavaSMT solver artifacts.
            implementation("org.sosy-lab:java-smt:5.0.0") {
                exclude(group = "io.github.uuverifiers")
                exclude(group = "org.scala-lang")
                exclude(group = "org.scala-lang.modules")
                exclude(group = "net.sf.squirrel-sql.thirdparty-non-maven")
            }
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
