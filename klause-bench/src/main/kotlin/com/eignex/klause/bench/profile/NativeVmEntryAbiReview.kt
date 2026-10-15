package com.eignex.klause.bench.profile

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object NativeVmEntryAbiReview {
    private const val MAX_REPORT_BYTES = 32 * 1024 * 1024
    private const val TIMEOUT_SECONDS = 60L

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(args.size == 6)
        require(options.keys == setOf(
            "context", "context-sha256", "layout", "layout-sha256", "entries", "entries-sha256",
        ))
        for (key in listOf("context", "layout", "entries")) {
            val uri = URI(requireNotNull(options[key]))
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
            require(requireNotNull(options["$key-sha256"]).matches(Regex("[0-9a-f]{64}")))
        }
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val inputs = listOf("context", "layout", "entries").map { key ->
                val connection = URI(requireNotNull(options[key])).toURL().openConnection().apply {
                    connectTimeout = 20_000
                    readTimeout = 20_000
                }
                val raw = connection.getInputStream().use { it.readNBytes(MAX_REPORT_BYTES + 1) }
                val file = File(out, "downloaded-vm-$key.json").apply { writeBytes(raw) }
                require(raw.size <= MAX_REPORT_BYTES) { "Captured report exceeds diagnostic bounds" }
                val actual = MessageDigest.getInstance("SHA-256").digest(raw)
                    .joinToString("") { "%02x".format(Locale.ROOT, it) }
                require(actual == options["$key-sha256"]) { "Captured report hash differs" }
                file
            }
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val tool = "review_native_vm_entry_abi.py"
            for (name in listOf(tool, "review_native_vm_dwarf_records.py")) {
                File(root, "klause-bench/tools/$name").copyTo(File(out, name))
            }
            val process = ProcessBuilder(
                "python3", File(root, "klause-bench/tools/$tool").path,
                inputs[0].path, requireNotNull(options["context-sha256"]),
                inputs[1].path, requireNotNull(options["layout-sha256"]),
                inputs[2].path, requireNotNull(options["entries-sha256"]),
                File(out, "vm-entry-abi-review.json").path,
            ).directory(root).redirectOutput(File(out, "entry-abi-review.stdout"))
                .redirectError(File(out, "entry-abi-review.stderr")).start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            File(out, "entry-abi-review.exit").writeText("${process.exitValue()}\n")
            check(finished) { "Saved VM entry ABI review timed out; metadata retained" }
            check(process.exitValue() == 0) { "Saved VM entry ABI review failed; metadata retained" }
        }
    }
}
