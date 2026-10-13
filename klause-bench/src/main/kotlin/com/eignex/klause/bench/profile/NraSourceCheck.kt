package com.eignex.klause.bench.profile

import java.io.File
import java.util.Base64

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
        val checker = root.resolve("klause-bench/tools/verify_nra_source_results.py")
        val process = ProcessBuilder(options["python"] ?: "python3", checker.absolutePath,
            "--bundle", input.absolutePath, "--timeout-ms", options["timeoutMs"] ?: "5000")
            .redirectOutput(out.resolve("source-checks.json"))
            .redirectError(out.resolve("source-checks.err")).start()
        check(process.waitFor() == 0) { "original-source check failed; see source-checks.err" }
        println(out.resolve("source-checks.json").readText())
    }
}
