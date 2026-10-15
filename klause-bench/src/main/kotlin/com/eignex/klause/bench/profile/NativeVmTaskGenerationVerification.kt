package com.eignex.klause.bench.profile

import java.io.File
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object NativeVmTaskGenerationVerification {
    private const val TOOL_URL = "https://github.com/bpftrace/bpftrace/releases/download/v0.27.0/bpftrace-x86_64"
    private const val TOOL_SHA256 = "16194f713ba1fbff2dd76b7abffad4cd61f91e5663b116a5b03c907ae3e20cd8"
    private const val TOOL_BYTES = 195597080L
    private const val PLAN_SHA256 = "1184253aa561ed5983e8be38aef4d129ce21418aeb0da3dca1d6198c6713910c"
    private const val ABI_SHA256 = "3aea5bb5831a570c7d5f09ff6e305a028745da5218b8dc884def3db3aa96a3c5"
    private const val PUBLIC_BASE = "https://github.com/Eignex/klause-lab/releases/download/" +
        "active-evidence-non-nra-parallel-oct11/"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val names = listOf(
                "native_vm_task_generation.py",
                "review_native_vm_task_lifecycle_abi.py",
                "review_native_vm_entry_abi.py",
                "review_native_vm_dwarf_records.py",
                "native_compiler_observer.py",
                "native_vm_live_arguments.py",
                "native_vm_event_loss.py",
                "read_native_vm_metadata.py",
                "native_vm_loaded_module.py",
                "read_native_vm_symbols.py",
            )
            for (name in names) File(root, "klause-bench/tools/$name").copyTo(File(out, name))
            val runtime = File(System.getProperty("java.home")).canonicalFile
            val plan = File(out, "task-lifecycle-plan.json")
            download(PUBLIC_BASE + "native-vm-task-lifecycle-abi4938-captured-$PLAN_SHA256.json",
                PLAN_SHA256, 4L * 1024 * 1024, plan)
            val abi = File(out, "compiler-entry-abi.json")
            download(PUBLIC_BASE + "native-vm-entry-abi4772-captured-$ABI_SHA256.json",
                ABI_SHA256, 4L * 1024 * 1024, abi)
            val image = File(out, "tools/bpftrace").apply { parentFile.mkdirs() }
            download(TOOL_URL, TOOL_SHA256, TOOL_BYTES, image)
            check(image.setExecutable(true, true))
            try {
                captureChild(out, root, runtime)
            } finally {
                image.delete()
            }
            capture(out, "child-compilations", listOf(
                File(runtime, "bin/jfr").path, "print", "--json",
                "--events", "jdk.Compilation,jdk.CompilerPhase,jdk.CompilerInlining",
                File(out, "live/child.jfr").path,
            ))
        }
    }

    private fun captureChild(out: File, root: File, runtime: File) {
        val live = File(out, "live").apply { mkdirs() }
        val settings = File(live, "compilation.jfc")
        capture(out, "jfc", listOf(
            File(runtime, "bin/jfr").path, "configure", "--input", "profile", "--output", settings.path,
            "jdk.Compilation#enabled=true", "jdk.Compilation#threshold=0ms",
            "jdk.CompilerPhase#enabled=true", "jdk.CompilerPhase#threshold=0ms",
            "jdk.CompilerInlining#enabled=true",
        ))
        val command = listOf(
            "python3", File(root, "klause-bench/tools/native_vm_task_generation.py").path,
            live.path, File(runtime, "lib/server/libjvm.so").path,
            File(out, "task-lifecycle-plan.json").path, File(out, "compiler-entry-abi.json").path,
            File(out, "tools/bpftrace").path, "--", File(runtime, "bin/java").path,
            "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC", "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+LogCompilation", "-XX:LogFile=${live.path}/hotspot.xml",
            "-XX:StartFlightRecording=filename=${live.path}/child.jfr,settings=${settings.path},dumponexit=true",
            "-version",
        )
        capture(out, "task-generation-capture", command)
    }

    private fun capture(out: File, name: String, command: List<String>) {
        val process = ProcessBuilder(command).redirectOutput(File(out, "$name.stdout"))
            .redirectError(File(out, "$name.stderr")).start()
        val finished = process.waitFor(5, TimeUnit.MINUTES)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
        }
        File(out, "$name.exit").writeText("${process.exitValue()}\n")
        check(finished && process.exitValue() == 0) { "$name failed; raw output retained" }
    }

    private fun download(url: String, expected: String, limit: Long, output: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = 60_000
            readTimeout = 60_000
        }
        connection.getInputStream().use { input ->
            DigestInputStream(input, digest).use { source ->
                output.outputStream().use { destination ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        destination.write(buffer, 0, count)
                        copied += count
                        require(copied <= limit) { "Diagnostic input exceeds download bounds" }
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        require(actual == expected) { "Pinned diagnostic input hash differs" }
    }
}
