plugins {
    id("com.eignex.kmp") version "1.2.2"
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
            // JavaSMT — unified API over SMT solvers. Pulls in SMTInterpol (pure-Java,
            // 1.5 MB) as a transitive dep — that's the default backend. Princess (another
            // pure-Java solver) is excluded because it drags in ~11 MB of Scala stdlib;
            // re-add via `implementation("io.github.uuverifiers:princess_2.13:...")` if
            // you want it. Native backends (Z3, CVC5, MathSAT5, Bitwuzla, Yices2) are
            // optional and added by depending on their JavaSMT solver artifacts; they
            // bring platform-specific natives.
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
