package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal object JvmSourceSyntaxBindings {
    @JvmStatic
    @Suppress("LongMethod")
    fun main(args: Array<String>) {
        val options = parse(args)
        ProfileCaptureArchive.run {
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val names = listOf("review_jvm_source_syntax.py", "jvm_source_syntax.py", "jvm_source_line_bindings.py",
                "jvm_source_debug.py", "review_jvm_source_lines.py", "review_jvm_declaration_bindings.py")
            for (name in names) File(root, "klause-bench/tools/$name").copyTo(File(out, name))
            File(root, "klause-bench/src/main/kotlin/com/eignex/klause/bench/profile/JvmSourceSyntaxBindings.kt")
                .copyTo(File(out, "JvmSourceSyntaxBindings.kt"))
            Reports.writeJson(File(out, "input-origins.json").path, options)
            val runtime = File(System.getProperty("java.home")).canonicalFile
            val bench = File(requireNotNull(System.getenv("KLAUSE_BENCH"))).canonicalFile
            val provenance = File(out, "observer-build-provenance.json")
            InstalledBuild.writeManifest(provenance, bench, runtime)
            val temporary = Files.createTempDirectory("klause-source-syntax-bindings-").toFile()
            val execution = runCatching {
                val process = ProcessBuilder(listOf("python3", File(root, "klause-bench/tools/${names.first()}").path,
                    options.getValue("catalog"), options.getValue("catalog-sha256"), out.path, temporary.path))
                    .directory(root).redirectOutput(File(out, "source-syntax-bindings.stdout"))
                    .redirectError(File(out, "source-syntax-bindings.stderr")).start()
                val finished = try {
                    process.waitFor(40, TimeUnit.MINUTES)
                } finally {
                    if (process.isAlive) CompilationStudyBuilds.kill(process)
                }
                File(out, "source-syntax-bindings.exit").writeText("${process.exitValue()}\n")
                check(finished && process.exitValue() == 0) { "Saved syntax/class source binding review failed" }
            }
            val preservation = runCatching { InstalledBuild.readManifest(provenance, bench, runtime, emptyMap()) }
            val cleanup = runCatching {
                check(temporary.deleteRecursively()) { "Cannot remove owned syntax binding input staging" }
            }
            execution.exceptionOrNull()?.let { failure ->
                preservation.exceptionOrNull()?.let(failure::addSuppressed)
                cleanup.exceptionOrNull()?.let(failure::addSuppressed)
            }
            execution.getOrThrow()
            preservation.exceptionOrNull()?.let { failure -> cleanup.exceptionOrNull()?.let(failure::addSuppressed) }
            preservation.getOrThrow()
            cleanup.getOrThrow()
        }
    }

    internal fun parse(args: Array<String>): Map<String, String> {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(args.size == options.size && options.keys == setOf("catalog", "catalog-sha256"))
        require(options.getValue("catalog-sha256").matches(Regex("[0-9a-f]{64}")))
        val address = options.getValue("catalog")
        require(address.length <= 4096)
        val uri = URI(address)
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null)
        return options
    }
}
