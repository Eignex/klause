package com.eignex.klause.bench.profile

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal object NraSourceCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val out = File(options["out"] ?: System.getenv("OUT") ?: "build/nra-source-check").apply { mkdirs() }
        val input = out.resolve("source-input.json")
        input.writeBytes(Base64.getDecoder().decode(requireNotNull(options["input"]) {
            "input=<base64 original-source bundle> is required"
        }))
        val root = File(System.getProperty("klause.workspace.root", "."))
        val checkerName = options["checker"] ?: "polynomial"
        require(checkerName in setOf("polynomial", "transcendental"))
        val checker = root.resolve("klause-bench/tools/verify_${
            if (checkerName == "polynomial") "nra" else "transcendental"
        }_source_results.py")
        val python = if (options["preparePython"] == "true") preparePython(root, out)
            else options["python"] ?: "python3"
        val command = if (checkerName == "transcendental") {
            val bundle = Json.parseToJsonElement(input.readText()).jsonObject
            require(bundle.keys == setOf("sources", "results"))
            val sources = out.resolve("sources.json").also { it.writeText(bundle.getValue("sources").toString()) }
            val results = out.resolve("results.json").also { it.writeText(bundle.getValue("results").toString()) }
            listOf(python, checker.absolutePath, sources.absolutePath, results.absolutePath)
        } else listOf(python, checker.absolutePath,
            "--bundle", input.absolutePath, "--timeout-ms", options["timeoutMs"] ?: "5000")
        val process = ProcessBuilder(command)
            .redirectOutput(out.resolve("source-checks.json"))
            .redirectError(out.resolve("source-checks.err")).start()
        check(process.waitFor() == 0) { "original-source check failed; see source-checks.err" }
        println(out.resolve("source-checks.json").readText())
    }

    private fun preparePython(root: File, out: File): String {
        val environment = out.resolve("checker-env")
        val log = out.resolve("python-setup.log")
        fun command(vararg args: String): Int = ProcessBuilder(*args).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start().waitFor()
        if (command("python3", "-m", "venv", environment.absolutePath) != 0) {
            check(command("sudo", "apt-get", "update", "-qq") == 0) { "Python package index setup failed" }
            check(command("sudo", "apt-get", "install", "-yq", "python3-venv") == 0) {
                "Python environment provisioning failed"
            }
            check(command("python3", "-m", "venv", environment.absolutePath) == 0) { "Python environment failed" }
        }
        val python = environment.resolve("bin/python").absolutePath
        check(command(python, "-m", "pip", "install", "-r",
            root.resolve("klause-bench/tools/requirements-source-checks.txt").absolutePath) == 0) {
            "Pinned source-checker dependency installation failed"
        }
        check(command(python, "--version") == 0)
        check(command(python, "-m", "pip", "freeze") == 0)
        return python
    }
}
