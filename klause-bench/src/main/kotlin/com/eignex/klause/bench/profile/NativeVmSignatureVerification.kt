package com.eignex.klause.bench.profile

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object NativeVmSignatureVerification {
    private const val MAX_REPORT_BYTES = 2 * 1024 * 1024
    private const val TIMEOUT_SECONDS = 60L
    private const val LIBRARY_SHA256 = "5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c"

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(args.size == 2 && options.keys == setOf("report", "report-sha256"))
        val uri = URI(requireNotNull(options["report"]))
        val expected = requireNotNull(options["report-sha256"])
        require(uri.scheme == "https" && expected.matches(Regex("[0-9a-f]{64}")))
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val connection = uri.toURL().openConnection().apply {
                connectTimeout = 20_000
                readTimeout = 20_000
            }
            val raw = connection.getInputStream().use { it.readNBytes(MAX_REPORT_BYTES + 1) }
            val captured = File(out, "captured-vm-symbols.json").apply { writeBytes(raw) }
            require(raw.size <= MAX_REPORT_BYTES) { "Captured symbol report exceeds diagnostic bounds" }
            val actual = MessageDigest.getInstance("SHA-256").digest(raw)
                .joinToString("") { "%02x".format(it) }
            require(actual == expected) { "Captured symbol report hash differs" }
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val names = listOf("read_native_vm_signatures.py", "native_vm_demangler.py",
                "native_vm_loaded_module.py", "read_native_vm_metadata.py")
            for (name in names) File(root, "klause-bench/tools/$name").copyTo(File(out, name))
            val runtime = File(System.getProperty("java.home")).canonicalFile
            val command = listOf(
                "python3", File(root, "klause-bench/tools/read_native_vm_signatures.py").path,
                File(runtime, "lib/server/libjvm.so").path, LIBRARY_SHA256, captured.path, expected,
                File(out, "vm-signatures.json").path,
            )
            val process = ProcessBuilder(command).directory(root)
                .redirectOutput(File(out, "signatures.stdout"))
                .redirectError(File(out, "signatures.stderr")).start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            File(out, "signatures.exit").writeText("${process.exitValue()}\n")
            check(finished) { "VM signature verification timed out; raw output retained" }
            check(process.exitValue() == 0) { "VM signature verification failed; raw output retained" }
        }
    }
}
