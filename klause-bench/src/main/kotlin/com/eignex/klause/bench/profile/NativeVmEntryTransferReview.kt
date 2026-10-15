package com.eignex.klause.bench.profile

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object NativeVmEntryTransferReview {
    private const val MAX_REPORT_BYTES = 32 * 1024 * 1024
    private const val TIMEOUT_SECONDS = 60L
    private const val LIBRARY_SHA256 = "5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c"

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(args.size == 3 && options.keys == setOf("entries", "entries-sha256", "producer-sha256"))
        val uri = URI(requireNotNull(options["entries"]))
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
        for (key in listOf("entries-sha256", "producer-sha256")) {
            require(requireNotNull(options[key]).matches(Regex("[0-9a-f]{64}")))
        }
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val connection = uri.toURL().openConnection().apply {
                connectTimeout = 20_000
                readTimeout = 20_000
            }
            val raw = connection.getInputStream().use { it.readNBytes(MAX_REPORT_BYTES + 1) }
            val entries = File(out, "captured-vm-entries.json").apply { writeBytes(raw) }
            require(raw.size <= MAX_REPORT_BYTES) { "Captured report exceeds diagnostic bounds" }
            val actual = MessageDigest.getInstance("SHA-256").digest(raw)
                .joinToString("") { "%02x".format(Locale.ROOT, it) }
            require(actual == options["entries-sha256"]) { "Captured report hash differs" }
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val names = listOf(
                "review_native_vm_entry_transfers.py",
                "read_native_vm_entries.py",
                "read_native_vm_symbols.py",
                "native_vm_entry_decoder.py",
            )
            for (name in names) File(root, "klause-bench/tools/$name").copyTo(File(out, name))
            val runtime = File(System.getProperty("java.home")).canonicalFile
            val command = listOf(
                "python3", File(root, "klause-bench/tools/review_native_vm_entry_transfers.py").path,
                File(runtime, "lib/server/libjvm.so").path, LIBRARY_SHA256,
                entries.path, requireNotNull(options["entries-sha256"]),
                requireNotNull(options["producer-sha256"]), File(out, "vm-entry-transfers.json").path,
            )
            val process = ProcessBuilder(command).directory(root)
                .redirectOutput(File(out, "entry-transfers.stdout"))
                .redirectError(File(out, "entry-transfers.stderr")).start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            File(out, "entry-transfers.exit").writeText("${process.exitValue()}\n")
            check(finished) { "Saved VM entry transfer review timed out; raw output retained" }
            check(process.exitValue() == 0) { "Saved VM entry transfer review failed; raw output retained" }
        }
    }
}
