package com.eignex.klause.bench.profile

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object NativeVmEntryRegisterVerification {
    private const val MAX_REPORT_BYTES = 32 * 1024 * 1024
    private const val TIMEOUT_SECONDS = 60L

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(args.size == 4 && options.keys == setOf("entries", "entries-sha256", "metadata", "metadata-sha256"))
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            for (name in listOf("entries", "metadata")) {
                val uri = URI(requireNotNull(options[name]))
                val expected = requireNotNull(options["$name-sha256"])
                require(uri.scheme == "https" && expected.matches(Regex("[0-9a-f]{64}")))
                val connection = uri.toURL().openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 20_000
                }
                val raw = connection.getInputStream().use { it.readNBytes(MAX_REPORT_BYTES + 1) }
                File(out, "captured-$name.json").writeBytes(raw)
                require(raw.size <= MAX_REPORT_BYTES) { "Captured report exceeds diagnostic bounds" }
                val actual = MessageDigest.getInstance("SHA-256").digest(raw)
                    .joinToString("") { "%02x".format(Locale.ROOT, it) }
                require(actual == expected) { "Captured report hash differs" }
            }
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val tool = File(root, "klause-bench/tools/review_native_vm_entry_registers.py")
            tool.copyTo(File(out, tool.name))
            val command = listOf("python3", tool.path, File(out, "captured-entries.json").path,
                requireNotNull(options["entries-sha256"]), File(out, "captured-metadata.json").path,
                requireNotNull(options["metadata-sha256"]), File(out, "vm-entry-registers.json").path)
            val process = ProcessBuilder(command).directory(root)
                .redirectOutput(File(out, "entry-registers.stdout"))
                .redirectError(File(out, "entry-registers.stderr")).start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            File(out, "entry-registers.exit").writeText("${process.exitValue()}\n")
            check(finished) { "VM entry register review timed out; raw output retained" }
            check(process.exitValue() == 0) { "VM entry register review failed; raw output retained" }
        }
    }
}
