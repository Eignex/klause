package com.eignex.klause.bench.runner

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal const val MZN_COMPATIBILITY_POLICY = "minizinc-compile-compatibility-v3"

@Serializable
internal data class MiniZincCompilerIdentity(
    val policy: String = MZN_COMPATIBILITY_POLICY,
    val version: String,
    val executable: String,
    val executableSha256: String,
    val standardLibrary: String,
    val standardLibrarySha256: String,
    val solverLibrarySha256: String,
    val configurationSha256: String,
    val randomSeed: Int = MZN_RANDOM_SEED,
)

internal class MiniZincCompileFailure(
    val exitCode: Int,
    val diagnostic: String,
    val compiler: MiniZincCompilerIdentity,
    val command: List<String>,
    val elapsedNs: Long,
    val sourceHashes: Map<String, String>,
    /** Set when the compiler was killed after this many seconds instead of exiting on its own. */
    val timeoutSec: Int? = null,
) : IllegalArgumentException(
    if (timeoutSec != null) {
        diagnostic.take(4000)
    } else {
        "minizinc compile failed (exit $exitCode): ${diagnostic.take(4000)}"
    },
) {
    /** The source-level reason the compiler rejected this model/data, or null for an infrastructure fault. */
    val incompatibility: String? = when {
        timeoutSec != null -> COMPILE_TIMEOUT
        infrastructure(exitCode, diagnostic) -> null
        else -> classify(diagnostic)
    }

    companion object {
        const val COMPILE_TIMEOUT = "compile-timeout"

        /** Faults of the environment rather than the source: the compiler died on a signal (crash or OOM kill),
         *  ran out of memory, could not open a file, or could not load its solver configuration. */
        fun infrastructure(exitCode: Int, diagnostic: String): Boolean =
            exitCode !in 1 until 128 || INFRASTRUCTURE.any { it in diagnostic }

        private val INFRASTRUCTURE = listOf(
            "cannot open file",
            "out of memory",
            "std::bad_alloc",
            "Config exception",
        )

        /** Best-effort reason for a source-level compile rejection; `compile-error` when no kind is recognised. */
        fun classify(diagnostic: String): String = when {
            "Error: type error:" in diagnostic -> "type-error"
            "Error: syntax error" in diagnostic -> "syntax-error"
            "Error: evaluation error: Index set mismatch" in diagnostic -> "index-set-mismatch"
            "result of evaluation is undefined" in diagnostic -> "undefined-evaluation"
            "assertion failed" in diagnostic.lowercase() -> "assertion"
            "not supported" in diagnostic || "unsupported" in diagnostic.lowercase() -> "unsupported"
            else -> "compile-error"
        }
    }
}

internal object MiniZincCompatibility {
    val compiler: MiniZincCompilerIdentity by lazy {
        val executable = System.getProperty("klause.bench.minizinc")?.let(::File)?.canonicalFile
            ?: System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .map { File(it, "minizinc") }.firstOrNull { it.isFile && it.canExecute() }?.canonicalFile
            ?: error("MiniZinc compiler is unavailable")
        val query = MiniZincIdentityQuery()
        val config = Json.parseToJsonElement(query.query(executable, "--config-dirs")).jsonObject
        val standard = File(config.getValue("mznStdlibDir").jsonPrimitive.content)
        require(standard.isDirectory) { "MiniZinc standard library is unavailable" }
        val root = CorpusFetcher.workspaceRoot()
        val version = query.query(executable, "--version").trim()
        val executableHash = hash(executable)
        MiniZincCompilerIdentity(
            version = version,
            executable = executable.path,
            executableSha256 = executableHash,
            standardLibrary = standard.canonicalPath,
            standardLibrarySha256 = treeHash(standard),
            solverLibrarySha256 = treeHash(File(root, "klause-mzn-lib/share/minizinc/klause")),
            configurationSha256 = hash(File(root, "klause-mzn-lib/share/minizinc/solvers/klause.msc")),
        ).also {
            check(hash(executable) == executableHash) { "MiniZinc executable changed during identity discovery" }
            System.err.println(buildJsonObject {
                put("phase", "compiler-identity-completed")
                put("compilerIdentity", Reports.json.encodeToJsonElement(it))
            })
        }
    }

    // Compile original bytes without invoking a solver or trusting an earlier compilation.
    private val freshCompile: (ProblemRef) -> Unit = { MiniZincRunner().compileFzn(it, fresh = true) }

    fun exclusion(ref: ProblemRef, compile: (ProblemRef) -> Unit = freshCompile): MiniZincCompileFailure? = try {
        compile(ref)
        null
    } catch (failure: MiniZincCompileFailure) {
        if (failure.incompatibility == null) throw failure
        failure
    }

    fun admit(suite: String, ref: ProblemRef, compile: (ProblemRef) -> Unit = freshCompile): Boolean {
        val failure = exclusion(ref, compile) ?: return true
        System.err.println(
            buildJsonObject {
                put("suite", suite)
                put("problem", ref.name)
                put("selectionPolicy", MZN_COMPATIBILITY_POLICY)
                put("status", "source-compiler-incompatible")
                put("reason", failure.incompatibility)
                put("diagnostic", failure.diagnostic.take(4000))
                put("compilerExit", failure.exitCode)
                put("compilerElapsedNs", failure.elapsedNs)
                failure.timeoutSec?.let { put("compilerTimeoutSec", it) }
                put("compilerCommand", Reports.json.encodeToJsonElement(failure.command))
                put("sourceHashes", Reports.json.encodeToJsonElement(failure.sourceHashes))
                put("compilerIdentity", Reports.json.encodeToJsonElement(failure.compiler))
            },
        )
        return false
    }

    fun treeHash(root: File): String {
        require(root.isDirectory) { "missing MiniZinc library $root" }
        val digest = MessageDigest.getInstance("SHA-256")
        root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach {
            digest.update(it.relativeTo(root).invariantSeparatorsPath.toByteArray())
            digest.update(0.toByte())
            it.inputStream().use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            digest.update(0.toByte())
        }
        return hex(digest.digest())
    }

    fun hash(file: File): String = hex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }
}
