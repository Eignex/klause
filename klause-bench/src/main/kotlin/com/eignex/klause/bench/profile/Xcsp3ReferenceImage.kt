package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import java.io.File
import java.util.concurrent.TimeUnit

internal object Xcsp3ReferenceImage {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty() || args.contentEquals(arrayOf("build")))
        val out = File(requireNotNull(System.getenv("OUT"))).resolve("xcsp3-reference-image").apply { mkdirs() }
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
        val image = if (args.isEmpty()) "klause-xcsp3-cpsat:latest" else "klause-xcsp3-cpsat:source-model-v2"
        if (args.isNotEmpty()) {
            run(out, "build", listOf("docker", "build", "-t", image,
                root.resolve("klause-bench/xcsp3-cpsat").absolutePath), 600)
        }
        run(out, "image", listOf("docker", "image", "inspect", "--format", "{{.Id}} {{json .RepoDigests}}",
            image))
        val script = root.resolve("klause-bench/xcsp3-cpsat/describe.py")
        require(script.isFile)
        script.copyTo(out.resolve("describe.py"))
        run(out, "runtime", listOf("docker", "run", "--rm", "--network", "none", "--memory", "512m",
            "--cpus", "1", "--entrypoint", "python", "-v", "${out.absolutePath}:/out",
            image, "/out/describe.py", "/out"))
    }

    private fun run(out: File, name: String, command: List<String>, timeoutSeconds: Long = 60) {
        out.resolve("$name.command").writeText(command.joinToString("\n"))
        val process = ProcessBuilder(command).redirectOutput(out.resolve("$name.stdout"))
            .redirectError(out.resolve("$name.stderr")).start()
        try {
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "$name operation timed out" }
            val exit = process.exitValue()
            out.resolve("$name.exit").writeText("$exit\n")
            check(exit == 0) { "$name inspection failed: $exit" }
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
    }
}
