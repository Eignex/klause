package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal object JvmSourceCompilerLinks {
    @JvmStatic
    @Suppress("LongMethod")
    fun main(args: Array<String>) {
        val budget = ProfileRunBudget()
        val options = parse(args)
        ProfileCaptureArchive.run(budget = budget) {
            budget.remainingMillis(30_000)
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            for (name in listOf(
                "JvmSourceCompilerLinks", "JvmSourceCompilerReview", "JvmSourceCompilerIndex",
                "JvmSourceCompilerReport", "JvmCompilerCandidates", "JvmSavedJson", "JvmSavedInputs", "JvmSavedReports",
                "JvmSavedReferenceCache", "JvmRecordedOrigins", "JvmSavedTables", "JvmStaticMethods",
                "ProfileRunBudget", "ProfileCaptureArchive",
            )) {
                File(root, "klause-bench/src/main/kotlin/com/eignex/klause/bench/profile/$name.kt")
                    .copyTo(File(out, "$name.kt"))
            }
            Reports.writeJson(File(out, "input-origins.json").path, options)
            val runtime = File(System.getProperty("java.home")).canonicalFile
            val bench = File(requireNotNull(System.getenv("KLAUSE_BENCH"))).canonicalFile
            val provenance = File(out, "observer-build-provenance.json")
            InstalledBuild.writeManifest(provenance, bench, runtime)
            val scratch = Files.createTempDirectory("klause-source-compiler-links-").toFile()
            val execution = runCatching {
                runReader(
                    listOf(
                        File(runtime, "bin/java").path, "-Xmx${Runtime.getRuntime().maxMemory()}",
                        "-XX:ActiveProcessorCount=${scheduledProcessors(
                            System.getenv("KLAUSE_CLI_OPTS"),
                            Runtime.getRuntime().availableProcessors(),
                        )}",
                            "-cp",
                        System.getProperty("java.class.path"), JvmSourceCompilerReview::class.java.name,
                        options.getValue("catalog"), options.getValue("catalog-sha256"), out.path, scratch.path,
                    ),
                    root,
                    out,
                    budget,
                )
            }
            val preservation = runCatching { InstalledBuild.readManifest(provenance, bench, runtime, emptyMap()) }
            val cleanup = runCatching { check(scratch.deleteRecursively()) { "Cannot remove owned input staging" } }
            val failures = listOfNotNull(
                execution.exceptionOrNull(),
                preservation.exceptionOrNull(),
                cleanup.exceptionOrNull(),
            )
            failures.firstOrNull()?.let { failure ->
                failures.drop(1).forEach(failure::addSuppressed)
                throw failure
            }
        }
    }

    internal fun parse(args: Array<String>): Map<String, String> {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(args.size == options.size && options.keys == setOf("catalog", "catalog-sha256"))
        val address = options.getValue("catalog")
        val uri = URI(address)
        require(
            address.length <= 4096 && uri.scheme == "https" && uri.host != null &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                Regex("[0-9a-f]{64}").matches(options.getValue("catalog-sha256")),
        )
        return options
    }

    internal fun runReader(arguments: List<String>, root: File, out: File, budget: ProfileRunBudget) {
        budget.remainingMillis(30_000)
        val builder = ProcessBuilder(arguments).directory(root)
            .redirectOutput(File(out, "source-compiler-links.stdout"))
            .redirectError(File(out, "source-compiler-links.stderr"))
        for (setting in listOf("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(setting)
        }
        builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        val process = builder.start()
        var waitMillis: Long? = null
        val execution = runCatching {
            val allowance = budget.remainingMillis(30_000)
            waitMillis = allowance
            process.waitFor(allowance, TimeUnit.MILLISECONDS)
        }
        val cleanup = runCatching {
            if (process.isAlive) {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly().waitFor()
            }
        }
        val exit = if (process.isAlive) null else process.exitValue()
        File(out, "source-compiler-links-process.json").writeText(
            buildJsonObject {
            put("waitCompleted", execution.getOrNull())
            put("timedOut", execution.isSuccess && execution.getOrNull() == false)
            put("waitMillis", waitMillis)
            put("exitCode", exit)
            put("waitErrorType", execution.exceptionOrNull()?.javaClass?.name)
            put("cleanupErrorType", cleanup.exceptionOrNull()?.javaClass?.name)
            put("childAliveAfterCleanup", process.isAlive)
            put("scope", "Saved-review child lifecycle; no original solver/compiler or savings proof")
        }.toString() + "\n"
        )
        exit?.let { File(out, "source-compiler-links.exit").writeText("$it\n") }
        execution.exceptionOrNull()?.let { failure -> cleanup.exceptionOrNull()?.let(failure::addSuppressed) }
        val finished = execution.getOrThrow()
        cleanup.getOrThrow()
        check(finished) { "Saved compiler/source review timed out" }
        check(exit == 0) { "Saved compiler/source review failed with exit $exit" }
    }

    internal fun scheduledProcessors(javaOptions: String?, available: Int): Int {
        require(available > 0)
        val prefix = "-XX:ActiveProcessorCount="
        val value = javaOptions.orEmpty().splitToSequence(' ', '\t', '\r', '\n')
            .lastOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
        val scheduled = value?.let { requireNotNull(it.toIntOrNull()?.takeIf { count -> count > 0 }) } ?: available
        return minOf(scheduled, available)
    }
}
