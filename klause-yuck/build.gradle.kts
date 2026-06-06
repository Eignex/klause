import java.net.URI

plugins {
    id("com.eignex.kmp") version "1.2.2"
}

eignexPublish {
    description.set("Yuck local-search reference adapter for klause (temporary, LS parity sweep).")
    githubRepo.set("Eignex/klause")
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":klause"))
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

// Yuck is not on Maven Central (it ships only as a GitHub release zip), so the adapter shells
// out to a locally provisioned distribution instead of declaring a dependency. `installYuck`
// downloads the pinned release into a user-level cache (shared across worktrees and cacheable
// in CI) and unpacks it; the test task points the adapter at it via `klause.yuck.home`.
val yuckVersion = "20251106"
val yuckCacheDir = File(System.getProperty("user.home"), ".cache/klause-yuck")
val yuckHome = yuckCacheDir.resolve("yuck-$yuckVersion")

abstract class InstallYuckTask : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Internal
    abstract val cacheDir: Property<File>

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun install() {
        val v = version.get()
        val cache = cacheDir.get()
        val home = cache.resolve("yuck-$v")
        if (home.resolve("lib").isDirectory) return
        val zip = cache.resolve("yuck-$v.zip")
        if (!zip.isFile) {
            cache.mkdirs()
            val url = "https://github.com/informarte/yuck/releases/download/$v/yuck-$v.zip"
            logger.lifecycle("Downloading Yuck $v from $url")
            val tmp = File.createTempFile("yuck-$v", ".zip", cache)
            URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(zip)) tmp.copyTo(zip, overwrite = true)
        }
        fs.copy {
            from(archives.zipTree(zip))
            into(cache)
        }
        check(home.resolve("lib").isDirectory) { "Yuck unpack failed: ${home.resolve("lib")} missing" }
    }
}

val installYuck by tasks.registering(InstallYuckTask::class) {
    description = "Downloads and unpacks the pinned Yuck release (GitHub zip; not on Maven Central)."
    version.set(yuckVersion)
    cacheDir.set(yuckCacheDir)
    outputs.dir(yuckHome)
}

tasks.named<Test>("jvmTest") {
    dependsOn(installYuck)
    systemProperty("klause.yuck.home", yuckHome.absolutePath)
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
