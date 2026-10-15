package com.eignex.klause.bench.profile

import java.io.File
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object NativeVmLiveArgumentVerification {
    private const val TOOL_URL = "https://github.com/bpftrace/bpftrace/releases/download/v0.27.0/bpftrace-x86_64"
    private const val TOOL_SHA256 = "16194f713ba1fbff2dd76b7abffad4cd61f91e5663b116a5b03c907ae3e20cd8"
    private const val TOOL_BYTES = 195597080L
    private const val ENTRIES_SHA256 = "b084bd021491dc839e66e01b0b3ce8543e43b205dfa783dfd68f5c44b0297812"
    private const val ENTRIES_URL = "https://github.com/Eignex/klause-lab/releases/download/" +
        "active-evidence-non-nra-parallel-oct11/native-vm-entries3864-captured-$ENTRIES_SHA256.json"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val names = listOf("native_vm_live_arguments.py", "native_compiler_observer.py",
                "read_native_vm_metadata.py", "native_vm_loaded_module.py", "native_vm_event_loss.py")
            for (name in names) File(root, "klause-bench/tools/$name").copyTo(File(out, name))
            val runtime = File(System.getProperty("java.home")).canonicalFile
            val entries = File(out, "captured-entries.json")
            download(ENTRIES_URL, ENTRIES_SHA256, 32L * 1024 * 1024, entries)
            val image = File(out, "tools/bpftrace").apply { parentFile.mkdirs() }
            download(TOOL_URL, TOOL_SHA256, TOOL_BYTES, image)
            check(image.setExecutable(true, true))
            File(out, "observer-tool.sha256").writeText("$TOOL_SHA256\n")
            File(out, "observer-tool.url").writeText("$TOOL_URL\n")
            val live = File(out, "live").apply { mkdirs() }
            val settings = File(live, "compilation.jfc")
            capture(out, "jfc", listOf(File(runtime, "bin/jfr").path, "configure", "--input", "profile",
                "--output", settings.path, "jdk.Compilation#enabled=true", "jdk.Compilation#threshold=0ms",
                "jdk.CompilerPhase#enabled=true", "jdk.CompilerPhase#threshold=0ms",
                "jdk.CompilerInlining#enabled=true"))
            val command = listOf("python3", File(root, "klause-bench/tools/native_vm_live_arguments.py").path,
                live.path, File(runtime, "lib/server/libjvm.so").path, entries.path, image.path,
                "--", File(runtime, "bin/java").path, "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+LogCompilation", "-XX:LogFile=${live.path}/hotspot.xml",
                "-XX:StartFlightRecording=filename=${live.path}/child.jfr,settings=${settings.path},dumponexit=true",
                "-version")
            try {
                capture(out, "live-capture", command)
            } finally {
                image.delete()
            }
            capture(out, "child-compilations", listOf(File(runtime, "bin/jfr").path, "print", "--json",
                "--events", "jdk.Compilation", File(live, "child.jfr").path))
        }
    }

    private fun capture(out: File, name: String, command: List<String>) {
        val process = ProcessBuilder(command).redirectOutput(File(out, "$name.stdout"))
            .redirectError(File(out, "$name.stderr")).start()
        val finished = process.waitFor(5, TimeUnit.MINUTES)
        if (!finished) process.destroyForcibly().waitFor()
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
