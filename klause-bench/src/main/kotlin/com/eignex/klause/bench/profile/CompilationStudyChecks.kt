package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

internal object CompilationStudyChecks {
    @Suppress("LongMethod")
    fun run(root: File, out: File, measurements: List<CompilationStudyMeasurement>) {
        val cases = measurements.map {
            CompilationSourceCase(it.case, if (it.exit == 0) "DONE" else "FAILED", it.arm, it.problem, it.record)
        }
        Reports.writeJson(File(out, "source-cases.json").path, cases)
        Reports.writeJson(File(out, "linear-source-cases.json").path,
            cases.filter { it.problem.format in setOf("SMTLIB", "MPS") })
        val environment = Files.createTempDirectory("klause-compilation-checks-").toFile()
        try {
            fun execute(name: String, arguments: List<String>, required: Boolean = true) =
                CompilationStudyBuilds.command(root, out, name, arguments, required)
            execute("python-preflight", listOf("sudo", "apt-get", "update", "-qq"))
            execute("python-venv-install", listOf("sudo", "env", "DEBIAN_FRONTEND=noninteractive",
                "apt-get", "install", "-yq", "python3-venv"))
            execute("python-venv", listOf("python3", "-m", "venv", environment.path))
            val python = File(environment, "bin/python").path
            val names = listOf("verify_source_results.py", "verify_boolean_source_results.py",
                "requirements-source-checks.txt")
            Reports.writeJson(File(out, "source-check-tools.json").path, names.associateWith { name ->
                val bytes = File(root, "klause-bench/tools/$name").readBytes()
                File(out, name).writeBytes(bytes)
                sha256(bytes)
            })
            execute("source-check-install", listOf(python, "-m", "pip", "install", "-r",
                File(out, "requirements-source-checks.txt").path))
            execute("source-check-environment", listOf(python, "-m", "pip", "freeze"))
            execute("source-check-runtime", listOf(python, "--version"))
            execute("boolean-check", listOf(python, File(out, "verify_boolean_source_results.py").path,
                File(out, "source-cases.json").path, "--source-root", File(out, "source-root").path,
                "--output", File(out, "boolean-source-checks.json").path), required = false)
            execute("linear-check", listOf(python, File(out, "verify_source_results.py").path,
                File(out, "linear-source-cases.json").path, "--source-root", File(out, "source-root").path,
                "--output", File(out, "linear-source-checks.json").path,
                "--progress", File(out, "linear-source-checks.jsonl").path), required = false)
            Reports.writeJson(File(out, "source-check-exits.json").path,
                listOf("boolean", "linear").associateWith { File(out, "$it-check.exit").readText().trim().toInt() })
        } finally {
            check(environment.deleteRecursively()) { "Cannot remove owned source checker environment" }
        }
    }
}

@Serializable
private data class CompilationSourceCase(
    val index: String,
    val status: String,
    val arm: String,
    val problem: CompilationStudyInput,
    val record: JsonElement?,
)
