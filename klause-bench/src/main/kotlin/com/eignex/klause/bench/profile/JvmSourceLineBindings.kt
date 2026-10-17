package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal object JvmSourceLineBindings {
    @JvmStatic
    @Suppress("LongMethod")
    fun main(args: Array<String>) {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(
            args.size == options.size && options.keys == setOf(
                "catalog", "catalog-sha256", "declarations", "declarations-sha256",
            )
        )
        for (name in listOf("catalog-sha256", "declarations-sha256")) {
            require(options.getValue(name).matches(Regex("[0-9a-f]{64}")))
        }
        for (name in listOf("catalog", "declarations")) {
            val address = options.getValue(name)
            require(address.length <= 4096)
            val uri = URI(address)
            require(
                uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
                    uri.rawQuery == null && uri.rawFragment == null
            )
        }
        ProfileCaptureArchive.run {
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val reader = File(root, "klause-bench/tools/review_jvm_source_lines.py")
            reader.copyTo(File(out, reader.name))
            for (name in listOf(
                "jvm_source_debug.py", "jvm_source_line_bindings.py", "review_jvm_declaration_bindings.py",
            )) {
                File(root, "klause-bench/tools/$name").copyTo(File(out, name))
            }
            File(
                root,
                "klause-bench/src/main/kotlin/com/eignex/klause/bench/profile/JvmSourceLineBindings.kt",
            )
                .copyTo(File(out, "JvmSourceLineBindings.kt"))
            File(out, "input-origins.json").writeText(
                buildJsonObject { options.forEach { (name, value) -> put(name, value) } }.toString() + "\n"
            )
            val runtime = File(System.getProperty("java.home")).canonicalFile
            val bench = File(requireNotNull(System.getenv("KLAUSE_BENCH"))).canonicalFile
            val provenance = File(out, "observer-build-provenance.json")
            InstalledBuild.writeManifest(provenance, bench, runtime)
            val temporary = Files.createTempDirectory("klause-source-line-bindings-").toFile()
            try {
                val command = listOf(
                    "python3", reader.path,
                    options.getValue("catalog"), options.getValue("catalog-sha256"),
                    options.getValue("declarations"), options.getValue("declarations-sha256"),
                    out.path, temporary.path,
                )
                val process = ProcessBuilder(command).directory(root)
                    .redirectOutput(File(out, "source-line-bindings.stdout"))
                    .redirectError(File(out, "source-line-bindings.stderr")).start()
                val finished = process.waitFor(40, TimeUnit.MINUTES)
                if (!finished) CompilationStudyBuilds.kill(process)
                File(out, "source-line-bindings.exit").writeText("${process.exitValue()}\n")
                check(finished && process.exitValue() == 0) { "Saved source-line binding review failed" }
                InstalledBuild.readManifest(provenance, bench, runtime, emptyMap())
            } finally {
                check(temporary.deleteRecursively()) { "Cannot remove owned source-line input staging" }
            }
        }
    }
}
