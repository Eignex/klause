package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal object JvmDeclarationBindings {
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
            val reader = File(root, "klause-bench/tools/review_jvm_declaration_bindings.py")
            reader.copyTo(File(out, reader.name))
            File(
                root,
                "klause-bench/src/main/kotlin/com/eignex/klause/bench/profile/JvmDeclarationBindings.kt",
            )
                .copyTo(File(out, "JvmDeclarationBindings.kt"))
            File(out, "input-origins.json").writeText(
                buildJsonObject { options.forEach { (name, value) -> put(name, value) } }.toString() + "\n"
            )
            val runtime = File(System.getProperty("java.home")).canonicalFile
            val bench = File(requireNotNull(System.getenv("KLAUSE_BENCH"))).canonicalFile
            val provenance = File(out, "observer-build-provenance.json")
            InstalledBuild.writeManifest(provenance, bench, runtime)
            val temporary = Files.createTempDirectory("klause-declaration-bindings-").toFile()
            try {
                val command = listOf(
                    "python3", reader.path,
                    options.getValue("catalog"), options.getValue("catalog-sha256"),
                    options.getValue("declarations"), options.getValue("declarations-sha256"),
                    out.path, temporary.path,
                )
                val process = ProcessBuilder(command).directory(root)
                    .redirectOutput(File(out, "declaration-bindings.stdout"))
                    .redirectError(File(out, "declaration-bindings.stderr")).start()
                val finished = process.waitFor(40, TimeUnit.MINUTES)
                if (!finished) CompilationStudyBuilds.kill(process)
                File(out, "declaration-bindings.exit").writeText("${process.exitValue()}\n")
                check(finished && process.exitValue() == 0) { "Saved declaration binding review failed" }
                InstalledBuild.readManifest(provenance, bench, runtime, emptyMap())
            } finally {
                check(temporary.deleteRecursively()) { "Cannot remove owned declaration input staging" }
            }
        }
    }
}
